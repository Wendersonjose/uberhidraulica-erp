/**
 * Regras de recebimento no navegador (DR-0022 D3). Espelha `ReceiptLineEvaluation` do backend, que é quem decide:
 * aqui serve só para orientar o operador antes de enviar (mostrar a justificativa quando houver divergência).
 *
 * Quantidade física tem três casas. Para não depender de ponto flutuante, a comparação é feita em milésimos inteiros.
 */
import type { ReceiptStatus } from '../../api/purchasing'

export type Divergence = 'NONE' | 'OVER' | 'SHORT_CLOSED'

export const RECEIPT_STATUS_LABEL: Record<ReceiptStatus, string> = {
  NONE: 'Não recebida', PARTIAL: 'Recebida em parte', COMPLETE: 'Recebida',
}
export const receiptTone = (status: ReceiptStatus) =>
  (status === 'COMPLETE' ? 'success' : status === 'PARTIAL' ? 'info' : 'warning') as 'success' | 'info' | 'warning'

export const milli = (value: number) => Math.round(value * 1000)

/** Quantidade digitada (aceita vírgula). `null` quando vazia, inválida ou não positiva. */
export function parseQuantity(text: string): number | null {
  const normalized = text.trim().replace(',', '.')
  if (!/^\d+(\.\d{1,3})?$/.test(normalized)) return null
  const value = Number(normalized)
  return value > 0 ? value : null
}

/**
 * - chegou o que faltava ou menos, sem encerrar: recebimento parcial, **não** é divergência;
 * - chegou menos e o resto não virá: encerramento (`SHORT_CLOSED`), com a quantidade cancelada registrada;
 * - chegou mais do que o pendente (inclusive linha já resolvida): excesso (`OVER`).
 */
export function evaluateLine(pending: number, received: number, closeRemaining: boolean): { divergence: Divergence; cancelled: number } {
  const expected = Math.max(milli(pending), 0)
  const got = milli(received)
  if (got > expected) return { divergence: 'OVER', cancelled: 0 }
  if (got < expected && closeRemaining) return { divergence: 'SHORT_CLOSED', cancelled: (expected - got) / 1000 }
  return { divergence: 'NONE', cancelled: 0 }
}

export const DIVERGENCE_LABELS: Record<Divergence, string> = {
  NONE: 'Sem divergência', OVER: 'Recebido a mais', SHORT_CLOSED: 'Saldo encerrado',
}
