import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { json, lastBody, mockApi, page, renderAt, requestsTo } from './harness'

const maria = {
  id: 'c1', personType: 'PF', name: 'Maria Souza', document: null, phone: '34998765432', email: 'maria@example.com',
  address: { zipCode: '38400100', street: 'Av. Rondon Pacheco', number: '1000', complement: null, district: 'Centro', city: 'Uberlândia', state: 'MG' },
  status: 'ACTIVE', createdAt: '2026-09-17T10:00:00Z', updatedAt: '2026-09-17T10:00:00Z',
}
const pedro = { ...maria, id: 'c2', name: 'Pedro Lima', address: null, email: null }
const truck = {
  id: 'v1', customerId: 'c1', plate: 'TRK1A11', manufacturer: 'Volvo', model: 'FH', modelYear: 2020, mileage: 100000,
  steeringGearManufacturer: null, color: 'Branco', notes: null, active: true,
}

test('lista clientes pela busca do servidor enviando termo, filtros e página', async () => {
  const fetch = mockApi({ 'GET /api/customers/search': (_init: RequestInit | undefined, url: URL) => json(page(url.searchParams.get('q') === 'maria' ? [maria] : [maria, pedro])) })
  renderAt('/clientes')
  expect(await screen.findByRole('cell', { name: 'Pedro Lima' })).toBeInTheDocument()
  expect(screen.getAllByRole('cell', { name: '(34) 99876-5432' })).toHaveLength(2)
  await userEvent.type(screen.getByRole('textbox', { name: 'Buscar clientes' }), 'maria')
  await userEvent.selectOptions(screen.getByLabelText('Status'), 'ACTIVE')
  await waitFor(() => expect(screen.queryByRole('cell', { name: 'Pedro Lima' })).not.toBeInTheDocument())
  await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input) === '/api/customers/search?q=maria&status=ACTIVE&page=0&size=20')).toBe(true))
})

test('cadastro PF envia telefone, e-mail e endereço estruturado e valida documento', async () => {
  const fetch = mockApi({ 'POST /api/customers': { ...maria, id: 'c9' }, 'GET /api/customers/c9': maria, 'GET /api/customers/c9/vehicles': [], 'GET /api/work-orders': [] })
  renderAt('/clientes/novo/pf')
  await userEvent.type(await screen.findByLabelText('Nome completo *'), 'Maria Souza')
  await userEvent.type(screen.getByLabelText('CPF'), '123')
  await userEvent.type(screen.getByLabelText('Telefone *'), '(34) 99876-5432')
  await userEvent.click(screen.getByRole('button', { name: 'Salvar cliente' }))
  expect(await screen.findByText('CPF deve ter 11 dígitos')).toBeInTheDocument()
  await userEvent.clear(screen.getByLabelText('CPF'))
  await userEvent.type(screen.getByLabelText('E-mail'), 'maria@example.com')
  await userEvent.type(screen.getByLabelText('CEP'), '38400-100')
  await userEvent.type(screen.getByLabelText('Cidade'), 'Uberlândia')
  await userEvent.type(screen.getByLabelText('UF'), 'mg')
  await userEvent.click(screen.getByRole('button', { name: 'Salvar cliente' }))
  await waitFor(() => expect(requestsTo(fetch, 'POST', '/api/customers')).toHaveLength(1))
  expect(lastBody(fetch, 'POST', '/api/customers')).toEqual({
    personType: 'PF', name: 'Maria Souza', document: null, phone: '34998765432', email: 'maria@example.com',
    address: { zipCode: '38400100', street: null, number: null, complement: null, district: null, city: 'Uberlândia', state: 'MG' },
  })
  expect(await screen.findByRole('heading', { name: 'Maria Souza' })).toBeInTheDocument()
})

test('detalhe do cliente mostra veículos, histórico de OS e inativa com confirmação', async () => {
  let status = 'ACTIVE'
  const fetch = mockApi({
    'GET /api/customers/c1': () => json({ ...maria, status }),
    'GET /api/customers/c1/vehicles': [truck],
    'GET /api/work-orders': [{ id: 'o1', number: 7, customerId: 'c1', vehicleId: 'v1', entryMileage: 100, openedAt: '2026-09-17T10:00:00Z', status: 'ABERTA', services: [], products: [] }],
    'POST /api/customers/c1/inactivate': () => { status = 'INACTIVE'; return json({ ...maria, status }) },
  })
  renderAt('/clientes/c1')
  expect(await screen.findByRole('link', { name: /TRK1A11/ })).toBeInTheDocument()
  expect(await screen.findByRole('link', { name: 'OS #7' })).toBeInTheDocument()
  expect(requestsTo(fetch, 'GET', '/api/work-orders?customerId=c1')).toHaveLength(1)
  await userEvent.click(screen.getByRole('button', { name: 'Inativar' }))
  expect(requestsTo(fetch, 'POST', '/api/customers/c1/inactivate')).toHaveLength(0)
  await userEvent.click(screen.getAllByRole('button', { name: 'Inativar' }).at(-1)!)
  expect(await screen.findByRole('button', { name: 'Reativar' })).toBeInTheDocument()
  expect(screen.getByText('Inativo')).toBeInTheDocument()
})

test('detalhe do veículo troca proprietário e mostra histórico de proprietários', async () => {
  let owner = 'c1'
  const fetch = mockApi({
    'GET /api/vehicles/v1': () => json({ ...truck, customerId: owner }),
    'GET /api/customers/c1': maria,
    'GET /api/customers/c2': pedro,
    'GET /api/customers': [maria, pedro],
    'GET /api/vehicles/v1/ownership-history': [{ id: 'h1', customerId: 'c1', customerName: 'Maria Souza', startedAt: '2026-01-01T10:00:00Z', endedAt: null }],
    'GET /api/work-orders': [],
    'POST /api/vehicles/v1/owner': () => { owner = 'c2'; return json({ ...truck, customerId: 'c2' }) },
  })
  renderAt('/veiculos/v1')
  expect(await screen.findByText(/— atual/)).toBeInTheDocument()
  await userEvent.selectOptions(await screen.findByLabelText('Novo proprietário'), 'c2')
  await userEvent.click(screen.getByRole('button', { name: 'Trocar proprietário' }))
  await userEvent.click(screen.getAllByRole('button', { name: 'Trocar proprietário' }).at(-1)!)
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/vehicles/v1/owner')).toEqual({ customerId: 'c2' }))
  expect(await screen.findByRole('link', { name: 'Pedro Lima' })).toBeInTheDocument()
})

test('cadastro de veículo pré-seleciona o cliente e envia campos opcionais nulos', async () => {
  const fetch = mockApi({ 'GET /api/customers': [maria, pedro], 'POST /api/vehicles': truck, 'GET /api/vehicles/search': page([]) })
  renderAt('/veiculos/novo?cliente=c2')
  await screen.findByRole('option', { name: 'Pedro Lima' })
  expect(screen.getByLabelText('Proprietário *')).toHaveValue('c2')
  await userEvent.type(screen.getByLabelText('Placa *'), 'abc1d23')
  await userEvent.type(screen.getByLabelText('Marca/Fabricante *'), 'Ford')
  await userEvent.type(screen.getByLabelText('Modelo *'), 'Cargo')
  await userEvent.click(screen.getByRole('button', { name: 'Salvar veículo' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/vehicles')).toEqual({
    customerId: 'c2', plate: 'ABC1D23', manufacturer: 'Ford', model: 'Cargo', modelYear: null, mileage: null,
    steeringGearManufacturer: null, color: null, notes: null,
  }))
})

test('lança serviço na OS com preço sugerido visível e preço praticado manual', async () => {
  const fetch = mockApi({
    'GET /api/work-orders/o1': { id: 'o1', number: 3, customerId: 'c1', vehicleId: 'v1', entryMileage: 10, openedAt: '2026-09-17T10:00:00Z', status: 'ABERTA', services: [], products: [] },
    'GET /api/customers/c1': maria,
    'GET /api/vehicles/v1': truck,
    'GET /api/products': [],
    'GET /api/services': [{ id: 's1', name: 'Reparo de caixa', description: null, category: null, basePrice: null, defaultWarrantyDays: 90, active: true }],
    'GET /api/services/s1/price-suggestion?vehicleId=v1': { serviceId: 's1', vehicleId: 'v1', price: 850, source: 'GROUP' },
    'POST /api/work-orders/o1/services': { id: 'o1' },
  })
  renderAt('/ordens-servico/o1')
  await screen.findByRole('option', { name: 'Reparo de caixa' })
  await userEvent.selectOptions(screen.getByLabelText('Catálogo ativo'), 's1')
  expect(await screen.findByTestId('price-suggestion')).toHaveTextContent('preço do grupo')
  await userEvent.type(screen.getByLabelText('Preço praticado (opcional)'), '700')
  await userEvent.click(screen.getByRole('button', { name: 'Adicionar à OS' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/work-orders/o1/services')).toEqual({ serviceId: 's1', price: 700 }))
})

test('serviço: cadastro sem preço base e prioridade de preços por grupo', async () => {
  const fetch = mockApi({
    'GET /api/service-categories': [{ id: 'k1', name: 'Direção', active: true }, { id: 'k2', name: 'Antiga', active: false }],
    'POST /api/services': { id: 's1' },
    'GET /api/services/s1': { id: 's1', name: 'Diagnóstico', description: null, categoryId: 'k1', category: 'Direção', basePrice: null, defaultWarrantyDays: 90, active: true },
    'GET /api/services/s1/prices': [],
    'GET /api/vehicle-groups': [{ id: 'g1', name: 'Pesados', description: null, active: true, vehicleCount: 2 }],
    'PUT /api/services/s1/prices/groups/g1': { id: 'p1' },
  })
  renderAt('/servicos/novo')
  await screen.findByRole('option', { name: 'Direção' })
  expect(screen.queryByRole('option', { name: /Antiga/ })).not.toBeInTheDocument()
  await userEvent.type(screen.getByLabelText('Nome *'), 'Diagnóstico')
  await userEvent.selectOptions(screen.getByLabelText('Categoria'), 'k1')
  await userEvent.click(screen.getByRole('button', { name: 'Salvar serviço' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/services')).toEqual({
    name: 'Diagnóstico', description: null, categoryId: 'k1', basePrice: null, defaultWarrantyDays: 90, active: true,
  }))
  expect(await screen.findByRole('heading', { name: 'Diagnóstico' })).toBeInTheDocument()
  await userEvent.selectOptions(await screen.findByLabelText('Grupo'), 'g1')
  await userEvent.type(screen.getAllByLabelText('Preço')[0], '850')
  await userEvent.click(screen.getAllByRole('button', { name: 'Salvar preço' })[0])
  await waitFor(() => expect(lastBody(fetch, 'PUT', '/api/services/s1/prices/groups/g1')).toEqual({ price: 850 }))
})
