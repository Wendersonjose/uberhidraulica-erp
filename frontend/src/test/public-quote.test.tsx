import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { expect, test, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthProvider'
import App from '../App'

const decidable = {
  itemReference: 'ir1', description: 'Recondicionamento da caixa', quantity: 1, unitPrice: 500,
  totalPrice: 500, decisionStatus: 'PENDING_APPROVAL', decisionAvailability: 'DECIDABLE',
}
const superseded = {
  itemReference: 'ir2', description: 'Mangueira', quantity: 2, unitPrice: 100, totalPrice: 200,
  decisionStatus: 'PENDING_APPROVAL', decisionAvailability: 'SUPERSEDED',
}
const approved = { ...decidable, itemReference: 'ir3', description: 'Alinhamento', decisionStatus: 'APPROVED', decisionAvailability: 'ALREADY_DECIDED' }
const publicQuote = {
  revisionReference: 'r1', revisionNumber: 2, presentedAt: '2026-09-14T10:00:00Z',
  validUntil: '2026-09-21T10:00:00Z', total: 700, items: [decidable, superseded, approved],
}

function json(body: unknown, status = 200) {
  return new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function mock(routes: Record<string, unknown>, errors: Record<string, [unknown, number]> = {}) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const key = `${init?.method || 'GET'} ${String(input)}`
    if (key in errors) return json(errors[key][0], errors[key][1])
    return key in routes ? json(routes[key], init?.method === 'POST' ? 201 : 200) : json({ message: 'Rota não simulada' }, 500)
  })
}

function renderPublic(token = 'tok-secreto') {
  // A rota pública é reconhecida pelo caminho do navegador, como em produção.
  window.history.replaceState({}, '', `/orcamento/${token}`)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[`/orcamento/${token}`]}>
    <AuthProvider><App /></AuthProvider>
  </MemoryRouter></QueryClientProvider>)
}

const bodyOf = (calls: Parameters<typeof fetch>[], path: string) =>
  JSON.parse(String(calls.filter(call => String(call[0]) === path && call[1]?.body).at(-1)?.[1]?.body))

test('página pública não consulta sessão nem envia cookie', async () => {
  const fetchMock = mock({ 'GET /api/public/quotes/tok-secreto': publicQuote })
  renderPublic()

  await screen.findByText('Recondicionamento da caixa')
  const paths = fetchMock.mock.calls.map(call => String(call[0]))
  expect(paths).not.toContain('/api/iam/session')
  expect(paths).not.toContain('/api/iam/csrf')
  expect(fetchMock).toHaveBeenCalledWith('/api/public/quotes/tok-secreto',
    expect.objectContaining({ credentials: 'omit' }))
})

test('item substituído e item já decidido não oferecem decisão', async () => {
  mock({ 'GET /api/public/quotes/tok-secreto': publicQuote })
  renderPublic()

  await screen.findByLabelText('Decisão para Recondicionamento da caixa')
  expect(screen.queryByLabelText('Decisão para Mangueira')).not.toBeInTheDocument()
  expect(screen.getByText('Substituído por uma versão mais recente')).toBeInTheDocument()
  expect(screen.queryByLabelText('Decisão para Alinhamento')).not.toBeInTheDocument()
  expect(screen.getByText('Já decidido')).toBeInTheDocument()
  expect(screen.getByTestId('public-total')).toHaveTextContent('R$ 700,00')
})

test('envio exige aceite explícito, nome e documento coerentes', async () => {
  mock({ 'GET /api/public/quotes/tok-secreto': publicQuote })
  renderPublic()

  await userEvent.selectOptions(await screen.findByLabelText('Decisão para Recondicionamento da caixa'), 'APPROVE')
  const send = screen.getByRole('button', { name: 'Enviar decisão' })
  expect(send).toBeDisabled()

  await userEvent.type(screen.getByLabelText('Seu nome completo'), 'José da Silva')
  expect(send).toBeDisabled()

  await userEvent.type(screen.getByLabelText('CPF'), '123.456.789-0')
  await userEvent.click(screen.getByLabelText(/Declaro que li e aceito/))
  // CPF ainda incompleto: dez dígitos não formam um documento.
  expect(send).toBeDisabled()

  await userEvent.type(screen.getByLabelText('CPF'), '1')
  expect(send).toBeEnabled()
})

test('aprovação parcial envia somente os itens decididos', async () => {
  const fetchMock = mock({
    'GET /api/public/quotes/tok-secreto': publicQuote,
    'POST /api/public/quotes/tok-secreto/decisions': { quote: publicQuote, replayed: false },
  })
  renderPublic()

  await userEvent.selectOptions(await screen.findByLabelText('Decisão para Recondicionamento da caixa'), 'APPROVE')
  await userEvent.type(screen.getByLabelText('Seu nome completo'), 'José da Silva')
  await userEvent.type(screen.getByLabelText('CPF'), '12345678901')
  await userEvent.click(screen.getByLabelText(/Declaro que li e aceito/))
  await userEvent.click(screen.getByRole('button', { name: 'Enviar decisão' }))

  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/public/quotes/tok-secreto/decisions',
    expect.objectContaining({ method: 'POST' })))
  const sent = bodyOf(fetchMock.mock.calls, '/api/public/quotes/tok-secreto/decisions')
  expect(sent.decisions).toEqual([{ itemReference: 'ir1', decision: 'APPROVE' }])
  expect(sent.customer).toEqual({ name: 'José da Silva', documentType: 'CPF', documentNumber: '12345678901' })
  expect(sent.explicitAcceptance).toBe(true)
  expect(sent.revisionReference).toBe('r1')
  expect(sent.requestId).toMatch(/^[0-9a-f-]{36}$/)
  expect(await screen.findByRole('status')).toHaveTextContent('Decisão registrada')
})

test('replay reconhecido pelo backend é informado sem alarme', async () => {
  mock({
    'GET /api/public/quotes/tok-secreto': publicQuote,
    'POST /api/public/quotes/tok-secreto/decisions': { quote: publicQuote, replayed: true },
  })
  renderPublic()

  await userEvent.selectOptions(await screen.findByLabelText('Decisão para Recondicionamento da caixa'), 'REJECT')
  await userEvent.type(screen.getByLabelText('Seu nome completo'), 'José da Silva')
  await userEvent.type(screen.getByLabelText('CPF'), '12345678901')
  await userEvent.click(screen.getByLabelText(/Declaro que li e aceito/))
  await userEvent.click(screen.getByRole('button', { name: 'Enviar decisão' }))

  expect(await screen.findByRole('status')).toHaveTextContent('já havia sido registrado')
})

test('link indisponível mostra a mensagem do backend e nada do orçamento', async () => {
  mock({}, { 'GET /api/public/quotes/tok-invalido': [{ code: 'PUBLIC_QUOTE_NOT_AVAILABLE', message: 'Orçamento indisponível' }, 404] })
  renderPublic('tok-invalido')

  expect(await screen.findByRole('alert')).toHaveTextContent('Orçamento indisponível')
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
})

test('recusa de item obsoleto pelo backend aparece ao cliente', async () => {
  mock({ 'GET /api/public/quotes/tok-secreto': publicQuote },
    { 'POST /api/public/quotes/tok-secreto/decisions': [{ code: 'QUOTE_ITEM_REVISION_STALE', message: 'Este item foi atualizado. Recarregue o orçamento antes de continuar' }, 409] })
  renderPublic()

  await userEvent.selectOptions(await screen.findByLabelText('Decisão para Recondicionamento da caixa'), 'APPROVE')
  await userEvent.type(screen.getByLabelText('Seu nome completo'), 'José da Silva')
  await userEvent.type(screen.getByLabelText('CPF'), '12345678901')
  await userEvent.click(screen.getByLabelText(/Declaro que li e aceito/))
  await userEvent.click(screen.getByRole('button', { name: 'Enviar decisão' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('Recarregue o orçamento')
})
