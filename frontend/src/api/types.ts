export type Session={id:string;name:string;email:string;profileCode:string;state:string;mustChangePassword:boolean;permissions:string[]};export type WorkOrder={id:string;number:number;customerId:string;vehicleId:string;entryMileage:number;openedAt:string;status:'ABERTA';services:WorkOrderService[];products:WorkOrderProduct[]}

export type ProductType = 'PART' | 'SUPPLY' | 'COMPONENT' | 'KIT' | 'INTERNAL_USE_MATERIAL'
export type ProductUnit = 'UNIDADE' | 'LITRO' | 'METRO' | 'QUILOGRAMA'
export type Product = {
  id: string; description: string; internalCode: string | null; category: string | null
  type: ProductType; unit: ProductUnit; referenceCost: number | null; salePrice: number | null
  minimumStock: number | null; active: boolean; createdAt: string; updatedAt: string
}
export const PRODUCT_TYPE_LABELS: Record<ProductType, string> = {
  PART: 'Peça', SUPPLY: 'Insumo', COMPONENT: 'Componente', KIT: 'Kit',
  INTERNAL_USE_MATERIAL: 'Material de uso interno',
}
export const PRODUCT_UNIT_LABELS: Record<ProductUnit, string> = {
  UNIDADE: 'Unidade', LITRO: 'Litro', METRO: 'Metro', QUILOGRAMA: 'Quilograma',
}
export type WorkOrderProduct = {
  id: string; productId: string; description: string; internalCode: string | null
  unit: string; quantity: number; unitPrice: number; addedAt: string
}
export type DecisionAvailability = 'AVAILABLE' | 'SUPERSEDED' | 'EXPIRED' | 'NOT_PRESENTED'
export type QuoteItemRevision = {
  id: string; revisionSequence: number; description: string; quantity: number; unitPrice: number
  totalPrice: number; revisionReason: string | null; presented: boolean
  availability: DecisionAvailability; createdAt: string; createdBy: string
}
export type QuoteItem = {
  id: string; workOrderServiceId: string | null; createdAt: string; revisions: QuoteItemRevision[]
}
export type QuoteRevisionEntry = { quoteItemRevisionId: string; displayOrder: number }
export type QuoteRevision = {
  id: string; revisionNumber: number; status: 'DRAFT' | 'PRESENTED'; presentedAt: string | null
  validUntil: string | null; expired: boolean; total: number; createdAt: string; createdBy: string
  items: QuoteRevisionEntry[]
}
export type Quote = {
  id: string; workOrderId: string; createdAt: string; createdBy: string; availableTotal: number
  revisions: QuoteRevision[]; items: QuoteItem[]
}
/** Permissão exigida pelo backend para apresentar; a interface apenas reflete a mesma regra. */
export const QUOTE_PRESENT = 'QUOTE_PRESENT'
export const AVAILABILITY_LABELS: Record<DecisionAvailability, string> = {
  AVAILABLE: 'Decidível', SUPERSEDED: 'Substituída', EXPIRED: 'Expirada', NOT_PRESENTED: 'Rascunho',
}
export type PublicDecisionStatus = 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED'
export type PublicDecisionAvailability = 'DECIDABLE' | 'ALREADY_DECIDED' | 'SUPERSEDED'
export type PublicQuoteItem = {
  itemReference: string; description: string; quantity: number; unitPrice: number; totalPrice: number
  decisionStatus: PublicDecisionStatus; decisionAvailability: PublicDecisionAvailability
}
export type PublicQuote = {
  revisionReference: string; revisionNumber: number; presentedAt: string; validUntil: string
  total: number; items: PublicQuoteItem[]
}
export type PublicQuoteAccess = {
  id: string; quoteRevisionId: string; createdAt: string; validUntil: string
  revokedAt: string | null; active: boolean
}
export type IssuedQuoteAccess = { accessId: string; quoteRevisionId: string; token: string; validUntil: string }
export const PUBLIC_STATUS_LABELS: Record<PublicDecisionStatus, string> = {
  PENDING_APPROVAL: 'Aguardando sua decisão', APPROVED: 'Aprovado', REJECTED: 'Recusado',
}

export type Address = {
  zipCode: string | null; street: string | null; number: string | null; complement: string | null
  district: string | null; city: string | null; state: string | null
}
export type Customer = {
  id: string; personType: 'PF' | 'PJ'; name: string; document: string | null
  phone?: string | null; email?: string | null; address?: Address | null
  status: 'ACTIVE' | 'INACTIVE'; createdAt: string; updatedAt: string
}
export type Vehicle = {
  id: string; customerId: string; plate: string; manufacturer: string; model: string
  modelYear: number | null; mileage: number | null; steeringGearManufacturer: string | null
  color?: string | null; notes?: string | null; active?: boolean; createdAt?: string; updatedAt?: string
}
export type Page<T> = { items: T[]; totalItems: number; page: number; size: number; totalPages: number }
export type VehicleListItem = { vehicle: Vehicle; customerName: string }
export type VehicleOwnership = { id: string; customerId: string; customerName: string; startedAt: string; endedAt: string | null }

export type Service = {
  id: string; name: string; description: string | null; categoryId?: string | null; category: string | null
  basePrice: number | null; defaultWarrantyDays: number; active: boolean
}
export type WorkOrderService = {
  id: string; serviceId: string; name: string; description: string | null; basePrice: number
  warrantyDays: number; addedAt: string; priceSource?: PriceSource | 'MANUAL' | null
}
export type ServiceCategory = { id: string; name: string; active: boolean }
export type VehicleGroup = { id: string; name: string; description: string | null; active: boolean; vehicleCount: number }
export type GroupVehicle = { id: string; customerId: string; plate: string; manufacturer: string; model: string; active: boolean }
export type ServicePrice = {
  id: string; serviceId: string; vehicleId: string | null; vehicleGroupId: string | null
  vehicleGroupName: string | null; price: number; updatedAt: string
}
export type PriceSource = 'VEHICLE' | 'GROUP' | 'BASE' | 'NONE'
export type PriceSuggestion = { serviceId: string; vehicleId: string | null; price: number | null; source: PriceSource }
export const PRICE_SOURCE_LABELS: Record<string, string> = {
  VEHICLE: 'preço do veículo', GROUP: 'preço do grupo', BASE: 'preço base', MANUAL: 'informado manualmente', NONE: 'sem preço definido',
}
