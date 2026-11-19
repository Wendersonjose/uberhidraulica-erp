import { useState } from 'react'
import { Link } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FINANCE_RECONCILE, financeApi, financeKeys, todayIso, type ReconciliationEntry } from '../../api/finance'
import { Badge, PageHeader } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { formatBrl } from '../../utils/format'
import { formatDay, useCan, useIdempotencyKey, validAmount } from './hooks'
import { firstOfMonth } from './reportsUtils'

/**
 * Conciliação manual (DR-0024, Q7): cada recebimento ou pagamento é marcado contra a linha do extrato. Diferença exige
 * motivo; o mesmo lançamento e a mesma referência de extrato não se repetem. Não há importação de OFX ou CSV.
 */
export function ReconciliationPage() {
  const canReconcile = useCan(FINANCE_RECONCILE)
  const [from, setFrom] = useState(firstOfMonth)
  const [to, setTo] = useState(todayIso)
  const [reconciled, setReconciled] = useState('false')
  const [type, setType] = useState('')
  const valid = Boolean(from && to && from <= to)
  const accounts = useQuery({ queryKey: financeKeys.accounts, queryFn: financeApi.accounts })
  const filter = { from, to, reconciled: reconciled || undefined, type: type || undefined }
  const entries = useQuery({ queryKey: [...financeKeys.reconciliation, filter], queryFn: () => financeApi.reconciliationEntries(filter),
    enabled: valid, placeholderData: keepPreviousData })
  const active = accounts.data?.filter(a => a.active) ?? []
  return <>
    <PageHeader title="Conciliação" subtitle="Confira cada recebimento e pagamento com o extrato. O valor esperado do cartão é o líquido da taxa." />
    <section className="card filters"><div className="inline-form">
      <div className="field"><label>De<input className="input" type="date" value={from} onChange={e => setFrom(e.target.value)} /></label></div>
      <div className="field"><label>Até<input className="input" type="date" value={to} onChange={e => setTo(e.target.value)} /></label></div>
      <div className="field"><label>Situação<select className="select" value={reconciled} onChange={e => setReconciled(e.target.value)}>
        <option value="false">Pendentes</option><option value="true">Conciliados</option><option value="">Todos</option></select></label></div>
      <div className="field"><label>Tipo<select className="select" value={type} onChange={e => setType(e.target.value)}>
        <option value="">Entradas e saídas</option><option value="RECEIPT">Recebimentos</option><option value="PAYMENT">Pagamentos</option></select></label></div>
    </div>
      {!valid && <div role="alert" className="notice error">O período final não pode ser anterior ao inicial.</div>}
    </section>
    {accounts.data && !active.length && <div role="status" className="notice">
      Nenhuma conta financeira ativa. Cadastre o banco ou o cartão em <Link to="/financeiro/configuracoes">Financeiro › Configurações</Link>.
    </div>}
    <QueryState label="os lançamentos" loading={entries.isLoading} error={entries.error} retry={entries.refetch}
                empty={!entries.data?.length} emptyMessage="Nenhum lançamento no período.">
      <div className="card table-wrap"><table className="table">
        <thead><tr><th>Data</th><th>Lançamento</th><th>Forma</th><th>Valor</th><th>Esperado no extrato</th><th>Conciliação</th></tr></thead>
        <tbody>{entries.data?.map(e => <EntryRow key={`${e.type}-${e.id}`} entry={e} accounts={active} canReconcile={canReconcile} />)}</tbody>
      </table></div>
    </QueryState>
  </>
}

function EntryRow({ entry: e, accounts, canReconcile }: { entry: ReconciliationEntry; accounts: { id: string; name: string }[]; canReconcile: boolean }) {
  const [open, setOpen] = useState(false)
  const r = e.reconciliation
  return <tr data-testid={`entry-${e.id}`}>
    <td>{formatDay(e.effectiveOn)}</td>
    <td>{e.type === 'RECEIPT' ? 'Recebimento' : 'Pagamento'} · {e.ownerLabel}</td>
    <td>{e.paymentMethodName}</td>
    <td>{e.type === 'PAYMENT' ? '−' : ''}{formatBrl(e.amount)}</td>
    <td>{formatBrl(e.netAmount)}{e.expectedSettlementOn && <div className="muted">repasse {formatDay(e.expectedSettlementOn)}</div>}</td>
    <td>{r ? <>
      <Badge tone={r.difference === 0 ? 'success' : 'warning'}>Conciliado</Badge>
      <div className="muted">{e.accountName} · {r.statementReference} · {formatDay(r.statementDate)}</div>
      {r.difference !== 0 && <div className="muted">diferença {formatBrl(r.difference)}: {r.differenceReason}</div>}
      {canReconcile && <UndoReconciliation id={r.id} />}
    </> : canReconcile && accounts.length > 0
      ? (open ? <ReconcileForm entry={e} accounts={accounts} onClose={() => setOpen(false)} />
        : <button type="button" className="btn secondary" onClick={() => setOpen(true)}>Conciliar</button>)
      : <Badge tone="info">Pendente</Badge>}</td>
  </tr>
}

function ReconcileForm({ entry, accounts, onClose }: { entry: ReconciliationEntry; accounts: { id: string; name: string }[]; onClose: () => void }) {
  const client = useQueryClient()
  const [accountId, setAccountId] = useState(accounts[0]?.id ?? '')
  const [date, setDate] = useState(entry.expectedSettlementOn ?? entry.effectiveOn)
  const [amount, setAmount] = useState(entry.netAmount.toFixed(2))
  const [reference, setReference] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const idempotency = useIdempotencyKey()
  const differs = validAmount(amount) && Math.round(Number(amount) * 100) !== Math.round(entry.netAmount * 100)
  const mutation = useMutation({
    mutationFn: () => financeApi.reconcile({ accountId, entryType: entry.type, entryId: entry.id, statementDate: date, statementAmount: Number(amount),
      statementReference: reference.trim(), differenceReason: differs ? reason.trim() : null }, idempotency.key),
    onSuccess: async () => { idempotency.renew(); onClose(); await client.invalidateQueries({ queryKey: financeKeys.reconciliation }) },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível conciliar'),
  })
  return <div role="group" aria-label="Conciliar lançamento" className="stack">
    {error && <div role="alert" className="notice error">{error}</div>}
    <label>Conta<select className="select" value={accountId} onChange={e => setAccountId(e.target.value)}>
      {accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>
    <label>Data no extrato<input className="input" type="date" value={date} onChange={e => setDate(e.target.value)} /></label>
    <label>Valor no extrato<input className="input" type="number" step="0.01" min="0" value={amount} onChange={e => setAmount(e.target.value)} /></label>
    <label>Identificador no extrato<input className="input" value={reference} onChange={e => setReference(e.target.value)} /></label>
    {differs && <label>Motivo da diferença *<input className="input" value={reason} onChange={e => setReason(e.target.value)} /></label>}
    <div className="actions">
      <button type="button" className="btn" disabled={!accountId || !date || !validAmount(amount) || !reference.trim()
        || (differs && reason.trim().length < 3) || mutation.isPending} onClick={() => mutation.mutate()}>Confirmar conciliação</button>
      <button type="button" className="btn secondary" onClick={onClose}>Voltar</button>
    </div>
  </div>
}

function UndoReconciliation({ id }: { id: string }) {
  const client = useQueryClient()
  const [asking, setAsking] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const idempotency = useIdempotencyKey()
  const mutation = useMutation({
    mutationFn: () => financeApi.reverseReconciliation(id, reason.trim(), idempotency.key),
    onSuccess: async () => { idempotency.renew(); setAsking(false); await client.invalidateQueries({ queryKey: financeKeys.reconciliation }) },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível desfazer'),
  })
  if (!asking) return <button type="button" className="btn secondary" onClick={() => setAsking(true)}>Desfazer</button>
  return <div role="group" aria-label="Desfazer conciliação">
    {error && <div role="alert" className="notice error">{error}</div>}
    <input className="input" aria-label="Motivo para desfazer" value={reason} onChange={e => setReason(e.target.value)} />
    <button type="button" className="btn danger" disabled={reason.trim().length < 3 || mutation.isPending} onClick={() => mutation.mutate()}>Confirmar</button>
    <button type="button" className="btn secondary" onClick={() => setAsking(false)}>Voltar</button>
  </div>
}
