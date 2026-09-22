import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  FINANCE_ADJUST, FINANCE_RECEIVE, FINANCE_REVERSE, RECEIVABLE_STATUS_LABELS, financeApi, financeKeys, statusTone, todayIso,
  type FinancialStatus, type Receivable,
} from '../../api/finance'
import { Badge, PageHeader } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { Pagination } from '../../components/controls'
import { formatBrl, formatDate } from '../../utils/format'
import { PaymentMethodSelect } from './common'
import { formatDay, useCan, useIdempotencyKey, validAmount } from './hooks'

export function ReceivablesPage() {
  const [status, setStatus] = useState('')
  const [number, setNumber] = useState('')
  const [page, setPage] = useState(0)
  const filter = { status: status || undefined, workOrderNumber: number.trim() || undefined, page }
  const list = useQuery({ queryKey: [...financeKeys.receivables, filter], queryFn: () => financeApi.receivables(filter), placeholderData: keepPreviousData })

  return <>
    <PageHeader title="Contas a receber" subtitle="Recebíveis gerados na finalização da OS, pelo valor aprovado no orçamento de faturamento." />
    <section className="card filters">
      <div className="inline-form">
        <div className="field"><label>Situação
          <select className="select" value={status} onChange={event => { setStatus(event.target.value); setPage(0) }}>
            <option value="">Todas</option>
            {Object.entries(RECEIVABLE_STATUS_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select></label></div>
        <div className="field"><label>Número da OS
          <input className="input" inputMode="numeric" value={number} onChange={event => { setNumber(event.target.value.replace(/\D/g, '')); setPage(0) }} />
        </label></div>
      </div>
    </section>
    <QueryState label="os recebíveis" loading={list.isLoading} error={list.error} retry={list.refetch}
                empty={!list.data?.items.length} emptyMessage="Nenhum recebível encontrado.">
      <div className="card table-wrap"><table className="table">
        <thead><tr><th>OS</th><th>Cliente</th><th>Vencimento</th><th>Valor original</th><th>Recebido</th><th>Saldo</th><th>Situação</th><th /></tr></thead>
        <tbody>{list.data?.items.map(item => <tr key={item.id}>
          <td>#{item.workOrderNumber}</td>
          <td>{item.customerName ?? '—'}</td>
          <td>{formatDay(item.dueDate)}</td>
          <td>{formatBrl(item.originalAmount)}</td>
          <td>{formatBrl(item.receivedAmount)}</td>
          <td>{formatBrl(item.outstandingBalance)}</td>
          <td><Badge tone={statusTone(item.status)}>{RECEIVABLE_STATUS_LABELS[item.status]}</Badge></td>
          <td><Link className="btn secondary" to={`/financeiro/recebiveis/${item.id}`}>Abrir</Link></td>
        </tr>)}</tbody>
      </table></div>
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onChange={setPage} />}
    </QueryState>
  </>
}

export function ReceivableDetailPage() {
  const { id = '' } = useParams()
  const client = useQueryClient()
  const receivable = useQuery({ queryKey: financeKeys.receivable(id), queryFn: () => financeApi.receivable(id) })
  const refresh = () => Promise.all([
    client.invalidateQueries({ queryKey: financeKeys.receivable(id) }), client.invalidateQueries({ queryKey: financeKeys.receivables }),
  ])

  return <QueryState label="o recebível" loading={receivable.isLoading} error={receivable.error} retry={receivable.refetch}>
    {receivable.data && <ReceivableView receivable={receivable.data} onChange={refresh} />}
  </QueryState>
}

function ReceivableView({ receivable: r, onChange }: { receivable: Receivable; onChange: () => Promise<unknown> }) {
  const canReceive = useCan(FINANCE_RECEIVE)
  const canReverse = useCan(FINANCE_REVERSE)
  const canAdjust = useCan(FINANCE_ADJUST)
  const open = r.status !== 'CANCELADO' && r.outstandingBalance > 0
  return <>
    <PageHeader title={`Recebível da OS #${r.workOrderNumber}`} subtitle="Valor original congelado na finalização; descontos, acréscimos e saldo derivam dos lançamentos.">
      <Link className="btn secondary" to={`/ordens-servico/${r.workOrderId}`}>Abrir OS</Link>
      <Link className="btn secondary" to="/financeiro/recebiveis">Voltar</Link>
    </PageHeader>
    <div className="detail-grid">
      <div className="stack">
        <section className="card" aria-label="Resumo do recebível">
          <h2>Resumo <Badge tone={statusTone(r.status)}>{RECEIVABLE_STATUS_LABELS[r.status as FinancialStatus]}</Badge></h2>
          <dl className="summary-list">
            <div><dt>Cliente</dt><dd>{r.customerName ?? '—'}</dd></div>
            <div><dt>Valor original</dt><dd data-testid="original-amount">{formatBrl(r.originalAmount)}</dd></div>
            <div><dt>Descontos</dt><dd>{formatBrl(r.discountAmount)}</dd></div>
            <div><dt>Acréscimos</dt><dd>{formatBrl(r.surchargeAmount)}</dd></div>
            <div><dt>Valor ajustado</dt><dd>{formatBrl(r.adjustedAmount)}</dd></div>
            <div><dt>Recebido</dt><dd>{formatBrl(r.receivedAmount)}</dd></div>
            <div><dt>Saldo em aberto</dt><dd data-testid="outstanding-balance">{formatBrl(r.outstandingBalance)}</dd></div>
            <div><dt>Emissão</dt><dd>{formatDay(r.issuedOn)}</dd></div>
            <div><dt>Vencimento</dt><dd>{formatDay(r.dueDate)}</dd></div>
            <div><dt>Orçamento de faturamento</dt><dd>
              <Link to={`/ordens-servico/${r.workOrderId}/orcamentos/${r.billingQuoteId}`}>Ver orçamento</Link></dd></div>
          </dl>
          {r.cancelledAt && <p className="notice">Cancelado em {formatDate(r.cancelledAt)}: {r.cancellationReason}</p>}
        </section>
        <section className="card">
          <h2>Itens aprovados cobrados</h2>
          <div className="table-wrap"><table className="table">
            <thead><tr><th>Descrição</th><th>Qtd.</th><th>Preço unit.</th><th>Desconto</th><th>Total</th></tr></thead>
            <tbody>{r.lines.map(line => <tr key={line.id}>
              <td>{line.description}</td><td>{Number(line.quantity)}</td><td>{formatBrl(line.unitPrice)}</td>
              <td>{formatBrl(line.discountAmount)}</td><td>{formatBrl(line.totalAmount)}</td>
            </tr>)}</tbody>
          </table></div>
        </section>
        <section className="card">
          <h2>Recebimentos</h2>
          {!r.receipts.length ? <p className="muted">Nenhum recebimento registrado.</p> : <div className="table-wrap"><table className="table">
            <thead><tr><th>Data</th><th>Forma</th><th>Valor</th><th>Situação</th><th /></tr></thead>
            <tbody>{r.receipts.map(receipt => <tr key={receipt.id}>
              <td>{formatDay(receipt.effectiveOn)}</td><td>{receipt.paymentMethodName}</td><td>{formatBrl(receipt.amount)}</td>
              <td>{receipt.reversal ? `Estornado: ${receipt.reversal.reason}` : 'Efetivo'}</td>
              <td>{canReverse && !receipt.reversal && <ReverseAction onReverse={(reason, key) => financeApi.reverseReceipt(receipt.id, reason, key)} onDone={onChange} />}</td>
            </tr>)}</tbody>
          </table></div>}
        </section>
        {(r.adjustments.length > 0 || r.dueDateChanges.length > 0) && <section className="card">
          <h2>Histórico financeiro</h2>
          <ul className="history">
            {r.adjustments.map(a => <li key={a.id}>{formatDate(a.recordedAt)} — {a.type === 'DISCOUNT' ? 'Desconto' : 'Acréscimo'} de {formatBrl(a.amount)}: {a.reason}</li>)}
            {r.dueDateChanges.map(c => <li key={c.id}>{formatDate(c.changedAt)} — vencimento de {formatDay(c.previousDueDate)} para {formatDay(c.newDueDate)}: {c.reason}</li>)}
          </ul>
        </section>}
      </div>
      <aside className="stack">
        {canReceive && open && <ReceiptForm receivable={r} onDone={onChange} />}
        {canAdjust && r.status !== 'CANCELADO' && <AdjustmentForm receivable={r} onDone={onChange} />}
        {canAdjust && open && <DueDateForm receivable={r} onDone={onChange} />}
      </aside>
    </div>
  </>
}

function ReceiptForm({ receivable, onDone }: { receivable: Receivable; onDone: () => Promise<unknown> }) {
  const [amount, setAmount] = useState(String(receivable.outstandingBalance))
  const [method, setMethod] = useState('')
  const [date, setDate] = useState(todayIso())
  const [notes, setNotes] = useState('')
  const [error, setError] = useState('')
  const idempotency = useIdempotencyKey()
  const exceeds = validAmount(amount) && Number(amount) > receivable.outstandingBalance
  const mutation = useMutation({
    mutationFn: () => financeApi.receive(receivable.id, { amount: Number(amount), paymentMethodId: method, receivedOn: date, notes: notes.trim() || null }, idempotency.key),
    onSuccess: async () => { idempotency.renew(); setNotes(''); setError(''); await onDone() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível registrar o recebimento'),
  })
  return <section className="card" aria-label="Registrar recebimento">
    <h2>Registrar recebimento</h2>
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="field"><label>Valor *<input className="input" type="number" step="0.01" min="0" value={amount} onChange={e => setAmount(e.target.value)} /></label>
      {exceeds && <span className="error-text">Maior que o saldo em aberto de {formatBrl(receivable.outstandingBalance)}.</span>}</div>
    <div className="field"><PaymentMethodSelect value={method} onChange={setMethod} /></div>
    <div className="field"><label>Data do recebimento *<input className="input" type="date" max={todayIso()} value={date} onChange={e => setDate(e.target.value)} /></label></div>
    <div className="field"><label>Observação<input className="input" value={notes} onChange={e => setNotes(e.target.value)} /></label></div>
    <button type="button" className="btn" style={{ width: '100%', marginTop: 12 }}
            disabled={!validAmount(amount) || exceeds || !method || !date || mutation.isPending} onClick={() => mutation.mutate()}>
      Registrar recebimento
    </button>
  </section>
}

function AdjustmentForm({ receivable, onDone }: { receivable: Receivable; onDone: () => Promise<unknown> }) {
  const [type, setType] = useState<'DISCOUNT' | 'SURCHARGE'>('DISCOUNT')
  const [amount, setAmount] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const idempotency = useIdempotencyKey()
  const mutation = useMutation({
    mutationFn: () => financeApi.adjust(receivable.id, { type, amount: Number(amount), reason: reason.trim() }, idempotency.key),
    onSuccess: async () => { idempotency.renew(); setAmount(''); setReason(''); setError(''); await onDone() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível registrar o ajuste'),
  })
  return <section className="card" aria-label="Desconto ou acréscimo">
    <h2>Desconto ou acréscimo</h2>
    <p className="muted">O valor original não muda; o ajuste fica registrado com motivo, autor e instante.</p>
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="field"><label>Tipo<select className="select" value={type} onChange={e => setType(e.target.value as 'DISCOUNT' | 'SURCHARGE')}>
      <option value="DISCOUNT">Desconto</option><option value="SURCHARGE">Acréscimo</option></select></label></div>
    <div className="field"><label>Valor do ajuste *<input className="input" type="number" step="0.01" min="0" value={amount} onChange={e => setAmount(e.target.value)} /></label></div>
    <div className="field"><label>Motivo do ajuste *<input className="input" value={reason} onChange={e => setReason(e.target.value)} /></label></div>
    <button type="button" className="btn" style={{ width: '100%', marginTop: 12 }}
            disabled={!validAmount(amount) || reason.trim().length < 3 || mutation.isPending} onClick={() => mutation.mutate()}>
      Registrar ajuste
    </button>
  </section>
}

function DueDateForm({ receivable, onDone }: { receivable: Receivable; onDone: () => Promise<unknown> }) {
  const [date, setDate] = useState(receivable.dueDate)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const mutation = useMutation({
    mutationFn: () => financeApi.changeDueDate(receivable.id, date, reason.trim()),
    onSuccess: async () => { setReason(''); setError(''); await onDone() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível alterar o vencimento'),
  })
  return <section className="card" aria-label="Alterar vencimento">
    <h2>Alterar vencimento</h2>
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="field"><label>Novo vencimento *<input className="input" type="date" value={date} onChange={e => setDate(e.target.value)} /></label></div>
    <div className="field"><label>Motivo da alteração *<input className="input" value={reason} onChange={e => setReason(e.target.value)} /></label></div>
    <button type="button" className="btn secondary" style={{ width: '100%', marginTop: 12 }}
            disabled={!date || date === receivable.dueDate || reason.trim().length < 3 || mutation.isPending} onClick={() => mutation.mutate()}>
      Alterar vencimento
    </button>
  </section>
}

/** Estorno total com motivo, em dois passos; a chave de idempotência protege o duplo clique. */
export function ReverseAction({ onReverse, onDone }: { onReverse: (reason: string, key: string) => Promise<unknown>; onDone: () => Promise<unknown> }) {
  const [asking, setAsking] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const idempotency = useIdempotencyKey()
  const mutation = useMutation({
    mutationFn: () => onReverse(reason.trim(), idempotency.key),
    onSuccess: async () => { idempotency.renew(); setAsking(false); setReason(''); await onDone() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível estornar'),
  })
  if (!asking) return <button type="button" className="btn secondary" onClick={() => setAsking(true)}>Estornar</button>
  return <div role="group" aria-label="Confirmar estorno">
    {error && <div role="alert" className="notice error">{error}</div>}
    <input className="input" aria-label="Motivo do estorno" placeholder="Motivo do estorno" value={reason} onChange={e => setReason(e.target.value)} />
    <button type="button" className="btn danger" disabled={reason.trim().length < 3 || mutation.isPending} onClick={() => mutation.mutate()}>Confirmar estorno</button>
    <button type="button" className="btn secondary" onClick={() => setAsking(false)}>Voltar</button>
  </div>
}
