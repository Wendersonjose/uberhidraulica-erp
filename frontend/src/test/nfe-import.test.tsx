import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'
import { json, mockApi, renderAt, requestsTo, session } from './harness'
import { suggestCatalogUnit } from '../features/purchasing/nfeUnits'

const IMPORT = ['PURCHASE_VIEW', 'PURCHASE_IMPORT_XML', 'FINANCE_VIEW']
const WITH_CATALOG = [...IMPORT, 'PRODUCT_MANAGE']
const as = (permissions: string[]) => ({ ...session, permissions })
vi.setConfig({ testTimeout: 30_000 })

/** XML SINTÉTICO: o conteúdo só precisa existir; quem interpreta a nota é o servidor, simulado abaixo. */
const XML = '<nfeProc><NFe><infNFe Id="NFe31261067757350000178550010000000211010202158"/></NFe></nfeProc>'
const KEY = '31261067757350000178550010000000211010202158'

const cost = (unitCost: number, quantity = 4) => ({
  baseAmount: unitCost * quantity, ipiAmount: 0, icmsStAmount: 0, icmsNormalAmount: 0, freightShare: 0, insuranceShare: 0,
  otherShare: 0, totalAmount: unitCost * quantity, quantity, unitCost,
})

const item = (lineNo: number, extra = {}) => ({
  lineNo, supplierCode: `COD${lineNo}`, gtin: null, description: `Item ${lineNo} da nota`, ncm: '84133090', cfop: '5102',
  unitOriginal: 'UN', quantityOriginal: 4, unitPrice: 55, productId: null, productDescription: null, productUnit: null,
  matchedBy: 'NONE', candidates: [], needsFactor: true, unitConversionFactor: null, convertedQuantity: null, cost: null, ...extra,
})

const linkedItem = (lineNo: number, extra = {}) => item(lineNo, {
  productId: 'prod-link', productDescription: 'Óleo ATF vinculado', productUnit: 'UNIDADE', matchedBy: 'LINK',
  needsFactor: false, unitConversionFactor: 1, convertedQuantity: 4, cost: cost(55), ...extra,
})

const preview = (extra = {}) => ({
  accessKey: KEY, issuerDocument: '67757350000178', issuerName: 'UBERHIDRAULICA PECAS LTDA', recipientDocument: '01637003000101',
  invoiceNumber: 21, invoiceSeries: '1', issuedOn: '2026-10-09', recipientCheck: 'AUTHORIZED', issues: [],
  supplier: { supplierId: 's1', document: '67757350000178', legalName: 'UBERHIDRAULICA PECAS LTDA', tradeName: null, inactive: false, relatedParty: true },
  items: [linkedItem(1)],
  totals: { goods: 220, discount: 0, freight: 0, insurance: 0, other: 0, taxAdded: 0, invoice: 220, itemsSum: 220, closes: true },
  installments: [
    { number: 1, dueDate: '2026-10-23', amount: 110, reference: '001' },
    { number: 2, dueDate: '2026-10-30', amount: 110, reference: '002' },
  ],
  installmentsFromInvoice: true, alreadyImportedPurchaseId: null, xmlSha256: 'a'.repeat(64), blockers: [], confirmable: true,
  officialCheckPerformed: false, ...extra,
})

const categories = [{ id: 'cat1', name: 'Peças e insumos', active: true }]
const catalog = [
  { id: 'prod-link', description: 'Óleo ATF vinculado', internalCode: null, category: null, type: 'PART', unit: 'UNIDADE', referenceCost: null, salePrice: null, minimumStock: null, active: true },
  { id: 'prod-filtro', description: 'Filtro hidráulico HF-100', internalCode: 'HF100', category: null, type: 'PART', unit: 'UNIDADE', referenceCost: null, salePrice: null, minimumStock: null, active: true },
  { id: 'prod-inativo', description: 'Filtro antigo', internalCode: null, category: null, type: 'PART', unit: 'UNIDADE', referenceCost: null, salePrice: null, minimumStock: null, active: false },
]

const headerOf = (init: RequestInit | undefined, name: string) => new Headers(init?.headers).get(name)
const isRefined = (init: RequestInit | undefined) => String(init?.body ?? '').startsWith('{')

/** Rotas comuns; `first` responde à leitura do arquivo e `refined` à reprévia com as escolhas do operador. */
function routes(first: unknown, refined: (decisions: { lineNo: number; productId: string; unitConversionFactor?: number }[]) => unknown = () => first, extra = {}) {
  return {
    'GET /api/finance/expense-categories': categories,
    'GET /api/products': catalog,
    'POST /api/purchasing/nfe/preview': (init: RequestInit | undefined) =>
      json(isRefined(init) ? refined(JSON.parse(String(init?.body)).decisions) : first),
    ...extra,
  }
}

const file = () => new File([XML], 'nota-21.xml', { type: 'text/xml' })

async function upload() {
  await userEvent.upload(await screen.findByLabelText('Arquivo XML da NF-e'), file())
}

test('sem PURCHASE_IMPORT_XML a rota nega o acesso, não oferece leitura de arquivo e não chama a API de prévia', async () => {
  const fetch = mockApi({}, as(['PURCHASE_VIEW']))
  renderAt('/compras/notas/importar')
  expect(await screen.findByRole('alert')).toHaveTextContent('Acesso negado')
  expect(screen.queryByLabelText('Arquivo XML da NF-e')).not.toBeInTheDocument()
  expect(requestsTo(fetch, 'POST', '/api/purchasing/nfe/preview')).toHaveLength(0)
})

test('a prévia mostra a nota, o fornecedor, o destinatário autorizado e que a consulta oficial não foi feita', async () => {
  const fetch = mockApi(routes(preview()), as(IMPORT))
  renderAt('/compras/notas/importar')
  await upload()

  expect(await screen.findByText('Destinatário confere com o estabelecimento autorizado')).toBeInTheDocument()
  const note = screen.getByRole('region', { name: 'Nota' })
  expect(within(note).getByText(/67\.757\.350\/0001-78/)).toBeInTheDocument()
  expect(within(note).getByText('01.637.003/0001-01')).toBeInTheDocument()
  expect(within(note).getByText(KEY)).toBeInTheDocument()
  expect(within(note).getByText(/declaradas no XML/)).toBeInTheDocument()
  expect(screen.getByRole('region', { name: 'Fornecedor' })).toHaveTextContent('Empresa relacionada')

  const [, init] = requestsTo(fetch, 'POST', '/api/purchasing/nfe/preview')[0]
  expect(headerOf(init, 'Content-Type')).toBe('application/xml')
  expect(String(init?.body)).toBe(XML)
  // Ler a nota não grava nada.
  expect(requestsTo(fetch, 'POST', '/api/purchasing/nfe/confirm')).toHaveLength(0)
})

test('nota destinada a outro CNPJ bloqueia a confirmação mesmo com tudo preenchido (D9)', async () => {
  mockApi(routes(preview({ recipientCheck: 'RECIPIENT_MISMATCH', recipientDocument: '01637003000290',
    blockers: ['Nota destinada a outro CNPJ'] })), as(IMPORT))
  renderAt('/compras/notas/importar')
  await upload()

  expect(await screen.findByText('Nota destinada a outro CNPJ', { selector: 'p' })).toBeInTheDocument()
  await userEvent.selectOptions(await screen.findByLabelText('Categoria de despesa'), 'cat1')
  expect(screen.getByRole('button', { name: 'Confirmar importação' })).toBeDisabled()
})

test('nota já importada oferece abrir a compra existente e não deixa confirmar', async () => {
  mockApi(routes(preview({ alreadyImportedPurchaseId: 'p9', blockers: ['Esta NF-e já foi importada e a compra está ativa'] })), as(IMPORT))
  renderAt('/compras/notas/importar')
  await upload()

  const link = await screen.findByRole('link', { name: 'Abrir a compra existente' })
  expect(link).toHaveAttribute('href', '/compras/notas/p9')
  await userEvent.selectOptions(await screen.findByLabelText('Categoria de despesa'), 'cat1')
  expect(screen.getByRole('button', { name: 'Confirmar importação' })).toBeDisabled()
})

test('confirmar envia as decisões e a Idempotency-Key, e o retry depois de falha de rede reaproveita a mesma chave', async () => {
  let attempts = 0
  const fetch = mockApi(routes(preview(), undefined, {
    'POST /api/purchasing/nfe/confirm': () => (++attempts === 1
      ? json({ code: 'NETWORK', message: 'Tempo esgotado' }, 503)
      : json({ purchaseId: 'p1', number: 1, supplierId: 's1', status: 'APPROVED', payableTotal: 220, itemCount: 1, installments: [], replayed: false }, 200)),
  }), as(IMPORT))
  renderAt('/compras/notas/importar')
  await upload()

  await userEvent.selectOptions(await screen.findByLabelText('Categoria de despesa'), 'cat1')
  const confirm = screen.getByRole('button', { name: 'Confirmar importação' })
  await waitFor(() => expect(confirm).toBeEnabled())
  await userEvent.click(confirm)
  expect(await screen.findByText('Tempo esgotado')).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Confirmar importação' }))

  await waitFor(() => expect(requestsTo(fetch, 'POST', '/api/purchasing/nfe/confirm')).toHaveLength(2))
  const [first, second] = requestsTo(fetch, 'POST', '/api/purchasing/nfe/confirm')
  expect(headerOf(first[1], 'Idempotency-Key')).toBeTruthy()
  expect(headerOf(second[1], 'Idempotency-Key')).toBe(headerOf(first[1], 'Idempotency-Key'))
  const body = JSON.parse(String(second[1]?.body))
  expect(body).toMatchObject({ xml: XML, createSupplier: true, expenseCategoryId: 'cat1' })
  expect(body.decisions).toEqual([{ lineNo: 1, productId: 'prod-link', unitConversionFactor: 1, saveLink: true }])
  // Nota com duplicatas não manda parcelas: elas vêm do documento.
  expect(body.installments).toBeUndefined()
})

test('item sem vínculo: escolher o produto reconsulta a prévia e só então mostra o custo e libera a confirmação', async () => {
  const unlinked = preview({ items: [item(1, { candidates: [{ productId: 'prod-filtro', description: 'Filtro hidráulico HF-100', internalCode: 'HF100', unit: 'UNIDADE' }] })],
    blockers: ['Item 1 (Item 1 da nota) não está vinculado a um produto do catálogo'] })
  const fetch = mockApi(routes(unlinked, decisions => preview({
    items: [item(1, { productId: decisions[0].productId, productDescription: 'Filtro hidráulico HF-100', productUnit: 'UNIDADE', matchedBy: 'MANUAL',
      needsFactor: false, unitConversionFactor: 1, convertedQuantity: 4, cost: cost(55) })],
  })), as(IMPORT))
  renderAt('/compras/notas/importar')
  await upload()

  await userEvent.selectOptions(await screen.findByLabelText('Categoria de despesa'), 'cat1')
  expect(await screen.findByText(/não está vinculado a um produto/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Confirmar importação' })).toBeDisabled()

  await userEvent.selectOptions(screen.getByLabelText('Produto do item 1'), 'prod-filtro')
  await waitFor(() => expect(screen.getByRole('button', { name: 'Confirmar importação' })).toBeEnabled(), { timeout: 8000 })

  const refined = requestsTo(fetch, 'POST', '/api/purchasing/nfe/preview').filter(([, init]) => isRefined(init))
  expect(refined).toHaveLength(1)
  expect(JSON.parse(String(refined[0][1]?.body))).toEqual({ xml: XML, decisions: [{ lineNo: 1, productId: 'prod-filtro' }] })
  // O custo que entraria no estoque aparece na linha escolhida à mão.
  expect(within(screen.getAllByRole('row')[1]).getByText(/55,00/)).toBeInTheDocument()
})

test('unidade que exige fator (KIT) trava a confirmação até o fator ser informado, e a prévia recalcula o custo', async () => {
  const kit = preview({ items: [linkedItem(1, { unitOriginal: 'KIT', needsFactor: true, unitConversionFactor: null, convertedQuantity: null, cost: null })],
    blockers: ['Item 1: unidade KIT da nota exige fator de conversão para UNIDADE'] })
  const fetch = mockApi(routes(kit, decisions => preview({
    items: [linkedItem(1, { unitOriginal: 'KIT', needsFactor: false, unitConversionFactor: decisions[0].unitConversionFactor, convertedQuantity: 48, cost: cost(4.5833, 48) })],
  })), as(IMPORT))
  renderAt('/compras/notas/importar')
  await upload()

  await userEvent.selectOptions(await screen.findByLabelText('Categoria de despesa'), 'cat1')
  expect(await screen.findByText('KIT não equivale a UNIDADE')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Confirmar importação' })).toBeDisabled()

  await userEvent.type(screen.getByLabelText('Fator de conversão do item 1'), '12')
  await waitFor(() => expect(screen.getByRole('button', { name: 'Confirmar importação' })).toBeEnabled(), { timeout: 8000 })

  const refined = requestsTo(fetch, 'POST', '/api/purchasing/nfe/preview').filter(([, init]) => isRefined(init))
  expect(JSON.parse(String(refined.at(-1)?.[1]?.body)).decisions).toEqual([{ lineNo: 1, productId: 'prod-link', unitConversionFactor: 12 }])
  expect(screen.queryByText('KIT não equivale a UNIDADE')).not.toBeInTheDocument()
})

test('a busca no catálogo encontra produto fora das sugestões e ignora produtos inativos', async () => {
  mockApi(routes(preview({ items: [item(1)], blockers: ['Item 1 (Item 1 da nota) não está vinculado a um produto do catálogo'] })), as(IMPORT))
  renderAt('/compras/notas/importar')
  await upload()

  const select = await screen.findByLabelText('Produto do item 1')
  expect(within(select).queryByRole('option', { name: /Filtro hidráulico/ })).not.toBeInTheDocument()
  await userEvent.type(await screen.findByLabelText('Buscar produto do item 1'), 'filtro')
  expect(await within(select).findByRole('option', { name: /Filtro hidráulico HF-100 · HF100/ })).toBeInTheDocument()
  expect(within(select).queryByRole('option', { name: /Filtro antigo/ })).not.toBeInTheDocument()
})

test('cadastro assistido cria o produto com a descrição da nota e sem preço, e o seleciona no item', async () => {
  const unlinked = preview({ items: [item(1, { unitOriginal: 'L', description: 'OLEO HIDRAULICO 68 BALDE' })],
    blockers: ['Item 1 (OLEO HIDRAULICO 68 BALDE) não está vinculado a um produto do catálogo'] })
  const fetch = mockApi(routes(unlinked, decisions => preview({
    items: [item(1, { productId: decisions[0].productId, productDescription: 'OLEO HIDRAULICO 68 BALDE', productUnit: 'LITRO', matchedBy: 'MANUAL',
      needsFactor: false, unitConversionFactor: 1, convertedQuantity: 4, cost: cost(55) })],
  }), {
    'POST /api/products': (init: RequestInit | undefined) => json({ id: 'prod-novo', ...JSON.parse(String(init?.body)), active: true,
      createdAt: '2026-10-14T12:00:00Z', updatedAt: '2026-10-14T12:00:00Z' }, 201),
  }), as(WITH_CATALOG))
  renderAt('/compras/notas/importar')
  await upload()

  await userEvent.click(await screen.findByRole('button', { name: 'Cadastrar produto' }))
  const form = await screen.findByRole('group', { name: 'Cadastrar produto do item 1' })
  expect(within(form).getByLabelText('Descrição do produto *')).toHaveValue('OLEO HIDRAULICO 68 BALDE')
  // A unidade da nota (L) tem equivalente direto no catálogo (litro) e vem sugerida.
  expect(within(form).getByLabelText('Unidade no catálogo *')).toHaveValue('LITRO')
  await userEvent.click(within(form).getByRole('button', { name: 'Cadastrar produto' }))

  await waitFor(() => expect(requestsTo(fetch, 'POST', '/api/products')).toHaveLength(1))
  const body = JSON.parse(String(requestsTo(fetch, 'POST', '/api/products')[0][1]?.body))
  expect(body).toMatchObject({ description: 'OLEO HIDRAULICO 68 BALDE', type: 'PART', unit: 'LITRO', internalCode: null,
    referenceCost: null, salePrice: null, minimumStock: null })
  expect(await screen.findByRole('option', { name: /OLEO HIDRAULICO 68 BALDE/ }, { timeout: 8000 })).toBeInTheDocument()
  await waitFor(() => {
    const refined = requestsTo(fetch, 'POST', '/api/purchasing/nfe/preview').filter(([, init]) => isRefined(init))
    expect(refined.length).toBeGreaterThan(0)
    expect(JSON.parse(String(refined.at(-1)?.[1]?.body)).decisions[0].productId).toBe('prod-novo')
  }, { timeout: 8000 })
})

test('sem PRODUCT_MANAGE o cadastro assistido não é oferecido', async () => {
  mockApi(routes(preview({ items: [item(1)], blockers: ['Item 1 não está vinculado'] })), as(IMPORT))
  renderAt('/compras/notas/importar')
  await upload()
  expect(await screen.findByLabelText('Produto do item 1')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Cadastrar produto' })).not.toBeInTheDocument()
})

test('nota sem duplicata exige o primeiro vencimento e envia as parcelas informadas (D11)', async () => {
  const fetch = mockApi(routes(preview({ installments: [], installmentsFromInvoice: false,
    blockers: ['Nota sem duplicata: informe vencimento e condições de pagamento na confirmação'] }), undefined, {
    'POST /api/purchasing/nfe/confirm': () => json({ purchaseId: 'p1', number: 1, supplierId: 's1', status: 'APPROVED', payableTotal: 220, itemCount: 1, installments: [], replayed: false }, 200),
  }), as(IMPORT))
  renderAt('/compras/notas/importar')
  await upload()

  await userEvent.selectOptions(await screen.findByLabelText('Categoria de despesa'), 'cat1')
  // A dica de duplicata ausente não aparece como erro vermelho: o formulário de parcelas está logo abaixo.
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Confirmar importação' })).toBeDisabled()

  const first = screen.getByLabelText('Primeiro vencimento')
  await userEvent.type(first, '2026-11-10')
  const parcels = screen.getByRole('spinbutton', { name: 'Parcelas' })
  await userEvent.clear(parcels)
  await userEvent.type(parcels, '2')
  await waitFor(() => expect(screen.getByRole('button', { name: 'Confirmar importação' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: 'Confirmar importação' }))

  await waitFor(() => expect(requestsTo(fetch, 'POST', '/api/purchasing/nfe/confirm')).toHaveLength(1))
  const body = JSON.parse(String(requestsTo(fetch, 'POST', '/api/purchasing/nfe/confirm')[0][1]?.body))
  expect(body.installments).toEqual({ count: 2, firstDueDate: '2026-11-10', intervalDays: 30 })
})

test('a sugestão de unidade do cadastro assistido espelha as equivalências diretas da D7', () => {
  expect(suggestCatalogUnit('UN')).toBe('UNIDADE')
  expect(suggestCatalogUnit('pç.')).toBe('UNIDADE')
  expect(suggestCatalogUnit('Lt')).toBe('LITRO')
  expect(suggestCatalogUnit('MT')).toBe('METRO')
  expect(suggestCatalogUnit('KG')).toBe('QUILOGRAMA')
  // Sem equivalência (caixa, kit, par), sugere unidade e deixa o fator cobrir a diferença.
  expect(suggestCatalogUnit('CX')).toBe('UNIDADE')
  expect(suggestCatalogUnit('KIT')).toBe('UNIDADE')
  expect(suggestCatalogUnit(null)).toBe('UNIDADE')
})
