import { api } from './http'
import type { Page } from './types'

export const PURCHASE_VIEW = 'PURCHASE_VIEW'
export const PURCHASE_SUPPLIER_MANAGE = 'PURCHASE_SUPPLIER_MANAGE'

export type PersonType = 'PF' | 'PJ'
export type SupplierStatus = 'ACTIVE' | 'INACTIVE'

export type SupplierAddress = {
  zipCode: string | null; street: string | null; number: string | null; complement: string | null
  district: string | null; city: string | null; state: string | null
}
export type SupplierTerms = {
  paymentTerms: string | null; preferredPaymentMethod: string | null; usualDueDay: number | null
  creditLimit: number | null; notes: string | null
}
export type Supplier = {
  id: string; personType: PersonType; legalName: string; tradeName: string | null
  /** CPF de pessoa física chega mascarado (`*********25`) para quem não pode gerenciar fornecedores. */
  document: string | null; documentMasked: boolean; stateRegistration: string | null; phone: string | null; email: string | null
  address: SupplierAddress; commercialTerms: SupplierTerms; relatedParty: boolean; relatedPartyNote: string | null
  status: SupplierStatus; inactivatedAt: string | null; inactivationReason: string | null
  /** Controle otimista: a alteração só vale sobre esta versão. */
  version: number; createdAt: string; updatedAt: string
}
export type SupplierInput = Omit<Supplier, 'id' | 'documentMasked' | 'status' | 'inactivatedAt' | 'inactivationReason' | 'createdAt' | 'updatedAt' | 'version'>
  & { version?: number }
export type SupplierContact = {
  id: string; supplierId: string; name: string; role: string | null; phone: string | null; email: string | null
  primary: boolean; active: boolean; createdAt: string; updatedAt: string
}
export type ContactInput = { name: string; role: string | null; phone: string | null; email: string | null; primary: boolean }
export type SupplierEventType = 'CREATED' | 'UPDATED' | 'INACTIVATED' | 'REACTIVATED' | 'CONTACT_ADDED' | 'CONTACT_UPDATED' | 'CONTACT_REMOVED'
export type SupplierEvent = {
  id: string; type: SupplierEventType; actorId: string; occurredAt: string; reason: string | null
  changes: Record<string, { before: string | null; after: string | null }>
}
export type SupplierSearch = { q?: string; personType?: PersonType; status?: SupplierStatus; page?: number; size?: number }

export const purchasingKeys = {
  suppliers: ['purchasing', 'suppliers'] as const,
  supplierList: (params: object) => ['purchasing', 'suppliers', 'list', params] as const,
  supplier: (id: string) => ['purchasing', 'suppliers', id] as const,
  contacts: (id: string) => ['purchasing', 'suppliers', id, 'contacts'] as const,
  events: (id: string, page: number) => ['purchasing', 'suppliers', id, 'events', page] as const,
}

const BASE = '/api/purchasing/suppliers'

function query(params: object) {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => { if (value !== undefined && value !== null && value !== '') search.set(key, String(value)) })
  const text = search.toString()
  return text ? `?${text}` : ''
}

const json = (method: string, body?: unknown): RequestInit => ({ method, body: body === undefined ? undefined : JSON.stringify(body) })

export const purchasingApi = {
  suppliers: (params: SupplierSearch) => api<Page<Supplier>>(`${BASE}${query(params)}`),
  supplier: (id: string) => api<Supplier>(`${BASE}/${id}`),
  createSupplier: (body: SupplierInput) => api<Supplier>(BASE, json('POST', body)),
  updateSupplier: (id: string, body: SupplierInput) => api<Supplier>(`${BASE}/${id}`, json('PUT', body)),
  inactivate: (id: string, reason: string) => api<Supplier>(`${BASE}/${id}/inactivate`, json('POST', { reason })),
  reactivate: (id: string) => api<Supplier>(`${BASE}/${id}/reactivate`, json('POST', {})),
  contacts: (id: string) => api<SupplierContact[]>(`${BASE}/${id}/contacts`),
  addContact: (id: string, body: ContactInput) => api<SupplierContact>(`${BASE}/${id}/contacts`, json('POST', body)),
  updateContact: (id: string, contactId: string, body: ContactInput) => api<SupplierContact>(`${BASE}/${id}/contacts/${contactId}`, json('PUT', body)),
  removeContact: (id: string, contactId: string) => api<void>(`${BASE}/${id}/contacts/${contactId}`, json('DELETE')),
  events: (id: string, page: number) => api<Page<SupplierEvent>>(`${BASE}/${id}/events${query({ page })}`),
}

// ---------------------------------------------------------------- I3: importação de NF-e e compras

export const PURCHASE_IMPORT_XML = 'PURCHASE_IMPORT_XML'
/** Cadastro assistido de produto a partir da linha da nota exige a permissão do catálogo. */
export const PRODUCT_MANAGE = 'PRODUCT_MANAGE'
export const PURCHASE_ORDER_MANAGE = 'PURCHASE_ORDER_MANAGE'
export const PURCHASE_CANCEL = 'PURCHASE_CANCEL'

export type RecipientCheck = 'AUTHORIZED' | 'OWN_TAX_ID_NOT_CONFIGURED' | 'RECIPIENT_MISMATCH' | 'RECIPIENT_MISSING'
/** `MANUAL` é o produto escolhido pelo operador na prévia; descrição nunca casa sozinha (D6). */
export type MatchKind = 'LINK' | 'GTIN' | 'MANUAL' | 'NONE'
export type PurchaseStatus = 'DRAFT' | 'AWAITING_APPROVAL' | 'APPROVED' | 'CANCELLED'
export type PurchaseOrigin = 'NFE_IMPORT' | 'DIRECT' | 'ORDER'

export type NfeIssue = { code: string; severity: 'BLOCKING' | 'WARNING' | 'INFO'; message: string; itemNumber: number | null }
export type AcquisitionCost = {
  baseAmount: number; ipiAmount: number; icmsStAmount: number; icmsNormalAmount: number
  freightShare: number; insuranceShare: number; otherShare: number; totalAmount: number
  quantity: number; unitCost: number
}
export type PreviewCandidate = { productId: string; description: string; internalCode: string | null; unit: string }
export type PreviewItem = {
  lineNo: number; supplierCode: string | null; gtin: string | null; description: string; ncm: string | null
  cfop: string | null; unitOriginal: string | null; quantityOriginal: number; unitPrice: number
  productId: string | null; productDescription: string | null; productUnit: string | null
  matchedBy: MatchKind; candidates: PreviewCandidate[]
  /** Unidade da nota não equivale à do catálogo: a D7 exige fator informado antes de confirmar. */
  needsFactor: boolean; unitConversionFactor: number | null; convertedQuantity: number | null
  cost: AcquisitionCost | null
}
export type PreviewTotals = {
  goods: number; discount: number; freight: number; insurance: number; other: number
  taxAdded: number; invoice: number; itemsSum: number; closes: boolean
}
export type PreviewInstallment = { number: number; dueDate: string; amount: number; reference: string | null }
export type NfePreviewSupplier = {
  supplierId: string | null; document: string | null; legalName: string | null; tradeName: string | null
  inactive: boolean; relatedParty: boolean
}
export type NfePreview = {
  accessKey: string | null; issuerDocument: string | null; issuerName: string | null; recipientDocument: string | null
  invoiceNumber: number | null; invoiceSeries: string | null; issuedOn: string | null
  recipientCheck: RecipientCheck; issues: NfeIssue[]
  supplier: NfePreviewSupplier
  items: PreviewItem[]; totals: PreviewTotals; installments: PreviewInstallment[]; installmentsFromInvoice: boolean
  alreadyImportedPurchaseId: string | null; xmlSha256: string | null
  blockers: string[]; confirmable: boolean
  /** Sempre falso nesta Task: a autorização é a declarada no XML, sem consulta à SEFAZ (D12). */
  officialCheckPerformed: boolean
}
export type PreviewSelection = { lineNo: number; productId: string; unitConversionFactor?: number }
export type ItemDecision = { lineNo: number; productId: string; unitConversionFactor?: number; saveLink?: boolean }
export type ConfirmInput = {
  xml: string; createSupplier?: boolean; expenseCategoryId: string; decisions: ItemDecision[]
  installments?: { count: number; firstDueDate: string; intervalDays: number }
  workOrderId?: string | null; notes?: string | null
}
export type PurchaseInstallment = {
  payableId: string; installmentNo: number; dueDate: string; amount: number; invoiceReference: string | null
}
export type ConfirmResult = {
  purchaseId: string; number: number; supplierId: string; status: PurchaseStatus; payableTotal: number
  itemCount: number; installments: PurchaseInstallment[]; replayed: boolean
}
export type PurchaseSummary = {
  id: string; number: number; supplierId: string; supplierName: string; origin: PurchaseOrigin
  status: PurchaseStatus; payableTotal: number; accessKey: string | null; invoiceNumber: number | null
  issuedOn: string | null; createdAt: string; installmentCount: number
}
export type PurchaseItem = {
  id: string; lineNo: number; productId: string | null; supplierCode: string | null; description: string
  unitOriginal: string | null; quantityOriginal: number | null; unitConversionFactor: number
  quantity: number; unitPrice: number; unitCost: number; discount: number; lineTotal: number
  ncm: string | null; cfop: string | null; gtin: string | null; cost: AcquisitionCost | null
}
export type PurchaseDocument = {
  id: string; kind: string; accessKey: string | null; series: string | null; number: number | null
  issuedAt: string | null; issuerDocument: string | null; recipientDocument: string | null
  totalAmount: number | null; protocolNumber: string | null; protocolStatus: string | null
  xmlSha256: string | null; voidedAt: string | null; voidReason: string | null; officialCheckPerformed: boolean
}
export type PurchaseDetail = {
  id: string; number: number; supplierId: string; origin: PurchaseOrigin; status: PurchaseStatus
  goodsTotal: number; discountTotal: number; freightTotal: number; insuranceTotal: number; otherTotal: number
  taxAddedTotal: number; payableTotal: number; expenseCategoryId: string | null; workOrderId: string | null
  notes: string | null; createdAt: string; cancelledAt: string | null; cancellationReason: string | null
  items: PurchaseItem[]; documents: PurchaseDocument[]; installments: PurchaseInstallment[]
}
export type PurchaseEvent = {
  id: string; type: string; actorId: string | null; occurredAt: string; reason: string | null; payload: string | null
}
export type PurchaseSearch = {
  q?: string; supplierId?: string; status?: PurchaseStatus; origin?: PurchaseOrigin
  issuedFrom?: string; issuedTo?: string; page?: number; size?: number
}
export type PurchasingSettings = {
  ownTaxId: string; ownTaxIdConfigured: boolean; costIncludeIpi: boolean; costIncludeIcmsSt: boolean
  costIncludeIcmsNormal: boolean; freightAllocation: 'VALUE' | 'QUANTITY'
  /** Condição que o proprietário anexou à D1: os parâmetros seguem pendentes de validação tributária. */
  costParametersValidatedByAccountant: boolean
  descriptions: Record<string, string>
}
export type SupplierProductLink = {
  id: string; supplierId: string; supplierCode: string; gtin: string | null; productId: string
  unitOriginal: string | null; unitConversionFactor: number; confirmedAt: string
}

export const purchaseKeys = {
  purchases: ['purchasing', 'purchases'] as const,
  purchaseList: (params: object) => ['purchasing', 'purchases', 'list', params] as const,
  purchase: (id: string) => ['purchasing', 'purchases', id] as const,
  purchaseEvents: (id: string, page: number) => ['purchasing', 'purchases', id, 'events', page] as const,
  settings: ['purchasing', 'settings'] as const,
  links: (supplierId: string) => ['purchasing', 'links', supplierId] as const,
}

const PURCHASING = '/api/purchasing'

export const purchaseApi = {
  /** Prévia somente leitura: nada é gravado, então pode ser chamada quantas vezes for preciso. */
  preview: (xml: string) => api<NfePreview>(`${PURCHASING}/nfe/preview`,
    { method: 'POST', body: xml, headers: { 'Content-Type': 'application/xml' } }),
  /**
   * Prévia que considera o produto e o fator já escolhidos: mostra o custo que entra no estoque e quais fatores
   * ainda faltam. Também é somente leitura.
   */
  previewWith: (xml: string, decisions: PreviewSelection[]) =>
    api<NfePreview>(`${PURCHASING}/nfe/preview`, json('POST', { xml, decisions })),
  /**
   * Confirma a importação. A `Idempotency-Key` é obrigatória no backend: sem ela um retry de rede
   * duplicaria a compra e as contas a pagar.
   */
  confirm: (body: ConfirmInput, idempotencyKey: string) => api<ConfirmResult>(`${PURCHASING}/nfe/confirm`,
    { method: 'POST', body: JSON.stringify(body), headers: { 'Idempotency-Key': idempotencyKey } }),
  purchases: (params: PurchaseSearch) => api<Page<PurchaseSummary>>(`${PURCHASING}/purchases${query(params)}`),
  purchase: (id: string) => api<PurchaseDetail>(`${PURCHASING}/purchases/${id}`),
  purchaseEvents: (id: string, page: number) =>
    api<Page<PurchaseEvent>>(`${PURCHASING}/purchases/${id}/events${query({ page })}`),
  cancelPurchase: (id: string, reason: string) =>
    api<PurchaseDetail>(`${PURCHASING}/purchases/${id}/cancel`, json('POST', { reason })),
  voidDocument: (id: string, reason: string) =>
    api<PurchaseDocument>(`${PURCHASING}/documents/${id}/void`, json('POST', { reason })),
  settings: () => api<PurchasingSettings>(`${PURCHASING}/settings`),
  changeSetting: (key: string, value: string) =>
    api<PurchasingSettings>(`${PURCHASING}/settings/${key}`, json('PUT', { value })),
  links: (supplierId: string) => api<SupplierProductLink[]>(`${PURCHASING}/suppliers/${supplierId}/product-links`),
  removeLink: (supplierId: string, supplierCode: string) =>
    api<void>(`${PURCHASING}/suppliers/${supplierId}/product-links/${encodeURIComponent(supplierCode)}`, json('DELETE')),
}
