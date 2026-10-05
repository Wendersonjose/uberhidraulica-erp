import { api } from './http'
import type { CashSession } from './finance'

export const cashApi = {
  /**
   * Sessão encerrada automaticamente que ainda precisa de conferência, ou `null` quando não há nenhuma.
   *
   * <p>O backend responde 204 sem corpo quando não há pendência, e `api()` devolve `undefined` nesse caso. O
   * TanStack Query v5 trata `undefined` como erro ("Query data cannot be undefined"), o que deixava a tela do
   * Caixa mostrando "Não foi possível carregar o caixa" justamente no caso normal, sem pendência.</p>
   */
  pendingCheck: async (): Promise<CashSession | null> =>
    (await api<CashSession | undefined>('/api/finance/cash/sessions/pending-check')) ?? null,
}

export const cashKeys = {
  pendingCheck: ['finance', 'cash-session', 'pending-check'] as const,
}
