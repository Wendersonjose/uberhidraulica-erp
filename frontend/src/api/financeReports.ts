import { api } from './http'
import type { FinanceDashboard } from './finance'

/** TASK-0024, etapa A: relatórios gerenciais somente leitura (`/api/finance/reports`). */

export type DueSummary = {
  overdueCount: number; overdueAmount: number; dueIn7Count: number; dueIn7Amount: number
  dueIn30Count: number; dueIn30Amount: number; openCount: number; openAmount: number
}
export type FinanceOverview = {
  today: string
  period: FinanceDashboard
  cash: { open: boolean; sessionId: string | null; openedAt: string | null; expectedBalance: number | null }
  receivables: DueSummary
  payables: DueSummary
}
export type AgingBucketCode = 'A_VENCER' | 'ATE_30' | 'DE_31_A_60' | 'DE_61_A_90' | 'ACIMA_90'
export type Aging = {
  asOf: string; totalOutstanding: number
  buckets: { code: AgingBucketCode; count: number; amount: number }[]
  debtors: { customerId: string; customerName: string | null; count: number; outstanding: number; overdue: number; oldestDueDate: string }[]
}
export type ExpensesByCategory = {
  from: string; to: string; registered: number; paid: number
  categories: { categoryId: string; categoryName: string; registeredCount: number; registered: number; paid: number }[]
}
export type WorkOrderResultLine = {
  receivableId: string; workOrderId: string; workOrderNumber: number; customerId: string; customerName: string | null
  issuedOn: string; dueDate: string; status: string; original: number; discount: number; surcharge: number; netRevenue: number
  received: number; outstanding: number; partsCost: number; result: number; margin: number
}
export type WorkOrderResults = { from: string; to: string; netRevenue: number; partsCost: number; result: number; workOrders: WorkOrderResultLine[] }
export type IncomeStatement = {
  from: string; to: string; grossRevenue: number; discounts: number; surcharges: number; netRevenue: number
  partsCost: number; grossProfit: number; expenses: ExpensesByCategory['categories']; totalExpenses: number
  operatingResult: number; operatingMargin: number; cashReceived: number; cashPaid: number; cashResult: number
}
export type ReceivableReportRow = {
  id: string; workOrderNumber: number; customerId: string; customerName: string | null; issuedOn: string; dueDate: string
  status: string; original: number; discount: number; surcharge: number; received: number; outstanding: number
}
export type PayableReportRow = {
  id: string; description: string; supplier: string | null; categoryName: string; registeredOn: string; dueDate: string
  status: string; amount: number; paid: number; outstanding: number
}

const period = (from: string, to: string) => `?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`
const BASE = '/api/finance/reports'

export const reportsApi = {
  overview: (from: string, to: string) => api<FinanceOverview>(`${BASE}/overview${period(from, to)}`),
  aging: () => api<Aging>(`${BASE}/aging`),
  expensesByCategory: (from: string, to: string) => api<ExpensesByCategory>(`${BASE}/expenses-by-category${period(from, to)}`),
  workOrders: (from: string, to: string) => api<WorkOrderResults>(`${BASE}/work-orders${period(from, to)}`),
  incomeStatement: (from: string, to: string) => api<IncomeStatement>(`${BASE}/income-statement${period(from, to)}`),
  receivables: (from: string, to: string) => api<ReceivableReportRow[]>(`${BASE}/receivables${period(from, to)}`),
  payables: (from: string, to: string) => api<PayableReportRow[]>(`${BASE}/payables${period(from, to)}`),
}

export const reportKeys = {
  overview: (from: string, to: string) => ['finance', 'reports', 'overview', from, to] as const,
  aging: ['finance', 'reports', 'aging'] as const,
  report: (name: string, from: string, to: string) => ['finance', 'reports', name, from, to] as const,
}

/** CSV com ponto e vírgula e vírgula decimal (abre direto no Excel em pt-BR); BOM para manter os acentos. */
export function toCsv(header: string[], rows: (string | number | null | undefined)[][]) {
  const cell = (value: string | number | null | undefined) => {
    if (value === null || value === undefined) return ''
    const text = typeof value === 'number' ? value.toFixed(2).replace('.', ',') : value
    return /[;"\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
  }
  return '﻿' + [header, ...rows].map(row => row.map(cell).join(';')).join('\r\n')
}

export function downloadCsv(fileName: string, csv: string) {
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.click()
  URL.revokeObjectURL(url)
}
