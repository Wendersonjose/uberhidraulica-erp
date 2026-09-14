import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { expect, test, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthProvider'
import App from '../App'

const session = { id: 'u1', name: 'Operador', email: 'op@teste.local', profileCode: 'ADMIN', state: 'ACTIVE', mustChangePassword: false, permissions: [] }
const fluid = { id: 'p1', description: 'Óleo ATF Dexron III', internalCode: 'ATF-D3', category: 'Fluidos', type: 'SUPPLY', unit: 'LITRO', referenceCost: 28.5, salePrice: 42.9, minimumStock: null, active: true, createdAt: '2026-09-14T10:00:00Z', updatedAt: '2026-09-14T10:00:00Z' }
const unpriced = { ...fluid, id: 'p2', description: 'Peça sem preço', internalCode: null, unit: 'UNIDADE', salePrice: null }
const inactive = { ...fluid, id: 'p3', description: 'Peça inativa', internalCode: null, unit: 'UNIDADE', active: false }
const order = {
  id: 'o1', number: 7, customerId: 'c1', vehicleId: 'v1', entryMileage: 100,
  openedAt: '2026-09-10T10:00:00Z', status: 'ABERTA',
  services: [{ id: 'i1', serviceId: 's1', name: 'Serviço', description: 'Desc', basePrice: 100, warrantyDays: 90, addedAt: '2026-09-10T10:00:00Z' }],
  products: [{ id: 'pi1', productId: 'p1', description: 'Óleo ATF Dexron III', internalCode: 'ATF-D3', unit: 'LITRO', quantity: 2.5, unitPrice: 42.9, addedAt: '2026-09-10T11:00:00Z' }],
}

function json(body: unknown, status = 200) {
  return new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function mock(overrides: Record<string, unknown> = {}, errors: Record<string, [unknown, number]> = {}) {
  const routes: Record<string, unknown> = {
    'GET /api/work-orders/o1': order,
    'GET /api/work-orders': [order],
    'GET /api/services': [],
    'GET /api/products': [fluid, unpriced, inactive],
    'GET /api/customers/c1': { id: 'c1', name: 'Cliente Teste' },
    'GET /api/vehicles/v1': { id: 'v1', manufacturer: 'Ford', model: 'Cargo', plate: 'AAA1A11' },
    ...overrides,
  }
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const path = String(input), method = init?.method || 'GET'
    if (path === '/api/iam/csrf') return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'test' })
    if (path === '/api/iam/session') return json(session)
    const key = `${method} ${path}`
    if (key in errors) return json(errors[key][0], errors[key][1])
    return key in routes ? json(routes[key], method === 'POST' ? 201 : 200) : json({ message: 'Rota não simulada' }, 500)
  })
}

function renderDetail() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/ordens-servico/o1']}>
    <AuthProvider><App /></AuthProvider>
  </MemoryRouter></QueryClientProvider>)
}

test('detalhe mostra snapshot do item físico e separa subtotais', async () => {
  mock()
  renderDetail()

  const item = await screen.findByText('Óleo ATF Dexron III')
  expect(item.closest('.service-row')).toHaveTextContent('ATF-D3 • Litro')
  expect(item.closest('.service-row')).toHaveTextContent('2,5 L × R$ 42,90')

  expect(screen.getByTestId('subtotal')).toHaveTextContent('100,00')
  expect(screen.getByTestId('products-subtotal')).toHaveTextContent('107,25')
  expect(screen.getByTestId('order-total')).toHaveTextContent('207,25')
})

test('catálogo do lançamento oferece apenas produto ativo com preço', async () => {
  mock()
  renderDetail()

  expect(await screen.findByRole('option', { name: /Óleo ATF Dexron III/ })).toBeInTheDocument()
  expect(screen.queryByRole('option', { name: /Peça sem preço/ })).not.toBeInTheDocument()
  expect(screen.queryByRole('option', { name: /Peça inativa/ })).not.toBeInTheDocument()
})

test('lançamento envia produto e quantidade e exige quantidade positiva', async () => {
  const fetchMock = mock({ 'POST /api/work-orders/o1/products': order })
  renderDetail()

  await screen.findByRole('option', { name: /Óleo ATF Dexron III/ })
  await userEvent.selectOptions(screen.getByLabelText('Produto do catálogo'), 'p1')
  const addButton = screen.getByRole('button', { name: 'Adicionar item à OS' })
  expect(addButton).toBeDisabled()

  await userEvent.type(screen.getByLabelText('Quantidade (Litro)'), '0')
  expect(addButton).toBeDisabled()

  await userEvent.clear(screen.getByLabelText('Quantidade (Litro)'))
  await userEvent.type(screen.getByLabelText('Quantidade (Litro)'), '2.5')
  await userEvent.click(addButton)

  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/work-orders/o1/products', expect.objectContaining({ method: 'POST' })))
  const request = fetchMock.mock.calls.find(call => String(call[0]) === '/api/work-orders/o1/products')?.[1]
  expect(JSON.parse(String(request?.body))).toEqual({ productId: 'p1', quantity: 2.5 })
})

test('recusa do backend aparece na tela', async () => {
  mock({}, { 'POST /api/work-orders/o1/products': [{ code: 'PRODUCT_INACTIVE', message: 'Produto inativo não pode ser lançado na OS' }, 409] })
  renderDetail()

  await screen.findByRole('option', { name: /Óleo ATF Dexron III/ })
  await userEvent.selectOptions(screen.getByLabelText('Produto do catálogo'), 'p1')
  await userEvent.type(screen.getByLabelText('Quantidade (Litro)'), '1')
  await userEvent.click(screen.getByRole('button', { name: 'Adicionar item à OS' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('Produto inativo não pode ser lançado na OS')
})

test('catálogo de produtos vazio orienta o operador', async () => {
  mock({ 'GET /api/products': [] })
  renderDetail()
  expect(await screen.findByText('Nenhum produto cadastrado.')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Adicionar item à OS' })).toBeDisabled()
})
