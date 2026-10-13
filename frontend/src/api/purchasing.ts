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
