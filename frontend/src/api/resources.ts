import{api,post,publicApi}from'./http';import type{Customer,Vehicle,Service,WorkOrder,Product,Quote,PublicQuote,PublicQuoteAccess,IssuedQuoteAccess,Page,VehicleListItem,VehicleOwnership}from'./types';export const servicesApi={list:()=>api<Service[]>('/api/services'),create:(body:unknown)=>post<Service>('/api/services',body)};export const workOrdersApi={list:(filter:{customerId?:string;vehicleId?:string}={})=>api<WorkOrder[]>('/api/work-orders'+queryString(filter)),get:(id:string)=>api<WorkOrder>(`/api/work-orders/${id}`),create:(body:unknown)=>post<WorkOrder>('/api/work-orders',body),addService:(id:string,serviceId:string)=>post<WorkOrder>(`/api/work-orders/${id}/services`,{serviceId}),addProduct:(id:string,productId:string,quantity:number)=>post<WorkOrder>(`/api/work-orders/${id}/products`,{productId,quantity})}

export const productsApi = {
  list: () => api<Product[]>('/api/products'),
  get: (id: string) => api<Product>(`/api/products/${id}`),
  create: (body: unknown) => post<Product>('/api/products', body),
  update: (id: string, body: unknown) => api<Product>(`/api/products/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
}

export type QuoteItemInput = {
  quoteItemId?: string | null; workOrderServiceId?: string | null
  description: string; quantity: number; unitPrice: number
}
const quotesBase = (workOrderId: string) => `/api/work-orders/${workOrderId}/quotes`
export const quotesApi = {
  list: (workOrderId: string) => api<Quote[]>(quotesBase(workOrderId)),
  get: (workOrderId: string, quoteId: string) => api<Quote>(`${quotesBase(workOrderId)}/${quoteId}`),
  open: (workOrderId: string) => post<Quote>(quotesBase(workOrderId), {}),
  createRevision: (workOrderId: string, quoteId: string, items: QuoteItemInput[]) =>
    post<Quote>(`${quotesBase(workOrderId)}/${quoteId}/revisions`, { items }),
  present: (workOrderId: string, quoteId: string, revisionId: string) =>
    post<Quote>(`${quotesBase(workOrderId)}/${quoteId}/revisions/${revisionId}/present`, {}),
}

export const publicQuotesApi = {
  view: (token: string) => publicApi<PublicQuote>(`/api/public/quotes/${encodeURIComponent(token)}`),
  decide: (token: string, body: unknown) =>
    publicApi<{ quote: PublicQuote; replayed: boolean }>(
      `/api/public/quotes/${encodeURIComponent(token)}/decisions`,
      { method: 'POST', body: JSON.stringify(body) }),
}
export const quoteAccessApi = {
  list: (workOrderId: string, quoteId: string) =>
    api<PublicQuoteAccess[]>(`${quotesBase(workOrderId)}/${quoteId}/public-access`),
  issue: (workOrderId: string, quoteId: string, revisionId: string) =>
    post<IssuedQuoteAccess>(`${quotesBase(workOrderId)}/${quoteId}/revisions/${revisionId}/public-access`, {}),
  revoke: (workOrderId: string, quoteId: string, accessId: string) =>
    post<void>(`${quotesBase(workOrderId)}/${quoteId}/public-access/${accessId}/revoke`, {}),
}

/** Monta a query string ignorando filtros vazios, para que a URL identifique a consulta de forma estável. */
export function queryString(params: Record<string, string | number | boolean | undefined | null>) {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) if (value !== undefined && value !== null && value !== '') search.set(key, String(value))
  const text = search.toString()
  return text ? `?${text}` : ''
}
const put = <T>(path: string, body: unknown) => api<T>(path, { method: 'PUT', body: JSON.stringify(body) })

export type CustomerSearchParams = { q?: string; personType?: string; status?: string; page?: number; size?: number }
export const customersApi = {
  /** Lista completa para seletores; telas de consulta usam `search`. */
  list: () => api<Customer[]>('/api/customers'),
  search: (params: CustomerSearchParams) => api<Page<Customer>>('/api/customers/search' + queryString(params)),
  get: (id: string) => api<Customer>(`/api/customers/${id}`),
  create: (body: unknown) => post<Customer>('/api/customers', body),
  update: (id: string, body: unknown) => put<Customer>(`/api/customers/${id}`, body),
  inactivate: (id: string) => post<Customer>(`/api/customers/${id}/inactivate`, {}),
  reactivate: (id: string) => post<Customer>(`/api/customers/${id}/reactivate`, {}),
  vehicles: (id: string) => api<Vehicle[]>(`/api/customers/${id}/vehicles`),
}

export type VehicleSearchParams = { q?: string; customerId?: string; active?: boolean; page?: number; size?: number }
export const vehiclesApi = {
  search: (params: VehicleSearchParams) => api<Page<VehicleListItem>>('/api/vehicles/search' + queryString(params)),
  get: (id: string) => api<Vehicle>(`/api/vehicles/${id}`),
  create: (body: unknown) => post<Vehicle>('/api/vehicles', body),
  update: (id: string, body: unknown) => put<Vehicle>(`/api/vehicles/${id}`, body),
  transfer: (id: string, customerId: string) => post<Vehicle>(`/api/vehicles/${id}/owner`, { customerId }),
  ownership: (id: string) => api<VehicleOwnership[]>(`/api/vehicles/${id}/ownership-history`),
  inactivate: (id: string) => post<Vehicle>(`/api/vehicles/${id}/inactivate`, {}),
  reactivate: (id: string) => post<Vehicle>(`/api/vehicles/${id}/reactivate`, {}),
}
