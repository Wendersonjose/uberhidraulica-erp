import{api,post,publicApi}from'./http';import type{Customer,Vehicle,Service,WorkOrder,Product,Quote,PublicQuote,PublicQuoteAccess,IssuedQuoteAccess,Page,VehicleListItem,VehicleOwnership,ServiceCategory,VehicleGroup,GroupVehicle,ServicePrice,PriceSuggestion,BoardColumn,StatusChange,StatusUsage,WorkflowStatus,Stage,StockLine,StockMovement,WriteOffMode}from'./types';export const workOrdersApi={list:(filter:{customerId?:string;vehicleId?:string}={})=>api<WorkOrder[]>('/api/work-orders'+queryString(filter)),get:(id:string)=>api<WorkOrder>(`/api/work-orders/${id}`),create:(body:unknown)=>post<WorkOrder>('/api/work-orders',body),addService:(id:string,serviceId:string,price?:number)=>post<WorkOrder>(`/api/work-orders/${id}/services`,price===undefined?{serviceId}:{serviceId,price}),addProduct:(id:string,productId:string,quantity:number)=>post<WorkOrder>(`/api/work-orders/${id}/products`,{productId,quantity})}

export const productsApi = {
  list: () => api<Product[]>('/api/products'),
  get: (id: string) => api<Product>(`/api/products/${id}`),
  create: (body: unknown) => post<Product>('/api/products', body),
  update: (id: string, body: unknown) => api<Product>(`/api/products/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
}

export type QuoteItemInput = {
  quoteItemId?: string | null; workOrderServiceId?: string | null; workOrderProductId?: string | null
  description: string; quantity: number; unitPrice: number; discount?: number
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
  decide: (workOrderId: string, quoteId: string, revisionId: string, body: { contactChannel: string; authorizedBy: string | null; notes: string | null; decisions: { itemReference: string; decision: 'APPROVE' | 'REJECT' }[] }) =>
    post<Quote>(`${quotesBase(workOrderId)}/${quoteId}/revisions/${revisionId}/decisions`, body),
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

export type ServiceSearchParams = { q?: string; categoryId?: string; active?: boolean; page?: number; size?: number }
export const servicesApi = {
  list: () => api<Service[]>('/api/services'),
  search: (params: ServiceSearchParams) => api<Page<Service>>('/api/services/search' + queryString(params)),
  get: (id: string) => api<Service>(`/api/services/${id}`),
  create: (body: unknown) => post<Service>('/api/services', body),
  update: (id: string, body: unknown) => put<Service>(`/api/services/${id}`, body),
  inactivate: (id: string) => post<Service>(`/api/services/${id}/inactivate`, {}),
  reactivate: (id: string) => post<Service>(`/api/services/${id}/reactivate`, {}),
  prices: (id: string) => api<ServicePrice[]>(`/api/services/${id}/prices`),
  setVehiclePrice: (id: string, vehicleId: string, price: number) => put<ServicePrice>(`/api/services/${id}/prices/vehicles/${vehicleId}`, { price }),
  setGroupPrice: (id: string, groupId: string, price: number) => put<ServicePrice>(`/api/services/${id}/prices/groups/${groupId}`, { price }),
  deletePrice: (id: string, priceId: string) => api<void>(`/api/services/${id}/prices/${priceId}`, { method: 'DELETE' }),
  suggestion: (id: string, vehicleId: string) => api<PriceSuggestion>(`/api/services/${id}/price-suggestion` + queryString({ vehicleId })),
}
export const serviceCategoriesApi = {
  list: () => api<ServiceCategory[]>('/api/service-categories'),
  create: (name: string) => post<ServiceCategory>('/api/service-categories', { name }),
  rename: (id: string, name: string) => put<ServiceCategory>(`/api/service-categories/${id}`, { name }),
  inactivate: (id: string) => post<ServiceCategory>(`/api/service-categories/${id}/inactivate`, {}),
  reactivate: (id: string) => post<ServiceCategory>(`/api/service-categories/${id}/reactivate`, {}),
}
export const vehicleGroupsApi = {
  list: () => api<VehicleGroup[]>('/api/vehicle-groups'),
  create: (body: { name: string; description: string | null }) => post<VehicleGroup>('/api/vehicle-groups', body),
  update: (id: string, body: { name: string; description: string | null }) => put<VehicleGroup>(`/api/vehicle-groups/${id}`, body),
  inactivate: (id: string) => post<VehicleGroup>(`/api/vehicle-groups/${id}/inactivate`, {}),
  reactivate: (id: string) => post<VehicleGroup>(`/api/vehicle-groups/${id}/reactivate`, {}),
  vehicles: (id: string) => api<GroupVehicle[]>(`/api/vehicle-groups/${id}/vehicles`),
  assign: (id: string, vehicleId: string) => api<void>(`/api/vehicle-groups/${id}/vehicles/${vehicleId}`, { method: 'PUT' }),
  remove: (id: string, vehicleId: string) => api<void>(`/api/vehicle-groups/${id}/vehicles/${vehicleId}`, { method: 'DELETE' }),
}

export const workflowApi = {
  board: (closedDays = 30) => api<BoardColumn[]>('/api/work-orders/board' + queryString({ closedDays })),
  history: (id: string) => api<StatusChange[]>(`/api/work-orders/${id}/status-history`),
  update: (id: string, body: { entryMileage: number | null; complaint: string; notes: string | null }) => put<WorkOrder>(`/api/work-orders/${id}`, body),
  move: (id: string, statusId: string, reason?: string) => post<WorkOrder>(`/api/work-orders/${id}/status`, reason ? { statusId, reason } : { statusId }),
  startExecution: (id: string) => post<WorkOrder>(`/api/work-orders/${id}/start-execution`, {}),
  /** {@code billingQuoteId} escolhe o orçamento de faturamento quando a OS tem mais de um aprovado (DR-0015). */
  finish: (id: string, billingQuoteId?: string) => post<WorkOrder>(`/api/work-orders/${id}/finish`, billingQuoteId ? { billingQuoteId } : {}),
  deliver: (id: string) => post<WorkOrder>(`/api/work-orders/${id}/deliver`, {}),
  cancel: (id: string, reason: string) => post<WorkOrder>(`/api/work-orders/${id}/cancel`, { reason }),
  statuses: () => api<StatusUsage[]>('/api/work-order-statuses'),
  createStatus: (name: string, stage: Stage) => post<WorkflowStatus>('/api/work-order-statuses', { name, stage }),
  renameStatus: (id: string, name: string) => put<WorkflowStatus>(`/api/work-order-statuses/${id}`, { name }),
  inactivateStatus: (id: string) => post<WorkflowStatus>(`/api/work-order-statuses/${id}/inactivate`, {}),
  reactivateStatus: (id: string) => post<WorkflowStatus>(`/api/work-order-statuses/${id}/reactivate`, {}),
  makeDefault: (id: string) => post<WorkflowStatus>(`/api/work-order-statuses/${id}/make-default`, {}),
  reorder: (statusIds: string[]) => put<WorkflowStatus[]>('/api/work-order-statuses/order', { statusIds }),
  diagnosis: (id: string, diagnosis: string) => put<WorkOrder>(`/api/work-orders/${id}/diagnosis`, { diagnosis }),
  automations: () => api<Record<string, boolean>>('/api/work-order-statuses/automations'),
  setAutomation: (event: string, enabled: boolean) => put<Record<string, boolean>>(`/api/work-order-statuses/automations/${event}`, { enabled }),
}

export type StockSearchParams = { q?: string; category?: string; active?: boolean; belowMinimum?: boolean; page?: number; size?: number }
export const inventoryApi = {
  stock: (params: StockSearchParams) => api<Page<StockLine>>('/api/inventory/stock' + queryString(params)),
  movements: (productId: string, page = 0, size = 20) =>
    api<Page<StockMovement>>(`/api/inventory/products/${productId}/movements` + queryString({ page, size })),
  entry: (productId: string, body: { quantity: number; unitCost: number | null; reason: string | null }) =>
    post<StockMovement>(`/api/inventory/products/${productId}/entries`, body),
  exit: (productId: string, body: { quantity: number; reason: string }) =>
    post<StockMovement>(`/api/inventory/products/${productId}/exits`, body),
  adjustment: (productId: string, body: { quantity: number; direction: 'IN' | 'OUT'; reason: string }) =>
    post<StockMovement>(`/api/inventory/products/${productId}/adjustments`, body),
  reverse: (movementId: string, reason: string) => post<StockMovement>(`/api/inventory/movements/${movementId}/reverse`, { reason }),
  settings: () => api<Record<string, string>>('/api/inventory/settings'),
  setWriteOff: (mode: WriteOffMode) => put<Record<string, string>>('/api/inventory/settings/write-off', { mode }),
}
