export type Session={id:string;name:string;email:string;profileCode:string;state:string;mustChangePassword:boolean;permissions:string[]};export type Customer={id:string;personType:'PF'|'PJ';name:string;document:string;status:'ACTIVE'|'INACTIVE';createdAt:string;updatedAt:string};export type Vehicle={id:string;customerId:string;plate:string;manufacturer:string;model:string;modelYear:number;mileage:number|null;steeringGearManufacturer:string|null};export type Service={id:string;name:string;description:string;category:string|null;basePrice:number;defaultWarrantyDays:number;active:boolean};export type WorkOrderService={id:string;serviceId:string;name:string;description:string;basePrice:number;warrantyDays:number;addedAt:string};export type WorkOrder={id:string;number:number;customerId:string;vehicleId:string;entryMileage:number;openedAt:string;status:'ABERTA';services:WorkOrderService[]}

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
