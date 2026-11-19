import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  APPROVAL_LABELS, FINANCE_PAYABLE, FINANCE_PAYABLE_APPROVE, FINANCE_REVERSE, PAYABLE_STATUS_LABELS, financeApi, financeKeys, statusTone, todayIso,
  type ApprovalStatus, type Payable, type PayableGroup,
} from '../../api/finance'
import { purchasingApi } from '../../api/purchasing'
import { useAuth } from '../../auth/useAuth'
import { addMonths, splitEqually } from './installments'
import { Badge, PageHeader } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { Pagination } from '../../components/controls'
import { formatBrl, formatDate } from '../../utils/format'
import { PaymentMethodSelect } from './common'
import { formatDay, useCan, useIdempotencyKey, validAmount } from './hooks'
import { ReverseAction } from './Receivables'

export function PayablesPage() {
  const [status, setStatus] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [approval, setApproval] = useState('')
  const [page, setPage] = useState(0)
  const canCreate = useCan(FINANCE_PAYABLE)
  const categories = useQuery({ queryKey: financeKeys.categories, queryFn: financeApi.categories })
  const filter = { status: status || undefined, categoryId: categoryId || undefined, approval: approval || undefined, page }
  const list = useQuery({ queryKey: [...financeKeys.payables, filter], queryFn: () => financeApi.payables(filter), placeholderData: keepPreviousData })

  return <>
    <PageHeader title="Contas a pagar" subtitle="Despesas e obrigações da oficina. Cancelar preserva o histórico; pagamento errado é estornado.">
      {canCreate && <Link className="btn" to="/financeiro/contas-a-pagar/nova">Nova conta a pagar</Link>}
    </PageHeader>
    <section className="card filters"><div className="inline-form">
      <div className="field"><label>Situação<select className="select" value={status} onChange={e => { setStatus(e.target.value); setPage(0) }}>
        <option value="">Todas</option>
        {Object.entries(PAYABLE_STATUS_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select></label></div>
      <div className="field"><label>Categoria<select className="select" value={categoryId} onChange={e => { setCategoryId(e.target.value); setPage(0) }}>
        <option value="">Todas</option>
        {categories.data?.map(category => <option key={category.id} value={category.id}>{category.name}</option>)}
      </select></label></div>
      <div className="field"><label>Aprovação<select className="select" value={approval} onChange={e => { setApproval(e.target.value); setPage(0) }}>
        <option value="">Todas</option>
        {Object.entries(APPROVAL_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select></label></div>
    </div></section>
    <QueryState label="as contas a pagar" loading={list.isLoading} error={list.error} retry={list.refetch}
                empty={!list.data?.items.length} emptyMessage="Nenhuma conta a pagar encontrada.">
      <div className="card table-wrap"><table className="table">
        <thead><tr><th>Descrição</th><th>Categoria</th><th>Vencimento</th><th>Valor</th><th>Saldo</th><th>Situação</th><th /></tr></thead>
        <tbody>{list.data?.items.map(item => <tr key={item.id}>
          <td><strong>{item.description}</strong><div className="muted">{item.supplier ?? '—'}</div></td>
          <td>{item.categoryName}</td><td>{formatDay(item.dueDate)}</td><td>{formatBrl(item.amount)}</td><td>{formatBrl(item.outstandingBalance)}</td>
          <td><Badge tone={statusTone(item.status)}>{PAYABLE_STATUS_LABELS[item.status]}</Badge>
            {item.approvalStatus === 'PENDING' && <div><Badge tone="warning">{APPROVAL_LABELS.PENDING}</Badge></div>}</td>
          <td><Link className="btn secondary" to={`/financeiro/contas-a-pagar/${item.id}`}>Abrir</Link></td>
        </tr>)}</tbody>
      </table></div>
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onChange={setPage} />}
    </QueryState>
  </>
}

export function PayableFormPage() {
  const navigate = useNavigate()
  const client = useQueryClient()
  const categories = useQuery({ queryKey: financeKeys.categories, queryFn: financeApi.categories })
  const { session } = useAuth()
  const canSearchSuppliers = Boolean(session?.permissions?.includes('PURCHASE_VIEW'))
  const [form, setForm] = useState({ description: '', supplier: '', supplierId: '', categoryId: '', amount: '', dueDate: todayIso(), notes: '',
    installments: '1' })
  const [supplierSearch, setSupplierSearch] = useState('')
  const suppliers = useQuery({ queryKey: ['finance', 'payable-form', 'suppliers', supplierSearch],
    queryFn: () => purchasingApi.suppliers({ q: supplierSearch, status: 'ACTIVE', size: 8 }), enabled: canSearchSuppliers && supplierSearch.trim().length >= 2 })
  const [error, setError] = useState('')
  const idempotency = useIdempotencyKey()
  const set = (patch: Partial<typeof form>) => setForm({ ...form, ...patch })
  const count = Number(form.installments)
  const mutation = useMutation({
    mutationFn: async () => {
      const common = { description: form.description.trim(), supplier: form.supplier.trim() || null, supplierId: form.supplierId || null,
        categoryId: form.categoryId, notes: form.notes.trim() || null }
      if (count > 1) {
        const group = await financeApi.createPayableInstallments({ ...common, totalAmount: Number(form.amount), installments: count,
          firstDueDate: form.dueDate }, idempotency.key)
        return `/financeiro/contas-a-pagar/grupos/${group.id}`
      }
      const payable = await financeApi.createPayable({ ...common, amount: Number(form.amount), dueDate: form.dueDate }, idempotency.key)
      return `/financeiro/contas-a-pagar/${payable.id}`
    },
    onSuccess: async path => { await client.invalidateQueries({ queryKey: financeKeys.payables }); navigate(path) },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível lançar a conta'),
  })
  const valid = form.description.trim().length >= 2 && form.categoryId && validAmount(form.amount) && form.dueDate
    && Number.isInteger(count) && count >= 1 && count <= 60
  return <>
    <PageHeader title="Nova conta a pagar" subtitle="Descrição, categoria, valor e vencimento são obrigatórios. O fornecedor pode vir do cadastro de Compras ou ser texto livre." />
    <section className="card">
      {error && <div role="alert" className="notice error">{error}</div>}
      <div className="form-grid">
        <div className="field full"><label>Descrição *<input className="input" value={form.description} onChange={e => set({ description: e.target.value })} /></label></div>
        <div className="field"><label>Fornecedor<input className="input" value={form.supplier}
          onChange={e => { set({ supplier: e.target.value, supplierId: '' }); setSupplierSearch(e.target.value) }} /></label>
          {form.supplierId && <span className="muted" data-testid="supplier-linked">Vinculado ao cadastro de fornecedores</span>}
          {!form.supplierId && suppliers.data && suppliers.data.items.length > 0 && <ul className="history" aria-label="Fornecedores do cadastro">
            {suppliers.data.items.map(s => <li key={s.id}><button type="button" className="btn secondary" onClick={() => {
              set({ supplierId: s.id, supplier: s.tradeName || s.legalName }); setSupplierSearch('') }}>{s.tradeName || s.legalName}</button></li>)}
          </ul>}
        </div>
        <div className="field"><label>Categoria *<select className="select" value={form.categoryId} onChange={e => set({ categoryId: e.target.value })}>
          <option value="">Selecione</option>
          {categories.data?.filter(c => c.active).map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select></label></div>
        {categories.data && categories.data.filter(c => c.active).length === 0 && <div role="status" className="notice full">
          Nenhuma categoria de despesa ativa. Cadastre uma em <Link to="/financeiro/configuracoes">Financeiro › Configurações</Link> antes de lançar a conta.
        </div>}
        <div className="field"><label>Valor *<input className="input" type="number" step="0.01" min="0" value={form.amount} onChange={e => set({ amount: e.target.value })} /></label></div>
        <div className="field"><label>{count > 1 ? 'Primeiro vencimento *' : 'Vencimento *'}<input className="input" type="date" value={form.dueDate} onChange={e => set({ dueDate: e.target.value })} /></label></div>
        <div className="field"><label>Parcelas<input className="input" type="number" min="1" max="60" value={form.installments} onChange={e => set({ installments: e.target.value })} /></label></div>
        {validAmount(form.amount) && count > 1 && count <= 60 && /^\d{4}-\d{2}-\d{2}$/.test(form.dueDate) && <div className="notice full" role="status" data-testid="payable-split">
          {splitEqually(Number(form.amount), count).map((amount, i) => `${i + 1}ª ${formatBrl(amount)} em ${formatDay(addMonths(form.dueDate, i))}`).join(' · ')}
        </div>}
        <div className="field full"><label>Observação<input className="input" value={form.notes} onChange={e => set({ notes: e.target.value })} /></label></div>
      </div>
      <div className="form-actions">
        <button type="button" className="btn secondary" onClick={() => navigate('/financeiro/contas-a-pagar')}>Cancelar</button>
        <button type="button" className="btn" disabled={!valid || mutation.isPending} onClick={() => mutation.mutate()}>Lançar conta</button>
      </div>
    </section>
  </>
}

export function PayableDetailPage() {
  const { id = '' } = useParams()
  const client = useQueryClient()
  const payable = useQuery({ queryKey: financeKeys.payable(id), queryFn: () => financeApi.payable(id) })
  const refresh = () => Promise.all([client.invalidateQueries({ queryKey: financeKeys.payable(id) }), client.invalidateQueries({ queryKey: financeKeys.payables })])
  return <QueryState label="a conta a pagar" loading={payable.isLoading} error={payable.error} retry={payable.refetch}>
    {payable.data && <PayableView payable={payable.data} onChange={refresh} />}
  </QueryState>
}

function PayableView({ payable: p, onChange }: { payable: Payable; onChange: () => Promise<unknown> }) {
  const canPay = useCan(FINANCE_PAYABLE)
  const canReverse = useCan(FINANCE_REVERSE)
  const canApprove = useCan(FINANCE_PAYABLE_APPROVE)
  const open = p.status !== 'CANCELADO' && p.outstandingBalance > 0
  const pending = p.approvalStatus === 'PENDING'
  return <>
    <PageHeader title={p.description} subtitle={`${p.categoryName}${p.supplier ? ` · ${p.supplier}` : ''}`}>
      {p.groupId && <Link className="btn secondary" to={`/financeiro/contas-a-pagar/grupos/${p.groupId}`}>Ver todas as parcelas</Link>}
      <Link className="btn secondary" to="/financeiro/contas-a-pagar">Voltar</Link>
    </PageHeader>
    <div className="detail-grid">
      <div className="stack">
        <section className="card" aria-label="Resumo da conta">
          <h2>Resumo <Badge tone={statusTone(p.status)}>{PAYABLE_STATUS_LABELS[p.status]}</Badge></h2>
          <dl className="summary-list">
            <div><dt>Valor</dt><dd>{formatBrl(p.amount)}</dd></div>
            <div><dt>Pago</dt><dd>{formatBrl(p.paidAmount)}</dd></div>
            <div><dt>Saldo em aberto</dt><dd data-testid="payable-balance">{formatBrl(p.outstandingBalance)}</dd></div>
            <div><dt>Vencimento</dt><dd>{formatDay(p.dueDate)}</dd></div>
            <div><dt>Aprovação</dt><dd data-testid="payable-approval">{APPROVAL_LABELS[(p.approvalStatus ?? 'NOT_REQUIRED') as ApprovalStatus]}
              {p.approval && <span className="muted"> em {formatDate(p.approval.approvedAt)}{p.approval.notes ? `: ${p.approval.notes}` : ''}</span>}</dd></div>
            {p.origin === 'PURCHASE' && <div><dt>Origem</dt><dd>Compra (aprovada no pedido)</dd></div>}
          </dl>
          {p.cancelledAt && <p className="notice">Cancelada em {formatDate(p.cancelledAt)}: {p.cancellationReason}</p>}
        </section>
        <section className="card">
          <h2>Pagamentos</h2>
          {!p.payments.length ? <p className="muted">Nenhum pagamento registrado.</p> : <div className="table-wrap"><table className="table">
            <thead><tr><th>Data</th><th>Forma</th><th>Valor</th><th>Situação</th><th /></tr></thead>
            <tbody>{p.payments.map(payment => <tr key={payment.id}>
              <td>{formatDay(payment.effectiveOn)}</td><td>{payment.paymentMethodName}</td><td>{formatBrl(payment.amount)}</td>
              <td>{payment.reversal ? `Estornado: ${payment.reversal.reason}` : 'Efetivo'}</td>
              <td>{canReverse && !payment.reversal && <ReverseAction onReverse={(reason, key) => financeApi.reversePayment(payment.id, reason, key)} onDone={onChange} />}</td>
            </tr>)}</tbody>
          </table></div>}
        </section>
      </div>
      <aside className="stack">
        {pending && <ApprovalCard payable={p} canApprove={canApprove} onDone={onChange} />}
        {canPay && open && !pending && <PaymentForm payable={p} onDone={onChange} />}
        {canPay && p.status !== 'CANCELADO' && <CancelPayable payable={p} onDone={onChange} />}
      </aside>
    </div>
  </>
}

function PaymentForm({ payable, onDone }: { payable: Payable; onDone: () => Promise<unknown> }) {
  const [amount, setAmount] = useState(String(payable.outstandingBalance))
  const [method, setMethod] = useState('')
  const [date, setDate] = useState(todayIso())
  const [error, setError] = useState('')
  const idempotency = useIdempotencyKey()
  const exceeds = validAmount(amount) && Number(amount) > payable.outstandingBalance
  const mutation = useMutation({
    mutationFn: () => financeApi.pay(payable.id, { amount: Number(amount), paymentMethodId: method, paidOn: date }, idempotency.key),
    onSuccess: async () => { idempotency.renew(); setError(''); await onDone() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível registrar o pagamento'),
  })
  return <section className="card" aria-label="Registrar pagamento">
    <h2>Registrar pagamento</h2>
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="field"><label>Valor *<input className="input" type="number" step="0.01" min="0" value={amount} onChange={e => setAmount(e.target.value)} /></label>
      {exceeds && <span className="error-text">Maior que o saldo em aberto.</span>}</div>
    <div className="field"><PaymentMethodSelect value={method} onChange={setMethod} /></div>
    <div className="field"><label>Data do pagamento *<input className="input" type="date" max={todayIso()} value={date} onChange={e => setDate(e.target.value)} /></label></div>
    <button type="button" className="btn" style={{ width: '100%', marginTop: 12 }}
            disabled={!validAmount(amount) || exceeds || !method || !date || mutation.isPending} onClick={() => mutation.mutate()}>Registrar pagamento</button>
  </section>
}

/** Aprovação segregada (DR-0024, Q5): quem lançou não aprova, salvo o Dono; o backend decide. */
function ApprovalCard({ payable, canApprove, onDone }: { payable: Payable; canApprove: boolean; onDone: () => Promise<unknown> }) {
  const [notes, setNotes] = useState('')
  const [error, setError] = useState('')
  const mutation = useMutation({
    mutationFn: () => financeApi.approvePayable(payable.id, notes.trim() || null),
    onSuccess: async () => { setError(''); await onDone() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível aprovar'),
  })
  return <section className="card" aria-label="Aprovação da conta">
    <h2>Aguardando aprovação</h2>
    <p className="muted">O valor passa do limite configurado. A conta só pode ser paga depois de aprovada por outro usuário.</p>
    {error && <div role="alert" className="notice error">{error}</div>}
    {canApprove ? <>
      <div className="field"><label>Observação<input className="input" value={notes} onChange={e => setNotes(e.target.value)} /></label></div>
      <button type="button" className="btn" style={{ width: '100%', marginTop: 12 }} disabled={mutation.isPending} onClick={() => mutation.mutate()}>Aprovar conta</button>
    </> : <p className="muted">Seu perfil não aprova contas a pagar.</p>}
  </section>
}

/** Visão consolidada de uma despesa parcelada (DR-0024, Q5). */
export function PayableGroupPage() {
  const { id = '' } = useParams()
  const client = useQueryClient()
  const canApprove = useCan(FINANCE_PAYABLE_APPROVE)
  const group = useQuery({ queryKey: financeKeys.payableGroup(id), queryFn: () => financeApi.payableGroup(id) })
  const [error, setError] = useState('')
  const approve = useMutation({
    mutationFn: () => financeApi.approvePayableGroup(id),
    onSuccess: async () => { setError(''); await Promise.all([client.invalidateQueries({ queryKey: financeKeys.payableGroup(id) }),
      client.invalidateQueries({ queryKey: financeKeys.payables })]) },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível aprovar'),
  })
  return <QueryState label="o grupo de parcelas" loading={group.isLoading} error={group.error} retry={group.refetch}>
    {group.data && <GroupView group={group.data} canApprove={canApprove} approving={approve.isPending} onApprove={() => approve.mutate()} error={error} />}
  </QueryState>
}

function GroupView({ group: g, canApprove, approving, onApprove, error }: { group: PayableGroup; canApprove: boolean; approving: boolean
  onApprove: () => void; error: string }) {
  const pending = g.payables.some(p => p.approvalStatus === 'PENDING' && p.status !== 'CANCELADO')
  return <>
    <PageHeader title={g.description} subtitle={`Despesa parcelada em ${g.installmentCount}x${g.supplier ? ` · ${g.supplier}` : ''}`}>
      <Link className="btn secondary" to="/financeiro/contas-a-pagar">Voltar</Link>
    </PageHeader>
    {error && <div role="alert" className="notice error">{error}</div>}
    <section className="card" aria-label="Totais do grupo">
      <dl className="summary-list">
        <div><dt>Total</dt><dd>{formatBrl(g.totalAmount)}</dd></div>
        <div><dt>Pago</dt><dd data-testid="group-paid">{formatBrl(g.paidAmount)}</dd></div>
        <div><dt>Em aberto</dt><dd data-testid="group-open">{formatBrl(g.outstandingBalance)}</dd></div>
      </dl>
      {pending && canApprove && <button type="button" className="btn" disabled={approving} onClick={onApprove}>Aprovar todas as parcelas</button>}
    </section>
    <div className="card table-wrap"><table className="table">
      <thead><tr><th>Parcela</th><th>Vencimento</th><th>Valor</th><th>Saldo</th><th>Situação</th><th /></tr></thead>
      <tbody>{g.payables.map(p => <tr key={p.id}>
        <td>{p.installmentNumber}/{g.installmentCount}</td><td>{formatDay(p.dueDate)}</td><td>{formatBrl(p.amount)}</td><td>{formatBrl(p.outstandingBalance)}</td>
        <td><Badge tone={statusTone(p.status)}>{PAYABLE_STATUS_LABELS[p.status]}</Badge>
          {p.approvalStatus === 'PENDING' && <div><Badge tone="warning">{APPROVAL_LABELS.PENDING}</Badge></div>}</td>
        <td><Link className="btn secondary" to={`/financeiro/contas-a-pagar/${p.id}`}>Abrir</Link></td>
      </tr>)}</tbody>
    </table></div>
  </>
}

function CancelPayable({ payable, onDone }: { payable: Payable; onDone: () => Promise<unknown> }) {
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const mutation = useMutation({
    mutationFn: () => financeApi.cancelPayable(payable.id, reason.trim()),
    onSuccess: async () => { setError(''); await onDone() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível cancelar'),
  })
  return <section className="card" aria-label="Cancelar conta">
    <h2>Cancelar conta</h2>
    <p className="muted">Só é possível sem pagamento efetivo; estorne os pagamentos antes.</p>
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="field"><label>Motivo do cancelamento *<input className="input" value={reason} onChange={e => setReason(e.target.value)} /></label></div>
    <button type="button" className="btn danger" style={{ width: '100%', marginTop: 12 }} disabled={reason.trim().length < 3 || mutation.isPending}
            onClick={() => mutation.mutate()}>Cancelar conta</button>
  </section>
}
