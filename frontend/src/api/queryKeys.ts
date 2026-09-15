export const queryKeys = {
  workOrders: ['work-orders'] as const,
  customerVehicles: (customerId: string) => ['customers', customerId, 'vehicles'] as const,
  products: ['products'] as const,
  product: (id: string) => ['products', id] as const,
  quotes: (workOrderId: string) => ['work-orders', workOrderId, 'quotes'] as const,
  quote: (workOrderId: string, quoteId: string) => ['work-orders', workOrderId, 'quotes', quoteId] as const,
}
