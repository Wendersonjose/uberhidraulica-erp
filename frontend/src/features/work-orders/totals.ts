import type { WorkOrderProduct } from '../../api/types'

/** Subtotal de apresentação: o backend não persiste nem calcula total comercial (DR-0006). */
export const productsSubtotal = (products: WorkOrderProduct[]) =>
  products.reduce((sum, item) => sum + Number(item.quantity) * Number(item.unitPrice), 0)
