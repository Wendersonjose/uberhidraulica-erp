import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { financeApi, financeKeys, todayIso, type Receivable } from '../../api/finance'
import { formatBrl } from '../../utils/format'
import { formatDay, useIdempotencyKey } from './hooks'

const percent = (value: number) => `${value.toFixed(2).replace('.', ',')}%`

/**
 * Juros e multa por atraso (DR-0024, Q3): o valor é sugerido, nunca cobrado sozinho. Quem tem FINANCE_ADJUST
 * aplica (vira acréscimo) ou dispensa com motivo; o servidor recalcula e recusa se o valor mudou desde a tela.
 */
export function LateChargeCard({ receivable, canDecide, onDone }: { receivable: Receivable; canDecide: boolean; onDone: () => Promise<unknown> }) {
  const on = todayIso()
  const suggestion = useQuery({ queryKey: financeKeys.lateCharge(receivable.id, on), queryFn: () => financeApi.lateCharge(receivable.id, on) })
  const [waiving, setWaiving] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const idempotency = useIdempotencyKey()
  const decide = useMutation({
    mutationFn: (decision: 'APPLIED' | 'WAIVED') => financeApi.decideLateCharge(receivable.id, { decision, calculatedOn: on,
      expectedTotal: suggestion.data?.total ?? 0, reason: decision === 'WAIVED' ? reason.trim() : null }, idempotency.key),
    onSuccess: async () => { idempotency.renew(); setWaiving(false); setReason(''); setError(''); await suggestion.refetch(); await onDone() },
    onError: async e => { setError(e instanceof Error ? e.message : 'Não foi possível registrar a decisão'); await suggestion.refetch() },
  })
  const data = suggestion.data
  if (!data || data.total <= 0) return null
  return <section className="card" aria-label="Juros e multa por atraso">
    <h2>Juros e multa por atraso</h2>
    <p className="muted">Multa de {percent(data.feePercent)} uma vez por parcela e juros de {percent(data.interestMonthlyPercent)} ao mês
      {' '}pro rata, sobre o principal vencido. O valor original não muda.</p>
    {error && <div role="alert" className="notice error">{error}</div>}
    <ul className="history">{data.lines.map(line => <li key={line.installmentNumber}>
      Parcela {line.installmentNumber} (venceu {formatDay(line.dueDate)}): multa {formatBrl(line.feeAmount)} + juros {formatBrl(line.interestAmount)}
      {' '}de {line.days} dia(s) sobre {formatBrl(line.baseAmount)}
    </li>)}</ul>
    <p><strong data-testid="late-charge-total">Total sugerido: {formatBrl(data.total)}</strong></p>
    {!canDecide && <p className="muted">Aplicar ou dispensar exige permissão de ajuste financeiro.</p>}
    {canDecide && !waiving && <div className="actions">
      <button type="button" className="btn" disabled={decide.isPending} onClick={() => decide.mutate('APPLIED')}>Aplicar juros e multa</button>
      <button type="button" className="btn secondary" onClick={() => setWaiving(true)}>Dispensar</button>
    </div>}
    {canDecide && waiving && <div role="group" aria-label="Dispensar juros e multa">
      <div className="field"><label>Motivo da dispensa *<input className="input" value={reason} onChange={e => setReason(e.target.value)} /></label></div>
      <div className="actions">
        <button type="button" className="btn danger" disabled={reason.trim().length < 3 || decide.isPending} onClick={() => decide.mutate('WAIVED')}>Confirmar dispensa</button>
        <button type="button" className="btn secondary" onClick={() => setWaiving(false)}>Voltar</button>
      </div>
    </div>}
  </section>
}
