import { api } from './http'
import type { Page } from './types'

export const FINANCE_VIEW = 'FINANCE_VIEW'
export const FINANCE_RECEIVE = 'FINANCE_RECEIVE'
export const FINANCE_REVERSE = 'FINANCE_REVERSE'
export const FINANCE_ADJUST = 'FINANCE_ADJUST'
export const FINANCE_PAYABLE = 'FINANCE_PAYABLE'
export const FINANCE_CONFIG = 'FINANCE_CONFIG'

export type FinancialStatus = 'ABERTO' | 'PARCIAL' | 'VENCIDO' | 'QUITADO' | 'CANCELADO'
export const RECEIVABLE_STATUS_LABELS: Record<FinancialStatus, string> = {
  ABERTO: 'Em aberto', PARCIAL: 'Parcialmente recebido', VENCIDO: 'Vencido', QUITADO: 'Quitado', CANCELADO: 'Cancelado',
}
export const PAYABLE_STATUS_LABELS: Record<FinancialStatus, string> = {
  ABERTO: 'Em aberto', PARCIAL: 'Parcialmente pago', VENCIDO: 'Vencido', QUITADO: 'Pago', CANCELADO: 'Cancelado',
}
export const statusTone = (status: FinancialStatus) =>
  status === 'QUITADO' ? 'success' : status === 'VENCIDO' || status === 'CANCELADO' ? 'warning' : 'info'

export type Settlement = {
  id: string; ownerId: string; amount: number; paymentMethodId: string; paymentMethodName: string; effectiveOn: string
  notes: string | null; recordedAt: string; recordedBy: string; reversed: boolean
  reversal: { id: string; reason: string; reversedAt: string; reversedBy: string } | null
}
export type ReceivableLine = {
  id: string; quoteItemRevisionId: string; description: string; quantity: number; unitPrice: number
  discountAmount: number; totalAmount: number; displayOrder: number
}
export type Receivable = {
  id: string; workOrderId: string; workOrderNumber: number; customerId: string; customerName: string | null; billingQuoteId: string
  originalAmount: number; discountAmount: number; surchargeAmount: number; adjustedAmount: number; receivedAmount: number
  outstandingBalance: number; issuedOn: string; dueDate: string; status: FinancialStatus; createdAt: string
  cancelledAt: string | null; cancellationReason: string | null; lines: ReceivableLine[]
  adjustments: { id: string; type: 'DISCOUNT' | 'SURCHARGE'; amount: number; reason: string; recordedAt: string }[]
  dueDateChanges: { id: string; previousDueDate: string; newDueDate: string; reason: string; changedAt: string }[]
  receipts: Settlement[]
}
export type ReceivableSummary = Pick<Receivable, 'id' | 'workOrderId' | 'workOrderNumber' | 'customerId' | 'customerName'
  | 'originalAmount' | 'discountAmount' | 'surchargeAmount' | 'receivedAmount' | 'outstandingBalance' | 'issuedOn' | 'dueDate' | 'status'>
export type Payable = {
  id: string; description: string; supplier: string | null; categoryId: string; categoryName: string; amount: number
  paidAmount: number; outstandingBalance: number; dueDate: string; status: FinancialStatus; notes: string | null
  createdAt: string; cancelledAt: string | null; cancellationReason: string | null; payments: Settlement[]
}
export type PayableSummary = Pick<Payable, 'id' | 'description' | 'supplier' | 'categoryId' | 'categoryName' | 'amount'
  | 'paidAmount' | 'outstandingBalance' | 'dueDate' | 'status'>
export type PaymentMethod = { id: string; code: string; name: string; active: boolean; cashSessionRequired: boolean }
export type ExpenseCategory = { id: string; name: string; active: boolean }
export type CashFlowDay = { date: string; inflows: number; outflows: number; net: number }
export type CashFlowBlock = { inflows: number; outflows: number; net: number; days: CashFlowDay[] }
export type CashFlow = { from: string; to: string; categoryId: string | null; realized: CashFlowBlock; forecast: CashFlowBlock }

/** Uma chave por intenção de envio: o retry da mesma intenção reaproveita a chave e não duplica o lançamento. */
export const newIdempotencyKey = () =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`

const idempotent = <T>(path: string, body: unknown, key: string) =>
  api<T>(path, { method: 'POST', body: JSON.stringify(body), headers: { 'Idempotency-Key': key } })
const put = <T>(path: string, body: unknown) => api<T>(path, { method: 'PUT', body: JSON.stringify(body) })
const query = (params: Record<string, string | number | undefined | null>) => {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => { if (value !== undefined && value !== null && value !== '') search.set(key, String(value)) })
  const text = search.toString()
  return text ? `?${text}` : ''
}

export const financeApi = {
  receivables: (filter: { status?: string; workOrderNumber?: string; page?: number }) =>
    api<Page<ReceivableSummary>>('/api/finance/receivables' + query({ ...filter, size: 20 })),
  receivable: (id: string) => api<Receivable>(`/api/finance/receivables/${id}`),
  workOrderReceivable: (workOrderId: string) => api<Receivable>(`/api/finance/work-orders/${workOrderId}/receivable`),
  receive: (id: string, body: { amount: number; paymentMethodId: string; receivedOn?: string; notes?: string | null }, key: string) =>
    idempotent<Settlement>(`/api/finance/receivables/${id}/receipts`, body, key),
  reverseReceipt: (id: string, reason: string, key: string) => idempotent<Settlement>(`/api/finance/receipts/${id}/reversal`, { reason }, key),
  adjust: (id: string, body: { type: 'DISCOUNT' | 'SURCHARGE'; amount: number; reason: string }, key: string) =>
    idempotent<Receivable>(`/api/finance/receivables/${id}/adjustments`, body, key),
  changeDueDate: (id: string, dueDate: string, reason: string) => put<Receivable>(`/api/finance/receivables/${id}/due-date`, { dueDate, reason }),

  payables: (filter: { status?: string; categoryId?: string; page?: number }) =>
    api<Page<PayableSummary>>('/api/finance/payables' + query({ ...filter, size: 20 })),
  payable: (id: string) => api<Payable>(`/api/finance/payables/${id}`),
  createPayable: (body: { description: string; supplier: string | null; categoryId: string; amount: number; dueDate: string; notes: string | null }, key: string) =>
    idempotent<Payable>('/api/finance/payables', body, key),
  pay: (id: string, body: { amount: number; paymentMethodId: string; paidOn?: string; notes?: string | null }, key: string) =>
    idempotent<Settlement>(`/api/finance/payables/${id}/payments`, body, key),
  reversePayment: (id: string, reason: string, key: string) => idempotent<Settlement>(`/api/finance/payments/${id}/reversal`, { reason }, key),
  cancelPayable: (id: string, reason: string) => api<Payable>(`/api/finance/payables/${id}/cancel`, { method: 'POST', body: JSON.stringify({ reason }) }),

  cashFlow: (from: string, to: string, categoryId?: string) => api<CashFlow>('/api/finance/cash-flow' + query({ from, to, categoryId })),
  paymentMethods: () => api<PaymentMethod[]>('/api/finance/payment-methods'),
  createPaymentMethod: (name: string) => api<PaymentMethod>('/api/finance/payment-methods', { method: 'POST', body: JSON.stringify({ name }) }),
  updatePaymentMethod: (id: string, name: string, active: boolean) => put<PaymentMethod>(`/api/finance/payment-methods/${id}`, { name, active }),
  categories: () => api<ExpenseCategory[]>('/api/finance/expense-categories'),
  createCategory: (name: string) => api<ExpenseCategory>('/api/finance/expense-categories', { method: 'POST', body: JSON.stringify({ name }) }),
  updateCategory: (id: string, name: string, active: boolean) => put<ExpenseCategory>(`/api/finance/expense-categories/${id}`, { name, active }),
  settings: () => api<{ defaultReceivableDueDays: number }>('/api/finance/settings'),
  changeSettings: (defaultReceivableDueDays: number) => put<{ defaultReceivableDueDays: number }>('/api/finance/settings', { defaultReceivableDueDays }),
}

export const financeKeys = {
  receivables: ['finance', 'receivables'] as const,
  receivable: (id: string) => ['finance', 'receivables', id] as const,
  workOrderReceivable: (workOrderId: string) => ['finance', 'work-orders', workOrderId] as const,
  payables: ['finance', 'payables'] as const,
  payable: (id: string) => ['finance', 'payables', id] as const,
  methods: ['finance', 'payment-methods'] as const,
  categories: ['finance', 'categories'] as const,
  settings: ['finance', 'settings'] as const,
  cashFlow: (from: string, to: string, categoryId: string) => ['finance', 'cash-flow', from, to, categoryId] as const,
}

/** Data de hoje no fuso da oficina, no formato ISO usado pela API. */
export const todayIso = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo' }).format(new Date())
