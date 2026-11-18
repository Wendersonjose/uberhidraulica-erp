import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  FINANCE_ADJUST, FINANCE_RECEIVABLE_MANUAL, FINANCE_RECEIVE, FINANCE_REVERSE, RECEIVABLE_STATUS_LABELS, financeApi, financeKeys,
  statusTone, todayIso, type FinancialStatus, type PaymentMethod, type Receivable, type ReceivableSummary,
} from '../../api/finance'
import { customersApi } from '../../api/resources'
import { Badge, PageHeader } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { Pagination } from '../../components/controls'
import { formatBrl, formatDate } from '../../utils/format'
import { PaymentMethodSelect } from './common'
import { formatDay, useCan, useIdempotencyKey, validAmount } from './hooks'
import { LateChargeCard } from './LateCharge'
import { splitEqually, addMonths } from './installments'

const originLabel = (r: Pick<ReceivableSummary, 'origin' | 'workOrderNumber' | 'description'>) =>
  r.origin === 'MANUAL' ? (r.description ?? 'Avulso') : `OS #${r.workOrderNumber}`

export function ReceivablesPage() {
  const [status, setStatus] = useState('')
  const [number, setNumber] = useState('')
  const [origin, setOrigin] = useState('')
  const [page, setPage] = useState(0)
  const canCreate = useCan(FINANCE_RECEIVABLE_MANUAL)
  const filter = { status: status || undefined, workOrderNumber: number.trim() || undefined, origin: origin || undefined, page }
  const list = useQuery({ queryKey: [...financeKeys.receivables, filter], queryFn: () => financeApi.receivables(filter), placeholderData: keepPreviousData })

  return <>
    <PageHeader title="Contas a receber" subtitle="Recebíveis das OS, pelo valor aprovado no orçamento, e contas avulsas lançadas no Financeiro.">
      {canCreate && <Link className="btn" to="/financeiro/recebiveis/nova">Nova conta a receber</Link>}
    </PageHeader>
    <section className="card filters">
      <div className="inline-form">
        <div className="field"><label>Situação
          <select className="select" value={status} onChange={event => { setStatus(event.target.value); setPage(0) }}>
            <option value="">Todas</option>
            {Object.entries(RECEIVABLE_STATUS_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select></label></div>
        <div className="field"><label>Origem
          <select className="select" value={origin} onChange={event => { setOrigin(event.target.value); setPage(0) }}>
            <option value="">Todas</option><option value="WORK_ORDER">Ordem de serviço</option><option value="MANUAL">Avulsa</option>
          </select></label></div>
        <div className="field"><label>Número da OS
          <input className="input" inputMode="numeric" value={number} onChange={event => { setNumber(event.target.value.replace(/\D/g, '')); setPage(0) }} />
        </label></div>
      </div>
    </section>
    <QueryState label="os recebíveis" loading={list.isLoading} error={list.error} retry={list.refetch}
                empty={!list.data?.items.length} emptyMessage="Nenhum recebível encontrado.">
      <div className="card table-wrap"><table className="table">
        <thead><tr><th>Origem</th><th>Cliente</th><th>Vencimento</th><th>Valor original</th><th>Recebido</th><th>Saldo</th><th>Situação</th><th /></tr></thead>
        <tbody>{list.data?.items.map(item => <tr key={item.id}>
          <td>{originLabel(item)}{(item.installmentCount ?? 1) > 1 && <div className="muted">{item.installmentCount} parcelas</div>}</td>
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

/** Conta a receber sem OS (DR-0024, Q2): venda de balcão, reembolso etc. */
export function ReceivableFormPage() {
  const navigate = useNavigate()
  const client = useQueryClient()
  const categories = useQuery({ queryKey: financeKeys.revenueCategories, queryFn: financeApi.revenueCategories })
  const [search, setSearch] = useState('')
  const customers = useQuery({ queryKey: ['finance', 'manual-receivable', 'customers', search], queryFn: () => customersApi.search({ q: search, status: 'ACTIVE', size: 10 }),
    enabled: search.trim().length >= 2 })
  const [form, setForm] = useState({ customerId: '', customerName: '', description: '', revenueCategoryId: '', amount: '', dueDate: todayIso(), installments: '1', notes: '' })
  const [error, setError] = useState('')
  const idempotency = useIdempotencyKey()
  const set = (patch: Partial<typeof form>) => setForm({ ...form, ...patch })
  const count = Number(form.installments)
  const mutation = useMutation({
    mutationFn: () => financeApi.createManualReceivable({ customerId: form.customerId, description: form.description.trim(),
      revenueCategoryId: form.revenueCategoryId, amount: Number(form.amount), dueDate: form.dueDate, notes: form.notes.trim() || null,
      installments: count }, idempotency.key),
    onSuccess: async receivable => { await client.invalidateQueries({ queryKey: financeKeys.receivables }); navigate(`/financeiro/recebiveis/${receivable.id}`) },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível lançar a conta a receber'),
  })
  const active = categories.data?.filter(c => c.active) ?? []
  const valid = form.customerId && form.description.trim().length >= 2 && form.revenueCategoryId && validAmount(form.amount) && form.dueDate
    && Number.isInteger(count) && count >= 1 && count <= 60
  return <>
    <PageHeader title="Nova conta a receber" subtitle="Lançamento avulso, sem OS. Com mais de uma parcela, os centavos que sobram vão para a última." />
    <section className="card">
      {error && <div role="alert" className="notice error">{error}</div>}
      <div className="form-grid">
        <div className="field full"><label>Cliente *
          {form.customerId
            ? <div className="inline-form"><strong>{form.customerName}</strong>
                <button type="button" className="btn secondary" onClick={() => set({ customerId: '', customerName: '' })}>Trocar</button></div>
            : <input className="input" placeholder="Digite ao menos 2 letras do nome" value={search} onChange={e => setSearch(e.target.value)} />}
        </label>
          {!form.customerId && customers.data && <ul className="history" aria-label="Clientes encontrados">
            {customers.data.items.map(c => <li key={c.id}><button type="button" className="btn secondary"
              onClick={() => set({ customerId: c.id, customerName: c.name })}>{c.name}</button></li>)}
            {!customers.data.items.length && <li className="muted">Nenhum cliente ativo encontrado.</li>}
          </ul>}
        </div>
        <div className="field full"><label>Descrição *<input className="input" value={form.description} onChange={e => set({ description: e.target.value })} /></label></div>
        <div className="field"><label>Categoria de receita *<select className="select" value={form.revenueCategoryId} onChange={e => set({ revenueCategoryId: e.target.value })}>
          <option value="">Selecione</option>
          {active.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select></label></div>
        {categories.data && !active.length && <div role="status" className="notice full">
          Nenhuma categoria de receita ativa. Cadastre uma em <Link to="/financeiro/configuracoes">Financeiro › Configurações</Link>.
        </div>}
        <div className="field"><label>Valor *<input className="input" type="number" step="0.01" min="0" value={form.amount} onChange={e => set({ amount: e.target.value })} /></label></div>
        <div className="field"><label>Vencimento *<input className="input" type="date" min={todayIso()} value={form.dueDate} onChange={e => set({ dueDate: e.target.value })} /></label></div>
        <div className="field"><label>Parcelas<input className="input" type="number" min="1" max="60" value={form.installments} onChange={e => set({ installments: e.target.value })} /></label></div>
        {validAmount(form.amount) && count > 1 && count <= 60 && /^\d{4}-\d{2}-\d{2}$/.test(form.dueDate) && <div className="notice full" role="status" data-testid="manual-split">
          {splitEqually(Number(form.amount), count).map((amount, i) => `${i + 1}ª ${formatBrl(amount)} em ${formatDay(addMonths(form.dueDate, i))}`).join(' · ')}
        </div>}
        <div className="field full"><label>Observação<input className="input" value={form.notes} onChange={e => set({ notes: e.target.value })} /></label></div>
      </div>
      <div className="form-actions">
        <button type="button" className="btn secondary" onClick={() => navigate('/financeiro/recebiveis')}>Cancelar</button>
        <button type="button" className="btn" disabled={!valid || mutation.isPending} onClick={() => mutation.mutate()}>Lançar conta a receber</button>
      </div>
    </section>
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
  const canManual = useCan(FINANCE_RECEIVABLE_MANUAL)
  const manual = r.origin === 'MANUAL'
  const open = r.status !== 'CANCELADO' && r.outstandingBalance > 0
  const installments = r.installmentPlan?.installments ?? []
  return <>
    <PageHeader title={manual ? `Conta a receber: ${r.description}` : `Recebível da OS #${r.workOrderNumber}`}
                subtitle={manual ? 'Lançamento avulso; o valor original não muda, e descontos, acréscimos e saldo derivam dos lançamentos.'
                  : 'Valor original congelado na finalização; descontos, acréscimos e saldo derivam dos lançamentos.'}>
      {!manual && r.workOrderId && <Link className="btn secondary" to={`/ordens-servico/${r.workOrderId}`}>Abrir OS</Link>}
      <Link className="btn secondary" to="/financeiro/recebiveis">Voltar</Link>
    </PageHeader>
    <div className="detail-grid">
      <div className="stack">
        <section className="card" aria-label="Resumo do recebível">
          <h2>Resumo <Badge tone={statusTone(r.status)}>{RECEIVABLE_STATUS_LABELS[r.status as FinancialStatus]}</Badge></h2>
          <dl className="summary-list">
            <div><dt>Cliente</dt><dd>{r.customerName ?? '—'}</dd></div>
            {manual && <div><dt>Categoria de receita</dt><dd>{r.revenueCategoryName ?? '—'}</dd></div>}
            <div><dt>Valor original</dt><dd data-testid="original-amount">{formatBrl(r.originalAmount)}</dd></div>
            <div><dt>Descontos</dt><dd>{formatBrl(r.discountAmount)}</dd></div>
            <div><dt>Acréscimos</dt><dd>{formatBrl(r.surchargeAmount)}</dd></div>
            <div><dt>Valor ajustado</dt><dd>{formatBrl(r.adjustedAmount)}</dd></div>
            <div><dt>Recebido</dt><dd>{formatBrl(r.receivedAmount)}</dd></div>
            <div><dt>Saldo em aberto</dt><dd data-testid="outstanding-balance">{formatBrl(r.outstandingBalance)}</dd></div>
            <div><dt>Emissão</dt><dd>{formatDay(r.issuedOn)}</dd></div>
            <div><dt>Vencimento</dt><dd data-testid="current-due-date">{formatDay(r.dueDate)}</dd></div>
            {!manual && r.workOrderId && r.billingQuoteId && <div><dt>Orçamento de faturamento</dt><dd>
              <Link to={`/ordens-servico/${r.workOrderId}/orcamentos/${r.billingQuoteId}`}>Ver orçamento</Link></dd></div>}
          </dl>
          {r.notes && <p className="muted">{r.notes}</p>}
          {r.cancelledAt && <p className="notice">Cancelado em {formatDate(r.cancelledAt)}: {r.cancellationReason}</p>}
        </section>
        {installments.length > 0 && <section className="card" aria-label="Parcelas">
          <h2>Parcelas{r.installmentPlan && r.installmentPlan.version > 0 ? ` · plano ${r.installmentPlan.version}` : ''}</h2>
          {r.installmentPlan?.reason && <p className="muted">{r.installmentPlan.reason}</p>}
          <div className="table-wrap"><table className="table">
            <thead><tr><th>Parcela</th><th>Vencimento</th><th>Valor</th><th>Em aberto</th><th>Situação</th></tr></thead>
            <tbody>{installments.map(i => <tr key={i.number} data-testid={`installment-${i.number}`}>
              <td>{i.number}/{installments.length}</td><td>{formatDay(i.dueDate)}</td><td>{formatBrl(i.amount)}</td><td>{formatBrl(i.openAmount)}</td>
              <td><Badge tone={statusTone(i.status)}>{RECEIVABLE_STATUS_LABELS[i.status]}</Badge></td>
            </tr>)}</tbody>
          </table></div>
          {(r.surchargeOutstanding ?? 0) > 0 && <p className="muted">Acréscimos em aberto, fora das parcelas: {formatBrl(r.surchargeOutstanding ?? 0)}.</p>}
        </section>}
        {!manual && <section className="card">
          <h2>Itens aprovados cobrados</h2>
          <div className="table-wrap"><table className="table">
            <thead><tr><th>Descrição</th><th>Qtd.</th><th>Preço unit.</th><th>Desconto</th><th>Total</th></tr></thead>
            <tbody>{r.lines.map(line => <tr key={line.id}>
              <td>{line.description}</td><td>{Number(line.quantity)}</td><td>{formatBrl(line.unitPrice)}</td>
              <td>{formatBrl(line.discountAmount)}</td><td>{formatBrl(line.totalAmount)}</td>
            </tr>)}</tbody>
          </table></div>
        </section>}
        <section className="card">
          <h2>Recebimentos</h2>
          {!r.receipts.length ? <p className="muted">Nenhum recebimento registrado.</p> : <div className="table-wrap"><table className="table">
            <thead><tr><th>Data</th><th>Forma</th><th>Valor</th><th>Taxa</th><th>Situação</th><th /></tr></thead>
            <tbody>{r.receipts.map(receipt => <tr key={receipt.id}>
              <td>{formatDay(receipt.effectiveOn)}</td>
              <td>{receipt.paymentMethodName}{receipt.cardFee && receipt.cardFee.installments > 1 ? ` · ${receipt.cardFee.installments}x` : ''}</td>
              <td>{formatBrl(receipt.amount)}</td>
              <td>{receipt.cardFee ? <>{formatBrl(receipt.cardFee.feeAmount)}<div className="muted">líquido {formatBrl(receipt.netAmount ?? receipt.amount)}
                · repasse {formatDay(receipt.cardFee.expectedSettlementOn)}</div></> : '—'}</td>
              <td>{receipt.reversal ? `Estornado: ${receipt.reversal.reason}` : 'Efetivo'}</td>
              <td>{canReverse && !receipt.reversal && <ReverseAction onReverse={(reason, key) => financeApi.reverseReceipt(receipt.id, reason, key)} onDone={onChange} />}</td>
            </tr>)}</tbody>
          </table></div>}
        </section>
        {(r.adjustments.length > 0 || r.dueDateChanges.length > 0) && <section className="card">
          <h2>Histórico financeiro</h2>
          <ul className="history">
            {r.adjustments.map(a => <li key={a.id}>
              {formatDate(a.recordedAt)} — {a.type === 'DISCOUNT' ? 'Desconto' : a.origin === 'LATE_CHARGE' ? 'Juros e multa' : 'Acréscimo'} de {formatBrl(a.amount)}: {a.reason}
              {a.reversal && <span className="muted"> · estornado em {formatDate(a.reversal.reversedAt)}: {a.reversal.reason}</span>}
              {canReverse && !a.reversal && r.status !== 'CANCELADO' &&
                <ReverseAction label="Estornar ajuste" onReverse={(reason, key) => financeApi.reverseAdjustment(a.id, reason, key)} onDone={onChange} />}
            </li>)}
            {r.dueDateChanges.map(c => <li key={c.id}>{formatDate(c.changedAt)} — vencimento{c.installmentNumber ? ` da parcela ${c.installmentNumber}` : ''} de{' '}
              {formatDay(c.previousDueDate)} para {formatDay(c.newDueDate)}: {c.reason}</li>)}
          </ul>
        </section>}
      </div>
      <aside className="stack">
        {open && <LateChargeCard receivable={r} canDecide={canAdjust} onDone={onChange} />}
        {canReceive && open && <ReceiptForm receivable={r} onDone={onChange} />}
        {canAdjust && r.installmentPlan?.editable && <InstallmentPlanForm receivable={r} onDone={onChange} />}
        {canAdjust && r.status !== 'CANCELADO' && <AdjustmentForm receivable={r} onDone={onChange} />}
        {canAdjust && open && <DueDateForm receivable={r} onDone={onChange} />}
        {canManual && manual && r.status !== 'CANCELADO' && <CancelManual receivable={r} onDone={onChange} />}
      </aside>
    </div>
  </>
}

function ReceiptForm({ receivable, onDone }: { receivable: Receivable; onDone: () => Promise<unknown> }) {
  const [amount, setAmount] = useState(String(receivable.outstandingBalance))
  const [method, setMethod] = useState('')
  const [selected, setSelected] = useState<PaymentMethod | undefined>()
  const [installments, setInstallments] = useState('1')
  const [date, setDate] = useState(todayIso())
  const [notes, setNotes] = useState('')
  const [error, setError] = useState('')
  const idempotency = useIdempotencyKey()
  const fees = useQuery({ queryKey: financeKeys.fees(method), queryFn: () => financeApi.fees(method), enabled: Boolean(method) && !selected?.cashSessionRequired })
  const fee = fees.data?.find(f => f.installments === Number(installments))
  const exceeds = validAmount(amount) && Number(amount) > receivable.outstandingBalance
  const mutation = useMutation({
    mutationFn: () => financeApi.receive(receivable.id, { amount: Number(amount), paymentMethodId: method, receivedOn: date, notes: notes.trim() || null,
      installments: fees.data?.length ? Number(installments) : undefined }, idempotency.key),
    onSuccess: async () => { idempotency.renew(); setNotes(''); setError(''); await onDone() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível registrar o recebimento'),
  })
  const feeAmount = fee && validAmount(amount) ? Math.round(Number(amount) * fee.feePercent) / 100 : 0
  return <section className="card" aria-label="Registrar recebimento">
    <h2>Registrar recebimento</h2>
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="field"><label>Valor *<input className="input" type="number" step="0.01" min="0" value={amount} onChange={e => setAmount(e.target.value)} /></label>
      {exceeds && <span className="error-text">Maior que o saldo em aberto de {formatBrl(receivable.outstandingBalance)}.</span>}</div>
    <div className="field"><PaymentMethodSelect value={method} onChange={id => { setMethod(id); setInstallments('1') }} onMethodChange={setSelected} /></div>
    {fees.data && fees.data.length > 0 && <div className="field"><label>Parcelas no cartão
      <select className="select" value={installments} onChange={e => setInstallments(e.target.value)}>
        {fees.data.map(f => <option key={f.installments} value={f.installments}>{f.installments}x — taxa {String(f.feePercent).replace('.', ',')}%</option>)}
      </select></label>
      {fee && validAmount(amount) && <span className="muted" data-testid="card-fee-preview">
        Taxa {formatBrl(feeAmount)} · líquido {formatBrl(Number(amount) - feeAmount)} · repasse em {fee.settlementDays} dias</span>}
    </div>}
    <div className="field"><label>Data do recebimento *<input className="input" type="date" max={todayIso()} value={date} onChange={e => setDate(e.target.value)} /></label></div>
    <div className="field"><label>Observação<input className="input" value={notes} onChange={e => setNotes(e.target.value)} /></label></div>
    <button type="button" className="btn" style={{ width: '100%', marginTop: 12 }}
            disabled={!validAmount(amount) || exceeds || !method || !date || mutation.isPending} onClick={() => mutation.mutate()}>
      Registrar recebimento
    </button>
  </section>
}

/** Novo plano de parcelas (DR-0024, Q1): só sem recebimento; a soma tem de fechar com o valor original. */
function InstallmentPlanForm({ receivable, onDone }: { receivable: Receivable; onDone: () => Promise<unknown> }) {
  const [count, setCount] = useState('2')
  const [first, setFirst] = useState(receivable.dueDate < todayIso() ? todayIso() : receivable.dueDate)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const idempotency = useIdempotencyKey()
  const n = Number(count)
  const validCount = Number.isInteger(n) && n >= 1 && n <= 60
  const validDate = /^\d{4}-\d{2}-\d{2}$/.test(first)
  const plan = validCount && validDate ? splitEqually(receivable.originalAmount, n).map((amount, i) => ({ amount, dueDate: addMonths(first, i) })) : []
  const mutation = useMutation({
    mutationFn: () => financeApi.replan(receivable.id, plan, reason.trim(), idempotency.key),
    onSuccess: async () => { idempotency.renew(); setReason(''); setError(''); await onDone() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível parcelar'),
  })
  return <section className="card" aria-label="Parcelar recebível">
    <h2>Parcelar</h2>
    <p className="muted">Divide o valor original em parcelas mensais; os centavos que sobram vão para a última. Só enquanto não houver recebimento.</p>
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="field"><label>Número de parcelas *<input className="input" type="number" min="1" max="60" value={count} onChange={e => setCount(e.target.value)} /></label></div>
    <div className="field"><label>Primeiro vencimento *<input className="input" type="date" min={receivable.issuedOn} value={first} onChange={e => setFirst(e.target.value)} /></label></div>
    {plan.length > 0 && <ul className="history" data-testid="plan-preview">{plan.map((p, i) => <li key={i}>{i + 1}ª {formatBrl(p.amount)} em {formatDay(p.dueDate)}</li>)}</ul>}
    <div className="field"><label>Motivo *<input className="input" value={reason} onChange={e => setReason(e.target.value)} /></label></div>
    <button type="button" className="btn" style={{ width: '100%', marginTop: 12 }}
            disabled={!plan.length || reason.trim().length < 3 || mutation.isPending} onClick={() => mutation.mutate()}>Gravar parcelas</button>
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
  const openInstallments = (receivable.installmentPlan?.installments ?? []).filter(i => i.openAmount > 0)
  const multiple = (receivable.installmentPlan?.installments.length ?? 1) > 1
  const [number, setNumber] = useState(String(openInstallments[0]?.number ?? 1))
  const current = receivable.installmentPlan?.installments.find(i => i.number === Number(number))?.dueDate ?? receivable.dueDate
  const [date, setDate] = useState(current)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const idempotency = useIdempotencyKey()
  const mutation = useMutation({
    mutationFn: () => financeApi.changeDueDate(receivable.id, date, reason.trim(), idempotency.key, multiple ? Number(number) : undefined),
    onSuccess: async () => { idempotency.renew(); setReason(''); setError(''); await onDone() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível alterar o vencimento'),
  })
  return <section className="card" aria-label="Alterar vencimento">
    <h2>Alterar vencimento</h2>
    {error && <div role="alert" className="notice error">{error}</div>}
    {multiple && openInstallments.length > 0 && <div className="field"><label>Parcela
      <select className="select" value={number} onChange={e => { setNumber(e.target.value)
        setDate(receivable.installmentPlan?.installments.find(i => i.number === Number(e.target.value))?.dueDate ?? date) }}>
        {openInstallments.map(i => <option key={i.number} value={i.number}>{i.number}ª — {formatDay(i.dueDate)}</option>)}
      </select></label></div>}
    <div className="field"><label>Novo vencimento *<input className="input" type="date" value={date} onChange={e => setDate(e.target.value)} /></label></div>
    <div className="field"><label>Motivo da alteração *<input className="input" value={reason} onChange={e => setReason(e.target.value)} /></label></div>
    <button type="button" className="btn secondary" style={{ width: '100%', marginTop: 12 }}
            disabled={!date || date === current || reason.trim().length < 3 || mutation.isPending} onClick={() => mutation.mutate()}>
      Alterar vencimento
    </button>
  </section>
}

function CancelManual({ receivable, onDone }: { receivable: Receivable; onDone: () => Promise<unknown> }) {
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const mutation = useMutation({
    mutationFn: () => financeApi.cancelManualReceivable(receivable.id, reason.trim()),
    onSuccess: async () => { setError(''); await onDone() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível cancelar'),
  })
  return <section className="card" aria-label="Cancelar conta a receber">
    <h2>Cancelar conta</h2>
    <p className="muted">Só sem recebimento efetivo; estorne os recebimentos antes. O histórico é preservado.</p>
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="field"><label>Motivo do cancelamento *<input className="input" value={reason} onChange={e => setReason(e.target.value)} /></label></div>
    <button type="button" className="btn danger" style={{ width: '100%', marginTop: 12 }} disabled={reason.trim().length < 3 || mutation.isPending}
            onClick={() => mutation.mutate()}>Cancelar conta</button>
  </section>
}

/** Estorno total com motivo, em dois passos; a chave de idempotência protege o duplo clique. */
export function ReverseAction({ onReverse, onDone, label = 'Estornar' }: {
  onReverse: (reason: string, key: string) => Promise<unknown>; onDone: () => Promise<unknown>; label?: string
}) {
  const [asking, setAsking] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const idempotency = useIdempotencyKey()
  const mutation = useMutation({
    mutationFn: () => onReverse(reason.trim(), idempotency.key),
    onSuccess: async () => { idempotency.renew(); setAsking(false); setReason(''); await onDone() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível estornar'),
  })
  if (!asking) return <button type="button" className="btn secondary" onClick={() => setAsking(true)}>{label}</button>
  return <div role="group" aria-label="Confirmar estorno">
    {error && <div role="alert" className="notice error">{error}</div>}
    <input className="input" aria-label="Motivo do estorno" placeholder="Motivo do estorno" value={reason} onChange={e => setReason(e.target.value)} />
    <button type="button" className="btn danger" disabled={reason.trim().length < 3 || mutation.isPending} onClick={() => mutation.mutate()}>Confirmar estorno</button>
    <button type="button" className="btn secondary" onClick={() => setAsking(false)}>Voltar</button>
  </div>
}
