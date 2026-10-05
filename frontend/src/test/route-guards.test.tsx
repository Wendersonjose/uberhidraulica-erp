import { screen } from '@testing-library/react'
import { expect, test } from 'vitest'
import { mockApi, renderAt, requestsTo, session } from './harness'

const as = (permissions: string[]) => ({ ...session, permissions })

test('sem FINANCE_VIEW, o endereço do Financeiro mostra acesso negado e nem consulta a API', async () => {
  const fetch = mockApi({ 'GET /api/work-orders/board': [] }, as(['CRM_MANAGE']))
  renderAt('/financeiro/dashboard')
  expect(await screen.findByRole('heading', { name: 'Acesso negado' })).toBeInTheDocument()
  expect(requestsTo(fetch, 'GET', '/api/finance/dashboard')).toHaveLength(0)
  expect(screen.queryByRole('link', { name: 'Resumo financeiro' })).not.toBeInTheDocument()
})

test('sem IAM_USERS_READ, a administração de usuários mostra acesso negado', async () => {
  const fetch = mockApi({}, as(['FINANCE_VIEW']))
  renderAt('/configuracoes/usuarios')
  expect(await screen.findByRole('heading', { name: 'Acesso negado' })).toBeInTheDocument()
  expect(requestsTo(fetch, 'GET', '/api/iam/users')).toHaveLength(0)
})

test('com a permissão, a área abre normalmente', async () => {
  mockApi({
    'GET /api/finance/dashboard': { from: '2026-10-01', to: '2026-10-05', revenue: 0, received: 0, receivableOpen: 0, overdue: 0, partsCost: 0,
      expensesRegistered: 0, expensesPaid: 0, grossProfit: 0, operatingResult: 0, grossMargin: 0, operatingMargin: 0 },
  }, as(['FINANCE_VIEW']))
  renderAt('/financeiro/dashboard')
  expect(await screen.findByRole('heading', { name: 'Resumo financeiro' })).toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: 'Acesso negado' })).not.toBeInTheDocument()
})
