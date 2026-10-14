import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'
import { json, lastBody, mockApi, page, renderAt, requestsTo, session } from './harness'

const VIEW = ['PURCHASE_VIEW']
const MANAGE = ['PURCHASE_VIEW', 'PURCHASE_ORDER_MANAGE']
const APPROVE = [...MANAGE, 'PURCHASE_APPROVE']
const APPROVE_OWN = [...APPROVE, 'PURCHASE_APPROVE_OWN']
const as = (permissions: string[]) => ({ ...session, permissions })
vi.setConfig({ testTimeout: 30_000 })

// A sessão do harness é o usuário "u1": createdBy 'u1' é o próprio autor, 'u2' é outra pessoa.
const ME = 'u1', OTHER = 'u2'

const supplier = {
  id: 's1', personType: 'PJ', legalName: 'Distribuidora Alfa Ltda', tradeName: 'Alfa Peças', document: '67757350000178',
  documentMasked: false, stateRegistration: null, phone: null, email: null,
  address: { zipCode: null, street: null, number: null, complement: null, district: null, city: null, state: null },
  commercialTerms: { paymentTerms: null, preferredPaymentMethod: null, usualDueDay: null, creditLimit: null, notes: null },
  relatedParty: false, relatedPartyNote: null, status: 'ACTIVE', inactivatedAt: null, inactivationReason: null, version: 1,
  createdAt: '2026-10-13T12:00:00Z', updatedAt: '2026-10-13T12:00:00Z',
}
const product = (extra = {}) => ({
  id: 'prod1', description: 'Retentor 35x62', internalCode: 'RET-35', category: null, type: 'PART', unit: 'UNIDADE',
  referenceCost: null, salePrice: null, minimumStock: null, active: true,
  createdAt: '2026-10-13T12:00:00Z', updatedAt: '2026-10-13T12:00:00Z', ...extra,
})

const order = (extra = {}) => ({
  id: 'p1', number: 5, supplierId: 's1', origin: 'ORDER', status: 'DRAFT', receiptStatus: 'NONE',
  goodsTotal: 100, discountTotal: 0, freightTotal: 10, insuranceTotal: 0, otherTotal: 0, taxAddedTotal: 0, payableTotal: 110,
  expenseCategoryId: null, workOrderId: null, notes: 'Urgente', createdAt: '2026-10-14T12:00:00Z', cancelledAt: null,
  cancellationReason: null,
  items: [{
    id: 'i1', lineNo: 1, productId: 'prod1', supplierCode: 'RET-1', description: 'Retentor 35x62', unitOriginal: 'UNIDADE',
    quantityOriginal: 10, unitConversionFactor: 1, quantity: 10, unitPrice: 10, unitCost: 11, discount: 0, lineTotal: 100,
    ncm: null, cfop: null, gtin: null, workOrderItemId: null, cost: null,
  }],
  documents: [], installments: [], version: 2, createdBy: ME, updatedAt: null, approvedAt: null, approvedBy: null, ...extra,
})

const detailRoutes = (value = order()) => ({
  'GET /api/purchasing/purchases/p1': value,
  'GET /api/purchasing/purchases/p1/events': page([]),
  'GET /api/purchasing/purchases/p1/receiving': { purchaseId: 'p1', status: 'NONE', lines: [] },
  'GET /api/purchasing/purchases/p1/receipts': [],
})

const formRoutes = (extra: Record<string, unknown> = {}) => ({
  'GET /api/purchasing/suppliers': page([supplier]),
  'GET /api/purchasing/suppliers/s1': supplier,
  'GET /api/products': [product(), product({ id: 'prod2', description: 'Inativo antigo', active: false })],
  'GET /api/work-orders': [],
  ...extra,
})

const headerOf = (init: RequestInit | undefined, name: string) => new Headers(init?.headers).get(name)
/** Espera a lista de fornecedores chegar: o select existe antes das opções. */
async function pickSupplier() {
  await screen.findByRole('option', { name: 'Distribuidora Alfa Ltda' })
  await userEvent.selectOptions(screen.getByLabelText('Fornecedor'), 's1')
}
const workflow = async () => within(await screen.findByRole('region', { name: 'Aprovação do pedido' }))

// ====================================================================== lista

test('quem gerencia pedidos vê "Novo pedido"; quem só consulta não vê', async () => {
  mockApi({ 'GET /api/purchasing/purchases': page([]) }, as(MANAGE))
  renderAt('/compras/notas')
  expect(await screen.findByRole('link', { name: 'Novo pedido' })).toHaveAttribute('href', '/compras/pedidos/novo')
})

test('sem PURCHASE_ORDER_MANAGE não há botão', async () => {
  mockApi({ 'GET /api/purchasing/purchases': page([]) }, as(VIEW))
  renderAt('/compras/notas')
  await screen.findByText('Nenhuma compra encontrada.')
  expect(screen.queryByRole('link', { name: 'Novo pedido' })).not.toBeInTheDocument()
})

test('a rota do formulário nega o acesso a quem só consulta', async () => {
  mockApi(formRoutes(), as(VIEW))
  renderAt('/compras/pedidos/novo')

  expect(await screen.findByRole('heading', { name: 'Acesso negado' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Salvar rascunho' })).not.toBeInTheDocument()
})

test('a lista filtra por origem e por situação, incluindo rascunho e aguardando aprovação', async () => {
  const fetchSpy = mockApi({ 'GET /api/purchasing/purchases': page([]) }, as(VIEW))
  renderAt('/compras/notas')

  await userEvent.selectOptions(await screen.findByLabelText('Origem'), 'ORDER')
  await userEvent.selectOptions(screen.getByLabelText('Situação'), 'AWAITING_APPROVAL')

  await waitFor(() => expect(fetchSpy.mock.calls.some(([input]) =>
    String(input).includes('origin=ORDER') && String(input).includes('status=AWAITING_APPROVAL'))).toBe(true))
})

// ====================================================================== formulário

test('o formulário calcula o total com o frete rateado e só salva com fornecedor e itens válidos', async () => {
  mockApi(formRoutes(), as(MANAGE))
  renderAt('/compras/pedidos/novo')

  const save = await screen.findByRole('button', { name: 'Salvar rascunho' })
  expect(save).toBeDisabled()

  await pickSupplier()
  await userEvent.type(screen.getByLabelText('Buscar produto do item 1'), 'retentor')
  await userEvent.selectOptions(screen.getByLabelText('Produto do item 1'), 'prod1')
  await userEvent.type(screen.getByLabelText('Quantidade do item 1'), '10')
  await userEvent.type(screen.getByLabelText('Preço unitário do item 1'), '5,5')
  await userEvent.type(screen.getByLabelText('Frete (R$)'), '5')

  const totals = within(screen.getByLabelText('Totais do pedido'))
  expect(totals.getByText('R$ 60,00')).toBeInTheDocument()
  // Custo unitário com o rateio do frete: (55,00 + 5,00) ÷ 10 = 6,00.
  expect(screen.getByText('R$ 6,00')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Salvar rascunho' })).toBeEnabled()
})

test('produto inativo não aparece para ser escolhido', async () => {
  mockApi(formRoutes(), as(MANAGE))
  renderAt('/compras/pedidos/novo')

  await userEvent.type(await screen.findByLabelText('Buscar produto do item 1'), 'inativo')
  expect(within(screen.getByLabelText('Produto do item 1')).queryByRole('option', { name: /Inativo antigo/ })).not.toBeInTheDocument()
})

test('quantidade inválida marca o item e bloqueia o salvamento', async () => {
  mockApi(formRoutes(), as(MANAGE))
  renderAt('/compras/pedidos/novo')

  await pickSupplier()
  await userEvent.type(screen.getByLabelText('Buscar produto do item 1'), 'retentor')
  await userEvent.selectOptions(screen.getByLabelText('Produto do item 1'), 'prod1')
  await userEvent.type(screen.getByLabelText('Quantidade do item 1'), '0')
  await userEvent.type(screen.getByLabelText('Preço unitário do item 1'), '5')

  expect(await screen.findByText(/quantidade inválida/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Salvar rascunho' })).toBeDisabled()
})

test('salvar cria o rascunho com Idempotency-Key e leva para a compra criada', async () => {
  const fetchSpy = mockApi(formRoutes({
    'POST /api/purchasing/orders': order({ id: 'p9', number: 9 }),
    'GET /api/purchasing/purchases/p9': order({ id: 'p9', number: 9 }),
    'GET /api/purchasing/purchases/p9/events': page([]),
    'GET /api/purchasing/purchases/p9/receiving': { purchaseId: 'p9', status: 'NONE', lines: [] },
    'GET /api/purchasing/purchases/p9/receipts': [],
  }), as(MANAGE))
  renderAt('/compras/pedidos/novo')

  await userEvent.selectOptions(await screen.findByLabelText('Tipo de compra'), 'DIRECT')
  await pickSupplier()
  await userEvent.type(screen.getByLabelText('Buscar produto do item 1'), 'retentor')
  await userEvent.selectOptions(screen.getByLabelText('Produto do item 1'), 'prod1')
  await userEvent.type(screen.getByLabelText('Quantidade do item 1'), '10')
  await userEvent.type(screen.getByLabelText('Preço unitário do item 1'), '5,5')
  await userEvent.type(screen.getByLabelText('Desconto do item 1'), '1')
  await userEvent.type(screen.getByLabelText('Código do fornecedor do item 1'), 'RET-1')
  await userEvent.click(screen.getByRole('button', { name: 'Salvar rascunho' }))

  expect(await screen.findByRole('heading', { name: 'Compra #9' })).toBeInTheDocument()
  expect(lastBody(fetchSpy, 'POST', '/api/purchasing/orders')).toEqual({
    origin: 'DIRECT', supplierId: 's1', workOrderId: null, notes: null, freight: 0, insurance: 0, other: 0,
    lines: [{ productId: 'prod1', quantity: 10, unitPrice: 5.5, discount: 1, supplierCode: 'RET-1' }],
  })
  expect(headerOf(requestsTo(fetchSpy, 'POST', '/api/purchasing/orders')[0][1], 'Idempotency-Key')).toBeTruthy()
})

test('falha ao salvar mostra o erro do servidor e mantém o que foi digitado', async () => {
  mockApi(formRoutes({
    'POST /api/purchasing/orders': () => json({ code: 'PURCHASE_SUPPLIER_INACTIVE', message: 'Fornecedor está inativo' }, 400),
  }), as(MANAGE))
  renderAt('/compras/pedidos/novo')

  await pickSupplier()
  await userEvent.type(screen.getByLabelText('Buscar produto do item 1'), 'retentor')
  await userEvent.selectOptions(screen.getByLabelText('Produto do item 1'), 'prod1')
  await userEvent.type(screen.getByLabelText('Quantidade do item 1'), '2')
  await userEvent.type(screen.getByLabelText('Preço unitário do item 1'), '3')
  await userEvent.click(screen.getByRole('button', { name: 'Salvar rascunho' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('Fornecedor está inativo')
  expect(screen.getByLabelText('Quantidade do item 1')).toHaveValue('2')
})

test('editar o rascunho já vem preenchido e envia a versão que a tela leu', async () => {
  const fetchSpy = mockApi({
    ...formRoutes(),
    ...detailRoutes(),
    'PUT /api/purchasing/orders/p1': order({ version: 3 }),
  }, as(MANAGE))
  renderAt('/compras/pedidos/p1/editar')

  expect(await screen.findByRole('heading', { name: 'Editar pedido #5' })).toBeInTheDocument()
  expect(await screen.findByLabelText('Quantidade do item 1')).toHaveValue('10')
  expect(screen.getByLabelText('Preço unitário do item 1')).toHaveValue('10')
  expect(screen.getByLabelText('Frete (R$)')).toHaveValue('10')
  expect(screen.queryByLabelText('Tipo de compra')).not.toBeInTheDocument()

  await userEvent.clear(screen.getByLabelText('Quantidade do item 1'))
  await userEvent.type(screen.getByLabelText('Quantidade do item 1'), '12')
  await userEvent.click(screen.getByRole('button', { name: 'Salvar alterações' }))

  await waitFor(() => expect(requestsTo(fetchSpy, 'PUT', '/api/purchasing/orders/p1')).toHaveLength(1))
  expect(lastBody(fetchSpy, 'PUT', '/api/purchasing/orders/p1')).toMatchObject({
    version: 2, supplierId: 's1', freight: 10, lines: [{ productId: 'prod1', quantity: 12, unitPrice: 10, supplierCode: 'RET-1' }],
  })
})

test('pedido que não é rascunho não abre para edição', async () => {
  mockApi({ ...formRoutes(), ...detailRoutes(order({ status: 'AWAITING_APPROVAL' })) }, as(MANAGE))
  renderAt('/compras/pedidos/p1/editar')

  expect(await screen.findByRole('alert')).toHaveTextContent('Só o rascunho')
  expect(screen.queryByRole('button', { name: 'Salvar alterações' })).not.toBeInTheDocument()
})

// ====================================================================== fluxo de aprovação

test('rascunho: oferece editar e enviar para aprovação, e envia a versão lida', async () => {
  const fetchSpy = mockApi({
    ...detailRoutes(),
    'POST /api/purchasing/orders/p1/submit': order({ status: 'AWAITING_APPROVAL', version: 3 }),
  }, as(MANAGE))
  renderAt('/compras/notas/p1')

  const flow = await workflow()
  expect(flow.getByRole('link', { name: 'Editar rascunho' })).toHaveAttribute('href', '/compras/pedidos/p1/editar')
  await userEvent.click(flow.getByRole('button', { name: 'Enviar para aprovação' }))
  await userEvent.click(flow.getByRole('button', { name: 'Confirmar envio' }))

  await waitFor(() => expect(requestsTo(fetchSpy, 'POST', '/api/purchasing/orders/p1/submit')).toHaveLength(1))
  expect(lastBody(fetchSpy, 'POST', '/api/purchasing/orders/p1/submit')).toEqual({ version: 2 })
})

test('quem só consulta não vê editar nem enviar o rascunho', async () => {
  mockApi(detailRoutes(), as(VIEW))
  renderAt('/compras/notas/p1')

  const flow = await workflow()
  expect(flow.queryByRole('link', { name: 'Editar rascunho' })).not.toBeInTheDocument()
  expect(flow.queryByRole('button', { name: 'Enviar para aprovação' })).not.toBeInTheDocument()
})

test('outra pessoa com PURCHASE_APPROVE aprova o pedido enviando a versão que viu', async () => {
  const fetchSpy = mockApi({
    ...detailRoutes(order({ status: 'AWAITING_APPROVAL', createdBy: OTHER, version: 4 })),
    'POST /api/purchasing/orders/p1/approve': order({ status: 'APPROVED', version: 5 }),
  }, as(APPROVE))
  renderAt('/compras/notas/p1')

  const flow = await workflow()
  await userEvent.click(flow.getByRole('button', { name: 'Aprovar pedido' }))
  await userEvent.click(flow.getByRole('button', { name: 'Confirmar aprovação' }))

  await waitFor(() => expect(requestsTo(fetchSpy, 'POST', '/api/purchasing/orders/p1/approve')).toHaveLength(1))
  expect(lastBody(fetchSpy, 'POST', '/api/purchasing/orders/p1/approve')).toEqual({ version: 4 })
})

test('D8: o autor sem PURCHASE_APPROVE_OWN não vê aprovar e lê o motivo', async () => {
  mockApi(detailRoutes(order({ status: 'AWAITING_APPROVAL', createdBy: ME })), as(APPROVE))
  renderAt('/compras/notas/p1')

  const flow = await workflow()
  expect(flow.queryByRole('button', { name: 'Aprovar pedido' })).not.toBeInTheDocument()
  expect(flow.getByRole('note')).toHaveTextContent('não pode aprová-lo')
})

test('D8: o autor com PURCHASE_APPROVE_OWN (exceção do dono) vê aprovar', async () => {
  mockApi(detailRoutes(order({ status: 'AWAITING_APPROVAL', createdBy: ME })), as(APPROVE_OWN))
  renderAt('/compras/notas/p1')

  const flow = await workflow()
  expect(flow.getByRole('button', { name: 'Aprovar pedido' })).toBeInTheDocument()
})

test('sem PURCHASE_APPROVE ninguém aprova nem devolve, mesmo sendo outra pessoa', async () => {
  mockApi(detailRoutes(order({ status: 'AWAITING_APPROVAL', createdBy: OTHER })), as(MANAGE))
  renderAt('/compras/notas/p1')

  const flow = await workflow()
  expect(flow.queryByRole('button', { name: 'Aprovar pedido' })).not.toBeInTheDocument()
  expect(flow.queryByRole('button', { name: 'Devolver para correção' })).not.toBeInTheDocument()
  expect(flow.getByText(/Quem tem permissão de aprovar/)).toBeInTheDocument()
})

test('devolver exige o motivo e o envia junto com a versão', async () => {
  const fetchSpy = mockApi({
    ...detailRoutes(order({ status: 'AWAITING_APPROVAL', createdBy: OTHER, version: 4 })),
    'POST /api/purchasing/orders/p1/return': order({ status: 'DRAFT', version: 5 }),
  }, as(APPROVE))
  renderAt('/compras/notas/p1')

  const flow = await workflow()
  await userEvent.click(flow.getByRole('button', { name: 'Devolver para correção' }))
  await userEvent.click(flow.getByRole('button', { name: 'Confirmar devolução' }))
  expect(await flow.findByRole('alert')).toHaveTextContent('Informe o motivo da devolução')
  expect(requestsTo(fetchSpy, 'POST', '/api/purchasing/orders/p1/return')).toHaveLength(0)

  await userEvent.type(flow.getByLabelText(/Motivo da devolução/), 'Preço acima do combinado')
  await userEvent.click(flow.getByRole('button', { name: 'Confirmar devolução' }))

  await waitFor(() => expect(requestsTo(fetchSpy, 'POST', '/api/purchasing/orders/p1/return')).toHaveLength(1))
  expect(lastBody(fetchSpy, 'POST', '/api/purchasing/orders/p1/return'))
    .toEqual({ version: 4, reason: 'Preço acima do combinado' })
})

test('erro do servidor na aprovação (conflito de versão) aparece na própria ação', async () => {
  mockApi({
    ...detailRoutes(order({ status: 'AWAITING_APPROVAL', createdBy: OTHER })),
    'POST /api/purchasing/orders/p1/approve': () => json({
      code: 'PURCHASE_ORDER_VERSION_CONFLICT', message: 'O pedido foi alterado por outra pessoa: recarregue antes de continuar',
    }, 409),
  }, as(APPROVE))
  renderAt('/compras/notas/p1')

  const flow = await workflow()
  await userEvent.click(flow.getByRole('button', { name: 'Aprovar pedido' }))
  await userEvent.click(flow.getByRole('button', { name: 'Confirmar aprovação' }))

  expect(await flow.findByRole('alert')).toHaveTextContent('alterado por outra pessoa')
})

test('pedido aprovado avisa que ainda não há ligação com a NF-e e não mostra contas a pagar', async () => {
  mockApi(detailRoutes(order({ status: 'APPROVED', approvedAt: '2026-10-14T15:00:00Z', approvedBy: OTHER })), as(VIEW))
  renderAt('/compras/notas/p1')

  const flow = await workflow()
  expect(flow.getByText(/Aprovado em/)).toBeInTheDocument()
  expect(flow.getByRole('note')).toHaveTextContent('nunca nos dois')
  const installments = screen.getByRole('region', { name: 'Parcelas' })
  expect(within(installments).getByText(/não geram contas a pagar/)).toBeInTheDocument()
})

test('aprovado pelo próprio autor fica dito na tela', async () => {
  mockApi(detailRoutes(order({ status: 'APPROVED', approvedAt: '2026-10-14T15:00:00Z', approvedBy: ME, createdBy: ME })), as(VIEW))
  renderAt('/compras/notas/p1')

  expect(await (await workflow()).findByText(/próprio autor/)).toBeInTheDocument()
})

test('a NF-e importada não tem seção de aprovação', async () => {
  mockApi(detailRoutes(order({ origin: 'NFE_IMPORT', status: 'APPROVED', createdBy: OTHER })), as(APPROVE))
  renderAt('/compras/notas/p1')

  await screen.findByRole('heading', { name: 'Compra #5' })
  expect(screen.queryByRole('region', { name: 'Aprovação do pedido' })).not.toBeInTheDocument()
})

test('o histórico mostra os eventos do pedido em português', async () => {
  mockApi({
    ...detailRoutes(order({ status: 'DRAFT' })),
    'GET /api/purchasing/purchases/p1/events': page([
      { id: 'e2', type: 'ORDER_RETURNED', actorId: 'u2', occurredAt: '2026-10-14T13:00:00Z', reason: 'Preço acima', payload: null },
      { id: 'e1', type: 'CREATED', actorId: 'u1', occurredAt: '2026-10-14T12:00:00Z', reason: null, payload: null },
    ]),
  }, as(VIEW))
  renderAt('/compras/notas/p1')

  const history = await screen.findByRole('region', { name: 'Histórico da compra' })
  expect(await within(history).findByText('Devolvido para correção')).toBeInTheDocument()
  expect(within(history).getByText('Preço acima')).toBeInTheDocument()
})
