import { useState } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth'
import { suppliersApi, type Supplier, type SupplierPayload } from '../../api/purchasing'
import { Badge, Field, PageHeader, State } from '../../components/ui'
import { ConfirmAction, Pagination } from '../../components/controls'
import { QueryState } from '../../components/QueryState'
import { useDebouncedValue } from '../../utils/useDebouncedValue'
import { formatDate, formatDocument, formatPhone } from '../../utils/format'

const PAGE_SIZE = 20
const supplierKeys = {
  all: ['purchasing', 'suppliers'] as const,
  search: (params: object) => ['purchasing', 'suppliers', 'search', params] as const,
  detail: (id: string) => ['purchasing', 'suppliers', id] as const,
}

export function SuppliersPage() {
  const { session } = useAuth()
  const canManage = session?.permissions?.includes('PURCHASE_SUPPLIER_MANAGE') ?? false
  const [search, setSearch] = useState('')
  const [personType, setPersonType] = useState('ALL')
  const [status, setStatus] = useState('ACTIVE')
  const [page, setPage] = useState(0)
  const q = useDebouncedValue(search.trim())
  const params = { q, personType: personType === 'ALL' ? undefined : personType, status: status === 'ALL' ? undefined : status, page, size: PAGE_SIZE }
  const query = useQuery({ queryKey: supplierKeys.search(params), queryFn: () => suppliersApi.search(params), placeholderData: keepPreviousData })
  const items = query.data?.items ?? []

  return <>
    <PageHeader title="Fornecedores" subtitle="Cadastro comercial para compras, pedidos e recebimentos.">
      {canManage && <Link className="btn" to="/compras/fornecedores/novo">Novo fornecedor</Link>}
    </PageHeader>
    <div className="toolbar">
      <input className="input" aria-label="Buscar fornecedores" placeholder="Buscar por razão social, fantasia ou CPF/CNPJ"
             value={search} onChange={e => { setSearch(e.target.value); setPage(0) }} />
      <select className="select" aria-label="Tipo" value={personType} onChange={e => { setPersonType(e.target.value); setPage(0) }}>
        <option value="ALL">PF e PJ</option><option value="PF">PF</option><option value="PJ">PJ</option>
      </select>
      <select className="select" aria-label="Status" value={status} onChange={e => { setStatus(e.target.value); setPage(0) }}>
        <option value="ACTIVE">Ativos</option><option value="INACTIVE">Inativos</option><option value="ALL">Todos</option>
      </select>
    </div>
    <State loading={query.isLoading} error={query.error} empty={!items.length}>
      <div className="card table-wrap">
        <table className="table">
          <thead><tr><th>Fornecedor</th><th>Tipo</th><th>Documento</th><th>Telefone</th><th>Status</th><th>Ações</th></tr></thead>
          <tbody>{items.map(s => <tr key={s.id}>
            <td><strong>{s.tradeName || s.legalName}</strong>{s.tradeName && <div className="muted">{s.legalName}</div>}</td>
            <td><Badge tone="info">{s.personType}</Badge></td>
            <td>{s.document ? formatDocument(s.document) : '—'}</td>
            <td>{s.phone ? formatPhone(s.phone) : '—'}</td>
            <td><SupplierStatus status={s.status} /></td>
            <td><Link className="btn secondary" to={`/compras/fornecedores/${s.id}`}>Ver</Link></td>
          </tr>)}</tbody>
        </table>
      </div>
      {query.data && <Pagination page={query.data.page} totalPages={query.data.totalPages} totalItems={query.data.totalItems} onChange={setPage} />}
    </State>
  </>
}

const SupplierStatus = ({ status }: { status: Supplier['status'] }) =>
  <Badge tone={status === 'ACTIVE' ? 'success' : 'warning'}>{status === 'ACTIVE' ? 'Ativo' : 'Inativo'}</Badge>

type SupplierFormState = {
  personType: 'PF' | 'PJ'; legalName: string; tradeName: string; document: string; phone: string; email: string
  zipCode: string; street: string; number: string; complement: string; district: string; city: string; state: string
  paymentTerms: string; preferredPaymentMethod: string; usualDueDay: string; creditLimit: string; commercialNotes: string
}

const emptyForm: SupplierFormState = {
  personType: 'PJ', legalName: '', tradeName: '', document: '', phone: '', email: '', zipCode: '', street: '', number: '',
  complement: '', district: '', city: '', state: '', paymentTerms: '', preferredPaymentMethod: '', usualDueDay: '', creditLimit: '', commercialNotes: '',
}

function formFromSupplier(s: Supplier): SupplierFormState {
  return {
    personType: s.personType, legalName: s.legalName, tradeName: s.tradeName ?? '', document: s.document ?? '', phone: s.phone ?? '', email: s.email ?? '',
    zipCode: s.address?.zipCode ?? '', street: s.address?.street ?? '', number: s.address?.number ?? '', complement: s.address?.complement ?? '',
    district: s.address?.district ?? '', city: s.address?.city ?? '', state: s.address?.state ?? '', paymentTerms: s.commercialTerms.paymentTerms ?? '',
    preferredPaymentMethod: s.commercialTerms.preferredPaymentMethod ?? '', usualDueDay: s.commercialTerms.usualDueDay?.toString() ?? '',
    creditLimit: s.commercialTerms.creditLimit?.toString() ?? '', commercialNotes: s.commercialTerms.notes ?? '',
  }
}

function payload(values: SupplierFormState): SupplierPayload {
  const optional = (value: string) => value.trim() || null
  const address = { zipCode: optional(values.zipCode), street: optional(values.street), number: optional(values.number), complement: optional(values.complement),
    district: optional(values.district), city: optional(values.city), state: values.state.trim() ? values.state.trim().toUpperCase() : null }
  return {
    personType: values.personType, legalName: values.legalName.trim(), tradeName: optional(values.tradeName),
    document: values.document.replace(/\D/g, '') || null, phone: values.phone.replace(/\D/g, '') || null, email: optional(values.email),
    address: Object.values(address).some(Boolean) ? address : null,
    commercialTerms: {
      paymentTerms: optional(values.paymentTerms), preferredPaymentMethod: optional(values.preferredPaymentMethod),
      usualDueDay: values.usualDueDay ? Number(values.usualDueDay) : null, creditLimit: values.creditLimit ? Number(values.creditLimit.replace(',', '.')) : null,
      notes: optional(values.commercialNotes),
    },
  }
}

export function SupplierFormPage() {
  const { id } = useParams()
  const existing = useQuery({ queryKey: supplierKeys.detail(id ?? ''), queryFn: () => suppliersApi.get(id!), enabled: Boolean(id) })
  if (id) return <QueryState loading={existing.isLoading} error={existing.error} label="o fornecedor" retry={existing.refetch}>
    {existing.data && <SupplierForm supplier={existing.data} />}
  </QueryState>
  return <SupplierForm />
}

function SupplierForm({ supplier }: { supplier?: Supplier }) {
  const navigate = useNavigate()
  const client = useQueryClient()
  const [values, setValues] = useState<SupplierFormState>(() => supplier ? formFromSupplier(supplier) : emptyForm)
  const [error, setError] = useState('')
  const mutation = useMutation({
    mutationFn: () => supplier ? suppliersApi.update(supplier.id, payload(values)) : suppliersApi.create(payload(values)),
    onSuccess: async saved => { await client.invalidateQueries({ queryKey: supplierKeys.all }); navigate(`/compras/fornecedores/${saved.id}`) },
  })
  const set = (key: keyof SupplierFormState) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
    setValues(current => ({ ...current, [key]: e.target.value }))

  return <>
    <PageHeader title={supplier ? 'Editar fornecedor' : 'Novo fornecedor'} subtitle="Dados comerciais usados nos pedidos serão preservados como snapshot." />
    <form className="card" onSubmit={async e => {
      e.preventDefault(); setError('')
      if (!values.legalName.trim()) { setError('Informe a razão social ou nome do fornecedor.'); return }
      try { await mutation.mutateAsync() } catch (e) { setError(e instanceof Error ? e.message : 'Falha ao salvar fornecedor') }
    }}>
      {error && <div className="notice error" role="alert">{error}</div>}
      <div className="form-grid">
        <Field label="Tipo de pessoa"><select className="select" value={values.personType} onChange={set('personType')}><option value="PJ">Pessoa jurídica</option><option value="PF">Pessoa física</option></select></Field>
        <Field label={values.personType === 'PJ' ? 'Razão social *' : 'Nome *'}><input className="input" value={values.legalName} onChange={set('legalName')} /></Field>
        <Field label="Nome fantasia"><input className="input" value={values.tradeName} onChange={set('tradeName')} /></Field>
        <Field label={values.personType === 'PJ' ? 'CNPJ' : 'CPF'}><input className="input" inputMode="numeric" value={values.document} onChange={set('document')} /></Field>
        <Field label="Telefone"><input className="input" value={values.phone} onChange={set('phone')} /></Field>
        <Field label="E-mail"><input className="input" type="email" value={values.email} onChange={set('email')} /></Field>
        <h2 className="form-section">Condição comercial padrão</h2>
        <Field label="Condição de pagamento"><input className="input" placeholder="Ex.: 28/35/42 dias" value={values.paymentTerms} onChange={set('paymentTerms')} /></Field>
        <Field label="Forma preferencial"><input className="input" placeholder="Ex.: Boleto" value={values.preferredPaymentMethod} onChange={set('preferredPaymentMethod')} /></Field>
        <Field label="Dia usual de vencimento"><input className="input" type="number" min={1} max={31} value={values.usualDueDay} onChange={set('usualDueDay')} /></Field>
        <Field label="Limite de crédito"><input className="input" inputMode="decimal" value={values.creditLimit} onChange={set('creditLimit')} /></Field>
        <Field label="Observações comerciais"><textarea className="input" rows={3} value={values.commercialNotes} onChange={set('commercialNotes')} /></Field>
        <h2 className="form-section">Endereço</h2>
        <Field label="CEP"><input className="input" value={values.zipCode} onChange={set('zipCode')} /></Field>
        <Field label="Logradouro"><input className="input" value={values.street} onChange={set('street')} /></Field>
        <Field label="Número"><input className="input" value={values.number} onChange={set('number')} /></Field>
        <Field label="Complemento"><input className="input" value={values.complement} onChange={set('complement')} /></Field>
        <Field label="Bairro"><input className="input" value={values.district} onChange={set('district')} /></Field>
        <Field label="Cidade"><input className="input" value={values.city} onChange={set('city')} /></Field>
        <Field label="UF"><input className="input" maxLength={2} value={values.state} onChange={set('state')} /></Field>
      </div>
      <div className="form-actions">
        <button className="btn secondary" type="button" onClick={() => navigate(supplier ? `/compras/fornecedores/${supplier.id}` : '/compras/fornecedores')}>Cancelar</button>
        <button className="btn" disabled={mutation.isPending}>Salvar fornecedor</button>
      </div>
    </form>
  </>
}

export function SupplierDetailPage() {
  const { id = '' } = useParams()
  const { session } = useAuth()
  const canManage = session?.permissions?.includes('PURCHASE_SUPPLIER_MANAGE') ?? false
  const client = useQueryClient()
  const query = useQuery({ queryKey: supplierKeys.detail(id), queryFn: () => suppliersApi.get(id) })
  return <QueryState loading={query.isLoading} error={query.error} label="o fornecedor" retry={query.refetch}>
    {query.data && (() => {
      const s = query.data
      const address = [s.address?.street && [s.address.street, s.address.number].filter(Boolean).join(', '), s.address?.district,
        [s.address?.city, s.address?.state].filter(Boolean).join('/'), s.address?.zipCode].filter(Boolean).join(' · ')
      const refresh = async () => { await client.invalidateQueries({ queryKey: supplierKeys.all }) }
      return <>
        <PageHeader title={s.tradeName || s.legalName} subtitle={s.tradeName ? s.legalName : (s.personType === 'PJ' ? 'Pessoa jurídica' : 'Pessoa física')}>
          <SupplierStatus status={s.status} />
          {canManage && <Link className="btn secondary" to={`/compras/fornecedores/${s.id}/editar`}>Editar</Link>}
          {canManage && (s.status === 'ACTIVE'
            ? <ConfirmAction label="Inativar" question="Inativar este fornecedor? O histórico será preservado." onConfirm={async () => { await suppliersApi.inactivate(id); await refresh() }} />
            : <ConfirmAction label="Reativar" tone="primary" question="Reativar este fornecedor?" onConfirm={async () => { await suppliersApi.reactivate(id); await refresh() }} />)}
        </PageHeader>
        <div className="detail-grid">
          <section className="card">
            <h2>Condições comerciais</h2>
            <dl className="summary-list">
              <div><dt>Pagamento</dt><dd>{s.commercialTerms.paymentTerms || '—'}</dd></div>
              <div><dt>Forma preferencial</dt><dd>{s.commercialTerms.preferredPaymentMethod || '—'}</dd></div>
              <div><dt>Dia usual</dt><dd>{s.commercialTerms.usualDueDay || '—'}</dd></div>
              <div><dt>Limite de crédito</dt><dd>{s.commercialTerms.creditLimit != null ? s.commercialTerms.creditLimit.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' }) : '—'}</dd></div>
              <div><dt>Observações</dt><dd>{s.commercialTerms.notes || '—'}</dd></div>
            </dl>
          </section>
          <aside className="card">
            <h2>Cadastro</h2>
            <dl className="summary-list">
              <div><dt>CPF/CNPJ</dt><dd>{s.document ? formatDocument(s.document) : '—'}</dd></div>
              <div><dt>Telefone</dt><dd>{s.phone ? formatPhone(s.phone) : '—'}</dd></div>
              <div><dt>E-mail</dt><dd>{s.email || '—'}</dd></div>
              <div><dt>Endereço</dt><dd>{address || '—'}</dd></div>
              <div><dt>Cadastrado em</dt><dd>{formatDate(s.createdAt)}</dd></div>
            </dl>
          </aside>
        </div>
      </>
    })()}
  </QueryState>
}
