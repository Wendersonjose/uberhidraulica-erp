import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { expect, test, vi } from 'vitest'
import { StrictMode } from 'react'
import { AuthProvider } from '../auth/AuthProvider'
import App from '../App'
import type { Customer, Session, Vehicle, WorkOrder, Service } from '../api/types'

const session: Session = { id: 'u1', name: 'Operador', email: 'op@teste.local', profileCode: 'ADMIN', state: 'ACTIVE', mustChangePassword: false, permissions: [] }
const customers: Customer[] = [
  { id: 'c1', name: 'Maria Silva', document: '12345678901', personType: 'PF', status: 'ACTIVE', createdAt: '', updatedAt: '' },
  { id: 'c2', name: 'Oficina Modelo', document: '12345678000199', personType: 'PJ', status: 'ACTIVE', createdAt: '', updatedAt: '' },
]
const vehicle: Vehicle = { id: 'v1', customerId: 'c1', manufacturer: 'Marca', model: 'Modelo', plate: 'AAA1A11', modelYear: 2020, mileage: null, steeringGearManufacturer: null }
const secondVehicle: Vehicle = { ...vehicle, id: 'v2', customerId: 'c2', model: 'Segundo', plate: 'BBB2B22' }
const order: WorkOrder = { id: 'o1', number: 1, customerId: 'c1', vehicleId: 'v1', entryMileage: 0, openedAt: '2026-09-10T10:00:00Z', status: 'ABERTA', services: [] }
const service: Service = { id: 's2', name: 'Segundo serviço', description: 'Descrição do catálogo', basePrice: 999, defaultWarrantyDays: 90, active: true, category: null }
function json(body: unknown, status = 200) {
  return new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
type Handler = (init?: RequestInit) => Response | Promise<Response>
type Routes = Record<string, Handler>
function mockHttp(overrides: Routes = {}, initial: Session | null = session) {
  let current = initial
  const routes: Routes = {
    'GET /api/customers': () => json(customers),
    'GET /api/customers/c1': () => json(customers[0]),
    'GET /api/customers/c2': () => json(customers[1]),
    'GET /api/customers/c1/vehicles': () => json([vehicle]),
    'GET /api/customers/c2/vehicles': () => json([secondVehicle]),
    'GET /api/vehicles/v1': () => json(vehicle),
    'GET /api/services': () => json([service]),
    'GET /api/work-orders': () => json([]),
    'GET /api/work-orders/o1': () => json(order),
    'GET /api/iam/session': () => current ? json(current) : json({}, 401),
    'POST /api/iam/auth/login': () => { current = session; return json(current) },
    'POST /api/iam/auth/logout': () => { current = null; return json(null, 204) },
    'POST /api/iam/password/change': () => { current = session; return json(null, 204) },
    ...overrides,
  }
  const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    if (String(input) === '/api/iam/csrf') return json({ headerName: 'X-DYNAMIC', parameterName: 'csrf', token: 'fixture' })
    const handler = routes[(init?.method || 'GET') + ' ' + String(input)]
    return handler ? handler(init) : json({ message: 'Rota não simulada' }, 500)
  })
  return { fetch, routes }
}
function Location() { return <span data-testid="location">{useLocation().pathname}</span> }
function renderAt(path: string, client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 15000, gcTime: Infinity } } }), strict = false) {
  const app = <QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><AuthProvider><App/><Location/></AuthProvider></MemoryRouter></QueryClientProvider>
  render(strict ? <StrictMode>{app}</StrictMode> : app)
  return client
}
async function login() {
  await userEvent.type(await screen.findByLabelText('E-mail'), 'op@teste.local')
  await userEvent.type(screen.getByLabelText('Senha'), 'fixture-password')
  await userEvent.click(screen.getByRole('button', { name: 'Entrar' }))
  await screen.findByRole('heading', { name: 'Ordens de serviço' })
}
async function selectCustomerAndVehicle() {
  await screen.findByRole('option', { name: 'Maria Silva' })
  await userEvent.selectOptions(screen.getByLabelText('Cliente'), 'c1')
  await screen.findByRole('option', { name: /AAA1A11/ })
  await userEvent.selectOptions(screen.getByLabelText('Veículo'), 'v1')
}
function posted(fetch: ReturnType<typeof mockHttp>['fetch'], path: string) {
  const call = fetch.mock.calls.find(([input, init]) => String(input) === path && init?.method === 'POST')
  expect(call).toBeDefined()
  return JSON.parse(String(call?.[1]?.body))
}
function deferred() {
  let resolve!: (value: Response) => void
  const promise = new Promise<Response>(done => { resolve = done })
  return { promise, resolve }
}

test.each([
  ['mArIa', ['Maria Silva']],
  ['Inexistente', []],
  ['123.456.789-01', ['Maria Silva']],
  ['12.345.678/0001-99', ['Oficina Modelo']],
  ['', ['Maria Silva', 'Oficina Modelo']],
])('REV-FE-001 busca %s pela interface', async (search, expected) => {
  mockHttp()
  renderAt('/clientes')
  await screen.findByRole('cell', { name: 'Maria Silva' })
  const input = screen.getByRole('textbox', { name: 'Buscar clientes' })
  await userEvent.type(input, 'temporário')
  await userEvent.clear(input)
  if (search) await userEvent.type(input, search)
  for (const customer of customers) {
    if (expected.includes(customer.name)) expect(screen.getByRole('cell', { name: customer.name })).toBeInTheDocument()
    else expect(screen.queryByRole('cell', { name: customer.name })).not.toBeInTheDocument()
  }
  if (!expected.length) expect(screen.getByText('Nenhum registro encontrado.')).toBeInTheDocument()
})

test('REV-FE-002 401 durante uso encerra sessão e permite login sem reload', async () => {
  const { fetch, routes } = mockHttp()
  const client = renderAt('/clientes')
  await screen.findByRole('cell', { name: 'Maria Silva' })
  routes['GET /api/work-orders'] = () => json({}, 401)
  await userEvent.click(screen.getByRole('link', { name: 'Ordens de serviço' }))
  await screen.findByRole('heading', { name: 'Entrar' })
  expect(screen.getByTestId('location')).toHaveTextContent('/login')
  expect(client.getQueryData(['customers'])).toBeUndefined()
  expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
  routes['GET /api/work-orders'] = () => json([])
  await login()
  expect(fetch.mock.calls.filter(([path]) => path === '/api/iam/session')).toHaveLength(1)
})

test.each([204, 401])('REV-FE-002 logout HTTP %s encerra sessão e limpa cache', async status => {
  const { fetch } = mockHttp({ 'POST /api/iam/auth/logout': () => json(null, status) })
  const client = renderAt('/clientes')
  await screen.findByRole('cell', { name: 'Maria Silva' })
  await userEvent.click(screen.getByRole('button', { name: /Sair/ }))
  await screen.findByRole('heading', { name: 'Entrar' })
  expect(client.getQueryCache().getAll()).toHaveLength(0)
  expect(client.getMutationCache().getAll()).toHaveLength(0)
  const call = fetch.mock.calls.find(([path]) => path === '/api/iam/auth/logout')
  expect(call?.[1]?.credentials).toBe('include')
  expect(new Headers(call?.[1]?.headers).get('X-DYNAMIC')).toBe('fixture')
})

test.each(['network', '500'])('REV-FE-002 logout falha %s é visível e permite tentar novamente', async failure => {
  const { routes } = mockHttp({
    'POST /api/iam/auth/logout': () => { if (failure === 'network') throw new TypeError('Offline'); return json({}, 500) },
  })
  renderAt('/clientes')
  await screen.findByRole('cell', { name: 'Maria Silva' })
  await userEvent.click(screen.getByRole('button', { name: /Sair/ }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível sair')
  expect(screen.getByRole('cell', { name: 'Maria Silva' })).toBeInTheDocument()
  routes['POST /api/iam/auth/logout'] = () => json(null, 204)
  await userEvent.click(screen.getByRole('button', { name: /Sair/ }))
  await screen.findByRole('heading', { name: 'Entrar' })
})

test('REV-FE-002 logout/login cancela query pendente e não reaproveita cache', async () => {
  const delayed = deferred()
  const { routes } = mockHttp()
  const client = renderAt('/clientes')
  await screen.findByRole('cell', { name: 'Maria Silva' })
  const request = client.fetchQuery({ queryKey: ['old-session'], queryFn: () => delayed.promise })
  const cancellation = request.catch(() => 'cancelled')
  await userEvent.click(screen.getByRole('button', { name: /Sair/ }))
  await screen.findByRole('heading', { name: 'Entrar' })
  expect(await cancellation).toBe('cancelled')
  expect(client.getQueryData(['customers'])).toBeUndefined()
  routes['GET /api/customers'] = () => json([{ ...customers[0], name: 'Cliente atualizado' }])
  await login()
  await userEvent.click(screen.getByRole('link', { name: 'Clientes' }))
  await screen.findByRole('cell', { name: 'Cliente atualizado' })
  await act(async () => { delayed.resolve(json(customers)); await delayed.promise })
  expect(screen.queryByRole('cell', { name: 'Maria Silva' })).not.toBeInTheDocument()
  expect(client.getQueryData(['old-session'])).toBeUndefined()
})

test('REV-FE-002 resposta 401 antiga não encerra novo login', async () => {
  const delayed = deferred()
  mockHttp({ 'GET /api/services': () => delayed.promise })
  renderAt('/servicos')
  await screen.findByRole('heading', { name: 'Serviços' })
  await userEvent.click(screen.getByRole('button', { name: /Sair/ }))
  await login()
  await act(async () => { delayed.resolve(json({}, 401)); await delayed.promise })
  expect(screen.getByRole('heading', { name: 'Ordens de serviço' })).toBeInTheDocument()
})

test('REV-FE-002 mustChangePassword submete troca e restaura sessão atualizada', async () => {
  const { fetch } = mockHttp({}, { ...session, mustChangePassword: true })
  renderAt('/clientes')
  await screen.findByRole('heading', { name: 'Alterar senha' })
  await userEvent.type(screen.getByLabelText('Senha atual'), 'old-fixture')
  await userEvent.type(screen.getByLabelText('Nova senha'), 'new-fixture')
  await userEvent.click(screen.getByRole('button', { name: 'Salvar nova senha' }))
  await screen.findByRole('heading', { name: 'Ordens de serviço' })
  expect(posted(fetch, '/api/iam/password/change')).toEqual({ currentPassword: 'old-fixture', newPassword: 'new-fixture' })
  expect(fetch.mock.calls.filter(([path]) => path === '/api/iam/session')).toHaveLength(2)
  await userEvent.click(screen.getByRole('link', { name: 'Clientes' }))
  await screen.findByRole('cell', { name: 'Maria Silva' })
})

test('REV-FE-002 restauração em StrictMode não cria loop de sessão anônima', async () => {
  const { fetch } = mockHttp({}, null)
  renderAt('/clientes', undefined, true)
  await screen.findByRole('heading', { name: 'Entrar' })
  await login()
  expect(fetch.mock.calls.filter(([path]) => path === '/api/iam/session')).toHaveLength(2)
  expect(fetch.mock.calls.some(([path]) => path === '/api/iam/auth/logout')).toBe(false)
})

test('REV-FE-002 exigência de troca recebida durante uso protege as rotas', async () => {
  mockHttp({ 'GET /api/customers': () => json({ code: 'PASSWORD_CHANGE_REQUIRED' }, 403) })
  renderAt('/clientes')
  await screen.findByRole('heading', { name: 'Alterar senha' })
  expect(screen.getByTestId('location')).toHaveTextContent('/alterar-senha')
})

test.each(['/veiculos/novo', '/ordens-servico/nova'])('REV-FE-003 clientes loading/erro/retry em %s', async path => {
  const delayed = deferred()
  const { routes } = mockHttp({ 'GET /api/customers': () => delayed.promise })
  renderAt(path)
  expect(await screen.findByRole('status')).toHaveTextContent('Carregando clientes')
  const submit = screen.getByRole('button', { name: path === '/veiculos/novo' ? 'Salvar veículo' : 'Criar OS' })
  expect(submit).toBeDisabled()
  await act(async () => { delayed.resolve(json({}, 500)) })
  expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar clientes')
  routes['GET /api/customers'] = () => json(customers)
  await userEvent.click(screen.getByRole('button', { name: 'Tentar novamente: clientes' }))
  await screen.findByRole('option', { name: 'Maria Silva' })
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

test('REV-FE-003 veículos loading/erro/retry e submit dependente', async () => {
  const delayed = deferred()
  const { routes } = mockHttp({ 'GET /api/customers/c1/vehicles': () => delayed.promise })
  renderAt('/ordens-servico/nova')
  await screen.findByRole('option', { name: 'Maria Silva' })
  await userEvent.selectOptions(screen.getByLabelText('Cliente'), 'c1')
  expect(await screen.findByRole('status')).toHaveTextContent('Carregando veículos')
  expect(screen.getByRole('button', { name: 'Criar OS' })).toBeDisabled()
  await act(async () => { delayed.resolve(json({}, 500)) })
  expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar veículos')
  routes['GET /api/customers/c1/vehicles'] = () => json([vehicle])
  await userEvent.click(screen.getByRole('button', { name: 'Tentar novamente: veículos' }))
  await screen.findByRole('option', { name: /AAA1A11/ })
  expect(screen.getByLabelText('Veículo')).toBeEnabled()
})

test('REV-FE-003 cliente sem veículo mostra orientação e caminho de cadastro', async () => {
  mockHttp({ 'GET /api/customers/c1/vehicles': () => json([]) })
  renderAt('/ordens-servico/nova')
  await screen.findByRole('option', { name: 'Maria Silva' })
  await userEvent.selectOptions(screen.getByLabelText('Cliente'), 'c1')
  expect(await screen.findByRole('status')).toHaveTextContent('O cliente ainda não possui veículo cadastrado')
  expect(screen.getByRole('button', { name: 'Criar OS' })).toBeDisabled()
  await userEvent.click(screen.getByRole('link', { name: 'Cadastrar veículo' }))
  await screen.findByRole('heading', { name: 'Cadastrar veículo' })
})

test.each(['cliente', 'veículo'])('REV-FE-003 erro de %s no detalhe tem retry e não fica carregando', async label => {
  const path = label === 'cliente' ? 'GET /api/customers/c1' : 'GET /api/vehicles/v1'
  const { routes } = mockHttp({ [path]: () => json({}, 500) })
  renderAt('/ordens-servico/o1')
  expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar ' + label)
  expect(screen.queryByText('Carregando ' + label + '…')).not.toBeInTheDocument()
  routes[path] = () => json(label === 'cliente' ? customers[0] : vehicle)
  await userEvent.click(screen.getByRole('button', { name: 'Tentar novamente: ' + label }))
  await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  expect(screen.getByText(label === 'cliente' ? 'Maria Silva' : 'Marca Modelo • AAA1A11')).toBeInTheDocument()
})

test('REV-FE-003 catálogo distingue loading, erro e vazio após retry', async () => {
  const delayed = deferred()
  const { routes } = mockHttp({ 'GET /api/services': () => delayed.promise })
  renderAt('/ordens-servico/o1')
  await screen.findByText('Carregando catálogo de serviços…')
  expect(screen.getByRole('button', { name: 'Adicionar à OS' })).toBeDisabled()
  await act(async () => { delayed.resolve(json({}, 500)) })
  expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar catálogo de serviços')
  routes['GET /api/services'] = () => json([])
  await userEvent.click(screen.getByRole('button', { name: 'Tentar novamente: catálogo de serviços' }))
  await screen.findByText('Nenhum serviço cadastrado.')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Adicionar à OS' })).toBeDisabled()
})

test.each(['0', '12345'])('REV-FE-005/006 cria OS pelo formulário com km %s e atualiza lista fresca', async mileage => {
  const created = { ...order, entryMileage: Number(mileage) }
  let saved = false
  const { fetch } = mockHttp({
    'POST /api/work-orders': () => { saved = true; return json(created, 201) },
    'GET /api/work-orders': () => json(saved ? [created] : []),
    'GET /api/work-orders/o1': () => json(created),
  })
  const client = renderAt('/ordens-servico')
  await screen.findByText('Nenhum registro encontrado.')
  expect(client.getQueryData(['work-orders'])).toEqual([])
  await userEvent.click(screen.getByRole('link', { name: 'Abrir nova OS' }))
  await selectCustomerAndVehicle()
  await userEvent.type(screen.getByLabelText('Quilometragem de entrada *'), mileage)
  await userEvent.click(screen.getByRole('button', { name: 'Criar OS' }))
  await screen.findByRole('heading', { name: 'OS #1' })
  expect(screen.getByTestId('location')).toHaveTextContent('/ordens-servico/o1')
  expect(posted(fetch, '/api/work-orders')).toEqual({ customerId: 'c1', vehicleId: 'v1', entryMileage: Number(mileage) })
  await userEvent.click(screen.getByRole('link', { name: 'Ordens de serviço' }))
  await screen.findByRole('link', { name: /OS #1/ })
  expect(fetch.mock.calls.filter(([path, init]) => path === '/api/work-orders' && !init?.method)).toHaveLength(2)
})

test.each(['', '0'])('REV-FE-005/006 cadastro veículo km "%s" atualiza veículos do cliente', async mileage => {
  const created = { ...vehicle, mileage: mileage === '' ? null : 0 }
  let saved = false
  const { fetch } = mockHttp({
    'GET /api/customers/c1/vehicles': () => json(saved ? [created] : []),
    'POST /api/vehicles': () => { saved = true; return json(created, 201) },
  })
  const client = renderAt('/ordens-servico/nova')
  await screen.findByRole('option', { name: 'Maria Silva' })
  await userEvent.selectOptions(screen.getByLabelText('Cliente'), 'c1')
  await screen.findByText(/O cliente ainda não possui veículo cadastrado/)
  expect(client.getQueryData(['customers', 'c1', 'vehicles'])).toEqual([])
  await userEvent.click(screen.getByRole('link', { name: 'Cadastrar veículo' }))
  await userEvent.selectOptions(await screen.findByLabelText('Proprietário *'), 'c1')
  await userEvent.type(screen.getByLabelText('Placa *'), 'AAA1A11')
  await userEvent.type(screen.getByLabelText('Marca/Fabricante *'), 'Marca')
  await userEvent.type(screen.getByLabelText('Modelo *'), 'Modelo')
  await userEvent.type(screen.getByLabelText('Ano modelo *'), '2020')
  if (mileage) await userEvent.type(screen.getByLabelText('Quilometragem'), mileage)
  await userEvent.click(screen.getByRole('button', { name: 'Salvar veículo' }))
  await screen.findByRole('heading', { name: 'Veículos' })
  expect(posted(fetch, '/api/vehicles')).toEqual({
    customerId: 'c1', plate: 'AAA1A11', manufacturer: 'Marca', model: 'Modelo', modelYear: 2020,
    mileage: mileage === '' ? null : 0, steeringGearManufacturer: null,
  })
  await userEvent.click(screen.getByRole('link', { name: 'Ordens de serviço' }))
  await userEvent.click(screen.getByRole('link', { name: 'Abrir nova OS' }))
  await userEvent.selectOptions(await screen.findByLabelText('Cliente'), 'c1')
  await screen.findByRole('option', { name: /AAA1A11/ })
})

test('REV-FE-006 troca de cliente limpa vehicleId e carrega somente os novos veículos', async () => {
  const { fetch } = mockHttp()
  renderAt('/ordens-servico/nova')
  await selectCustomerAndVehicle()
  await userEvent.type(screen.getByLabelText('Quilometragem de entrada *'), '0')
  await userEvent.selectOptions(screen.getByLabelText('Cliente'), 'c2')
  await screen.findByRole('option', { name: /BBB2B22/ })
  expect(screen.getByLabelText('Veículo')).toHaveValue('')
  expect(screen.queryByRole('option', { name: /AAA1A11/ })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Criar OS' })).toBeDisabled()
  expect(fetch.mock.calls.some(([path, init]) => path === '/api/work-orders' && init?.method === 'POST')).toBe(false)
})

test('REV-FE-006 inclusão refaz GET, exibe novo snapshot e soma múltiplos preços da OS', async () => {
  const first = { id: 'i1', serviceId: 's1', name: 'Serviço original', description: 'Snapshot original', basePrice: 12.5, warrantyDays: 90, addedAt: order.openedAt }
  const second = { ...first, id: 'i2', serviceId: 's2', name: 'Segundo serviço', description: 'Snapshot incluído', basePrice: 7.5 }
  let added = false
  const { fetch } = mockHttp({
    'GET /api/work-orders/o1': () => json({ ...order, services: added ? [first, second] : [first] }),
    'POST /api/work-orders/o1/services': () => { added = true; return json({ ...order, services: [first, second] }, 201) },
  })
  renderAt('/ordens-servico/o1')
  expect(await screen.findByTestId('subtotal')).toHaveTextContent('12,50')
  await screen.findByRole('option', { name: /Segundo serviço/ })
  await userEvent.selectOptions(screen.getByLabelText('Catálogo ativo'), 's2')
  await userEvent.click(screen.getByRole('button', { name: 'Adicionar à OS' }))
  await screen.findByText(/Snapshot incluído/)
  expect(screen.getByTestId('subtotal')).toHaveTextContent('20,00')
  expect(screen.getByLabelText('Catálogo ativo')).toHaveValue('')
  expect(posted(fetch, '/api/work-orders/o1/services')).toEqual({ serviceId: 's2' })
  expect(fetch.mock.calls.filter(([path, init]) => path === '/api/work-orders/o1' && !init?.method)).toHaveLength(2)
  expect(within(screen.getByTestId('subtotal')).queryByText(/999/)).not.toBeInTheDocument()
})


test('REV-FE-002 login rejeitado não gera ciclo de refresh/logout', async () => {
  const { fetch } = mockHttp({ 'POST /api/iam/auth/login': () => json({ message: 'Credenciais inválidas' }, 401) }, null)
  renderAt('/login')
  await userEvent.type(await screen.findByLabelText('E-mail'), 'op@teste.local')
  await userEvent.type(screen.getByLabelText('Senha'), 'invalid-fixture')
  await userEvent.click(screen.getByRole('button', { name: 'Entrar' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Credenciais inválidas')
  expect(screen.getByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
  expect(fetch.mock.calls.filter(([path]) => path === '/api/iam/session')).toHaveLength(1)
  expect(fetch.mock.calls.some(([path]) => path === '/api/iam/auth/logout')).toBe(false)
})

test('REV-FE-002 401 na mutação encerra sessão sem repetir POST', async () => {
  const { fetch } = mockHttp({ 'POST /api/work-orders': () => json({}, 401) })
  renderAt('/ordens-servico/nova')
  await selectCustomerAndVehicle()
  await userEvent.type(screen.getByLabelText('Quilometragem de entrada *'), '0')
  await userEvent.click(screen.getByRole('button', { name: 'Criar OS' }))
  await screen.findByRole('heading', { name: 'Entrar' })
  expect(fetch.mock.calls.filter(([path, init]) => path === '/api/work-orders' && init?.method === 'POST')).toHaveLength(1)
})

test('REV-FE-006 submit duplo não duplica abertura de OS pendente', async () => {
  const delayed = deferred()
  const { fetch } = mockHttp({ 'POST /api/work-orders': () => delayed.promise })
  renderAt('/ordens-servico/nova')
  await selectCustomerAndVehicle()
  await userEvent.type(screen.getByLabelText('Quilometragem de entrada *'), '0')
  await userEvent.dblClick(screen.getByRole('button', { name: 'Criar OS' }))
  expect(screen.getByRole('button', { name: 'Criar OS' })).toBeDisabled()
  expect(fetch.mock.calls.filter(([path, init]) => path === '/api/work-orders' && init?.method === 'POST')).toHaveLength(1)
  await act(async () => { delayed.resolve(json(order, 201)) })
  await screen.findByRole('heading', { name: 'OS #1' })
})
