import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { json, mockApi, renderAt, requestsTo, session } from './harness'

const admin = { ...session, permissions: ['FINANCE_VIEW'] }

const dashboard = {
  from: '2026-09-01', to: '2026-09-29',
  revenue: 1000, received: 800, receivableOpen: 200, overdue: 50,
  partsCost: 300, expensesRegistered: 120, expensesPaid: 100,
  grossProfit: 700, operatingResult: 580, grossMargin: 70, operatingMargin: 58,
}

test('resumo financeiro exibe os indicadores formatados', async () => {
  mockApi({ 'GET /api/finance/dashboard': () => json(dashboard) }, admin)
  renderAt('/financeiro/dashboard')

  expect(await screen.findByTestId('dashboard-revenue')).toHaveTextContent('1.000,00')
  expect(screen.getByTestId('dashboard-received')).toHaveTextContent('800,00')
  expect(screen.getByTestId('dashboard-receivable-open')).toHaveTextContent('200,00')
  expect(screen.getByTestId('dashboard-overdue')).toHaveTextContent('50,00')
  expect(screen.getByTestId('dashboard-parts-cost')).toHaveTextContent('300,00')
  expect(screen.getByTestId('dashboard-expenses-registered')).toHaveTextContent('120,00')
  expect(screen.getByTestId('dashboard-expenses-paid')).toHaveTextContent('100,00')
  expect(screen.getByTestId('dashboard-gross-profit')).toHaveTextContent('700,00')
  expect(screen.getByTestId('dashboard-operating-result')).toHaveTextContent('580,00')
  expect(screen.getByTestId('dashboard-gross-margin')).toHaveTextContent('70,00%')
  expect(screen.getByTestId('dashboard-operating-margin')).toHaveTextContent('58,00%')
})

test('valor vencido em aberto usa o mesmo destaque de aviso da situação vencida', async () => {
  mockApi({ 'GET /api/finance/dashboard': () => json(dashboard) }, admin)
  renderAt('/financeiro/dashboard')
  const overdue = await screen.findByTestId('dashboard-overdue')
  expect(overdue.querySelector('.badge.warning')).toBeInTheDocument()
})

test('atalhos de período preenchem as datas automaticamente', async () => {
  mockApi({ 'GET /api/finance/dashboard': () => json(dashboard) }, admin)
  renderAt('/financeiro/dashboard')
  await screen.findByTestId('dashboard-revenue')

  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo' }).format(new Date())
  await userEvent.click(screen.getByRole('button', { name: 'Hoje' }))
  expect(screen.getByLabelText('De')).toHaveValue(today)
  expect(screen.getByLabelText('Até')).toHaveValue(today)

  await userEvent.click(screen.getByRole('button', { name: 'Mês atual' }))
  expect(screen.getByLabelText('De')).toHaveValue(`${today.slice(0, 8)}01`)
  expect(screen.getByLabelText('Até')).toHaveValue(today)
})

test('período inválido mostra alerta e não consulta a API', async () => {
  const fetch = mockApi({ 'GET /api/finance/dashboard': () => json(dashboard) }, admin)
  renderAt('/financeiro/dashboard')
  await screen.findByTestId('dashboard-revenue')
  const before = requestsTo(fetch, 'GET', '/api/finance/dashboard').length

  const from = screen.getByLabelText('De')
  await userEvent.clear(from)
  await userEvent.type(from, '2099-01-01')

  expect(await screen.findByRole('alert')).toHaveTextContent('O período final não pode ser anterior ao inicial.')
  await waitFor(() => expect(requestsTo(fetch, 'GET', '/api/finance/dashboard')).toHaveLength(before))
})
