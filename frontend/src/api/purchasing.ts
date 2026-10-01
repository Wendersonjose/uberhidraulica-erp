import { api } from './http'

export type Supplier = {
  id: string
  personType: 'PF' | 'PJ'
  legalName: string
  tradeName?: string | null
  document?: string | null
  phone?: string | null
  email?: string | null
  address?: {
    zipCode?: string | null; street?: string | null; number?: string | null; complement?: string | null
    district?: string | null; city?: string | null; state?: string | null
  } | null
  commercialTerms: {
    paymentTerms?: string | null
    preferredPaymentMethod?: string | null
    usualDueDay?: number | null
    creditLimit?: number | null
    notes?: string | null
  }
  status: 'ACTIVE' | 'INACTIVE'
  createdAt: string
  updatedAt: string
}

export type SupplierPayload = Omit<Supplier, 'id' | 'status' | 'createdAt' | 'updatedAt'>
export type SupplierPage = { items: Supplier[]; totalItems: number; page: number; size: number; totalPages: number }
export type SupplierSearch = { q?: string; personType?: string; status?: string; page?: number; size?: number }

function queryString(params: SupplierSearch) {
  const q = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') q.set(key, String(value))
  })
  const suffix = q.toString()
  return suffix ? `?${suffix}` : ''
}

export const suppliersApi = {
  search: (params: SupplierSearch) => api<SupplierPage>(`/api/purchasing/suppliers/search${queryString(params)}`),
  get: (id: string) => api<Supplier>(`/api/purchasing/suppliers/${id}`),
  create: (body: SupplierPayload) => api<Supplier>('/api/purchasing/suppliers', { method: 'POST', body: JSON.stringify(body) }),
  update: (id: string, body: SupplierPayload) => api<Supplier>(`/api/purchasing/suppliers/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  inactivate: (id: string) => api<Supplier>(`/api/purchasing/suppliers/${id}/inactivate`, { method: 'POST', body: '{}' }),
  reactivate: (id: string) => api<Supplier>(`/api/purchasing/suppliers/${id}/reactivate`, { method: 'POST', body: '{}' }),
}
