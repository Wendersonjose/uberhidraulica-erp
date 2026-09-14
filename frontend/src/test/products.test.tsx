import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { expect, test, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthProvider'
import App from '../App'

const session = { id: 'u1', name: 'Operador', email: 'op@teste.local', profileCode: 'ADMIN', state: 'ACTIVE', mustChangePassword: false, permissions: [] }
const fluid = {
  id: 'p1', description: 'Óleo ATF Dexron III', internalCode: 'ATF-D3', category: 'Fluidos',
  type: 'SUPPLY', unit: 'LITRO', referenceCost: 28.5, salePrice: 42.9, minimumStock: 20,
  active: true, createdAt: '2026-09-14T10:00:00Z', updatedAt: '2026-09-14T10:00:00Z',
}
const bareProduct = {
  id: 'p2', description: 'Retentor genérico', internalCode: null, category: null,
  type: 'PART', unit: 'UNIDADE', referenceCost: null, salePrice: null, minimumStock: null,
  active: false, createdAt: '2026-09-14T10:00:00Z', updatedAt: '2026-09-14T10:00:00Z',
}

function json(body: unknown, status = 200) {
  return new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function mock(routes: Record<string, unknown>) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const path = String(input), method = init?.method || 'GET'
    if (path === '/api/iam/csrf') return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'test' })
    if (path === '/api/iam/session') return json(session)
    const key = `${method} ${path}`
    return key in routes ? json(routes[key], method === 'POST' ? 201 : 200) : json({ message: 'Falha simulada' }, 500)
  })
}

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}>
    <AuthProvider><App /></AuthProvider>
  </MemoryRouter></QueryClientProvider>)
}

const bodyOf = (calls: Parameters<typeof fetch>[], path: string) =>
  JSON.parse(String(calls.filter(call => String(call[0]) === path && call[1]?.body).at(-1)?.[1]?.body))

test('lista produtos formatando valores e tratando opcionais ausentes', async () => {
  mock({ 'GET /api/products': [fluid, bareProduct] })
  renderAt('/produtos')

  const fluidRow = (await screen.findByText('Óleo ATF Dexron III')).closest('tr')!
  expect(fluidRow).toHaveTextContent('ATF-D3 • Fluidos')
  expect(fluidRow).toHaveTextContent('Insumo')
  expect(fluidRow).toHaveTextContent('Litro')
  expect(fluidRow).toHaveTextContent('20 L')
  expect(fluidRow).toHaveTextContent('Ativo')

  const bareRow = screen.getByText('Retentor genérico').closest('tr')!
  expect(bareRow).toHaveTextContent('Peça')
  expect(bareRow).toHaveTextContent('Inativo')
  expect(bareRow.querySelectorAll('td')[3]).toHaveTextContent('—')
  expect(bareRow.querySelectorAll('td')[4]).toHaveTextContent('—')
})

test('cadastro envia contrato sem status e com opcionais nulos', async () => {
  const fetchMock = mock({ 'POST /api/products': fluid, 'GET /api/products': [] })
  renderAt('/produtos/novo')

  await userEvent.type(await screen.findByLabelText('Descrição *'), 'Óleo ATF Dexron III')
  await userEvent.selectOptions(screen.getByLabelText('Tipo *'), 'SUPPLY')
  await userEvent.selectOptions(screen.getByLabelText('Unidade *'), 'LITRO')
  await userEvent.type(screen.getByLabelText('Preço de venda'), '42.90')
  expect(screen.queryByLabelText('Status')).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Salvar produto' }))

  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/products', expect.objectContaining({ method: 'POST' })))
  expect(bodyOf(fetchMock.mock.calls, '/api/products')).toEqual({
    description: 'Óleo ATF Dexron III', internalCode: null, category: null, type: 'SUPPLY', unit: 'LITRO',
    referenceCost: null, salePrice: 42.9, minimumStock: null,
  })
})

test('descrição vazia bloqueia o envio', async () => {
  const fetchMock = mock({ 'GET /api/products': [] })
  renderAt('/produtos/novo')

  await userEvent.click(await screen.findByRole('button', { name: 'Salvar produto' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Informe a descrição')
  expect(fetchMock.mock.calls.some(call => String(call[0]) === '/api/products')).toBe(false)
})

test('edição carrega o produto e envia inativação', async () => {
  const fetchMock = mock({ 'GET /api/products/p1': fluid, 'PUT /api/products/p1': { ...fluid, active: false }, 'GET /api/products': [] })
  renderAt('/produtos/p1')

  expect(await screen.findByLabelText('Descrição *')).toHaveValue('Óleo ATF Dexron III')
  expect(screen.getByLabelText('Código interno')).toHaveValue('ATF-D3')
  await userEvent.selectOptions(screen.getByLabelText('Status'), 'false')
  await userEvent.click(screen.getByRole('button', { name: 'Salvar produto' }))

  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/products/p1', expect.objectContaining({ method: 'PUT' })))
  expect(bodyOf(fetchMock.mock.calls, '/api/products/p1')).toMatchObject({
    description: 'Óleo ATF Dexron III', internalCode: 'ATF-D3', category: 'Fluidos',
    referenceCost: 28.5, salePrice: 42.9, minimumStock: 20, active: false,
  })
})

test('falha ao carregar o produto oferece nova tentativa', async () => {
  mock({})
  renderAt('/produtos/p1')
  expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar o produto')
  expect(screen.getByRole('button', { name: /Tentar novamente/ })).toBeInTheDocument()
})
