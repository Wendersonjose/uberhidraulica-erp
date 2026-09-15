import{api,post}from'./http';import type{Customer,Vehicle,Service,WorkOrder,Product,Quote}from'./types';export const customersApi={list:()=>api<Customer[]>('/api/customers'),get:(id:string)=>api<Customer>(`/api/customers/${id}`),create:(body:unknown)=>post<Customer>('/api/customers',body),vehicles:(id:string)=>api<Vehicle[]>(`/api/customers/${id}/vehicles`)};export const vehiclesApi={create:(body:unknown)=>post<Vehicle>('/api/vehicles',body),get:(id:string)=>api<Vehicle>(`/api/vehicles/${id}`),async listAll(){const customers=await customersApi.list(),groups=await Promise.all(customers.map(async customer=>({customer,vehicles:await customersApi.vehicles(customer.id)})));return groups.flatMap(({customer,vehicles})=>vehicles.map(vehicle=>({vehicle,customer})))}};export const servicesApi={list:()=>api<Service[]>('/api/services'),create:(body:unknown)=>post<Service>('/api/services',body)};export const workOrdersApi={list:()=>api<WorkOrder[]>('/api/work-orders'),get:(id:string)=>api<WorkOrder>(`/api/work-orders/${id}`),create:(body:unknown)=>post<WorkOrder>('/api/work-orders',body),addService:(id:string,serviceId:string)=>post<WorkOrder>(`/api/work-orders/${id}/services`,{serviceId}),addProduct:(id:string,productId:string,quantity:number)=>post<WorkOrder>(`/api/work-orders/${id}/products`,{productId,quantity})}

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
