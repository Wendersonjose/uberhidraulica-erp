import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { expect, test, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthProvider'
import App from '../App'

const session = { id: 'u1', name: 'Operador', email: 'op@teste.local', profileCode: 'DONO', state: 'ACTIVE', mustChangePassword: false, permissions: ['QUOTE_PRESENT'] }
const sessionWithoutPresent = { ...session, profileCode: 'GERENTE_FINANCEIRO', permissions: [] }

const itemRevisionV1 = {
  id: 'ir1', revisionSequence: 1, description: 'Recondicionamento da caixa', quantity: 1, unitPrice: 500,
  totalPrice: 500, revisionReason: null, presented: true, availability: 'AVAILABLE',
  createdAt: '2026-09-14T10:00:00Z', createdBy: 'u1',
}
const itemRevisionV2 = {
  ...itemRevisionV1, id: 'ir2', revisionSequence: 2, unitPrice: 600, totalPrice: 600,
  presented: false, availability: 'NOT_PRESENTED',
}
const presentedRevision = {
  id: 'r1', revisionNumber: 1, status: 'PRESENTED', presentedAt: '2026-09-14T10:00:00Z',
  validUntil: '2026-09-21T10:00:00Z', expired: false, total: 500, createdAt: '2026-09-14T09:00:00Z', createdBy: 'u1',
  items: [{ quoteItemRevisionId: 'ir1', displayOrder: 1 }],
}
const draftRevision = {
  id: 'r2', revisionNumber: 2, status: 'DRAFT', presentedAt: null, validUntil: null, expired: false, total: 600,
  createdAt: '2026-09-14T11:00:00Z', createdBy: 'u1', items: [{ quoteItemRevisionId: 'ir2', displayOrder: 1 }],
}
const quote = {
  id: 'q1234567-abcd', workOrderId: 'o1', createdAt: '2026-09-14T09:00:00Z', createdBy: 'u1',
  availableTotal: 500,
  revisions: [presentedRevision, draftRevision],
  items: [{ id: 'i1234567-abcd', workOrderServiceId: null, createdAt: '2026-09-14T09:30:00Z', revisions: [itemRevisionV1, itemRevisionV2] }],
}
const singleRevisionQuote = { ...quote, revisions: [presentedRevision], items: [{ ...quote.items[0], revisions: [itemRevisionV1] }] }

function json(body: unknown, status = 200) {
  return new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function mock(overrides: Record<string, unknown> = {}, errors: Record<string, [unknown, number]> = {},
  current: typeof session = session) {
  const routes: Record<string, unknown> = {
    'GET /api/work-orders/o1/quotes': [quote],
    'GET /api/work-orders/o1/quotes/q1234567-abcd': quote,
    'GET /api/work-orders/o1/quotes/q1234567-abcd/public-access': [],
    ...overrides,
  }
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const path = String(input), method = init?.method || 'GET'
    if (path === '/api/iam/csrf') return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'test' })
    if (path === '/api/iam/session') return json(current)
    const key = `${method} ${path}`
    if (key in errors) return json(errors[key][0], errors[key][1])
    return key in routes ? json(routes[key], method === 'POST' ? 201 : 200) : json({ message: 'Rota não simulada' }, 500)
  })
}

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}>
    <AuthProvider><App /></AuthProvider>
  </MemoryRouter></QueryClientProvider>)
}

const bodyOf = (calls: Parameters<typeof fetch>[], path: string) =>
  JSON.parse(String(calls.filter(call => String(call[0]) === path && call[1]?.body).at(-1)?.[1]?.body))

test('lista de orçamentos mostra a última apresentação e sua validade', async () => {
  mock()
  renderAt('/ordens-servico/o1/orcamentos')

  const row = (await screen.findByText('q1234567')).closest('tr')!
  expect(row).toHaveTextContent('R1')
  expect(row).toHaveTextContent('Dentro da validade')
  expect(row).toHaveTextContent('2')
})

test('validade encerrada aparece como tal na listagem', async () => {
  mock({ 'GET /api/work-orders/o1/quotes': [{ ...quote, revisions: [{ ...presentedRevision, expired: true }] }] })
  renderAt('/ordens-servico/o1/orcamentos')
  expect(await screen.findByText('Validade encerrada')).toBeInTheDocument()
})

test('abrir novo orçamento navega para o detalhe', async () => {
  const fetchMock = mock({ 'POST /api/work-orders/o1/quotes': quote })
  renderAt('/ordens-servico/o1/orcamentos')

  await userEvent.click(await screen.findByRole('button', { name: 'Novo orçamento' }))
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/work-orders/o1/quotes', expect.objectContaining({ method: 'POST' })))
  expect(await screen.findByRole('heading', { name: 'Orçamento' })).toBeInTheDocument()
})

test('detalhe mostra situação derivada de cada versão e o total decidível', async () => {
  mock()
  renderAt('/ordens-servico/o1/orcamentos/q1234567-abcd')

  const v1 = (await screen.findByText('i1234567-v1')).closest('tr')!
  expect(v1).toHaveTextContent('Decidível')
  expect(v1).toHaveTextContent('R$ 500,00')
  const v2 = screen.getByText('i1234567-v2').closest('tr')!
  expect(v2).toHaveTextContent('Rascunho')
  expect(v2).toHaveTextContent('R$ 600,00')
  // O total vem do backend, somando parcelas já arredondadas (DR-0007).
  expect(screen.getByTestId('quote-available-total')).toHaveTextContent('R$ 500,00')
})

test('sem QUOTE_PRESENT o botão de apresentar fica desabilitado', async () => {
  mock({}, {}, sessionWithoutPresent)
  renderAt('/ordens-servico/o1/orcamentos/q1234567-abcd')

  const button = await screen.findByRole('button', { name: 'Apresentar' })
  expect(button).toBeDisabled()
  expect(button).toHaveAttribute('title', 'Seu perfil não pode apresentar orçamento')
})

test('apresentar existe apenas para rascunho e envia a chamada', async () => {
  const fetchMock = mock({ 'POST /api/work-orders/o1/quotes/q1234567-abcd/revisions/r2/present': quote })
  renderAt('/ordens-servico/o1/orcamentos/q1234567-abcd')

  const buttons = await screen.findAllByRole('button', { name: 'Apresentar' })
  expect(buttons).toHaveLength(1)
  await userEvent.click(buttons[0])
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
    '/api/work-orders/o1/quotes/q1234567-abcd/revisions/r2/present', expect.objectContaining({ method: 'POST' })))
})

test('nova revisão é semeada com a última apresentação e preserva o item comercial', async () => {
  const fetchMock = mock({
    'GET /api/work-orders/o1/quotes/q1234567-abcd': singleRevisionQuote,
    'POST /api/work-orders/o1/quotes/q1234567-abcd/revisions': singleRevisionQuote,
  })
  renderAt('/ordens-servico/o1/orcamentos/q1234567-abcd')

  const price = await screen.findByLabelText('Preço unitário 1')
  expect(screen.getByLabelText('Descrição 1')).toHaveValue('Recondicionamento da caixa')
  expect(price).toHaveValue(500)

  await userEvent.clear(price)
  await userEvent.type(price, '600')
  await userEvent.click(screen.getByRole('button', { name: 'Criar revisão' }))

  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
    '/api/work-orders/o1/quotes/q1234567-abcd/revisions', expect.objectContaining({ method: 'POST' })))
  expect(bodyOf(fetchMock.mock.calls, '/api/work-orders/o1/quotes/q1234567-abcd/revisions')).toEqual({
    items: [{ quoteItemId: 'i1234567-abcd', description: 'Recondicionamento da caixa', quantity: 1, unitPrice: 600 }],
  })
})

test('item novo é enviado sem vínculo com item comercial existente', async () => {
  const fetchMock = mock({
    'GET /api/work-orders/o1/quotes/q1234567-abcd': singleRevisionQuote,
    'POST /api/work-orders/o1/quotes/q1234567-abcd/revisions': singleRevisionQuote,
  })
  renderAt('/ordens-servico/o1/orcamentos/q1234567-abcd')

  await userEvent.click(await screen.findByRole('button', { name: 'Adicionar item' }))
  await userEvent.type(screen.getByLabelText('Descrição 2'), 'Mangueira')
  await userEvent.type(screen.getByLabelText('Preço unitário 2'), '80')
  await userEvent.click(screen.getByRole('button', { name: 'Criar revisão' }))

  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
    '/api/work-orders/o1/quotes/q1234567-abcd/revisions', expect.objectContaining({ method: 'POST' })))
  const sent = bodyOf(fetchMock.mock.calls, '/api/work-orders/o1/quotes/q1234567-abcd/revisions')
  expect(sent.items[1]).toEqual({ quoteItemId: null, description: 'Mangueira', quantity: 1, unitPrice: 80 })
})

test('conflito de concorrência do backend aparece na tela', async () => {
  mock({ 'GET /api/work-orders/o1/quotes/q1234567-abcd': singleRevisionQuote }, {
    'POST /api/work-orders/o1/quotes/q1234567-abcd/revisions': [{
      code: 'QUOTE_REVISION_CONCURRENTLY_MODIFIED',
      message: 'A revisão foi alterada por outra operação; recarregue o orçamento',
    }, 409],
  })
  renderAt('/ordens-servico/o1/orcamentos/q1234567-abcd')

  await userEvent.click(await screen.findByRole('button', { name: 'Criar revisão' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('recarregue o orçamento')
})

test('falha ao carregar o orçamento oferece nova tentativa', async () => {
  mock({}, { 'GET /api/work-orders/o1/quotes/q1234567-abcd': [{ message: 'erro' }, 500] })
  renderAt('/ordens-servico/o1/orcamentos/q1234567-abcd')
  expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar o orçamento')
  expect(screen.getByRole('button', { name: /Tentar novamente/ })).toBeInTheDocument()
})
