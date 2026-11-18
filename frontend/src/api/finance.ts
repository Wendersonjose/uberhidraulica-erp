import { api } from './http'
import type { Page } from './types'

export const FINANCE_VIEW = 'FINANCE_VIEW'
export const FINANCE_RECEIVE = 'FINANCE_RECEIVE'
export const FINANCE_REVERSE = 'FINANCE_REVERSE'
export const FINANCE_ADJUST = 'FINANCE_ADJUST'
export const FINANCE_PAYABLE = 'FINANCE_PAYABLE'
export const FINANCE_CONFIG = 'FINANCE_CONFIG'
export const FINANCE_BILL = 'FINANCE_BILL'
export const FINANCE_RECEIVABLE_MANUAL = 'FINANCE_RECEIVABLE_MANUAL'
export const FINANCE_PAYABLE_APPROVE = 'FINANCE_PAYABLE_APPROVE'
export const FINANCE_RECONCILE = 'FINANCE_RECONCILE'
export const CASH_SESSION_OPEN = 'CASH_SESSION_OPEN'
export const CASH_SESSION_CLOSE = 'CASH_SESSION_CLOSE'
export const CASH_SUPPLY = 'CASH_SUPPLY'
export const CASH_WITHDRAWAL = 'CASH_WITHDRAWAL'
export const CASH_REVERSAL = 'CASH_REVERSAL'

export type FinancialStatus = 'ABERTO' | 'PARCIAL' | 'VENCIDO' | 'QUITADO' | 'CANCELADO'
export const RECEIVABLE_STATUS_LABELS: Record<FinancialStatus, string> = {
  ABERTO: 'Em aberto', PARCIAL: 'Parcialmente recebido', VENCIDO: 'Vencido', QUITADO: 'Quitado', CANCELADO: 'Cancelado',
}
export const PAYABLE_STATUS_LABELS: Record<FinancialStatus, string> = {
  ABERTO: 'Em aberto', PARCIAL: 'Parcialmente pago', VENCIDO: 'Vencido', QUITADO: 'Pago', CANCELADO: 'Cancelado',
}
export const statusTone = (status: FinancialStatus) =>
  status === 'QUITADO' ? 'success' : status === 'VENCIDO' || status === 'CANCELADO' ? 'warning' : 'info'

export type CardFee = { installments: number; feePercent: number; feeAmount: number; expectedSettlementOn: string }
export type Settlement = {
  id: string; ownerId: string; amount: number; paymentMethodId: string; paymentMethodName: string; effectiveOn: string
  notes: string | null; recordedAt: string; recordedBy: string; reversed: boolean
  reversal: { id: string; reason: string; reversedAt: string; reversedBy: string } | null
  cardFee?: CardFee | null; netAmount?: number
}
export type ReceivableOrigin = 'WORK_ORDER' | 'MANUAL'
export type Installment = { number: number; amount: number; dueDate: string; openAmount: number; status: FinancialStatus }
export type InstallmentPlan = { version: number; reason: string | null; createdAt: string | null; editable: boolean; installments: Installment[] }
export type ReceivableLine = {
  id: string; quoteItemRevisionId: string; description: string; quantity: number; unitPrice: number
  discountAmount: number; totalAmount: number; displayOrder: number
}
export type Receivable = {
  id: string; origin?: ReceivableOrigin; workOrderId: string | null; workOrderNumber: number | null; customerId: string
  customerName: string | null; billingQuoteId: string | null
  description?: string | null; revenueCategoryId?: string | null; revenueCategoryName?: string | null; notes?: string | null
  principalOutstanding?: number; surchargeOutstanding?: number; installmentPlan?: InstallmentPlan
  originalAmount: number; discountAmount: number; surchargeAmount: number; adjustedAmount: number; receivedAmount: number
  outstandingBalance: number; issuedOn: string; dueDate: string; status: FinancialStatus; createdAt: string
  cancelledAt: string | null; cancellationReason: string | null; lines: ReceivableLine[]
  adjustments: {
    id: string; type: 'DISCOUNT' | 'SURCHARGE'; origin?: 'MANUAL' | 'LATE_CHARGE'; amount: number; reason: string; recordedAt: string; reversed: boolean
    reversal: { id: string; reason: string; reversedAt: string } | null
  }[]
  dueDateChanges: { id: string; installmentNumber?: number | null; previousDueDate: string; newDueDate: string; reason: string; changedAt: string }[]
  receipts: Settlement[]
}
export type ReceivableSummary = Pick<Receivable, 'id' | 'workOrderId' | 'workOrderNumber' | 'customerId' | 'customerName'
  | 'originalAmount' | 'discountAmount' | 'surchargeAmount' | 'receivedAmount' | 'outstandingBalance' | 'issuedOn' | 'dueDate' | 'status'
  | 'origin' | 'description'> & { installmentCount?: number }
export type ApprovalStatus = 'NOT_REQUIRED' | 'PENDING' | 'APPROVED'
export const APPROVAL_LABELS: Record<ApprovalStatus, string> = { NOT_REQUIRED: 'Sem aprovação', PENDING: 'Aguardando aprovação', APPROVED: 'Aprovada' }
export type LateChargeLine = { installmentNumber: number; dueDate: string; baseAmount: number; interestFrom: string; days: number
  feeAmount: number; interestAmount: number }
export type LateChargeSuggestion = { calculatedOn: string; feePercent: number; interestMonthlyPercent: number; feeAmount: number
  interestAmount: number; total: number; lines: LateChargeLine[] }
export type LateChargeHistory = { id: string; decision: 'APPLIED' | 'WAIVED'; calculatedOn: string; total: number; reason: string
  recordedAt: string; counts: boolean }
export type PaymentMethodFee = { installments: number; feePercent: number; settlementDays: number }
export type FinanceSettings = { defaultReceivableDueDays: number; lateFeePercent: number; lateInterestMonthlyPercent: number
  payableApprovalThreshold: number }
export type FinancialAccountType = 'CASH' | 'BANK' | 'CARD'
export const ACCOUNT_TYPE_LABELS: Record<FinancialAccountType, string> = { CASH: 'Caixa físico', BANK: 'Conta bancária', CARD: 'Cartão / adquirente' }
export type FinancialAccount = { id: string; name: string; type: FinancialAccountType; bankName: string | null; branch: string | null
  accountNumber: string | null; active: boolean }
export type Reconciliation = { id: string; accountId: string; entryType: 'RECEIPT' | 'PAYMENT'; entryId: string; statementDate: string
  statementAmount: number; statementReference: string; expectedAmount: number; difference: number; differenceReason: string | null
  active: boolean }
export type ReconciliationEntry = { type: 'RECEIPT' | 'PAYMENT'; id: string; ownerId: string; ownerLabel: string; effectiveOn: string
  paymentMethodName: string; amount: number; netAmount: number; expectedSettlementOn: string | null; accountName: string | null
  reconciliation: Reconciliation | null }
export type Payable = {
  id: string; description: string; supplier: string | null; categoryId: string; categoryName: string; amount: number
  paidAmount: number; outstandingBalance: number; dueDate: string; status: FinancialStatus; notes: string | null
  createdAt: string; cancelledAt: string | null; cancellationReason: string | null; payments: Settlement[]
  supplierId?: string | null; origin?: 'MANUAL' | 'PURCHASE'; groupId?: string | null; installmentNumber?: number | null
  approvalStatus?: ApprovalStatus; approval?: { approvedAt: string; approvedBy: string; notes: string | null } | null; createdBy?: string
}
export type PayableSummary = Pick<Payable, 'id' | 'description' | 'supplier' | 'categoryId' | 'categoryName' | 'amount'
  | 'paidAmount' | 'outstandingBalance' | 'dueDate' | 'status' | 'supplierId' | 'groupId' | 'installmentNumber' | 'approvalStatus'>
export type PayableGroup = { id: string; description: string; supplier: string | null; supplierId: string | null; categoryId: string
  totalAmount: number; installmentCount: number; notes: string | null; createdAt: string; paidAmount: number; outstandingBalance: number
  payables: Payable[] }
export type PaymentMethod = { id: string; code: string; name: string; active: boolean; cashSessionRequired: boolean }
export type ExpenseCategory = { id: string; name: string; active: boolean }
export type CashFlowDay = { date: string; inflows: number; outflows: number; net: number }
export type CashFlowBlock = { inflows: number; outflows: number; net: number; days: CashFlowDay[] }
export type CashFlow = { from: string; to: string; categoryId: string | null; realized: CashFlowBlock; forecast: CashFlowBlock }
export type FinanceDashboard = {
  from: string; to: string
  revenue: number; discounts?: number; surcharges?: number; netRevenue?: number; cardFees?: number; received: number; receivableOpen: number; overdue: number
  partsCost: number; expensesRegistered: number; expensesPaid: number
  grossProfit: number; operatingResult: number; grossMargin: number; operatingMargin: number
}
export type CashSessionStatus = 'OPEN' | 'CLOSED' | 'AUTO_CLOSED'
export type CashConferenceStatus = 'CHECKED' | 'NOT_CHECKED'
export type CashMovementType = 'RECEIPT' | 'CHANGE' | 'PAYABLE_PAYMENT' | 'SUPPLY' | 'WITHDRAWAL' | 'ADJUSTMENT' | 'REVERSAL'
export type CashMovement = {
  id: string; cashSessionId: string; type: CashMovementType; direction: 'IN' | 'OUT'; amount: number
  reason: string | null; receiptId: string | null; payablePaymentId: string | null; reversedMovementId: string | null
  recordedAt: string; recordedBy: string
}
export type CashSession = {
  id: string; status: CashSessionStatus; conferenceStatus: CashConferenceStatus
  openedAt: string; openedBy: string; openingExpectedBalance: number; openingCountedBalance: number; openingDifferenceReason: string | null
  closedAt: string | null; closedBy: string | null; closingExpectedBalance: number | null; closingCountedBalance: number | null
  closingDifferenceReason: string | null; checkedAt: string | null; checkedBy: string | null; checkedCountedBalance: number | null
  checkedDifferenceReason: string | null; currentExpectedBalance: number
}

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
  receivables: (filter: { status?: string; workOrderNumber?: string; origin?: string; page?: number }) =>
    api<Page<ReceivableSummary>>('/api/finance/receivables' + query({ ...filter, size: 20 })),
  receivable: (id: string) => api<Receivable>(`/api/finance/receivables/${id}`),
  workOrderReceivable: (workOrderId: string) => api<Receivable>(`/api/finance/work-orders/${workOrderId}/receivable`),
  receive: (id: string, body: { amount: number; paymentMethodId: string; receivedOn?: string; notes?: string | null; cashTendered?: number
    installments?: number }, key: string) =>
    idempotent<Settlement>(`/api/finance/receivables/${id}/receipts`, body, key),
  reverseReceipt: (id: string, reason: string, key: string) => idempotent<Settlement>(`/api/finance/receipts/${id}/reversal`, { reason }, key),
  adjust: (id: string, body: { type: 'DISCOUNT' | 'SURCHARGE'; amount: number; reason: string }, key: string) =>
    idempotent<Receivable>(`/api/finance/receivables/${id}/adjustments`, body, key),
  changeDueDate: (id: string, dueDate: string, reason: string, key: string, installmentNumber?: number) =>
    api<Receivable>(`/api/finance/receivables/${id}/due-date`, { method: 'PUT', body: JSON.stringify({ dueDate, reason, installmentNumber }), headers: { 'Idempotency-Key': key } }),
  replan: (id: string, installments: { amount: number; dueDate: string }[], reason: string, key: string) =>
    api<Receivable>(`/api/finance/receivables/${id}/installments`, { method: 'PUT', body: JSON.stringify({ installments, reason }), headers: { 'Idempotency-Key': key } }),
  createManualReceivable: (body: { customerId: string; description: string; revenueCategoryId: string; amount: number; dueDate: string
    issuedOn?: string; notes: string | null; installments: number }, key: string) => idempotent<Receivable>('/api/finance/receivables', body, key),
  cancelManualReceivable: (id: string, reason: string) =>
    api<Receivable>(`/api/finance/receivables/${id}/cancel`, { method: 'POST', body: JSON.stringify({ reason }) }),
  lateCharge: (id: string, on?: string) => api<LateChargeSuggestion>(`/api/finance/receivables/${id}/late-charge` + query({ on })),
  lateChargeHistory: (id: string) => api<LateChargeHistory[]>(`/api/finance/receivables/${id}/late-charges`),
  decideLateCharge: (id: string, body: { decision: 'APPLIED' | 'WAIVED'; calculatedOn: string; expectedTotal: number; reason?: string | null }, key: string) =>
    idempotent<Receivable>(`/api/finance/receivables/${id}/late-charge`, body, key),
  reverseAdjustment: (id: string, reason: string, key: string) => idempotent<Receivable>(`/api/finance/adjustments/${id}/reversal`, { reason }, key),

  payables: (filter: { status?: string; categoryId?: string; approval?: string; groupId?: string; page?: number }) =>
    api<Page<PayableSummary>>('/api/finance/payables' + query({ ...filter, size: 20 })),
  payable: (id: string) => api<Payable>(`/api/finance/payables/${id}`),
  createPayable: (body: { description: string; supplier: string | null; supplierId?: string | null; categoryId: string; amount: number
    dueDate: string; notes: string | null }, key: string) => idempotent<Payable>('/api/finance/payables', body, key),
  createPayableInstallments: (body: { description: string; supplier: string | null; supplierId?: string | null; categoryId: string
    totalAmount: number; installments: number; firstDueDate: string; notes: string | null }, key: string) =>
    idempotent<PayableGroup>('/api/finance/payables/installments', body, key),
  payableGroup: (id: string) => api<PayableGroup>(`/api/finance/payable-groups/${id}`),
  approvePayable: (id: string, notes?: string | null) => api<Payable>(`/api/finance/payables/${id}/approval`, { method: 'POST', body: JSON.stringify({ notes }) }),
  approvePayableGroup: (id: string, notes?: string | null) =>
    api<PayableGroup>(`/api/finance/payable-groups/${id}/approval`, { method: 'POST', body: JSON.stringify({ notes }) }),
  pay: (id: string, body: { amount: number; paymentMethodId: string; paidOn?: string; notes?: string | null }, key: string) =>
    idempotent<Settlement>(`/api/finance/payables/${id}/payments`, body, key),
  reversePayment: (id: string, reason: string, key: string) => idempotent<Settlement>(`/api/finance/payments/${id}/reversal`, { reason }, key),
  cancelPayable: (id: string, reason: string) => api<Payable>(`/api/finance/payables/${id}/cancel`, { method: 'POST', body: JSON.stringify({ reason }) }),

  cashSuggestedOpeningBalance: () => api<{ amount: number }>('/api/finance/cash/suggested-opening-balance'),
  openCashSession: () => api<CashSession>('/api/finance/cash/sessions/open'),
  cashSession: (id: string) => api<CashSession>(`/api/finance/cash/sessions/${id}`),
  cashMovements: (id: string) => api<CashMovement[]>(`/api/finance/cash/sessions/${id}/movements`),
  createCashSession: (countedBalance: number, differenceReason?: string | null) =>
    api<CashSession>('/api/finance/cash/sessions', { method: 'POST', body: JSON.stringify({ countedBalance, differenceReason }) }),
  closeCashSession: (id: string, countedBalance: number, differenceReason?: string | null) =>
    api<CashSession>(`/api/finance/cash/sessions/${id}/close`, { method: 'POST', body: JSON.stringify({ countedBalance, differenceReason }) }),
  checkCashSession: (id: string, countedBalance: number, differenceReason?: string | null) =>
    api<CashSession>(`/api/finance/cash/sessions/${id}/check`, { method: 'POST', body: JSON.stringify({ countedBalance, differenceReason }) }),
  cashSupply: (amount: number, reason: string, key: string) => idempotent<CashMovement>('/api/finance/cash/movements/supply', { amount, reason }, key),
  cashWithdrawal: (amount: number, reason: string, key: string) => idempotent<CashMovement>('/api/finance/cash/movements/withdrawal', { amount, reason }, key),
  reverseCashMovement: (id: string, reason: string, key: string) => idempotent<CashMovement>(`/api/finance/cash/movements/${id}/reversal`, { reason }, key),

  cashFlow: (from: string, to: string, categoryId?: string) => api<CashFlow>('/api/finance/cash-flow' + query({ from, to, categoryId })),
  dashboard: (from: string, to: string) => api<FinanceDashboard>('/api/finance/dashboard' + query({ from, to })),
  paymentMethods: () => api<PaymentMethod[]>('/api/finance/payment-methods'),
  /** A natureza (movimenta dinheiro físico) é obrigatória e definitiva: não é inferida do nome nem alterada depois. */
  createPaymentMethod: (name: string, cashSessionRequired: boolean) =>
    api<PaymentMethod>('/api/finance/payment-methods', { method: 'POST', body: JSON.stringify({ name, cashSessionRequired }) }),
  updatePaymentMethod: (id: string, name: string, active: boolean) => put<PaymentMethod>(`/api/finance/payment-methods/${id}`, { name, active }),
  categories: () => api<ExpenseCategory[]>('/api/finance/expense-categories'),
  createCategory: (name: string) => api<ExpenseCategory>('/api/finance/expense-categories', { method: 'POST', body: JSON.stringify({ name }) }),
  updateCategory: (id: string, name: string, active: boolean) => put<ExpenseCategory>(`/api/finance/expense-categories/${id}`, { name, active }),
  settings: () => api<FinanceSettings>('/api/finance/settings'),
  changeSettings: (changes: Partial<FinanceSettings>) => put<FinanceSettings>('/api/finance/settings', changes),
  revenueCategories: () => api<ExpenseCategory[]>('/api/finance/revenue-categories'),
  createRevenueCategory: (name: string) => api<ExpenseCategory>('/api/finance/revenue-categories', { method: 'POST', body: JSON.stringify({ name }) }),
  updateRevenueCategory: (id: string, name: string, active: boolean) => put<ExpenseCategory>(`/api/finance/revenue-categories/${id}`, { name, active }),
  fees: (methodId: string) => api<PaymentMethodFee[]>(`/api/finance/payment-methods/${methodId}/fees`),
  replaceFees: (methodId: string, fees: PaymentMethodFee[]) => put<PaymentMethodFee[]>(`/api/finance/payment-methods/${methodId}/fees`, { fees }),
  accounts: () => api<FinancialAccount[]>('/api/finance/accounts'),
  createAccount: (body: { name: string; type: FinancialAccountType; bankName?: string | null; branch?: string | null; accountNumber?: string | null }) =>
    api<FinancialAccount>('/api/finance/accounts', { method: 'POST', body: JSON.stringify(body) }),
  updateAccount: (id: string, body: { name: string; bankName: string | null; branch: string | null; accountNumber: string | null; active: boolean }) =>
    put<FinancialAccount>(`/api/finance/accounts/${id}`, body),
  reconciliationEntries: (filter: { from: string; to: string; type?: string; reconciled?: string; accountId?: string }) =>
    api<ReconciliationEntry[]>('/api/finance/reconciliation/entries' + query(filter)),
  reconcile: (body: { accountId: string; entryType: 'RECEIPT' | 'PAYMENT'; entryId: string; statementDate: string; statementAmount: number
    statementReference: string; differenceReason?: string | null }, key: string) => idempotent<Reconciliation>('/api/finance/reconciliations', body, key),
  reverseReconciliation: (id: string, reason: string, key: string) =>
    idempotent<Reconciliation>(`/api/finance/reconciliations/${id}/reversal`, { reason }, key),
}

export const financeKeys = {
  receivables: ['finance', 'receivables'] as const,
  receivable: (id: string) => ['finance', 'receivables', id] as const,
  workOrderReceivable: (workOrderId: string) => ['finance', 'work-orders', workOrderId] as const,
  payables: ['finance', 'payables'] as const,
  payable: (id: string) => ['finance', 'payables', id] as const,
  methods: ['finance', 'payment-methods'] as const,
  categories: ['finance', 'categories'] as const,
  revenueCategories: ['finance', 'revenue-categories'] as const,
  fees: (methodId: string) => ['finance', 'payment-methods', methodId, 'fees'] as const,
  lateCharge: (id: string, on: string) => ['finance', 'receivables', id, 'late-charge', on] as const,
  lateChargeHistory: (id: string) => ['finance', 'receivables', id, 'late-charges'] as const,
  payableGroup: (id: string) => ['finance', 'payable-groups', id] as const,
  accounts: ['finance', 'accounts'] as const,
  reconciliation: ['finance', 'reconciliation'] as const,
  settings: ['finance', 'settings'] as const,
  cashSession: ['finance', 'cash-session'] as const,
  cashSuggestedOpening: ['finance', 'cash-session', 'suggested-opening'] as const,
  cashMovements: (id: string) => ['finance', 'cash-session', id, 'movements'] as const,
  cashFlow: (from: string, to: string, categoryId: string) => ['finance', 'cash-flow', from, to, categoryId] as const,
  dashboard: (from: string, to: string) => ['finance', 'dashboard', from, to] as const,
}

/** Data de hoje no fuso da oficina, no formato ISO usado pela API. */
export const todayIso = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo' }).format(new Date())
