export const queryKeys = {
  workOrders: ['work-orders'] as const,
  customerVehicles: (customerId: string) => ['customers', customerId, 'vehicles'] as const,
}
