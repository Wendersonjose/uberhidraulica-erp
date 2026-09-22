import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { json, lastBody, mockApi, renderAt, requestsTo, session } from './harness'

const ALL = ['FINANCE_VIEW', 'FINANCE_RECEIVE', 'FINANCE_REVERSE', 'FINANCE_ADJUST', 'FINANCE_PAYABLE', 'FINANCE_CONFIG']
const as = (permissions: string[]) => ({ ...session, permissions })
const methods = [
  { id: 'm-cash', code: 'DINHEIRO', name: 'Dinheiro', active: true, cashSessionRequired: true },
  { id: 'm-pix', code: 'PIX', name: 'PIX', active: true, cashSessionRequired: false },
  { id: 'm-off', code: 'VALE', name: 'Vale', active: false, cashSessionRequired: false },
]
const receipt = (id: string, extra = {}) => ({
  id, ownerId: 'r1', amount: 100, paymentMethodId: 'm-pix', paymentMethodName: 'PIX', effectiveOn: '2026-09-20', notes: null,
  recordedAt: '2026-09-20T12:00:00Z', recordedBy: 'u1', reversed: false, reversal: null, ...extra,
})
const receivable = (extra = {}) => ({
  id: 'r1', workOrderId: 'o1', workOrderNumber: 12, customerId: 'c1', customerName: 'Maria Souza', billingQuoteId: 'q1',
  originalAmount: 450, discountAmount: 0, surchargeAmount: 0, adjustedAmount: 450, receivedAmount: 100, outstandingBalance: 350,
  issuedOn: '2026-09-20', dueDate: '2026-09-30', status: 'PARCIAL', createdAt: '2026-09-20T12:00:00Z', cancelledAt: null, cancellationReason: null,
  lines: [{ id: 'l1', quoteItemRevisionId: 'qr1', description: 'Troca de retentor', quantity: 1, unitPrice: 350, discountAmount: 0, totalAmount: 350, displayOrder: 1 },
    { id: 'l2', quoteItemRevisionId: 'qr2', description: 'Alinhamento', quantity: 1, unitPrice: 120, discountAmount: 20, totalAmount: 100, displayOrder: 2 }],
  adjustments: [], dueDateChanges: [], receipts: [receipt('p1')], ...extra,
})
const headerOf = (init: RequestInit | undefined, name: string) => new Headers(init?.headers).get(name)

test('menu do Financeiro só aparece para quem tem FINANCE_VIEW', async () => {
  mockApi({ 'GET /api/work-orders/board': [] }, as(['FINANCE_VIEW']))
  renderAt('/ordens-servico')
  expect(await screen.findByRole('link', { name: 'Contas a receber' })).toHaveAttribute('href', '/financeiro/recebiveis')
  expect(screen.getByRole('link', { name: 'Fluxo de caixa' })).toBeInTheDocument()
})

test('sem FINANCE_VIEW o menu não oferece o Financeiro', async () => {
  mockApi({ 'GET /api/work-orders/board': [] }, as([]))
  renderAt('/ordens-servico')
  await screen.findByRole('link', { name: 'Ordens de serviço' })
  expect(screen.queryByRole('link', { name: 'Contas a receber' })).not.toBeInTheDocument()
})

test('recebível mostra valores derivados e registra recebimento com chave de idempotência reaproveitada no retry', async () => {
  let attempts = 0
  const fetch = mockApi({
    'GET /api/finance/receivables/r1': receivable(),
    'GET /api/finance/payment-methods': methods,
    'POST /api/finance/receivables/r1/receipts': () => (++attempts === 1
      ? json({ code: 'NETWORK', message: 'Tempo esgotado' }, 503)
      : json(receipt('p2', { amount: 350 }), 201)),
  }, as(ALL))
  renderAt('/financeiro/recebiveis/r1')
  expect(await screen.findByTestId('outstanding-balance')).toHaveTextContent('350,00')
  expect(screen.getByTestId('original-amount')).toHaveTextContent('450,00')
  expect(screen.getByRole('cell', { name: 'Alinhamento' })).toBeInTheDocument()

  const form = screen.getByRole('region', { name: 'Registrar recebimento' })
  const method = within(form).getByLabelText(/Forma de pagamento/)
  await within(form).findByRole('option', { name: /PIX/ })
  expect(within(form).getByRole('option', { name: /Dinheiro — exige sessão de caixa/ })).toBeDisabled()
  expect(within(form).queryByRole('option', { name: 'Vale' })).not.toBeInTheDocument()
  await userEvent.selectOptions(method, 'm-pix')
  await userEvent.click(within(form).getByRole('button', { name: 'Registrar recebimento' }))
  expect(await within(form).findByRole('alert')).toHaveTextContent('Tempo esgotado')
  await userEvent.click(within(form).getByRole('button', { name: 'Registrar recebimento' }))

  await waitFor(() => expect(requestsTo(fetch, 'POST', '/api/finance/receivables/r1/receipts')).toHaveLength(2))
  const [first, second] = requestsTo(fetch, 'POST', '/api/finance/receivables/r1/receipts')
  expect(headerOf(first[1], 'Idempotency-Key')).toBeTruthy()
  expect(headerOf(second[1], 'Idempotency-Key')).toBe(headerOf(first[1], 'Idempotency-Key'))
  expect(lastBody(fetch, 'POST', '/api/finance/receivables/r1/receipts')).toMatchObject({ amount: 350, paymentMethodId: 'm-pix' })
})

test('valor acima do saldo não pode ser enviado', async () => {
  mockApi({ 'GET /api/finance/receivables/r1': receivable(), 'GET /api/finance/payment-methods': methods }, as(ALL))
  renderAt('/financeiro/recebiveis/r1')
  const form = await screen.findByRole('region', { name: 'Registrar recebimento' })
  const amount = within(form).getByLabelText(/Valor/)
  await userEvent.clear(amount)
  await userEvent.type(amount, '350.01')
  expect(within(form).getByText(/Maior que o saldo em aberto/)).toBeInTheDocument()
  expect(within(form).getByRole('button', { name: 'Registrar recebimento' })).toBeDisabled()
})

test('gerente administrativo recebe, mas não vê estorno, ajuste nem vencimento', async () => {
  mockApi({ 'GET /api/finance/receivables/r1': receivable(), 'GET /api/finance/payment-methods': methods }, as(['FINANCE_VIEW', 'FINANCE_RECEIVE']))
  renderAt('/financeiro/recebiveis/r1')
  expect(await screen.findByRole('region', { name: 'Registrar recebimento' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Estornar' })).not.toBeInTheDocument()
  expect(screen.queryByRole('region', { name: 'Desconto ou acréscimo' })).not.toBeInTheDocument()
  expect(screen.queryByRole('region', { name: 'Alterar vencimento' })).not.toBeInTheDocument()
})

test('estorno exige motivo e envia a chave de idempotência', async () => {
  const fetch = mockApi({
    'GET /api/finance/receivables/r1': receivable(),
    'GET /api/finance/payment-methods': methods,
    'POST /api/finance/receipts/p1/reversal': receipt('p1', { reversed: true }),
  }, as(ALL))
  renderAt('/financeiro/recebiveis/r1')
  await userEvent.click(await screen.findByRole('button', { name: 'Estornar' }))
  const confirm = screen.getByRole('button', { name: 'Confirmar estorno' })
  expect(confirm).toBeDisabled()
  await userEvent.type(screen.getByLabelText('Motivo do estorno'), 'Valor errado')
  await userEvent.click(confirm)
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/finance/receipts/p1/reversal')).toEqual({ reason: 'Valor errado' }))
  expect(headerOf(requestsTo(fetch, 'POST', '/api/finance/receipts/p1/reversal')[0][1], 'Idempotency-Key')).toBeTruthy()
})

test('desconto exige motivo e preserva o valor original na tela', async () => {
  const fetch = mockApi({
    'GET /api/finance/receivables/r1': receivable(),
    'GET /api/finance/payment-methods': methods,
    'POST /api/finance/receivables/r1/adjustments': receivable({ discountAmount: 50, adjustedAmount: 400, outstandingBalance: 300 }),
  }, as(ALL))
  renderAt('/financeiro/recebiveis/r1')
  const form = await screen.findByRole('region', { name: 'Desconto ou acréscimo' })
  await userEvent.type(within(form).getByLabelText(/Valor do ajuste/), '50')
  expect(within(form).getByRole('button', { name: 'Registrar ajuste' })).toBeDisabled()
  await userEvent.type(within(form).getByLabelText(/Motivo do ajuste/), 'Cliente antigo')
  await userEvent.click(within(form).getByRole('button', { name: 'Registrar ajuste' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/finance/receivables/r1/adjustments'))
    .toEqual({ type: 'DISCOUNT', amount: 50, reason: 'Cliente antigo' }))
})

test('finalizar OS com mais de um orçamento aprovado pede a escolha e reenvia com billingQuoteId', async () => {
  const lifecycle = { executionStartedAt: '2026-09-20T10:00:00Z', finishedAt: null, finishedBy: null, deliveredAt: null, deliveredBy: null, cancelledAt: null, cancelledBy: null, cancellationReason: null }
  const execution = { id: 's6', name: 'Em execução', stage: 'EM_EXECUCAO', position: 60, active: true, stageDefault: true }
  const fetch = mockApi({
    'GET /api/work-orders/o1': { id: 'o1', number: 12, customerId: 'c1', vehicleId: 'v1', entryMileage: null, openedAt: '2026-09-17T10:00:00Z',
      status: 'EM_EXECUCAO', statusInfo: execution, complaint: 'Vazamento', notes: null, lifecycle, services: [], products: [] },
    'GET /api/customers/c1': { id: 'c1', name: 'Maria Souza' },
    'GET /api/vehicles/v1': { id: 'v1', manufacturer: 'Volvo', model: 'FH', plate: 'TRK1A11' },
    'GET /api/services': [], 'GET /api/products': [], 'GET /api/work-orders/o1/status-history': [],
    'GET /api/work-order-statuses': [{ status: execution, orderCount: 1 }],
    'GET /api/work-orders/o1/quotes': [{ id: 'qa', workOrderId: 'o1', createdAt: '2026-09-18T10:00:00Z', revisions: [], items: [] },
      { id: 'qb', workOrderId: 'o1', createdAt: '2026-09-19T10:00:00Z', revisions: [], items: [] }],
    'POST /api/work-orders/o1/finish': (init: RequestInit | undefined) => (String(init?.body).includes('billingQuoteId')
      ? json({ id: 'o1' }) : json({ code: 'BILLING_QUOTE_SELECTION_REQUIRED', message: 'A OS tem mais de um orçamento aprovado; escolha qual será cobrado' }, 409)),
  }, as(['FINANCE_BILL']))
  renderAt('/ordens-servico/o1')
  await userEvent.click(await screen.findByRole('button', { name: 'Finalizar OS' }))
  const dialog = screen.getByRole('group', { name: /Finalizar a OS/ })
  await userEvent.click(within(dialog).getByRole('button', { name: 'Finalizar OS' }))
  expect(await within(dialog).findByRole('alert')).toHaveTextContent('escolha qual será cobrado')
  await within(dialog).findByRole('option', { name: /Orçamento 2/ })
  await userEvent.selectOptions(within(dialog).getByLabelText(/Orçamento a cobrar/), 'qb')
  await userEvent.click(within(dialog).getByRole('button', { name: 'Finalizar OS' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/work-orders/o1/finish')).toEqual({ billingQuoteId: 'qb' }))
})

test('fluxo de caixa separa realizado de previsto e fala em variação líquida, não em saldo', async () => {
  const block = (inflows: number, outflows: number) => ({ inflows, outflows, net: inflows - outflows, days: [{ date: '2026-09-20', inflows, outflows, net: inflows - outflows }] })
  mockApi({
    'GET /api/finance/expense-categories': [],
    'GET /api/finance/cash-flow': { from: '2026-09-01', to: '2026-09-22', categoryId: null, realized: block(300, 200), forecast: block(700, 300) },
  }, as(ALL))
  renderAt('/financeiro/fluxo-de-caixa')
  expect(await screen.findByTestId('Realizado-net')).toHaveTextContent('100,00')
  expect(screen.getByTestId('Previsto-inflows')).toHaveTextContent('700,00')
  expect(screen.getAllByText('Variação líquida')).toHaveLength(2)
  // Sem saldo inicial, nenhum indicador se apresenta como saldo de caixa.
  expect([...document.querySelectorAll('dt')].map(term => term.textContent)).not.toContainEqual(expect.stringMatching(/saldo/i))
})

test('nova conta a pagar exige vencimento e envia chave de idempotência', async () => {
  const fetch = mockApi({
    'GET /api/finance/expense-categories': [{ id: 'cat1', name: 'Aluguel', active: true }, { id: 'cat2', name: 'Antiga', active: false }],
    'POST /api/finance/payables': { id: 'pay1' },
    'GET /api/finance/payables/pay1': { id: 'pay1', description: 'Aluguel', supplier: null, categoryId: 'cat1', categoryName: 'Aluguel', amount: 3000,
      paidAmount: 0, outstandingBalance: 3000, dueDate: '2026-09-30', status: 'ABERTO', notes: null, createdAt: '', cancelledAt: null, cancellationReason: null, payments: [] },
    'GET /api/finance/payment-methods': methods,
  }, as(ALL))
  renderAt('/financeiro/contas-a-pagar/nova')
  await userEvent.type(await screen.findByLabelText(/Descrição/), 'Aluguel de setembro')
  await screen.findByRole('option', { name: 'Aluguel' })
  expect(screen.queryByRole('option', { name: 'Antiga' })).not.toBeInTheDocument()
  await userEvent.selectOptions(screen.getByLabelText(/Categoria/), 'cat1')
  await userEvent.type(screen.getByLabelText(/Valor/), '3000')
  await userEvent.clear(screen.getByLabelText(/Vencimento/))
  expect(screen.getByRole('button', { name: 'Lançar conta' })).toBeDisabled()
  await userEvent.type(screen.getByLabelText(/Vencimento/), '2026-09-30')
  await userEvent.click(screen.getByRole('button', { name: 'Lançar conta' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/finance/payables')).toEqual({
    description: 'Aluguel de setembro', supplier: null, categoryId: 'cat1', amount: 3000, dueDate: '2026-09-30', notes: null,
  }))
  expect(headerOf(requestsTo(fetch, 'POST', '/api/finance/payables')[0][1], 'Idempotency-Key')).toBeTruthy()
  expect(await screen.findByTestId('payable-balance')).toHaveTextContent('3.000,00')
})

test('editor de orçamento cobra um item físico da OS com o vínculo da DR-0008', async () => {
  const fetch = mockApi({
    'GET /api/work-orders/o1/quotes/q1': { id: 'q1', workOrderId: 'o1', createdAt: '2026-09-18T10:00:00Z', revisions: [], items: [] },
    'GET /api/work-orders/o1': { id: 'o1', number: 12, customerId: 'c1', vehicleId: 'v1', status: 'ABERTA', lifecycle: {}, services: [],
      products: [{ id: 'wp1', productId: 'p1', description: 'Bomba hidráulica', internalCode: null, unit: 'UNIDADE', quantity: 1, unitPrice: 900, addedAt: '' }] },
    'POST /api/work-orders/o1/quotes/q1/revisions': { id: 'q1', workOrderId: 'o1', createdAt: '', revisions: [], items: [] },
  }, as(['QUOTE_PRESENT']))
  renderAt('/ordens-servico/o1/orcamentos/q1')
  await screen.findByRole('option', { name: 'Bomba hidráulica' })
  await userEvent.selectOptions(screen.getByLabelText('Cobrar item físico da OS'), 'wp1')
  expect(screen.getByLabelText('Descrição 1')).toHaveValue('Bomba hidráulica')
  await userEvent.click(screen.getByRole('button', { name: /Criar revisão|Salvar revisão|Criar rascunho/ }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/work-orders/o1/quotes/q1/revisions')).toEqual({
    items: [{ quoteItemId: null, workOrderProductId: 'wp1', description: 'Bomba hidráulica', quantity: 1, unitPrice: 900 }],
  }))
  expect(requestsTo(fetch, 'GET', '/api/work-orders/o1')).not.toHaveLength(0)
})

const executionOrder = () => {
  const execution = { id: 's6', name: 'Em execução', stage: 'EM_EXECUCAO', position: 60, active: true, stageDefault: true }
  return {
    'GET /api/work-orders/o1': { id: 'o1', number: 12, customerId: 'c1', vehicleId: 'v1', entryMileage: null, openedAt: '2026-09-17T10:00:00Z',
      status: 'EM_EXECUCAO', statusInfo: execution, complaint: 'Vazamento', notes: null, lifecycle: {}, services: [], products: [] },
    'GET /api/customers/c1': { id: 'c1', name: 'Maria Souza' },
    'GET /api/vehicles/v1': { id: 'v1', manufacturer: 'Volvo', model: 'FH', plate: 'TRK1A11' },
    'GET /api/services': [], 'GET /api/products': [], 'GET /api/work-orders/o1/status-history': [],
    'GET /api/work-order-statuses': [{ status: execution, orderCount: 1 }],
  }
}

test('sem FINANCE_BILL a finalização não é oferecida', async () => {
  mockApi(executionOrder(), as(['FINANCE_VIEW']))
  renderAt('/ordens-servico/o1')
  expect(await screen.findByText(/exige a permissão de faturamento/)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Finalizar OS' })).not.toBeInTheDocument()
})

test('nova forma de pagamento exige declarar se movimenta dinheiro físico', async () => {
  const fetch = mockApi({
    'GET /api/finance/payment-methods': methods,
    'GET /api/finance/expense-categories': [],
    'GET /api/finance/settings': { defaultReceivableDueDays: 0 },
    'POST /api/finance/payment-methods': { id: 'm-new', code: 'DINHEIRO_BALCAO', name: 'Dinheiro balcão', active: true, cashSessionRequired: true },
  }, as(ALL))
  renderAt('/financeiro/configuracoes')
  const card = await screen.findByRole('region', { name: 'Formas de pagamento' })
  await userEvent.type(within(card).getByLabelText('Nome'), 'Dinheiro balcão')
  expect(within(card).getByRole('button', { name: 'Cadastrar' })).toBeDisabled()
  await userEvent.click(within(card).getByLabelText(/Sim — fica indisponível/))
  await userEvent.click(within(card).getByRole('button', { name: 'Cadastrar' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/finance/payment-methods')).toEqual({ name: 'Dinheiro balcão', cashSessionRequired: true }))
})

test('ajuste lançado errado é estornado com motivo e chave, e o histórico mostra o estorno', async () => {
  const adjustment = { id: 'a1', type: 'DISCOUNT', amount: 50, reason: 'Negociação', recordedAt: '2026-09-21T10:00:00Z', reversed: false, reversal: null }
  const fetch = mockApi({
    'GET /api/finance/receivables/r1': receivable({ adjustments: [adjustment, { ...adjustment, id: 'a2', amount: 10,
      reversed: true, reversal: { id: 'x', reason: 'Duplicado', reversedAt: '2026-09-21T11:00:00Z' } }] }),
    'GET /api/finance/payment-methods': methods,
    'POST /api/finance/adjustments/a1/reversal': receivable(),
  }, as(ALL))
  renderAt('/financeiro/recebiveis/r1')
  expect(await screen.findByText(/estornado em .*Duplicado/)).toBeInTheDocument()
  const buttons = screen.getAllByRole('button', { name: 'Estornar ajuste' })
  expect(buttons).toHaveLength(1)
  await userEvent.click(buttons[0])
  await userEvent.type(screen.getByLabelText('Motivo do estorno'), 'Lançado em duplicidade')
  await userEvent.click(screen.getByRole('button', { name: 'Confirmar estorno' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/finance/adjustments/a1/reversal')).toEqual({ reason: 'Lançado em duplicidade' }))
  expect(headerOf(requestsTo(fetch, 'POST', '/api/finance/adjustments/a1/reversal')[0][1], 'Idempotency-Key')).toBeTruthy()
})

test('alteração de vencimento envia chave de idempotência', async () => {
  const fetch = mockApi({
    'GET /api/finance/receivables/r1': receivable(),
    'GET /api/finance/payment-methods': methods,
    'PUT /api/finance/receivables/r1/due-date': receivable({ dueDate: '2026-10-15' }),
  }, as(ALL))
  renderAt('/financeiro/recebiveis/r1')
  const form = await screen.findByRole('region', { name: 'Alterar vencimento' })
  const date = within(form).getByLabelText(/Novo vencimento/)
  await userEvent.clear(date)
  await userEvent.type(date, '2026-10-15')
  await userEvent.type(within(form).getByLabelText(/Motivo da alteração/), 'Pedido do cliente')
  await userEvent.click(within(form).getByRole('button', { name: 'Alterar vencimento' }))
  await waitFor(() => expect(lastBody(fetch, 'PUT', '/api/finance/receivables/r1/due-date')).toEqual({ dueDate: '2026-10-15', reason: 'Pedido do cliente' }))
  expect(headerOf(requestsTo(fetch, 'PUT', '/api/finance/receivables/r1/due-date')[0][1], 'Idempotency-Key')).toBeTruthy()
})
