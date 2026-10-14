import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'
import { json, lastBody, mockApi, page, renderAt, requestsTo, session } from './harness'

const VIEW = ['PURCHASE_VIEW']
const IMPORT = ['PURCHASE_VIEW', 'PURCHASE_IMPORT_XML', 'PURCHASE_ORDER_MANAGE']
const CANCEL = ['PURCHASE_VIEW', 'PURCHASE_CANCEL']
const as = (permissions: string[]) => ({ ...session, permissions })
vi.setConfig({ testTimeout: 20_000 })

const cost = (base: number, extra = {}) => ({
  baseAmount: base, ipiAmount: 0, icmsStAmount: 0, icmsNormalAmount: 0,
  freightShare: 0, insuranceShare: 0, otherShare: 0, totalAmount: base, quantity: 1, unitCost: base, ...extra,
})

const summary = (extra = {}) => ({
  id: 'p1', number: 1, supplierId: 's1', supplierName: 'UBERHIDRAULICA PECAS LTDA', origin: 'NFE_IMPORT',
  status: 'APPROVED', payableTotal: 1943.1, accessKey: '31261067757350000178550010000000211010202158',
  invoiceNumber: 21, issuedOn: '2026-10-09', createdAt: '2026-10-13T12:00:00Z', installmentCount: 3, receiptStatus: 'NONE', ...extra,
})

const detail = (extra = {}) => ({
  id: 'p1', number: 1, supplierId: 's1', origin: 'NFE_IMPORT', status: 'APPROVED', receiptStatus: 'NONE',
  goodsTotal: 1943.1, discountTotal: 0, freightTotal: 0, insuranceTotal: 0, otherTotal: 0, taxAddedTotal: 0,
  payableTotal: 1943.1, expenseCategoryId: 'cat1', workOrderId: null, notes: null,
  createdAt: '2026-10-13T12:00:00Z', cancelledAt: null, cancellationReason: null,
  items: [{
    id: 'i1', lineNo: 1, productId: 'prod1', supplierCode: '70893E19BR', description: 'OLEO LUB 0W20 SINTETICO 1L',
    unitOriginal: 'un', quantityOriginal: 4, unitConversionFactor: 1, quantity: 4, unitPrice: 55, unitCost: 55,
    discount: 0, lineTotal: 220, ncm: '27101932', cfop: '5405', gtin: null, cost: cost(220, { quantity: 4, unitCost: 55 }),
  }],
  documents: [{
    id: 'd1', kind: 'NFE', accessKey: '31261067757350000178550010000000211010202158', series: '1', number: 21,
    issuedAt: '2026-10-09T12:55:01Z', issuerDocument: '67757350000178', recipientDocument: '01637003000101',
    totalAmount: 1943.1, protocolNumber: '131267983727896', protocolStatus: '100',
    xmlSha256: 'abc123def456abc123def456abc123def456abc123def456abc123def456abcd',
    voidedAt: null, voidReason: null, officialCheckPerformed: false,
  }],
  installments: [
    { payableId: 'pay1', installmentNo: 1, dueDate: '2026-10-23', amount: 647.7, invoiceReference: '001' },
    { payableId: 'pay2', installmentNo: 2, dueDate: '2026-10-30', amount: 647.7, invoiceReference: '002' },
    { payableId: 'pay3', installmentNo: 3, dueDate: '2026-11-06', amount: 647.7, invoiceReference: '003' },
  ],
  ...extra,
})

const purchaseEvents = page([
  { id: 'e2', type: 'PAYABLES_REGISTERED', actorId: 'a1', occurredAt: '2026-10-13T12:00:01Z', reason: null, payload: null },
  { id: 'e1', type: 'IMPORTED', actorId: 'a1', occurredAt: '2026-10-13T12:00:00Z', reason: null, payload: null },
])

const detailRoutes = (value = detail()) => ({
  'GET /api/purchasing/purchases/p1': value,
  'GET /api/purchasing/purchases/p1/events': purchaseEvents,
  'GET /api/purchasing/purchases/p1/receiving': { purchaseId: 'p1', status: 'NONE', lines: [] },
  'GET /api/purchasing/purchases/p1/receipts': [],
})

// ====================================================================== lista

test('a lista mostra a compra com o total e o número da nota', async () => {
  mockApi({ 'GET /api/purchasing/purchases': page([summary()]) }, as(VIEW))
  renderAt('/compras/notas')

  const row = (await screen.findByRole('link', { name: '#1' })).closest('tr') as HTMLElement
  expect(within(row).getByText('UBERHIDRAULICA PECAS LTDA')).toBeInTheDocument()
  expect(within(row).getByText('NF-e importada')).toBeInTheDocument()
  expect(within(row).getByText('21')).toBeInTheDocument()
  expect(within(row).getByText('R$ 1.943,10')).toBeInTheDocument()
  expect(within(row).getByText('Aprovada')).toBeInTheDocument()
})

test('quem não pode importar não vê o botão de importar NF-e', async () => {
  mockApi({ 'GET /api/purchasing/purchases': page([]) }, as(VIEW))
  renderAt('/compras/notas')

  await screen.findByText('Nenhuma compra encontrada.')
  expect(screen.queryByRole('link', { name: 'Importar NF-e' })).not.toBeInTheDocument()
})

test('quem pode importar vê o botão e o menu de Compras', async () => {
  mockApi({ 'GET /api/purchasing/purchases': page([]) }, as(IMPORT))
  renderAt('/compras/notas')

  expect(await screen.findByRole('link', { name: 'Importar NF-e' }))
    .toHaveAttribute('href', '/compras/notas/importar')
  expect(screen.getByRole('link', { name: 'Compras e NF-e' })).toHaveAttribute('href', '/compras/notas')
})

// ====================================================================== detalhe

test('o detalhe mostra a composição do custo, as parcelas e que a consulta oficial não foi feita', async () => {
  mockApi(detailRoutes(), as(VIEW))
  renderAt('/compras/notas/p1')

  expect(await screen.findByRole('heading', { name: 'Compra #1' })).toBeInTheDocument()

  // D1: a composição aparece parcela por parcela, que é a condição de auditabilidade.
  expect(screen.getByText(/produto R\$ 220,00/)).toBeInTheDocument()

  // As três parcelas e a soma.
  const installments = screen.getByRole('region', { name: 'Parcelas' })
  expect(within(installments).getAllByText('R$ 647,70')).toHaveLength(3)
  expect(within(installments).getByText('R$ 1.943,10')).toBeInTheDocument()
  expect(within(installments).getAllByRole('link', { name: 'Abrir no Financeiro' })).toHaveLength(3)
  expect(within(installments).getByText(/nunca marca nada como pago/)).toBeInTheDocument()

  // D12: a tela não pode sugerir que a nota foi conferida na SEFAZ.
  expect(screen.getByText(/declarada no XML/)).toBeInTheDocument()
})

test('sem PURCHASE_CANCEL a compra não oferece cancelamento nem anulação', async () => {
  mockApi(detailRoutes(), as(VIEW))
  renderAt('/compras/notas/p1')

  await screen.findByRole('heading', { name: 'Compra #1' })
  expect(screen.queryByRole('button', { name: 'Cancelar compra' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Anular' })).not.toBeInTheDocument()
})

test('cancelar exige motivo e envia o motivo digitado', async () => {
  const fetchSpy = mockApi({
    ...detailRoutes(),
    'POST /api/purchasing/purchases/p1/cancel': detail({ status: 'CANCELLED', cancelledAt: '2026-10-14T12:00:00Z', cancellationReason: 'mercadoria nunca chegou' }),
  }, as(CANCEL))
  renderAt('/compras/notas/p1')

  await userEvent.click(await screen.findByRole('button', { name: 'Cancelar compra' }))
  await userEvent.click(screen.getByRole('button', { name: 'Confirmar cancelamento' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Informe o motivo do cancelamento')
  await userEvent.type(screen.getByLabelText(/Motivo do cancelamento/), 'mercadoria nunca chegou')
  await userEvent.click(screen.getByRole('button', { name: 'Confirmar cancelamento' }))

  await waitFor(() => expect(requestsTo(fetchSpy, 'POST', '/api/purchasing/purchases/p1/cancel')).toHaveLength(1))
  expect(lastBody(fetchSpy, 'POST', '/api/purchasing/purchases/p1/cancel')).toEqual({ reason: 'mercadoria nunca chegou' })
})

test('anular documento só aparece depois de a compra estar cancelada', async () => {
  mockApi(detailRoutes(detail({ status: 'CANCELLED', cancelledAt: '2026-10-14T12:00:00Z', cancellationReason: 'troca' })), as(CANCEL))
  renderAt('/compras/notas/p1')

  await screen.findByRole('heading', { name: 'Compra #1' })
  expect(screen.getByRole('button', { name: 'Anular' })).toBeInTheDocument()
  expect(screen.getByText(/Cancelada em/)).toBeInTheDocument()
})

// ====================================================================== configuração

test('a configuração avisa que os parâmetros de custo não foram validados pelo contador', async () => {
  mockApi({
    'GET /api/purchasing/settings': {
      ownTaxId: '01637003000101', ownTaxIdConfigured: true, costIncludeIpi: true, costIncludeIcmsSt: true,
      costIncludeIcmsNormal: false, freightAllocation: 'VALUE', costParametersValidatedByAccountant: false,
      descriptions: {
        OWN_TAX_ID: 'CNPJ completo do estabelecimento autorizado.',
        COST_INCLUDE_IPI: 'Somar IPI.', COST_INCLUDE_ICMS_ST: 'Somar ICMS-ST.',
        COST_INCLUDE_ICMS_NORMAL: 'Somar ICMS normal.', FREIGHT_ALLOCATION: 'Rateio.',
      },
    },
  }, as(IMPORT))
  renderAt('/compras/configuracoes')

  expect(await screen.findByRole('note')).toHaveTextContent('não foram validados pelo contador')
  expect(screen.getByText('01.637.003/0001-01')).toBeInTheDocument()
  expect(screen.getByLabelText('Somar IPI ao custo')).toBeChecked()
  expect(screen.getByLabelText('Somar ICMS normal ao custo')).not.toBeChecked()
})

test('a configuração recusa raiz de CNPJ, porque a D9 exige o CNPJ completo', async () => {
  mockApi({
    'GET /api/purchasing/settings': {
      ownTaxId: '', ownTaxIdConfigured: false, costIncludeIpi: true, costIncludeIcmsSt: true,
      costIncludeIcmsNormal: false, freightAllocation: 'VALUE', costParametersValidatedByAccountant: false,
      descriptions: { OWN_TAX_ID: 'CNPJ completo.', COST_INCLUDE_IPI: '', COST_INCLUDE_ICMS_ST: '', COST_INCLUDE_ICMS_NORMAL: '', FREIGHT_ALLOCATION: '' },
    },
    'PUT /api/purchasing/settings/OWN_TAX_ID': () => json({
      code: 'PURCHASE_SETTING_INVALID',
      message: 'OWN_TAX_ID deve ser o CNPJ completo e válido do estabelecimento, com 14 posições',
    }, 400),
  }, as(IMPORT))
  renderAt('/compras/configuracoes')

  expect(await screen.findByText('não configurado')).toBeInTheDocument()
  await userEvent.type(screen.getByLabelText('Novo CNPJ do estabelecimento'), '01637003')
  await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('CNPJ completo')
})
