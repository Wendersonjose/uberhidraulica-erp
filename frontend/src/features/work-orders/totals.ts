import type { WorkOrderProduct } from '../../api/types'

/** Item que a OS ainda consome: nem removido, nem rejeitado pelo cliente (DR-0019). */
export const isConsumed = (item: WorkOrderProduct) => !item.removedAt && item.customerDecision !== 'REJECTED'

/**
 * Subtotal de apresentação: o backend não persiste nem calcula total comercial (DR-0006). Item removido da OS ou
 * rejeitado pelo cliente fica fora da soma, porque não será entregue nem cobrado.
 */
export const productsSubtotal = (products: WorkOrderProduct[]) =>
  products.filter(isConsumed).reduce((sum, item) => sum + Number(item.quantity) * Number(item.unitPrice), 0)
