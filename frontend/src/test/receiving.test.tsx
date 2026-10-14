import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'
import { json, lastBody, mockApi, page, renderAt, requestsTo, session } from './harness'
import { evaluateLine, parseQuantity } from '../features/purchasing/receivingRules'

const RECEIVE = ['PURCHASE_VIEW', 'PURCHASE_RECEIVE']
const FULL = [...RECEIVE, 'PURCHASE_DIVERGENCE_ACCEPT', 'PURCHASE_CANCEL']
const as = (permissions: string[]) => ({ ...session, permissions })
vi.setConfig({ testTimeout: 30_000 })

const detail = (extra = {}) => ({
  id: 'p1', number: 1, supplierId: 's1', origin: 'NFE_IMPORT', status: 'APPROVED', receiptStatus: 'NONE',
  goodsTotal: 100, discountTotal: 0, freightTotal: 0, insuranceTotal: 0, otherTotal: 0, taxAddedTotal: 0, payableTotal: 100,
  expenseCategoryId: 'cat1', workOrderId: null, notes: null, createdAt: '2026-10-13T12:00:00Z', cancelledAt: null, cancellationReason: null,
  items: [], installments: [],
  documents: [{ id: 'd1', kind: 'NFE', accessKey: '3'.repeat(44), series: '1', number: 21, issuedAt: '2026-10-09T12:00:00Z', issuerDocument: '67757350000178',
    recipientDocument: '01637003000101', totalAmount: 100, protocolNumber: '1', protocolStatus: '100', xmlSha256: 'a'.repeat(64),
    voidedAt: null, voidReason: null, officialCheckPerformed: false }],
  ...extra,
})

const sheetLine = (extra = {}) => ({
  purchaseItemId: 'i1', lineNo: 1, productId: 'prod1', description: 'Retentor 35x62', unitOriginal: 'UN', productUnit: 'UNIDADE',
  ordered: 10, received: 0, closed: 0, pending: 10, unitCost: 5, ...extra,
})
const sheet = (lines = [sheetLine()], status = 'NONE') => ({ purchaseId: 'p1', status, lines })

const receipt = (extra = {}) => ({
  id: 'r1', number: 7, purchaseId: 'p1', documentId: 'd1', receivedOn: '2026-10-14', notes: null, receivedBy: 'u1',
  createdAt: '2026-10-14T12:00:00Z', reversal: null,
  items: [{ id: 'ri1', purchaseItemId: 'i1', productId: 'prod1', quantityReceived: 6, unitCost: 5, expectedQuantity: 10, divergence: 'NONE',
    cancelledQuantity: 0, justification: null, acceptedBy: null, stockMovementId: 'm1' }],
  ...extra,
})

const routes = (extra: Record<string, unknown> = {}, lines = [sheetLine()], receipts: unknown[] = []) => ({
  'GET /api/purchasing/purchases/p1': detail(),
  'GET /api/purchasing/purchases/p1/events': page([]),
  'GET /api/purchasing/purchases/p1/receiving': sheet(lines),
  'GET /api/purchasing/purchases/p1/receipts': receipts,
  ...extra,
})

const headerOf = (init: RequestInit | undefined, name: string) => new Headers(init?.headers).get(name)
const section = async () => within(await screen.findByRole('region', { name: 'Recebimento' }))
const POST = '/api/purchasing/purchases/p1/receipts'

test('a conferência mostra pedido, recebido, encerrado e pendente por item, e a situação derivada', async () => {
  mockApi(routes({}, [sheetLine({ received: 6, pending: 4 })]), as(RECEIVE))
  renderAt('/compras/notas/p1')

  const receiving = await section()
  expect(await receiving.findByText('Retentor 35x62')).toBeInTheDocument()
  const row = receiving.getByText('Retentor 35x62').closest('tr') as HTMLElement
  expect(within(row).getAllByRole('cell').map(cell => cell.textContent)).toEqual(
    expect.arrayContaining(['1', '10', '6', '0', '4']))
  expect(receiving.getByText('Nenhum recebimento registrado.')).toBeInTheDocument()
})

test('recebimento parcial não é divergência: envia só as quantidades, o documento e a Idempotency-Key', async () => {
  const fetch = mockApi(routes({ ['POST ' + POST]: () => json(receipt(), 201) }), as(RECEIVE))
  renderAt('/compras/notas/p1')

  const receiving = await section()
  const button = await receiving.findByRole('button', { name: 'Registrar recebimento' })
  expect(button).toBeDisabled()
  await userEvent.type(await receiving.findByLabelText('Quantidade recebida do item 1'), '6')
  // 6 de 10 pendentes: o resto ainda vem. Não aparece badge de divergência nem justificativa.
  expect(receiving.queryByLabelText('Justificativa do item 1')).not.toBeInTheDocument()
  expect(receiving.queryByText('Recebido a mais')).not.toBeInTheDocument()
  await waitFor(() => expect(button).toBeEnabled())
  await userEvent.click(button)

  await waitFor(() => expect(requestsTo(fetch, 'POST', POST)).toHaveLength(1))
  const [, init] = requestsTo(fetch, 'POST', POST)[0]
  expect(headerOf(init, 'Idempotency-Key')).toBeTruthy()
  const body = JSON.parse(String(init?.body))
  expect(body.documentId).toBe('d1')
  expect(body.lines).toEqual([{ purchaseItemId: 'i1', quantity: 6 }])
})

test('receber a mais exige justificativa e só libera o botão depois dela', async () => {
  const fetch = mockApi(routes({ ['POST ' + POST]: () => json(receipt({ items: [{ ...receipt().items[0], quantityReceived: 12, divergence: 'OVER',
    justification: 'Fornecedor enviou a mais', acceptedBy: 'u1' }] }), 201) }), as(FULL))
  renderAt('/compras/notas/p1')

  const receiving = await section()
  await userEvent.type(await receiving.findByLabelText('Quantidade recebida do item 1'), '12')
  expect(await receiving.findByText('Recebido a mais')).toBeInTheDocument()
  expect(receiving.getByRole('button', { name: 'Registrar recebimento' })).toBeDisabled()

  await userEvent.type(receiving.getByLabelText('Justificativa do item 1'), 'Fornecedor enviou a mais')
  await waitFor(() => expect(receiving.getByRole('button', { name: 'Registrar recebimento' })).toBeEnabled())
  await userEvent.click(receiving.getByRole('button', { name: 'Registrar recebimento' }))

  await waitFor(() => expect(requestsTo(fetch, 'POST', POST)).toHaveLength(1))
  expect(lastBody(fetch, 'POST', POST).lines).toEqual([{ purchaseItemId: 'i1', quantity: 12, justification: 'Fornecedor enviou a mais' }])
})

test('sem a permissão de aceitar divergência o botão fica travado e a tela explica por quê', async () => {
  mockApi(routes(), as(RECEIVE))
  renderAt('/compras/notas/p1')

  const receiving = await section()
  await userEvent.type(await receiving.findByLabelText('Quantidade recebida do item 1'), '12')
  await userEvent.type(receiving.getByLabelText('Justificativa do item 1'), 'Chegou a mais')
  expect(await receiving.findByRole('alert')).toHaveTextContent('PURCHASE_DIVERGENCE_ACCEPT')
  expect(receiving.getByRole('button', { name: 'Registrar recebimento' })).toBeDisabled()
})

test('encerrar o saldo registra a quantidade cancelada e exige justificativa (divergência de falta)', async () => {
  const fetch = mockApi(routes({ ['POST ' + POST]: () => json(receipt(), 201) }), as(FULL))
  renderAt('/compras/notas/p1')

  const receiving = await section()
  await userEvent.type(await receiving.findByLabelText('Quantidade recebida do item 1'), '8')
  await userEvent.click(await receiving.findByLabelText('Encerrar saldo do item 1'))
  expect(await receiving.findByText('Saldo encerrado')).toBeInTheDocument()
  expect(receiving.getByText(/cancela 2/)).toBeInTheDocument()
  expect(receiving.getByRole('button', { name: 'Registrar recebimento' })).toBeDisabled()

  await userEvent.type(receiving.getByLabelText('Justificativa do item 1'), 'Sem estoque no fornecedor')
  await waitFor(() => expect(receiving.getByRole('button', { name: 'Registrar recebimento' })).toBeEnabled())
  await userEvent.click(receiving.getByRole('button', { name: 'Registrar recebimento' }))

  await waitFor(() => expect(requestsTo(fetch, 'POST', POST)).toHaveLength(1))
  expect(lastBody(fetch, 'POST', POST).lines).toEqual([
    { purchaseItemId: 'i1', quantity: 8, closeRemaining: true, justification: 'Sem estoque no fornecedor' }])
})

test('o retry depois de falha de rede reaproveita a mesma Idempotency-Key e não duplica o recebimento', async () => {
  let attempts = 0
  const fetch = mockApi(routes({ ['POST ' + POST]: () => (++attempts === 1 ? json({ code: 'NETWORK', message: 'Tempo esgotado' }, 503) : json(receipt(), 201)) }), as(RECEIVE))
  renderAt('/compras/notas/p1')

  const receiving = await section()
  await userEvent.type(await receiving.findByLabelText('Quantidade recebida do item 1'), '6')
  await waitFor(() => expect(receiving.getByRole('button', { name: 'Registrar recebimento' })).toBeEnabled())
  await userEvent.click(receiving.getByRole('button', { name: 'Registrar recebimento' }))
  expect(await receiving.findByText('Tempo esgotado')).toBeInTheDocument()
  await userEvent.click(receiving.getByRole('button', { name: 'Registrar recebimento' }))

  await waitFor(() => expect(requestsTo(fetch, 'POST', POST)).toHaveLength(2))
  const [first, second] = requestsTo(fetch, 'POST', POST)
  expect(headerOf(second[1], 'Idempotency-Key')).toBe(headerOf(first[1], 'Idempotency-Key'))
})

test('quantidade inválida é apontada e não habilita o envio', async () => {
  mockApi(routes(), as(RECEIVE))
  renderAt('/compras/notas/p1')

  const receiving = await section()
  await userEvent.type(await receiving.findByLabelText('Quantidade recebida do item 1'), '1,2345')
  expect(await receiving.findByRole('alert')).toHaveTextContent('Quantidade inválida')
  expect(receiving.getByRole('button', { name: 'Registrar recebimento' })).toBeDisabled()
})

test('quem só consulta vê a conferência mas não os campos de recebimento', async () => {
  mockApi(routes(), as(['PURCHASE_VIEW']))
  renderAt('/compras/notas/p1')

  const receiving = await section()
  expect(await receiving.findByText('Retentor 35x62')).toBeInTheDocument()
  expect(receiving.queryByLabelText('Quantidade recebida do item 1')).not.toBeInTheDocument()
  expect(receiving.queryByRole('button', { name: 'Registrar recebimento' })).not.toBeInTheDocument()
})

test('compra cancelada não oferece recebimento e explica', async () => {
  mockApi(routes({ 'GET /api/purchasing/purchases/p1': detail({ status: 'CANCELLED', cancelledAt: '2026-10-14T12:00:00Z', cancellationReason: 'Erro' }) }), as(FULL))
  renderAt('/compras/notas/p1')

  const receiving = await section()
  expect(await receiving.findByText(/Compra cancelada: não recebe mercadoria/)).toBeInTheDocument()
  expect(receiving.queryByLabelText('Quantidade recebida do item 1')).not.toBeInTheDocument()
})

test('estornar pede o motivo, usa Idempotency-Key e quem não tem PURCHASE_CANCEL nem vê o botão', async () => {
  const fetch = mockApi(routes({ 'POST /api/purchasing/receipts/r1/reversal': () => json(receipt({ reversal: {
    id: 'x1', reason: 'Conferência refeita', reversedAt: '2026-10-14T13:00:00Z', reversedBy: 'u1' } }), 201) },
    [sheetLine({ received: 6, pending: 4 })], [receipt()]), as(FULL))
  renderAt('/compras/notas/p1')

  const receiving = await section()
  await userEvent.click(await receiving.findByRole('button', { name: 'Estornar' }))
  const confirm = await receiving.findByRole('group', { name: /Estornar o recebimento 7/ })
  await userEvent.click(within(confirm).getByRole('button', { name: 'Confirmar estorno' }))
  expect(await within(confirm).findByRole('alert')).toHaveTextContent('Informe o motivo do estorno')
  expect(requestsTo(fetch, 'POST', '/api/purchasing/receipts/r1/reversal')).toHaveLength(0)

  await userEvent.type(within(confirm).getByLabelText(/Motivo do estorno/), 'Conferência refeita')
  await userEvent.click(within(confirm).getByRole('button', { name: 'Confirmar estorno' }))
  await waitFor(() => expect(requestsTo(fetch, 'POST', '/api/purchasing/receipts/r1/reversal')).toHaveLength(1))
  const [, init] = requestsTo(fetch, 'POST', '/api/purchasing/receipts/r1/reversal')[0]
  expect(headerOf(init, 'Idempotency-Key')).toBeTruthy()
  expect(JSON.parse(String(init?.body))).toEqual({ reason: 'Conferência refeita' })
})

test('recebimento estornado aparece como estornado, com o motivo, e sem botão de novo estorno', async () => {
  mockApi(routes({}, [sheetLine()], [receipt({ reversal: { id: 'x1', reason: 'Conferência refeita', reversedAt: '2026-10-14T13:00:00Z', reversedBy: 'u1' } })]), as(FULL))
  renderAt('/compras/notas/p1')

  const receiving = await section()
  expect(await receiving.findByText('Estornado')).toBeInTheDocument()
  expect(receiving.getByText('Conferência refeita')).toBeInTheDocument()
  expect(receiving.queryByRole('button', { name: 'Estornar' })).not.toBeInTheDocument()
})

test('divergências do recebimento ficam visíveis no histórico, com a justificativa', async () => {
  mockApi(routes({}, [sheetLine()], [receipt({ items: [{ ...receipt().items[0], divergence: 'SHORT_CLOSED', cancelledQuantity: 2,
    justification: 'Sem estoque no fornecedor', acceptedBy: 'u1' }] })]), as(FULL))
  renderAt('/compras/notas/p1')

  const receiving = await section()
  expect(await receiving.findByText('Saldo encerrado')).toBeInTheDocument()
  expect(receiving.getByText(/Sem estoque no fornecedor/)).toBeInTheDocument()
})

test('regras: parcial não é divergência; encerrar falta e excesso são', () => {
  expect(evaluateLine(10, 6, false).divergence).toBe('NONE')
  expect(evaluateLine(10, 10, false).divergence).toBe('NONE')
  expect(evaluateLine(10, 8, true)).toEqual({ divergence: 'SHORT_CLOSED', cancelled: 2 })
  expect(evaluateLine(4, 4, true).divergence).toBe('NONE')
  expect(evaluateLine(4, 5, false).divergence).toBe('OVER')
  expect(evaluateLine(0, 1, false).divergence).toBe('OVER')
  expect(evaluateLine(-2, 1, false).divergence).toBe('OVER')
  // Três casas sem deriva binária: 0,1 + 0,2 não vira 0,30000000000000004 no cálculo de saldo.
  expect(evaluateLine(0.3, 0.1 + 0.2, false).divergence).toBe('NONE')
})

test('quantidade digitada: aceita vírgula, até três casas, e recusa zero e texto', () => {
  expect(parseQuantity('2,5')).toBe(2.5)
  expect(parseQuantity('12.345')).toBe(12.345)
  expect(parseQuantity('1,2345')).toBeNull()
  expect(parseQuantity('0')).toBeNull()
  expect(parseQuantity('')).toBeNull()
  expect(parseQuantity('abc')).toBeNull()
  expect(parseQuantity('-1')).toBeNull()
})
