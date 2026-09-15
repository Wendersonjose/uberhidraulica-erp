export type Session={id:string;name:string;email:string;profileCode:string;state:string;mustChangePassword:boolean;permissions:string[]};export type Customer={id:string;personType:'PF'|'PJ';name:string;document:string;status:'ACTIVE'|'INACTIVE';createdAt:string;updatedAt:string};export type Vehicle={id:string;customerId:string;plate:string;manufacturer:string;model:string;modelYear:number;mileage:number|null;steeringGearManufacturer:string|null};export type Service={id:string;name:string;description:string;category:string|null;basePrice:number;defaultWarrantyDays:number;active:boolean};export type WorkOrderService={id:string;serviceId:string;name:string;description:string;basePrice:number;warrantyDays:number;addedAt:string};export type WorkOrder={id:string;number:number;customerId:string;vehicleId:string;entryMileage:number;openedAt:string;status:'ABERTA';services:WorkOrderService[];products:WorkOrderProduct[]}

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
