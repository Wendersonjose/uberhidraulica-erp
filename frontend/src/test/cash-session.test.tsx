import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { json, lastBody, mockApi, renderAt, requestsTo, session } from './harness'

const ALL = ['FINANCE_VIEW', 'CASH_SESSION_OPEN', 'CASH_SESSION_CLOSE', 'CASH_SUPPLY', 'CASH_WITHDRAWAL', 'CASH_REVERSAL']
const as = (permissions: string[]) => ({ ...session, permissions })
const cashSession = (extra = {}) => ({
  id: 's1', status: 'OPEN', conferenceStatus: 'NOT_CHECKED', openedAt: '2026-10-05T12:00:00Z', openedBy: 'u1',
  openingExpectedBalance: 100, openingCountedBalance: 100, openingDifferenceReason: null, closedAt: null, closedBy: null,
  closingExpectedBalance: null, closingCountedBalance: null, closingDifferenceReason: null, checkedAt: null, checkedBy: null,
  checkedCountedBalance: null, checkedDifferenceReason: null, currentExpectedBalance: 150, ...extra,
})
const noSession = () => json({ code: 'CASH_SESSION_REQUIRED', message: 'Não há sessão de caixa aberta', details: [] }, 409)
const noContent = () => new Response(null, { status: 204 })

test('sem sessão aberta e sem conferência pendente (204), o Caixa mostra o estado fechado e não um erro', async () => {
  mockApi({
    'GET /api/finance/cash/sessions/open': noSession,
    'GET /api/finance/cash/sessions/pending-check': noContent,
    'GET /api/finance/cash/suggested-opening-balance': { amount: 120 },
  }, as(ALL))
  renderAt('/financeiro/caixa')
  expect(await screen.findByRole('heading', { name: 'Caixa fechado' })).toBeInTheDocument()
  expect(screen.queryByText(/Não foi possível carregar/)).not.toBeInTheDocument()
  expect(screen.queryByText('Conferência pendente')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Abrir caixa' })).toBeInTheDocument()
})

test('abertura exige justificativa quando o saldo contado diverge do sugerido e envia o corpo esperado', async () => {
  const fetch = mockApi({
    'GET /api/finance/cash/sessions/open': noSession,
    'GET /api/finance/cash/sessions/pending-check': noContent,
    'GET /api/finance/cash/suggested-opening-balance': { amount: 120 },
    'POST /api/finance/cash/sessions': () => json(cashSession({ openingCountedBalance: 100, openingExpectedBalance: 120 })),
  }, as(ALL))
  renderAt('/financeiro/caixa')
  const counted = await screen.findByLabelText(/Saldo físico contado/)
  const open = screen.getByRole('button', { name: 'Abrir caixa' })
  expect(open).toBeEnabled()
  await userEvent.clear(counted)
  await userEvent.type(counted, '100')
  expect(open).toBeDisabled()
  await userEvent.type(await screen.findByLabelText(/Justificativa da divergência/), 'Troco levado ao banco')
  await userEvent.click(open)
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/finance/cash/sessions')).toEqual({ countedBalance: 100, differenceReason: 'Troco levado ao banco' }))
})

test('sessão aberta mostra saldo, movimentações e as ações permitidas ao perfil', async () => {
  mockApi({
    'GET /api/finance/cash/sessions/open': cashSession(),
    'GET /api/finance/cash/sessions/pending-check': noContent,
    'GET /api/finance/cash/suggested-opening-balance': { amount: 100 },
    'GET /api/finance/cash/sessions/s1/movements': [{
      id: 'm1', cashSessionId: 's1', type: 'SUPPLY', direction: 'IN', amount: 50, reason: 'Reforço de troco',
      receiptId: null, payablePaymentId: null, reversedMovementId: null, recordedAt: '2026-10-05T12:30:00Z', recordedBy: 'u1',
    }],
  }, as(ALL))
  renderAt('/financeiro/caixa')
  expect(await screen.findByRole('heading', { name: /Sessão aberta/ })).toBeInTheDocument()
  expect(await screen.findByText('Reforço de troco')).toBeInTheDocument()
  expect(screen.getAllByText(/R\$\s*150,00/).length).toBeGreaterThan(0)
  expect(screen.getByRole('heading', { name: 'Suprimento' })).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: 'Sangria' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Fechar caixa' })).toBeInTheDocument()
})

test('perfil só com FINANCE_VIEW consulta o caixa mas não vê ações de suprimento, sangria nem fechamento', async () => {
  mockApi({
    'GET /api/finance/cash/sessions/open': cashSession(),
    'GET /api/finance/cash/sessions/pending-check': noContent,
    'GET /api/finance/cash/suggested-opening-balance': { amount: 100 },
    'GET /api/finance/cash/sessions/s1/movements': [],
  }, as(['FINANCE_VIEW']))
  renderAt('/financeiro/caixa')
  expect(await screen.findByRole('heading', { name: /Sessão aberta/ })).toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: 'Suprimento' })).not.toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: 'Sangria' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Fechar caixa' })).not.toBeInTheDocument()
})

test('fechamento automático pendente aparece para conferência e envia a contagem', async () => {
  const pending = cashSession({ status: 'AUTO_CLOSED', closedAt: '2026-10-04T23:59:00Z', closingExpectedBalance: 80, currentExpectedBalance: 80 })
  const fetch = mockApi({
    'GET /api/finance/cash/sessions/open': noSession,
    'GET /api/finance/cash/sessions/pending-check': pending,
    'GET /api/finance/cash/suggested-opening-balance': { amount: 80 },
    'POST /api/finance/cash/sessions/s1/check': () => json(cashSession({ ...pending, conferenceStatus: 'CHECKED' })),
  }, as(ALL))
  renderAt('/financeiro/caixa')
  const card = (await screen.findByText('Conferência pendente')).closest('section') as HTMLElement
  await userEvent.click(within(card).getByRole('button', { name: 'Conferir sessão' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/finance/cash/sessions/s1/check')).toEqual({ countedBalance: 80, differenceReason: null }))
  expect(requestsTo(fetch, 'POST', '/api/finance/cash/sessions/s1/check')).toHaveLength(1)
})

test('depois de um suprimento, a contagem do fechamento acompanha o novo saldo esperado', async () => {
  let expected = 150
  mockApi({
    'GET /api/finance/cash/sessions/open': () => json(cashSession({ currentExpectedBalance: expected })),
    'GET /api/finance/cash/sessions/pending-check': noContent,
    'GET /api/finance/cash/suggested-opening-balance': { amount: 100 },
    'GET /api/finance/cash/sessions/s1/movements': [],
    'POST /api/finance/cash/movements/supply': () => { expected = 160; return json({ id: 'm2', cashSessionId: 's1', type: 'SUPPLY', direction: 'IN', amount: 10,
      reason: 'Reforço', receiptId: null, payablePaymentId: null, reversedMovementId: null, recordedAt: '2026-10-05T13:00:00Z', recordedBy: 'u1' }) },
  }, as(ALL))
  renderAt('/financeiro/caixa')
  const close = (await screen.findByRole('heading', { name: 'Fechar e conferir' })).closest('section') as HTMLElement
  expect(within(close).getByLabelText(/Saldo físico contado/)).toHaveValue(150)
  const supply = screen.getByRole('heading', { name: 'Suprimento' }).closest('section') as HTMLElement
  await userEvent.type(within(supply).getByLabelText(/Valor/), '10')
  await userEvent.type(within(supply).getByLabelText(/Motivo/), 'Reforço')
  await userEvent.click(within(supply).getByRole('button', { name: 'Registrar suprimento' }))
  // A chave refaz o formulário de fechamento: o elemento antigo sai do DOM, então ele é consultado de novo.
  const closeSection = () => screen.getByRole('heading', { name: 'Fechar e conferir' }).closest('section') as HTMLElement
  await waitFor(() => expect(within(closeSection()).getByLabelText(/Saldo físico contado/)).toHaveValue(160))
  expect(within(closeSection()).getByRole('button', { name: 'Fechar caixa' })).toBeEnabled()
})

test('depois de fechar a sessão, a abertura seguinte parte do novo saldo sugerido sem exigir justificativa', async () => {
  let open = true
  let suggested = 100
  mockApi({
    'GET /api/finance/cash/sessions/open': () => open ? json(cashSession()) : noSession(),
    'GET /api/finance/cash/sessions/pending-check': noContent,
    'GET /api/finance/cash/suggested-opening-balance': () => json({ amount: suggested }),
    'GET /api/finance/cash/sessions/s1/movements': [],
    'POST /api/finance/cash/sessions/s1/close': () => { open = false; suggested = 150; return json(cashSession({ status: 'CLOSED' })) },
  }, as(ALL))
  renderAt('/financeiro/caixa')
  await userEvent.click(await screen.findByRole('button', { name: 'Fechar caixa' }))
  expect(await screen.findByRole('heading', { name: 'Caixa fechado' })).toBeInTheDocument()
  await waitFor(() => expect(screen.getByLabelText(/Saldo físico contado/)).toHaveValue(150))
  expect(screen.queryByLabelText(/Justificativa da divergência/)).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Abrir caixa' })).toBeEnabled()
})
