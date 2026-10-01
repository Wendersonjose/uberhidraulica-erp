import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { json, lastBody, mockApi, renderAt, session } from './harness'

const aberta = { id: 's1', name: 'Aberta', stage: 'ABERTA', position: 10, active: true, stageDefault: true }
const diagnostico = { id: 's2', name: 'Em diagnóstico', stage: 'EM_DIAGNOSTICO', position: 20, active: true, stageDefault: true }
const order = (extra = {}) => ({
  id: 'o1', number: 5, customerId: 'c1', vehicleId: 'v1', entryMileage: null, openedAt: '2026-09-17T10:00:00Z', status: 'ABERTA',
  statusInfo: aberta, complaint: 'Direção pesada', notes: null, diagnosis: null, lifecycle: {}, services: [], products: [], ...extra,
})
const itemRevision = (id: string, description: string, extra = {}) => ({
  id, revisionSequence: 1, description, quantity: 1, unitPrice: 100, discountAmount: 0, grossTotal: 100, totalPrice: 100,
  revisionReason: null, presented: true, availability: 'AVAILABLE', decision: null, createdAt: '2026-09-17T10:00:00Z', createdBy: 'u1', ...extra,
})
const quote = (revisions: object[] = [itemRevision('ir1', 'Reparo'), itemRevision('ir2', 'Alinhamento')]) => ({
  id: 'q1', workOrderId: 'o1', createdAt: '2026-09-17T10:00:00Z', createdBy: 'u1', availableTotal: 200,
  revisions: [{ id: 'r1', revisionNumber: 1, status: 'PRESENTED', presentedAt: '2026-09-17T10:00:00Z', validUntil: '2026-09-24T10:00:00Z',
    expired: false, total: 200, createdAt: '2026-09-17T10:00:00Z', createdBy: 'u1',
    items: revisions.map((r, i) => ({ quoteItemRevisionId: (r as { id: string }).id, displayOrder: i + 1 })) }],
  items: revisions.map((r, i) => ({ id: `item${i}`, workOrderServiceId: null, createdAt: '2026-09-17T10:00:00Z', revisions: [r] })),
})

test('registra diagnóstico e reflete o novo status da OS', async () => {
  let current = order()
  const fetch = mockApi({
    'GET /api/work-orders/o1': () => json(current),
    'PUT /api/work-orders/o1/diagnosis': () => { current = order({ diagnosis: 'Folga na caixa', status: 'EM_DIAGNOSTICO', statusInfo: diagnostico }); return json(current) },
    'GET /api/customers/c1': { id: 'c1', name: 'Maria' }, 'GET /api/vehicles/v1': { id: 'v1', manufacturer: 'Volvo', model: 'FH', plate: 'TRK1A11' },
    'GET /api/services': [], 'GET /api/products': [], 'GET /api/work-orders/o1/status-history': [], 'GET /api/work-order-statuses': [],
  })
  renderAt('/ordens-servico/o1')
  const card = await screen.findByRole('region', { name: 'Diagnóstico' })
  await userEvent.click(within(card).getByRole('button', { name: 'Registrar diagnóstico' }))
  await userEvent.type(within(card).getByLabelText('Diagnóstico técnico'), 'Folga na caixa')
  await userEvent.click(within(card).getByRole('button', { name: 'Salvar diagnóstico' }))
  await waitFor(() => expect(lastBody(fetch, 'PUT', '/api/work-orders/o1/diagnosis')).toEqual({ diagnosis: 'Folga na caixa' }))
  expect(await within(card).findByText('Folga na caixa')).toBeInTheDocument()
  expect(await screen.findByText('Em diagnóstico', { selector: '.badge' })).toBeInTheDocument()
})

test('desconto só é editável com permissão e vai na nova revisão quando maior que zero', async () => {
  const draftQuote = quote([itemRevision('ir1', 'Reparo', { presented: false, availability: 'NOT_PRESENTED' })])
  const routes = {
    'GET /api/work-orders/o1/quotes/q1': { ...draftQuote, revisions: [{ ...draftQuote.revisions[0], status: 'DRAFT', presentedAt: null, validUntil: null }] },
    'GET /api/work-orders/o1/quotes/q1/public-access': [],
    'POST /api/work-orders/o1/quotes/q1/revisions': draftQuote,
  }
  const fetch = mockApi(routes, { ...session, permissions: ['QUOTE_PRESENT', 'QUOTE_DISCOUNT'] })
  renderAt('/ordens-servico/o1/orcamentos/q1')
  const discount = await screen.findByLabelText('Desconto 1')
  expect(discount).toBeEnabled()
  await userEvent.type(discount, '15')
  await userEvent.click(screen.getByRole('button', { name: 'Criar revisão' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/work-orders/o1/quotes/q1/revisions')).toEqual({
    items: [{ quoteItemId: 'item0', description: 'Reparo', quantity: 1, unitPrice: 100, discount: 15 }],
  }))
})

test('perfil sem permissão de desconto vê o campo bloqueado', async () => {
  mockApi({ 'GET /api/work-orders/o1/quotes/q1': quote(), 'GET /api/work-orders/o1/quotes/q1/public-access': [] },
    { ...session, permissions: ['QUOTE_PRESENT'] })
  renderAt('/ordens-servico/o1/orcamentos/q1')
  expect(await screen.findByLabelText('Desconto 1')).toBeDisabled()
})

test('registra decisão interna aprovando todos os itens pendentes com canal de contato', async () => {
  let current = quote()
  const fetch = mockApi({
    'GET /api/work-orders/o1/quotes/q1': () => json(current),
    'GET /api/work-orders/o1/quotes/q1/public-access': [],
    'POST /api/work-orders/o1/quotes/q1/revisions/r1/decisions': () => {
      current = quote([itemRevision('ir1', 'Reparo', { decision: 'APPROVE' }), itemRevision('ir2', 'Alinhamento', { decision: 'APPROVE' })])
      return json(current)
    },
  }, { ...session, permissions: ['QUOTE_PRESENT'] })
  renderAt('/ordens-servico/o1/orcamentos/q1')
  const card = await screen.findByRole('region', { name: 'Registrar decisão do cliente' })
  await userEvent.selectOptions(within(card).getByLabelText('Canal de contato'), 'TELEFONE')
  await userEvent.type(within(card).getByLabelText('Quem autorizou'), 'Maria')
  await userEvent.click(within(card).getByRole('button', { name: 'Aprovar todos' }))
  await userEvent.click(within(card).getByRole('button', { name: 'Registrar decisão' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/work-orders/o1/quotes/q1/revisions/r1/decisions')).toEqual({
    contactChannel: 'TELEFONE', authorizedBy: 'Maria', notes: null,
    decisions: [{ itemReference: 'ir1', decision: 'APPROVE' }, { itemReference: 'ir2', decision: 'APPROVE' }],
  }))
  await waitFor(() => expect(screen.queryByRole('region', { name: 'Registrar decisão do cliente' })).not.toBeInTheDocument())
  expect(screen.getAllByText('Aprovado')).toHaveLength(2)
})

test('configuração permite desligar regra automática do orçamento', async () => {
  let rules = { DIAGNOSIS_REGISTERED: true, QUOTE_PRESENTED: true, QUOTE_APPROVED: true, QUOTE_REJECTED: true }
  const fetch = mockApi({
    'GET /api/work-order-statuses': [],
    'GET /api/work-order-statuses/automations': () => json(rules),
    'PUT /api/work-order-statuses/automations/QUOTE_PRESENTED': () => { rules = { ...rules, QUOTE_PRESENTED: false }; return json(rules) },
  })
  renderAt('/ordens-servico/status')
  const toggle = await screen.findByRole('checkbox', { name: /Orçamento apresentado/ })
  expect(toggle).toBeChecked()
  await userEvent.click(toggle)
  await waitFor(() => expect(lastBody(fetch, 'PUT', '/api/work-order-statuses/automations/QUOTE_PRESENTED')).toEqual({ enabled: false }))
  await waitFor(() => expect(screen.getByRole('checkbox', { name: /Orçamento apresentado/ })).not.toBeChecked())
})
