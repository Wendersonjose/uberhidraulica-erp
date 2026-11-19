import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { json, lastBody, mockApi, page, renderAt, requestsTo, session } from './harness'
import { addMonths, splitEqually } from '../features/finance/installments'

/** TASK-0024 etapa B (DR-0024): parcelas, avulso, juros e multa, cartão, aprovação, fornecedor e conciliação. */
const ALL = ['FINANCE_VIEW', 'FINANCE_RECEIVE', 'FINANCE_REVERSE', 'FINANCE_ADJUST', 'FINANCE_PAYABLE', 'FINANCE_CONFIG',
  'FINANCE_RECEIVABLE_MANUAL', 'FINANCE_PAYABLE_APPROVE', 'FINANCE_RECONCILE']
const as = (permissions: string[]) => ({ ...session, permissions })
const methods = [
  { id: 'm-cash', code: 'DINHEIRO', name: 'Dinheiro', active: true, cashSessionRequired: true },
  { id: 'm-card', code: 'CARTAO_CREDITO', name: 'Cartão de crédito', active: true, cashSessionRequired: false },
]
const plan = (editable: boolean, installments: object[]) => ({ version: 1, reason: 'Negociado', createdAt: '2026-11-18T12:00:00Z', editable, installments })
const receivable = (extra = {}) => ({
  id: 'r1', origin: 'WORK_ORDER', workOrderId: 'o1', workOrderNumber: 12, customerId: 'c1', customerName: 'Maria Souza', billingQuoteId: 'q1',
  originalAmount: 300, discountAmount: 0, surchargeAmount: 0, adjustedAmount: 300, receivedAmount: 0, outstandingBalance: 300,
  principalOutstanding: 300, surchargeOutstanding: 0, issuedOn: '2026-11-01', dueDate: '2026-11-10', status: 'VENCIDO',
  createdAt: '2026-11-01T12:00:00Z', cancelledAt: null, cancellationReason: null,
  lines: [{ id: 'l1', quoteItemRevisionId: 'qr1', description: 'Revisão', quantity: 1, unitPrice: 300, discountAmount: 0, totalAmount: 300, displayOrder: 1 }],
  installmentPlan: plan(true, [{ number: 1, amount: 300, dueDate: '2026-11-10', openAmount: 300, status: 'VENCIDO' }]),
  adjustments: [], dueDateChanges: [], receipts: [], ...extra,
})

test('divisão em parcelas deixa os centavos na última e soma meses como o backend', () => {
  expect(splitEqually(100, 3)).toEqual([33.33, 33.33, 33.34])
  expect(splitEqually(100.01, 2)).toEqual([50, 50.01])
  expect(addMonths('2026-01-31', 1)).toBe('2026-02-28')
  expect(addMonths('2026-11-18', 2)).toBe('2027-01-18')
})

test('recebível mostra as parcelas e grava um novo plano com motivo e chave de idempotência', async () => {
  const fetch = mockApi({
    'GET /api/finance/receivables/r1': receivable(),
    'PUT /api/finance/receivables/r1/installments': () => json(receivable({ installmentPlan: plan(true, [
      { number: 1, amount: 150, dueDate: '2026-11-20', openAmount: 150, status: 'ABERTO' },
      { number: 2, amount: 150, dueDate: '2026-12-20', openAmount: 150, status: 'ABERTO' }]) }), 201),
    'GET /api/finance/receivables/r1/late-charge': { calculatedOn: '2026-11-18', feePercent: 2, interestMonthlyPercent: 1, feeAmount: 0, interestAmount: 0, total: 0, lines: [] },
  }, as(ALL))
  renderAt('/financeiro/recebiveis/r1')
  expect(await screen.findByTestId('installment-1')).toHaveTextContent('300,00')
  const form = screen.getByRole('region', { name: 'Parcelar recebível' })
  await userEvent.clear(within(form).getByLabelText(/Primeiro vencimento/))
  await userEvent.type(within(form).getByLabelText(/Primeiro vencimento/), '2026-11-20')
  expect(within(form).getByTestId('plan-preview')).toHaveTextContent('2ª R$ 150,00 em 20/12/2026')
  expect(within(form).getByRole('button', { name: 'Gravar parcelas' })).toBeDisabled()
  await userEvent.type(within(form).getByLabelText(/Motivo/), 'Cliente pediu em duas vezes')
  await userEvent.click(within(form).getByRole('button', { name: 'Gravar parcelas' }))
  await waitFor(() => expect(lastBody(fetch, 'PUT', '/api/finance/receivables/r1/installments')).toEqual({
    reason: 'Cliente pediu em duas vezes', installments: [{ amount: 150, dueDate: '2026-11-20' }, { amount: 150, dueDate: '2026-12-20' }],
  }))
  const call = requestsTo(fetch, 'PUT', '/api/finance/receivables/r1/installments')[0]
  expect(new Headers(call[1]?.headers).get('Idempotency-Key')).toBeTruthy()
})

test('com recebimento, o plano não é editável e o formulário de parcelas some', async () => {
  mockApi({ 'GET /api/finance/receivables/r1': receivable({ receivedAmount: 100, outstandingBalance: 200, status: 'PARCIAL',
    installmentPlan: plan(false, [{ number: 1, amount: 300, dueDate: '2026-11-30', openAmount: 200, status: 'PARCIAL' }]) }) }, as(ALL))
  renderAt('/financeiro/recebiveis/r1')
  await screen.findByTestId('installment-1')
  expect(screen.queryByRole('region', { name: 'Parcelar recebível' })).not.toBeInTheDocument()
})

test('juros e multa são sugeridos; aplicar envia o total exibido e dispensar exige motivo', async () => {
  const fetch = mockApi({
    'GET /api/finance/receivables/r1': receivable(),
    'GET /api/finance/receivables/r1/late-charge': { calculatedOn: '2026-11-18', feePercent: 2, interestMonthlyPercent: 1, feeAmount: 6, interestAmount: 0.8,
      total: 6.8, lines: [{ installmentNumber: 1, dueDate: '2026-11-10', baseAmount: 300, interestFrom: '2026-11-10', days: 8, feeAmount: 6, interestAmount: 0.8 }] },
    'POST /api/finance/receivables/r1/late-charge': () => json(receivable({ surchargeAmount: 6.8 }), 201),
  }, as(ALL))
  renderAt('/financeiro/recebiveis/r1')
  expect(await screen.findByTestId('late-charge-total')).toHaveTextContent('6,80')
  await userEvent.click(screen.getByRole('button', { name: 'Dispensar' }))
  expect(screen.getByRole('button', { name: 'Confirmar dispensa' })).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: 'Voltar' }))
  await userEvent.click(screen.getByRole('button', { name: 'Aplicar juros e multa' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/finance/receivables/r1/late-charge')).toMatchObject({
    decision: 'APPLIED', expectedTotal: 6.8, reason: null,
  }))
})

test('sem FINANCE_ADJUST a sugestão aparece, mas não há botão de aplicar', async () => {
  mockApi({
    'GET /api/finance/receivables/r1': receivable(),
    'GET /api/finance/receivables/r1/late-charge': { calculatedOn: '2026-11-18', feePercent: 2, interestMonthlyPercent: 1, feeAmount: 6, interestAmount: 0, total: 6, lines: [] },
  }, as(['FINANCE_VIEW', 'FINANCE_RECEIVE']))
  renderAt('/financeiro/recebiveis/r1')
  await screen.findByTestId('late-charge-total')
  expect(screen.queryByRole('button', { name: 'Aplicar juros e multa' })).not.toBeInTheDocument()
})

test('recebimento no cartão escolhe as parcelas da tabela e mostra taxa e líquido', async () => {
  const fetch = mockApi({
    'GET /api/finance/receivables/r1': receivable({ status: 'ABERTO' }),
    'GET /api/finance/receivables/r1/late-charge': { calculatedOn: '2026-11-18', feePercent: 2, interestMonthlyPercent: 1, feeAmount: 0, interestAmount: 0, total: 0, lines: [] },
    'GET /api/finance/payment-methods': methods,
    'GET /api/finance/payment-methods/m-card/fees': [{ installments: 1, feePercent: 3.5, settlementDays: 30 }, { installments: 3, feePercent: 5.99, settlementDays: 30 }],
    'POST /api/finance/receivables/r1/receipts': { id: 'p1' },
  }, as(ALL))
  renderAt('/financeiro/recebiveis/r1')
  await screen.findByRole('option', { name: 'Cartão de crédito' })
  await userEvent.selectOptions(screen.getByLabelText(/Forma de pagamento/), 'm-card')
  await userEvent.selectOptions(await screen.findByLabelText(/Parcelas no cartão/), '3')
  expect(screen.getByTestId('card-fee-preview')).toHaveTextContent('Taxa R$ 17,97')
  expect(screen.getByTestId('card-fee-preview')).toHaveTextContent('líquido R$ 282,03')
  await userEvent.click(screen.getByRole('button', { name: 'Registrar recebimento' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/finance/receivables/r1/receipts')).toMatchObject({ amount: 300, paymentMethodId: 'm-card', installments: 3 }))
})

test('conta a receber avulsa escolhe cliente e categoria e mostra a divisão antes de lançar', async () => {
  const fetch = mockApi({
    'GET /api/finance/revenue-categories': [{ id: 'rc1', name: 'Venda de balcão', active: true }],
    'GET /api/customers/search': page([{ id: 'c9', personType: 'PF', name: 'João Balcão', document: null }]),
    'POST /api/finance/receivables': { id: 'r9' },
    'GET /api/finance/receivables/r9': receivable({ id: 'r9', origin: 'MANUAL', workOrderId: null, workOrderNumber: null, billingQuoteId: null,
      description: 'Venda de mangueira', revenueCategoryName: 'Venda de balcão', lines: [] }),
  }, as(ALL))
  renderAt('/financeiro/recebiveis/nova')
  await userEvent.type(await screen.findByPlaceholderText(/2 letras/), 'Jo')
  await userEvent.click(await screen.findByRole('button', { name: 'João Balcão' }))
  await userEvent.type(screen.getByLabelText(/Descrição/), 'Venda de mangueira')
  await screen.findByRole('option', { name: 'Venda de balcão' })
  await userEvent.selectOptions(screen.getByLabelText(/Categoria de receita/), 'rc1')
  await userEvent.type(screen.getByLabelText(/^Valor/), '100.01')
  await userEvent.clear(screen.getByLabelText(/Parcelas/))
  await userEvent.type(screen.getByLabelText(/Parcelas/), '2')
  expect(screen.getByTestId('manual-split')).toHaveTextContent('2ª R$ 50,01')
  await userEvent.click(screen.getByRole('button', { name: 'Lançar conta a receber' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/finance/receivables')).toMatchObject({
    customerId: 'c9', description: 'Venda de mangueira', revenueCategoryId: 'rc1', amount: 100.01, installments: 2,
  }))
  expect(await screen.findByRole('heading', { name: /Conta a receber: Venda de mangueira/ })).toBeInTheDocument()
})

test('botão de nova conta a receber só aparece com FINANCE_RECEIVABLE_MANUAL', async () => {
  mockApi({ 'GET /api/finance/receivables': page([]) }, as(['FINANCE_VIEW']))
  renderAt('/financeiro/recebiveis')
  await screen.findByText('Nenhum recebível encontrado.')
  expect(screen.queryByRole('link', { name: 'Nova conta a receber' })).not.toBeInTheDocument()
})

const payable = (extra = {}) => ({
  id: 'p1', description: 'Aluguel do galpão', supplier: null, categoryId: 'cat1', categoryName: 'Aluguel', amount: 2500, paidAmount: 0,
  outstandingBalance: 2500, dueDate: '2026-11-30', status: 'ABERTO', notes: null, createdAt: '2026-11-18T12:00:00Z', cancelledAt: null,
  cancellationReason: null, payments: [], approvalStatus: 'PENDING', approval: null, origin: 'MANUAL', groupId: null, installmentNumber: null, ...extra,
})

test('conta pendente de aprovação não oferece pagamento e o aprovador aprova', async () => {
  const fetch = mockApi({
    'GET /api/finance/payables/p1': payable(),
    'POST /api/finance/payables/p1/approval': () => json(payable({ approvalStatus: 'APPROVED' })),
  }, as(ALL))
  renderAt('/financeiro/contas-a-pagar/p1')
  expect(await screen.findByTestId('payable-approval')).toHaveTextContent('Aguardando aprovação')
  expect(screen.queryByRole('region', { name: 'Registrar pagamento' })).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Aprovar conta' }))
  await waitFor(() => expect(requestsTo(fetch, 'POST', '/api/finance/payables/p1/approval')).toHaveLength(1))
})

test('sem FINANCE_PAYABLE_APPROVE a conta pendente só informa', async () => {
  mockApi({ 'GET /api/finance/payables/p1': payable() }, as(['FINANCE_VIEW', 'FINANCE_PAYABLE']))
  renderAt('/financeiro/contas-a-pagar/p1')
  expect(await screen.findByText('Seu perfil não aprova contas a pagar.')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Aprovar conta' })).not.toBeInTheDocument()
})

test('conta a pagar parcelada cria o grupo e vai para a visão consolidada', async () => {
  const fetch = mockApi({
    'GET /api/finance/expense-categories': [{ id: 'cat1', name: 'Equipamentos', active: true }],
    'POST /api/finance/payables/installments': { id: 'g1' },
    'GET /api/finance/payable-groups/g1': { id: 'g1', description: 'Elevador', supplier: null, supplierId: null, categoryId: 'cat1', totalAmount: 1000,
      installmentCount: 3, notes: null, createdAt: '2026-11-18T12:00:00Z', paidAmount: 0, outstandingBalance: 1000,
      payables: [payable({ id: 'a', amount: 333.33, groupId: 'g1', installmentNumber: 1, approvalStatus: 'NOT_REQUIRED' })] },
  }, as(ALL))
  renderAt('/financeiro/contas-a-pagar/nova')
  await userEvent.type(await screen.findByLabelText(/Descrição/), 'Elevador')
  await screen.findByRole('option', { name: 'Equipamentos' })
  await userEvent.selectOptions(screen.getByLabelText(/Categoria/), 'cat1')
  await userEvent.type(screen.getByLabelText(/^Valor/), '1000')
  await userEvent.clear(screen.getByLabelText(/Parcelas/))
  await userEvent.type(screen.getByLabelText(/Parcelas/), '3')
  expect(screen.getByTestId('payable-split')).toHaveTextContent('3ª R$ 333,34')
  await userEvent.click(screen.getByRole('button', { name: 'Lançar conta' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/finance/payables/installments')).toMatchObject({ totalAmount: 1000, installments: 3, categoryId: 'cat1' }))
  expect(await screen.findByTestId('group-open')).toHaveTextContent('1.000,00')
})

test('configurações salvam juros, multa e limite de aprovação juntos', async () => {
  const fetch = mockApi({
    'GET /api/finance/payment-methods': methods, 'GET /api/finance/expense-categories': [], 'GET /api/finance/revenue-categories': [],
    'GET /api/finance/accounts': [],
    'GET /api/finance/settings': { defaultReceivableDueDays: 0, lateFeePercent: 2, lateInterestMonthlyPercent: 1, payableApprovalThreshold: 0 },
    'PUT /api/finance/settings': { defaultReceivableDueDays: 0, lateFeePercent: 2, lateInterestMonthlyPercent: 1, payableApprovalThreshold: 1000 },
  }, as(ALL))
  renderAt('/financeiro/configuracoes')
  const limit = await screen.findByLabelText(/Aprovar contas a pagar acima de/)
  await userEvent.clear(limit)
  await userEvent.type(limit, '1000')
  expect(screen.getByText(/Contas acima de R\$ 1.000,00 precisam de aprovação/)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Salvar configurações' }))
  await waitFor(() => expect(lastBody(fetch, 'PUT', '/api/finance/settings')).toEqual({
    defaultReceivableDueDays: 0, lateFeePercent: 2, lateInterestMonthlyPercent: 1, payableApprovalThreshold: 1000,
  }))
})

test('conciliação: diferença entre extrato e lançamento exige motivo', async () => {
  const fetch = mockApi({
    'GET /api/finance/accounts': [{ id: 'a1', name: 'Banco PJ', type: 'BANK', bankName: null, branch: null, accountNumber: null, active: true }],
    'GET /api/finance/reconciliation/entries': [{ type: 'RECEIPT', id: 'e1', ownerId: 'r1', ownerLabel: 'OS #12', effectiveOn: '2026-11-18',
      paymentMethodName: 'PIX', amount: 200, netAmount: 200, expectedSettlementOn: null, accountName: null, reconciliation: null }],
    'POST /api/finance/reconciliations': { id: 'c1' },
  }, as(ALL))
  renderAt('/financeiro/conciliacao')
  await userEvent.click(await screen.findByRole('button', { name: 'Conciliar' }))
  const group = screen.getByRole('group', { name: 'Conciliar lançamento' })
  await userEvent.type(within(group).getByLabelText('Identificador no extrato'), 'EXT-1')
  await userEvent.clear(within(group).getByLabelText('Valor no extrato'))
  await userEvent.type(within(group).getByLabelText('Valor no extrato'), '199.50')
  expect(within(group).getByRole('button', { name: 'Confirmar conciliação' })).toBeDisabled()
  await userEvent.type(within(group).getByLabelText(/Motivo da diferença/), 'Tarifa do banco')
  await userEvent.click(within(group).getByRole('button', { name: 'Confirmar conciliação' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/finance/reconciliations')).toMatchObject({
    accountId: 'a1', entryType: 'RECEIPT', entryId: 'e1', statementAmount: 199.5, statementReference: 'EXT-1', differenceReason: 'Tarifa do banco',
  }))
})

test('conciliação sem FINANCE_RECONCILE só mostra a situação', async () => {
  mockApi({
    'GET /api/finance/accounts': [{ id: 'a1', name: 'Banco PJ', type: 'BANK', bankName: null, branch: null, accountNumber: null, active: true }],
    'GET /api/finance/reconciliation/entries': [{ type: 'PAYMENT', id: 'e2', ownerId: 'p1', ownerLabel: 'Aluguel', effectiveOn: '2026-11-18',
      paymentMethodName: 'PIX', amount: 80, netAmount: 80, expectedSettlementOn: null, accountName: null, reconciliation: null }],
  }, as(['FINANCE_VIEW']))
  renderAt('/financeiro/conciliacao')
  expect(await screen.findByTestId('entry-e2')).toHaveTextContent('Pendente')
  expect(screen.queryByRole('button', { name: 'Conciliar' })).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Conciliação' })).toHaveAttribute('href', '/financeiro/conciliacao')
})
