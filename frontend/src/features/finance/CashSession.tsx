import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  CASH_REVERSAL, CASH_SESSION_CLOSE, CASH_SESSION_OPEN, CASH_SUPPLY, CASH_WITHDRAWAL,
  financeApi, financeKeys, type CashMovement, type CashSession,
} from '../../api/finance'
import { ApiError } from '../../api/http'
import { Badge, PageHeader } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { formatBrl, formatDate } from '../../utils/format'
import { useCan, useIdempotencyKey, validAmount } from './hooks'

const movementLabel: Record<CashMovement['type'], string> = {
  RECEIPT: 'Recebimento', CHANGE: 'Troco', PAYABLE_PAYMENT: 'Pagamento', SUPPLY: 'Suprimento',
  WITHDRAWAL: 'Sangria', ADJUSTMENT: 'Ajuste', REVERSAL: 'Estorno',
}

export function CashSessionPage() {
  const client = useQueryClient()
  const session = useQuery({
    queryKey: financeKeys.cashSession,
    queryFn: async () => {
      try { return await financeApi.openCashSession() }
      catch (error) { if (error instanceof ApiError && error.code === 'CASH_SESSION_REQUIRED') return null; throw error }
    },
  })
  const suggested = useQuery({ queryKey: financeKeys.cashSuggestedOpening, queryFn: financeApi.cashSuggestedOpeningBalance })
  const movements = useQuery({
    queryKey: financeKeys.cashMovements(session.data?.id ?? ''),
    queryFn: () => financeApi.cashMovements(session.data!.id), enabled: Boolean(session.data?.id),
  })
  const refresh = async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: financeKeys.cashSession }),
      client.invalidateQueries({ queryKey: financeKeys.cashSuggestedOpening }),
      session.data?.id ? client.invalidateQueries({ queryKey: financeKeys.cashMovements(session.data.id) }) : Promise.resolve(),
    ])
  }

  return <>
    <PageHeader title="Caixa físico" subtitle="Custódia do dinheiro em espécie, separada dos lançamentos financeiros." />
    <QueryState label="o caixa" loading={session.isLoading || suggested.isLoading} error={session.error ?? suggested.error} retry={() => { session.refetch(); suggested.refetch() }}>
      {session.data
        ? <OpenSession session={session.data} movements={movements.data ?? []} movementsLoading={movements.isLoading} onChange={refresh} />
        : <ClosedState suggested={suggested.data?.amount ?? 0} onChange={refresh} />}
    </QueryState>
  </>
}

function ClosedState({ suggested, onChange }: { suggested: number; onChange: () => Promise<unknown> }) {
  const canOpen = useCan(CASH_SESSION_OPEN)
  const [counted, setCounted] = useState(String(suggested))
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const divergent = Number(counted) !== suggested
  const mutation = useMutation({
    mutationFn: () => financeApi.createCashSession(Number(counted), reason.trim() || null),
    onSuccess: async () => { setError(''); await onChange() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível abrir o caixa'),
  })
  return <div className="detail-grid">
    <section className="card">
      <h2>Caixa fechado</h2>
      <p className="muted">Saldo sugerido da sessão anterior: <strong>{formatBrl(suggested)}</strong>.</p>
      {!canOpen && <p className="notice">Seu perfil pode consultar o financeiro, mas não possui permissão para abrir o caixa.</p>}
    </section>
    {canOpen && <section className="card">
      <h2>Abrir sessão</h2>
      {error && <div role="alert" className="notice error">{error}</div>}
      <div className="field"><label>Saldo físico contado *<input className="input" type="number" min="0" step="0.01" value={counted} onChange={e => setCounted(e.target.value)} /></label></div>
      {divergent && <div className="field"><label>Justificativa da divergência *<input className="input" value={reason} onChange={e => setReason(e.target.value)} /></label></div>}
      <button type="button" className="btn" disabled={Number(counted) < 0 || counted.trim() === '' || (divergent && reason.trim().length < 3) || mutation.isPending} onClick={() => mutation.mutate()}>Abrir caixa</button>
    </section>}
  </div>
}

function OpenSession({ session, movements, movementsLoading, onChange }: {
  session: CashSession; movements: CashMovement[]; movementsLoading: boolean; onChange: () => Promise<unknown>
}) {
  const reversed = new Set(movements.filter(m => m.type === 'REVERSAL' && m.reversedMovementId).map(m => m.reversedMovementId))
  return <div className="stack">
    <section className="card">
      <h2>Sessão aberta <Badge tone="success">Aberta</Badge></h2>
      <dl className="summary-list">
        <div><dt>Aberta em</dt><dd>{formatDate(session.openedAt)}</dd></div>
        <div><dt>Saldo inicial contado</dt><dd>{formatBrl(session.openingCountedBalance)}</dd></div>
        <div><dt>Saldo esperado agora</dt><dd><strong>{formatBrl(session.currentExpectedBalance)}</strong></dd></div>
      </dl>
    </section>
    <div className="detail-grid">
      <div className="stack">
        <MovementList session={session} movements={movements} reversed={reversed} loading={movementsLoading} onChange={onChange} />
      </div>
      <aside className="stack">
        <ManualMovement type="SUPPLY" onChange={onChange} />
        <ManualMovement type="WITHDRAWAL" onChange={onChange} />
        <CloseForm session={session} onChange={onChange} />
      </aside>
    </div>
  </div>
}

function MovementList({ session, movements, reversed, loading, onChange }: {
  session: CashSession; movements: CashMovement[]; reversed: Set<string | null>; loading: boolean; onChange: () => Promise<unknown>
}) {
  const canReverse = useCan(CASH_REVERSAL)
  return <section className="card">
    <h2>Movimentações</h2>
    {loading ? <p className="muted">Carregando movimentações…</p> : !movements.length ? <p className="muted">Nenhuma movimentação nesta sessão.</p> :
      <div className="table-wrap"><table className="table"><thead><tr><th>Horário</th><th>Tipo</th><th>Entrada</th><th>Saída</th><th>Motivo</th><th /></tr></thead>
        <tbody>{movements.map(m => <tr key={m.id}>
          <td>{formatDate(m.recordedAt)}</td><td>{movementLabel[m.type]}</td>
          <td>{m.direction === 'IN' ? formatBrl(m.amount) : '—'}</td><td>{m.direction === 'OUT' ? formatBrl(m.amount) : '—'}</td>
          <td>{m.reason ?? '—'}</td><td>{canReverse && m.type !== 'REVERSAL' && !reversed.has(m.id) && <ReverseMovement movement={m} onChange={onChange} />}</td>
        </tr>)}</tbody></table></div>}
    <p className="muted">Saldo esperado: {formatBrl(session.currentExpectedBalance)}.</p>
  </section>
}

function ManualMovement({ type, onChange }: { type: 'SUPPLY' | 'WITHDRAWAL'; onChange: () => Promise<unknown> }) {
  const permission = type === 'SUPPLY' ? CASH_SUPPLY : CASH_WITHDRAWAL
  const allowed = useCan(permission)
  const [amount, setAmount] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const idempotency = useIdempotencyKey()
  const mutation = useMutation({
    mutationFn: () => type === 'SUPPLY' ? financeApi.cashSupply(Number(amount), reason.trim(), idempotency.key)
      : financeApi.cashWithdrawal(Number(amount), reason.trim(), idempotency.key),
    onSuccess: async () => { idempotency.renew(); setAmount(''); setReason(''); setError(''); await onChange() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível registrar a movimentação'),
  })
  if (!allowed) return null
  return <section className="card">
    <h2>{type === 'SUPPLY' ? 'Suprimento' : 'Sangria'}</h2>
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="field"><label>Valor *<input className="input" type="number" min="0" step="0.01" value={amount} onChange={e => setAmount(e.target.value)} /></label></div>
    <div className="field"><label>Motivo *<input className="input" value={reason} onChange={e => setReason(e.target.value)} /></label></div>
    <button type="button" className="btn secondary" disabled={!validAmount(amount) || reason.trim().length < 3 || mutation.isPending} onClick={() => mutation.mutate()}>Registrar {type === 'SUPPLY' ? 'suprimento' : 'sangria'}</button>
  </section>
}

function CloseForm({ session, onChange }: { session: CashSession; onChange: () => Promise<unknown> }) {
  const allowed = useCan(CASH_SESSION_CLOSE)
  const [counted, setCounted] = useState(String(session.currentExpectedBalance))
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const divergent = Number(counted) !== session.currentExpectedBalance
  const mutation = useMutation({
    mutationFn: () => financeApi.closeCashSession(session.id, Number(counted), reason.trim() || null),
    onSuccess: async () => { setError(''); await onChange() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível fechar o caixa'),
  })
  if (!allowed) return null
  return <section className="card">
    <h2>Fechar e conferir</h2>
    <p className="muted">Saldo esperado: {formatBrl(session.currentExpectedBalance)}.</p>
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="field"><label>Saldo físico contado *<input className="input" type="number" min="0" step="0.01" value={counted} onChange={e => setCounted(e.target.value)} /></label></div>
    {divergent && <div className="field"><label>Justificativa da divergência *<input className="input" value={reason} onChange={e => setReason(e.target.value)} /></label></div>}
    <button type="button" className="btn" disabled={counted.trim() === '' || Number(counted) < 0 || (divergent && reason.trim().length < 3) || mutation.isPending} onClick={() => mutation.mutate()}>Fechar caixa</button>
  </section>
}

function ReverseMovement({ movement, onChange }: { movement: CashMovement; onChange: () => Promise<unknown> }) {
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const idempotency = useIdempotencyKey()
  const mutation = useMutation({
    mutationFn: () => financeApi.reverseCashMovement(movement.id, reason.trim(), idempotency.key),
    onSuccess: async () => { idempotency.renew(); setReason(''); setError(''); await onChange() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível estornar'),
  })
  return <div className="inline-form">
    <input className="input" aria-label={`Motivo do estorno de ${movementLabel[movement.type]}`} placeholder="Motivo do estorno" value={reason} onChange={e => setReason(e.target.value)} />
    <button type="button" className="btn secondary" disabled={reason.trim().length < 3 || mutation.isPending} onClick={() => mutation.mutate()}>Estornar</button>
    {error && <span className="error-text">{error}</span>}
  </div>
}
