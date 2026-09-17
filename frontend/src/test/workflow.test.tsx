import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { json, lastBody, mockApi, renderAt, requestsTo } from './harness'

const status = (id: string, name: string, stage: string, extra = {}) => ({ id, name, stage, position: 10, active: true, stageDefault: true, ...extra })
const aberta = status('s1', 'Aberta', 'ABERTA')
const execucao = status('s6', 'Em execução', 'EM_EXECUCAO')
const peca = status('s10', 'Aguardando peça', 'EM_EXECUCAO', { stageDefault: false })
const finalizada = status('s7', 'Finalizada', 'FINALIZADA')
const cancelada = status('s9', 'Cancelada', 'CANCELADA')
const lifecycle = { executionStartedAt: null, finishedAt: null, finishedBy: null, deliveredAt: null, deliveredBy: null, cancelledAt: null, cancelledBy: null, cancellationReason: null }
const order = (stage: string, statusInfo: object, extra = {}) => ({
  id: 'o1', number: 12, customerId: 'c1', vehicleId: 'v1', entryMileage: null, openedAt: '2026-09-17T10:00:00Z',
  status: stage, statusInfo, complaint: 'Direção pesada', notes: null, lifecycle, services: [], products: [], ...extra,
})
const detailRoutes = {
  'GET /api/customers/c1': { id: 'c1', name: 'Maria Souza' },
  'GET /api/vehicles/v1': { id: 'v1', manufacturer: 'Volvo', model: 'FH', plate: 'TRK1A11' },
  'GET /api/services': [],
  'GET /api/products': [],
  'GET /api/work-orders/o1/status-history': [{ id: 'h1', fromStatusId: null, fromStatusName: null, toStatusId: 's1', toStatusName: 'Aberta', changedAt: '2026-09-17T10:00:00Z', changedBy: null, reason: null, automatic: true }],
  'GET /api/work-order-statuses': [{ status: aberta, orderCount: 1 }, { status: peca, orderCount: 0 }, { status: execucao, orderCount: 0 }, { status: finalizada, orderCount: 0 }],
}

test('Kanban mostra colunas, cartões com cliente e veículo e muda o período das encerradas', async () => {
  const fetch = mockApi({
    'GET /api/work-orders/board': (_init: RequestInit | undefined, url: URL) => json([
      { status: aberta, orders: [{ id: 'o1', number: 12, openedAt: '2026-09-17T10:00:00Z', complaint: 'Direção pesada', customerName: 'Maria Souza', vehicleLabel: 'Volvo FH • TRK1A11' }] },
      { status: cancelada, orders: url.searchParams.get('closedDays') === '90' ? [{ id: 'o2', number: 3, openedAt: '2026-06-01T10:00:00Z', complaint: null, customerName: 'Pedro', vehicleLabel: null }] : [] },
    ]),
  })
  renderAt('/ordens-servico')
  const open = await screen.findByRole('region', { name: 'Aberta' })
  expect(within(open).getByRole('link', { name: /OS #12/ })).toHaveTextContent('Volvo FH • TRK1A11')
  expect(within(screen.getByRole('region', { name: 'Cancelada' })).queryByRole('link')).not.toBeInTheDocument()
  await userEvent.selectOptions(screen.getByLabelText('Período das encerradas'), '90')
  expect(await within(screen.getByRole('region', { name: 'Cancelada' })).findByRole('link', { name: /OS #3/ })).toBeInTheDocument()
  expect(requestsTo(fetch, 'GET', '/api/work-orders/board?closedDays=90')).toHaveLength(1)
})

test('detalhe move para status operacional e inicia execução', async () => {
  let current = order('ABERTA', aberta)
  const fetch = mockApi({
    ...detailRoutes,
    'GET /api/work-orders/o1': () => json(current),
    'POST /api/work-orders/o1/status': () => { current = order('EM_EXECUCAO', peca); return json(current) },
    'POST /api/work-orders/o1/start-execution': () => { current = order('EM_EXECUCAO', execucao); return json(current) },
  })
  renderAt('/ordens-servico/o1')
  expect(await screen.findByText(/Aberta$/, { selector: 'strong' })).toBeInTheDocument()
  const moveTo = await screen.findByLabelText('Mover para')
  await screen.findByRole('option', { name: 'Aguardando peça' })
  expect(within(moveTo).queryByRole('option', { name: 'Finalizada' })).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Iniciar execução' }))
  await waitFor(() => expect(requestsTo(fetch, 'POST', '/api/work-orders/o1/start-execution')).toHaveLength(1))
  expect(await screen.findByRole('button', { name: 'Finalizar OS' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Iniciar execução' })).not.toBeInTheDocument()
})

test('cancelamento exige motivo e encerra as ações do fluxo', async () => {
  let current = order('ABERTA', aberta)
  const fetch = mockApi({
    ...detailRoutes,
    'GET /api/work-orders/o1': () => json(current),
    'POST /api/work-orders/o1/cancel': () => {
      current = order('CANCELADA', cancelada, { lifecycle: { ...lifecycle, cancelledAt: '2026-09-17T12:00:00Z', cancellationReason: 'Cliente desistiu' } })
      return json(current)
    },
  })
  renderAt('/ordens-servico/o1')
  await userEvent.click(await screen.findByRole('button', { name: 'Cancelar OS' }))
  const dialog = screen.getByRole('group', { name: /Cancelar a OS/ })
  await userEvent.click(within(dialog).getByRole('button', { name: 'Cancelar OS' }))
  expect(await within(dialog).findByRole('alert')).toHaveTextContent('Informe o motivo')
  expect(requestsTo(fetch, 'POST', '/api/work-orders/o1/cancel')).toHaveLength(0)
  await userEvent.type(within(dialog).getByLabelText('Motivo do cancelamento *'), 'Cliente desistiu')
  await userEvent.click(within(dialog).getByRole('button', { name: 'Cancelar OS' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/work-orders/o1/cancel')).toEqual({ reason: 'Cliente desistiu' }))
  expect(await screen.findByText(/Cliente desistiu/, { selector: 'dd' })).toBeInTheDocument()
  expect(screen.queryByRole('region', { name: 'Fluxo da OS' })).not.toBeInTheDocument()
  expect(screen.queryByLabelText('Catálogo ativo')).not.toBeInTheDocument()
})

test('abertura envia reclamação obrigatória e quilometragem opcional', async () => {
  const fetch = mockApi({
    'GET /api/customers': [{ id: 'c1', name: 'Maria Souza', status: 'ACTIVE', personType: 'PF', document: null }],
    'GET /api/customers/c1/vehicles': [
      { id: 'v1', customerId: 'c1', manufacturer: 'Volvo', model: 'FH', plate: 'TRK1A11', active: true },
      { id: 'v2', customerId: 'c1', manufacturer: 'Ford', model: 'Velho', plate: 'OLD0A00', active: false },
    ],
    'POST /api/work-orders': order('ABERTA', aberta),
    ...detailRoutes,
    'GET /api/work-orders/o1': order('ABERTA', aberta),
  })
  renderAt('/ordens-servico/nova')
  await screen.findByRole('option', { name: 'Maria Souza' })
  await userEvent.selectOptions(screen.getByLabelText('Cliente'), 'c1')
  await screen.findByRole('option', { name: /TRK1A11/ })
  expect(screen.queryByRole('option', { name: /OLD0A00/ })).not.toBeInTheDocument()
  await userEvent.selectOptions(screen.getByLabelText('Veículo'), 'v1')
  await userEvent.type(screen.getByLabelText('Defeito/reclamação relatada *'), 'Direção pesada')
  await userEvent.click(screen.getByRole('button', { name: 'Criar OS' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/work-orders')).toEqual({
    customerId: 'c1', vehicleId: 'v1', entryMileage: null, complaint: 'Direção pesada', notes: null,
  }))
})

test('configuração de status cria, reordena e protege o padrão da etapa', async () => {
  const fetch = mockApi({
    'GET /api/work-order-statuses': [{ status: aberta, orderCount: 2 }, { status: peca, orderCount: 0 }, { status: execucao, orderCount: 1 }],
    'POST /api/work-order-statuses': status('s11', 'Lavagem', 'EM_EXECUCAO', { stageDefault: false }),
    'PUT /api/work-order-statuses/order': [],
    'POST /api/work-order-statuses/s10/make-default': peca,
  })
  renderAt('/ordens-servico/status')
  const row = (await screen.findByRole('button', { name: 'Renomear Aguardando peça' })).closest('tr')!
  expect(within(row).queryByText('Padrão')).not.toBeInTheDocument()
  const defaultRow = screen.getByRole('button', { name: 'Renomear Aberta' }).closest('tr')!
  expect(within(defaultRow).queryByRole('button', { name: 'Inativar' })).not.toBeInTheDocument()

  await userEvent.click(screen.getByRole('button', { name: 'Descer Aberta' }))
  await waitFor(() => expect(lastBody(fetch, 'PUT', '/api/work-order-statuses/order')).toEqual({ statusIds: ['s10', 's1', 's6'] }))
  await userEvent.click(within(row).getByRole('button', { name: 'Tornar padrão' }))
  await waitFor(() => expect(requestsTo(fetch, 'POST', '/api/work-order-statuses/s10/make-default')).toHaveLength(1))

  await userEvent.type(screen.getByLabelText('Novo status'), 'Lavagem')
  await userEvent.click(screen.getByRole('button', { name: 'Adicionar status' }))
  await waitFor(() => expect(lastBody(fetch, 'POST', '/api/work-order-statuses')).toEqual({ name: 'Lavagem', stage: 'EM_EXECUCAO' }))
})
