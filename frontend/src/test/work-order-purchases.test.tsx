import { screen, within } from '@testing-library/react'
import { expect, test } from 'vitest'
import { mockApi, page, renderAt, session } from './harness'

const as = (permissions: string[]) => ({ ...session, permissions })
const order = {
  id: 'o1', number: 7, customerId: 'c1', vehicleId: 'v1', entryMileage: 100, openedAt: '2026-09-10T10:00:00Z',
  status: 'ABERTA', services: [], products: [],
}
const purchase = (extra = {}) => ({
  id: 'p1', number: 12, supplierId: 's1', supplierName: 'Hidráulica Sul Ltda', origin: 'NFE_IMPORT', status: 'APPROVED',
  payableTotal: 640.5, accessKey: null, invoiceNumber: 2210, issuedOn: '2026-11-03', createdAt: '2026-11-03T12:00:00Z',
  installmentCount: 2, receiptStatus: 'PARTIAL', ...extra,
})

const routes = (purchases: unknown[]) => ({
  'GET /api/work-orders/o1': order,
  'GET /api/work-orders/o1/status-history': [],
  'GET /api/work-order-statuses': [],
  'GET /api/services': [],
  'GET /api/products': [],
  'GET /api/customers/c1': { id: 'c1', name: 'Cliente Teste' },
  'GET /api/vehicles/v1': { id: 'v1', manufacturer: 'Ford', model: 'Cargo', plate: 'AAA1A11' },
  'GET /api/purchasing/purchases': page(purchases),
})

test('a OS lista as compras ligadas a ela, soma só as notas ativas e oferece os atalhos de compra', async () => {
  const fetch = mockApi(routes([
    purchase(),
    purchase({ id: 'p2', number: 13, origin: 'ORDER', status: 'APPROVED', payableTotal: 300, invoiceNumber: null, issuedOn: null }),
    purchase({ id: 'p3', number: 14, status: 'CANCELLED', payableTotal: 999 }),
  ]), as(['PURCHASE_VIEW', 'PURCHASE_IMPORT_XML', 'PURCHASE_ORDER_MANAGE']))
  renderAt('/ordens-servico/o1')

  const card = within(await screen.findByRole('region', { name: 'Compras desta OS' }))
  expect(await card.findByText('Compra #12')).toBeInTheDocument()
  expect(card.getAllByText(/Hidráulica Sul Ltda · nota 2210/)[0]).toHaveTextContent('recebimento')
  expect(card.getByText('Compra #13')).toBeInTheDocument()
  // Pedido não é obrigação e compra cancelada não conta: só a nota ativa entra na soma.
  expect(card.getByTestId('work-order-purchases-total')).toHaveTextContent('640,50')
  expect(card.getByRole('link', { name: 'Importar NF-e para esta OS' })).toHaveAttribute('href', '/compras/notas/importar?os=o1')
  expect(card.getByRole('link', { name: 'Novo pedido para esta OS' })).toHaveAttribute('href', '/compras/pedidos/novo?os=o1')

  const call = fetch.mock.calls.find(([input]) => String(input).startsWith('/api/purchasing/purchases?'))
  expect(String(call?.[0])).toContain('workOrderId=o1')
})

test('sem PURCHASE_VIEW a OS não mostra nem consulta as compras', async () => {
  const fetch = mockApi(routes([purchase()]), as([]))
  renderAt('/ordens-servico/o1')

  expect(await screen.findByText('OS #7')).toBeInTheDocument()
  expect(screen.queryByRole('region', { name: 'Compras desta OS' })).not.toBeInTheDocument()
  expect(fetch.mock.calls.some(([input]) => String(input).startsWith('/api/purchasing/purchases'))).toBe(false)
})

test('OS encerrada lista as compras mas não oferece comprar mais', async () => {
  mockApi({ ...routes([purchase()]), 'GET /api/work-orders/o1': { ...order, status: 'ENTREGUE' } },
    as(['PURCHASE_VIEW', 'PURCHASE_IMPORT_XML', 'PURCHASE_ORDER_MANAGE']))
  renderAt('/ordens-servico/o1')

  const card = within(await screen.findByRole('region', { name: 'Compras desta OS' }))
  expect(await card.findByText('Compra #12')).toBeInTheDocument()
  expect(card.queryByRole('link', { name: 'Importar NF-e para esta OS' })).not.toBeInTheDocument()
})
