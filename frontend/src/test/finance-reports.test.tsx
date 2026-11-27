import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { json, mockApi, renderAt, requestsTo, session } from './harness'

const finance = { ...session, permissions: ['FINANCE_VIEW'] }
const due = (overdue: number, d7: number, open: number) => ({
  overdueCount: overdue ? 1 : 0, overdueAmount: overdue, dueIn7Count: d7 ? 1 : 0, dueIn7Amount: d7,
  dueIn30Count: d7 ? 1 : 0, dueIn30Amount: d7, openCount: 2, openAmount: open,
})
const dashboard = { from: '2026-11-01', to: '2026-11-18', revenue: 500, received: 150, receivableOpen: 345, overdue: 205, partsCost: 40,
  expensesRegistered: 110, expensesPaid: 80, grossProfit: 460, operatingResult: 350, grossMargin: 92, operatingMargin: 70 }
const overview = (open: boolean) => ({
  today: '2026-11-18', period: dashboard,
  cash: open ? { open: true, sessionId: 's1', openedAt: '2026-11-18T11:00:00Z', expectedBalance: 150 } : { open: false, sessionId: null, openedAt: null, expectedBalance: null },
  receivables: due(205, 140, 345), payables: due(0, 30, 30),
})
const cashFlow = { from: '2026-11-01', to: '2026-11-18', categoryId: null,
  realized: { inflows: 150, outflows: 80, net: 70, days: [{ date: '2026-11-18', inflows: 150, outflows: 80, net: 70 }] },
  forecast: { inflows: 0, outflows: 0, net: 0, days: [] } }

test('painel mostra o caixa aberto e os títulos vencidos e a vencer', async () => {
  mockApi({ 'GET /api/finance/dashboard': () => json(dashboard), 'GET /api/finance/reports/overview': () => json(overview(true)),
    'GET /api/finance/cash-flow': () => json(cashFlow) }, finance)
  renderAt('/financeiro/dashboard')
  expect(await screen.findByTestId('overview-cash')).toHaveTextContent('150,00')
  expect(screen.getByTestId('overview-receivables-overdue')).toHaveTextContent('205,00')
  expect(screen.getByTestId('overview-receivables-overdue').querySelector('.badge.warning')).toBeInTheDocument()
  expect(screen.getByTestId('overview-payables-7')).toHaveTextContent('30,00')
  expect(await screen.findByRole('img', { name: 'Entradas e saídas por dia' })).toBeInTheDocument()
})

test('painel avisa quando o caixa está fechado', async () => {
  mockApi({ 'GET /api/finance/dashboard': () => json(dashboard), 'GET /api/finance/reports/overview': () => json(overview(false)),
    'GET /api/finance/cash-flow': () => json(cashFlow) }, finance)
  renderAt('/financeiro/dashboard')
  expect(await screen.findByTestId('overview-cash')).toHaveTextContent('Caixa fechado')
})

const incomeStatement = { from: '2026-11-01', to: '2026-11-18', grossRevenue: 500, discounts: 10, surcharges: 5, netRevenue: 495, partsCost: 40,
  grossProfit: 455, expenses: [{ categoryId: 'c1', categoryName: 'Aluguel', registeredCount: 1, registered: 80, paid: 80 }],
  totalExpenses: 110, operatingResult: 345, operatingMargin: 69.7, cashReceived: 150, cashPaid: 80, cashResult: 70 }

test('relatórios abrem no demonstrativo gerencial com competência e caixa', async () => {
  mockApi({ 'GET /api/finance/reports/income-statement': () => json(incomeStatement) }, finance)
  renderAt('/financeiro/relatorios')
  expect(await screen.findByTestId('is-net')).toHaveTextContent('495,00')
  expect(screen.getByTestId('is-gross-profit')).toHaveTextContent('455,00')
  expect(screen.getByTestId('is-operating')).toHaveTextContent('345,00')
  expect(screen.getByTestId('is-cash')).toHaveTextContent('70,00')
  expect(screen.getByText('(−) Aluguel')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Relatórios' })).toHaveAttribute('href', '/financeiro/relatorios')
})

test('demonstrativo mostra as compras para estoque à parte, fora do resultado', async () => {
  mockApi({ 'GET /api/finance/reports/income-statement': () => json({ ...incomeStatement, stockPurchasesRegistered: 1943.1 }) }, finance)
  renderAt('/financeiro/relatorios')
  expect(await screen.findByTestId('is-stock-purchases')).toHaveTextContent('1.943,10')
  expect(screen.getByTestId('is-stock-purchases')).toHaveTextContent('Não entram no resultado')
  expect(screen.getByTestId('is-total-expenses')).toHaveTextContent('Total de despesas operacionais')
  expect(screen.getByTestId('is-operating')).toHaveTextContent('345,00')
})

test('despesas por categoria listam as compras para estoque fora do total', async () => {
  mockApi({
    'GET /api/finance/reports/income-statement': () => json(incomeStatement),
    'GET /api/finance/reports/expenses-by-category': () => json({ from: '2026-11-01', to: '2026-11-18', registered: 80, paid: 80,
      categories: [{ categoryId: 'c1', categoryName: 'Aluguel', registeredCount: 1, registered: 80, paid: 80 }],
      stockPurchasesRegistered: 1943.1, stockPurchasesPaid: 647.7 }),
  }, finance)
  renderAt('/financeiro/relatorios')
  await screen.findByTestId('is-net')
  await userEvent.click(screen.getByRole('tab', { name: 'Despesas por categoria' }))
  expect(await screen.findByTestId('expenses-total')).toHaveTextContent('80,00')
  const stock = screen.getByTestId('expenses-stock-purchases')
  expect(stock).toHaveTextContent('1.943,10')
  expect(stock).toHaveTextContent('647,70')
})

test('inadimplência mostra as faixas, destaca o vencido e lista os clientes', async () => {
  mockApi({ 'GET /api/finance/reports/income-statement': () => json(incomeStatement), 'GET /api/finance/reports/aging': () => json({
    asOf: '2026-11-18', totalOutstanding: 345,
    buckets: [{ code: 'A_VENCER', count: 1, amount: 140 }, { code: 'ATE_30', count: 0, amount: 0 }, { code: 'DE_31_A_60', count: 1, amount: 205 },
      { code: 'DE_61_A_90', count: 0, amount: 0 }, { code: 'ACIMA_90', count: 0, amount: 0 }],
    debtors: [{ customerId: 'k1', customerName: 'Transportes Silva', count: 1, outstanding: 205, overdue: 205, oldestDueDate: '2026-10-09' }],
  }) }, finance)
  renderAt('/financeiro/relatorios')
  await userEvent.click(await screen.findByRole('tab', { name: 'Inadimplência' }))
  expect(await screen.findByTestId('aging-DE_31_A_60')).toHaveTextContent('205,00')
  expect(screen.getByTestId('aging-DE_31_A_60').querySelector('.badge.warning')).toBeInTheDocument()
  expect(screen.getByTestId('aging-A_VENCER').querySelector('.badge')).toBeNull()
  const table = screen.getByRole('table')
  expect(within(table).getByText('Transportes Silva')).toBeInTheDocument()
  expect(within(table).getByText('09/10/2026')).toBeInTheDocument()
  expect(screen.queryByLabelText('De')).not.toBeInTheDocument()
})

test('resultado por OS soma os totais e permite exportar CSV', async () => {
  mockApi({ 'GET /api/finance/reports/income-statement': () => json(incomeStatement), 'GET /api/finance/reports/work-orders': () => json({
    from: '2026-11-01', to: '2026-11-18', netRevenue: 495, partsCost: 40, result: 455, workOrders: [
      { receivableId: 'r1', workOrderId: 'o1', workOrderNumber: 7, customerId: 'k1', customerName: 'Cliente A', issuedOn: '2026-11-18', dueDate: '2026-11-18',
        status: 'PARCIAL', original: 300, discount: 10, surcharge: 0, netRevenue: 290, received: 150, outstanding: 140, partsCost: 40, result: 250, margin: 86.21 }],
  }) }, finance)
  renderAt('/financeiro/relatorios')
  await userEvent.click(await screen.findByRole('tab', { name: 'Resultado por OS' }))
  expect(await screen.findByText('#7')).toBeInTheDocument()
  expect(screen.getByTestId('os-total')).toHaveTextContent('455,00')
  expect(screen.getByRole('button', { name: 'Exportar CSV' })).toBeEnabled()
})

test('erro do relatório mostra estado de erro com nova tentativa', async () => {
  mockApi({}, finance)
  renderAt('/financeiro/relatorios')
  expect(await screen.findByRole('alert')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /tentar/i })).toBeInTheDocument()
})

test('período inválido não consulta o relatório', async () => {
  const fetch = mockApi({ 'GET /api/finance/reports/income-statement': () => json(incomeStatement) }, finance)
  renderAt('/financeiro/relatorios')
  await screen.findByTestId('is-net')
  const before = fetch.mock.calls.filter(([input]) => String(input).startsWith('/api/finance/reports/income-statement')).length
  await userEvent.clear(screen.getByLabelText('Até'))
  await userEvent.type(screen.getByLabelText('Até'), '2020-01-01')
  expect(await screen.findByText('O período final não pode ser anterior ao inicial.')).toBeInTheDocument()
  expect(fetch.mock.calls.filter(([input]) => String(input).startsWith('/api/finance/reports/income-statement')).length).toBe(before)
  expect(requestsTo(fetch, 'GET', '/api/finance/reports/aging')).toHaveLength(0)
})
