import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { json, lastBody, mockApi, page, renderAt, requestsTo } from './harness'

const oil = {
  productId: 'p1', description: 'Óleo ATF Dexron', internalCode: 'ATF-D3', category: 'Fluidos', unit: 'GALAO_5L',
  quantity: 2, minimumStock: 4, averageCost: 180, salePrice: 250, active: true, belowMinimum: true,
}
const seal = { ...oil, productId: 'p2', description: 'Retentor', internalCode: null, unit: 'UNIDADE', quantity: 20, minimumStock: 10, belowMinimum: false }
const movement = (id: string, type: string, incoming: boolean, extra = {}) => ({
  id, productId: 'p1', type, quantity: 2, unitCost: 180, balanceAfter: 2, averageCostAfter: 180, reason: 'Compra',
  source: 'MANUAL', workOrderId: null, reversesMovementId: null, occurredAt: '2026-09-17T10:00:00Z', recordedBy: 'u1', incoming, ...extra,
})

test('lista o estoque com filtro de abaixo do mínimo e alterna a regra de baixa', async () => {
  const fetch = mockApi({
    'GET /api/inventory/stock': (_init: RequestInit | undefined, url: URL) =>
      json(page(url.searchParams.get('belowMinimum') === 'true' ? [oil] : [oil, seal])),
    'GET /api/inventory/settings': { WORK_ORDER_WRITE_OFF: 'ITEM_LAUNCH' },
    'PUT /api/inventory/settings/write-off': { WORK_ORDER_WRITE_OFF: 'WORK_ORDER_FINISH' },
  })
  renderAt('/estoque')
  expect(await screen.findByRole('cell', { name: /^Retentor/ })).toBeInTheDocument()
  const oilRow = screen.getByRole('cell', { name: /^Óleo ATF Dexron/ }).closest('tr')!
  expect(within(oilRow).getByText('Abaixo do mínimo')).toBeInTheDocument()
  expect(within(oilRow).getByRole('cell', { name: 'Galão de 5 L' })).toBeInTheDocument()

  await userEvent.click(screen.getByRole('checkbox', { name: /Abaixo do mínimo/ }))
  await waitFor(() => expect(screen.queryByRole('cell', { name: /^Retentor/ })).not.toBeInTheDocument())

  await userEvent.selectOptions(screen.getByLabelText('Baixa pela OS'), 'WORK_ORDER_FINISH')
  await waitFor(() => expect(lastBody(fetch, 'PUT', '/api/inventory/settings/write-off')).toEqual({ mode: 'WORK_ORDER_FINISH' }))
})

test('registra entrada com custo e mostra o saldo atualizado', async () => {
  let balance = 2
  const fetch = mockApi({
    'GET /api/products/p1': { id: 'p1', description: 'Óleo ATF Dexron', unit: 'GALAO_5L', type: 'SUPPLY', active: true },
    'GET /api/inventory/stock': () => json(page([{ ...oil, quantity: balance }])),
    'GET /api/inventory/products/p1/movements': () => json(page([movement('m1', 'ENTRY', true)])),
    'POST /api/inventory/products/p1/entries': () => { balance = 5; return json(movement('m2', 'ENTRY', true, { balanceAfter: 5 }), 201) },
  })
  renderAt('/estoque/p1')
  expect(await screen.findByTestId('stock-balance')).toHaveTextContent('2')
  const form = screen.getByRole('region', { name: 'Registrar entrada' })
  await userEvent.type(within(form).getByLabelText('Quantidade'), '3')
  await userEvent.type(within(form).getByLabelText('Custo unitário'), '190')
  await userEvent.click(within(form).getByRole('button', { name: 'Registrar entrada' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/inventory/products/p1/entries')).toEqual({ quantity: 3, unitCost: 190, reason: null }))
  await waitFor(() => expect(screen.getByTestId('stock-balance')).toHaveTextContent('5'))
})

test('saída e ajuste exigem motivo e o ajuste envia a direção', async () => {
  const fetch = mockApi({
    'GET /api/products/p1': { id: 'p1', description: 'Óleo ATF Dexron', unit: 'GALAO_5L', type: 'SUPPLY', active: true },
    'GET /api/inventory/stock': page([oil]),
    'GET /api/inventory/products/p1/movements': page([]),
    'POST /api/inventory/products/p1/adjustments': movement('m3', 'ADJUSTMENT_OUT', false),
  })
  renderAt('/estoque/p1')
  const exitForm = await screen.findByRole('region', { name: 'Registrar saída' })
  await userEvent.type(within(exitForm).getByLabelText('Quantidade'), '1')
  expect(within(exitForm).getByRole('button', { name: 'Registrar saída' })).toBeDisabled()

  const adjustForm = screen.getByRole('region', { name: 'Ajustar saldo' })
  await userEvent.type(within(adjustForm).getByLabelText('Quantidade'), '1')
  await userEvent.type(within(adjustForm).getByLabelText('Motivo *'), 'Quebra no manuseio')
  await userEvent.click(within(adjustForm).getByRole('button', { name: 'Ajustar saldo' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/inventory/products/p1/adjustments'))
    .toEqual({ quantity: 1, direction: 'OUT', reason: 'Quebra no manuseio' }))
})

test('movimentação da OS não é estornável pela tela de estoque e a manual é', async () => {
  const fetch = mockApi({
    'GET /api/products/p1': { id: 'p1', description: 'Óleo ATF Dexron', unit: 'GALAO_5L', type: 'SUPPLY', active: true },
    'GET /api/inventory/stock': page([oil]),
    'GET /api/inventory/products/p1/movements': page([
      movement('m1', 'ENTRY', true),
      movement('m2', 'WORK_ORDER_OUT', false, { source: 'WORK_ORDER', workOrderId: 'o1', reason: null }),
    ]),
    'POST /api/inventory/movements/m1/reverse': movement('m3', 'REVERSAL', false),
  })
  renderAt('/estoque/p1')
  const workOrderRow = (await screen.findByRole('cell', { name: 'Baixa pela OS' })).closest('tr')!
  expect(within(workOrderRow).queryByRole('button', { name: 'Estornar' })).not.toBeInTheDocument()
  expect(within(workOrderRow).getByRole('link', { name: 'OS vinculada' })).toHaveAttribute('href', '/ordens-servico/o1')

  const entryRow = screen.getByRole('cell', { name: 'Entrada' }).closest('tr')!
  await userEvent.click(within(entryRow).getByRole('button', { name: 'Estornar' }))
  await waitFor(() => expect(requestsTo(fetch, 'POST', '/api/inventory/movements/m1/reverse')).toHaveLength(1))
})

test('saldo insuficiente ao lançar item físico na OS aparece na tela da OS', async () => {
  mockApi({
    'GET /api/work-orders/o1': {
      id: 'o1', number: 9, customerId: 'c1', vehicleId: 'v1', entryMileage: null, openedAt: '2026-09-17T10:00:00Z',
      status: 'ABERTA', statusInfo: { id: 's1', name: 'Aberta', stage: 'ABERTA', position: 10, active: true, stageDefault: true },
      complaint: 'Vazamento', notes: null, lifecycle: {}, services: [], products: [],
    },
    'GET /api/customers/c1': { id: 'c1', name: 'Maria' },
    'GET /api/vehicles/v1': { id: 'v1', manufacturer: 'Volvo', model: 'FH', plate: 'TRK1A11' },
    'GET /api/services': [],
    'GET /api/work-orders/o1/status-history': [],
    'GET /api/work-order-statuses': [],
    'GET /api/products': [{ id: 'p1', description: 'Bomba', internalCode: null, category: null, type: 'PART', unit: 'UNIDADE', referenceCost: null, salePrice: 900, minimumStock: null, active: true, createdAt: '', updatedAt: '' }],
    'POST /api/work-orders/o1/products': () => json({ code: 'INSUFFICIENT_STOCK', message: 'Saldo insuficiente: disponível 1' }, 409),
  })
  renderAt('/ordens-servico/o1')
  await screen.findByRole('option', { name: /Bomba/ })
  await userEvent.selectOptions(screen.getByLabelText('Produto do catálogo'), 'p1')
  await userEvent.type(screen.getByLabelText(/Quantidade/), '5')
  await userEvent.click(screen.getByRole('button', { name: 'Adicionar item à OS' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Saldo insuficiente')
})

test('unidade contável não aceita fração na movimentação e a contínua aceita três casas', async () => {
  mockApi({
    'GET /api/products/p1': { id: 'p1', description: 'Óleo ATF Dexron', unit: 'GALAO_5L', type: 'SUPPLY', active: true },
    'GET /api/inventory/stock': page([oil]),
    'GET /api/inventory/products/p1/movements': page([]),
  })
  renderAt('/estoque/p1')
  await screen.findByTestId('stock-balance')
  const form = screen.getByRole('region', { name: 'Registrar entrada' })
  const quantity = within(form).getByLabelText(/Quantidade/)
  expect(quantity).toHaveAttribute('step', '1')
  await userEvent.type(quantity, '1.5')
  expect(within(form).getByRole('button', { name: 'Registrar entrada' })).toBeDisabled()
  expect(within(form).getByText('Esta unidade aceita somente quantidade inteira.')).toBeInTheDocument()
  await userEvent.clear(quantity)
  await userEvent.type(quantity, '2')
  expect(within(form).getByRole('button', { name: 'Registrar entrada' })).toBeEnabled()
})
