import { api } from './http'
import type { CashSession } from './finance'

export const cashApi = {
  pendingCheck: () => api<CashSession | undefined>('/api/finance/cash/sessions/pending-check'),
}

export const cashKeys = {
  pendingCheck: ['finance', 'cash-session', 'pending-check'] as const,
}
