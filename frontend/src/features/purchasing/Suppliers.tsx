import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  PURCHASE_SUPPLIER_MANAGE, purchasingApi, purchasingKeys,
  type ContactInput, type PersonType, type Supplier, type SupplierContact, type SupplierEvent,
} from '../../api/purchasing'
import { Badge, PageHeader } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { ConfirmAction, Pagination } from '../../components/controls'
import { useCan } from '../finance/hooks'
import { useDebouncedValue } from '../../utils/useDebouncedValue'
import { formatBrl, formatDate, formatPhone, formatZipCode } from '../../utils/format'
import { formatTaxId } from '../../utils/taxId'
import { EMAIL, STATES, blankToNull, emptyForm, fromSupplier, toInput, validateSupplierForm, type FormState } from './supplierForm'

const PAGE_SIZE = 20
const PERSON_LABELS: Record<PersonType, string> = { PF: 'Pessoa física', PJ: 'Pessoa jurídica' }

// ====================================================================== lista

export function SuppliersPage() {
  const [search, setSearch] = useState('')
  const [type, setType] = useState('')
  const [status, setStatus] = useState('ACTIVE')
  const [page, setPage] = useState(0)
  const canManage = useCan(PURCHASE_SUPPLIER_MANAGE)
  const q = useDebouncedValue(search.trim())
  const params = { q: q || undefined, personType: (type || undefined) as PersonType | undefined,
    status: (status || undefined) as 'ACTIVE' | 'INACTIVE' | undefined, page, size: PAGE_SIZE }
  const list = useQuery({ queryKey: purchasingKeys.supplierList(params), queryFn: () => purchasingApi.suppliers(params), placeholderData: keepPreviousData })

  return <>
    <PageHeader title="Fornecedores" subtitle="Cadastro de fornecedores e condições comerciais. Fornecedor com histórico nunca é apagado: inativar impede novos usos.">
      {canManage && <Link className="btn" to="/compras/fornecedores/novo">Novo fornecedor</Link>}
    </PageHeader>
    <div className="toolbar">
      <input aria-label="Buscar fornecedores" className="input" placeholder="Buscar por nome, nome fantasia ou CPF/CNPJ"
             value={search} onChange={e => { setSearch(e.target.value); setPage(0) }} />
      <select aria-label="Tipo de pessoa" className="select" value={type} onChange={e => { setType(e.target.value); setPage(0) }}>
        <option value="">Todos os tipos</option>
        <option value="PJ">Pessoa jurídica</option>
        <option value="PF">Pessoa física</option>
      </select>
      <select aria-label="Situação" className="select" value={status} onChange={e => { setStatus(e.target.value); setPage(0) }}>
        <option value="ACTIVE">Ativos</option>
        <option value="INACTIVE">Inativos</option>
        <option value="">Todos</option>
      </select>
    </div>
    <QueryState label="os fornecedores" loading={list.isLoading} error={list.error} retry={list.refetch}
                empty={!list.data?.items.length} emptyMessage="Nenhum fornecedor encontrado.">
      <div className="card table-wrap"><table className="table">
        <thead><tr><th>Fornecedor</th><th>CPF/CNPJ</th><th>Tipo</th><th>Cidade</th><th>Situação</th><th /></tr></thead>
        <tbody>{list.data?.items.map(item => <tr key={item.id}>
          <td><strong>{item.legalName}</strong>{item.tradeName && <div className="muted">{item.tradeName}</div>}
            {item.relatedParty && <div><Badge tone="info">Empresa relacionada</Badge></div>}</td>
          <td>{formatTaxId(item.document)}</td>
          <td>{PERSON_LABELS[item.personType]}</td>
          <td>{item.address.city ? `${item.address.city}${item.address.state ? `/${item.address.state}` : ''}` : '—'}</td>
          <td><Badge tone={item.status === 'ACTIVE' ? 'success' : 'warning'}>{item.status === 'ACTIVE' ? 'Ativo' : 'Inativo'}</Badge></td>
          <td><Link className="btn secondary" to={`/compras/fornecedores/${item.id}`}>Abrir</Link></td>
        </tr>)}</tbody>
      </table></div>
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onChange={setPage} />}
    </QueryState>
  </>
}

/** `/compras/fornecedores/novo` cadastra; `/compras/fornecedores/:id/editar` altera sobre a versão carregada. */
export function SupplierFormPage() {
  const { id } = useParams()
  const existing = useQuery({ queryKey: purchasingKeys.supplier(id ?? ''), queryFn: () => purchasingApi.supplier(id ?? ''), enabled: Boolean(id) })
  if (!id) return <SupplierForm />
  return <QueryState label="o fornecedor" loading={existing.isLoading} error={existing.error} retry={existing.refetch}>
    {existing.data && <SupplierForm supplier={existing.data} />}
  </QueryState>
}

function SupplierForm({ supplier }: { supplier?: Supplier }) {
  const navigate = useNavigate()
  const client = useQueryClient()
  const [form, setForm] = useState<FormState>(() => (supplier ? fromSupplier(supplier) : emptyForm))
  const [error, setError] = useState('')
  const set = (patch: Partial<FormState>) => setForm(current => ({ ...current, ...patch }))
  const errors = validateSupplierForm(form)
  const valid = Object.keys(errors).length === 0
  const mutation = useMutation({
    mutationFn: () => (supplier ? purchasingApi.updateSupplier(supplier.id, toInput(form, supplier.version)) : purchasingApi.createSupplier(toInput(form))),
    onSuccess: async saved => {
      await client.invalidateQueries({ queryKey: purchasingKeys.suppliers })
      navigate(`/compras/fornecedores/${saved.id}`)
    },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível salvar o fornecedor'),
  })
  const person = form.personType === 'PF'

  return <>
    <PageHeader title={supplier ? 'Editar fornecedor' : 'Novo fornecedor'}
                subtitle="CPF/CNPJ é opcional, mas quando informado precisa ser válido e único. Condições comerciais são sugestões: cada compra pode usar outras.">
      <Link className="btn secondary" to={supplier ? `/compras/fornecedores/${supplier.id}` : '/compras/fornecedores'}>Voltar</Link>
    </PageHeader>
    <section className="card">
      {error && <div role="alert" className="notice error">{error}</div>}
      <h2>Identificação</h2>
      <div className="form-grid">
        <div className="field"><label>Tipo de pessoa *<select className="select" value={form.personType} onChange={e => set({ personType: e.target.value as PersonType })}>
          <option value="PJ">Pessoa jurídica</option><option value="PF">Pessoa física</option>
        </select></label></div>
        <div className="field"><label>{person ? 'CPF' : 'CNPJ'}<input className="input" value={form.document} onChange={e => set({ document: e.target.value })} /></label>
          {errors.document && <span role="alert" className="error-text">{errors.document}</span>}</div>
        <div className="field"><label>{person ? 'Nome *' : 'Razão social *'}<input className="input" value={form.legalName} onChange={e => set({ legalName: e.target.value })} /></label>
          {form.legalName !== '' && errors.legalName && <span role="alert" className="error-text">{errors.legalName}</span>}</div>
        <div className="field"><label>Nome fantasia<input className="input" value={form.tradeName} onChange={e => set({ tradeName: e.target.value })} /></label></div>
        <div className="field"><label>Inscrição estadual<input className="input" value={form.stateRegistration} onChange={e => set({ stateRegistration: e.target.value })} /></label></div>
        <div className="field"><label>Telefone<input className="input" value={form.phone} onChange={e => set({ phone: e.target.value })} /></label></div>
        <div className="field"><label>E-mail<input className="input" value={form.email} onChange={e => set({ email: e.target.value })} /></label>
          {errors.email && <span role="alert" className="error-text">{errors.email}</span>}</div>
        <div className="field full"><label><input type="checkbox" checked={form.relatedParty} onChange={e => set({ relatedParty: e.target.checked })} />{' '}
          Empresa relacionada à oficina</label>
          <span className="muted">Apenas informativo: continua sendo outra pessoa jurídica, com CNPJ próprio, compras e contas a pagar próprias.</span></div>
        {form.relatedParty && <div className="field full"><label>Observação sobre a relação<input className="input" value={form.relatedPartyNote}
                                                                                                   onChange={e => set({ relatedPartyNote: e.target.value })} /></label></div>}
      </div>
      <h2 style={{ marginTop: 28 }}>Endereço</h2>
      <div className="form-grid">
        <div className="field"><label>CEP<input className="input" value={form.zipCode} onChange={e => set({ zipCode: e.target.value })} /></label>
          {errors.zipCode && <span role="alert" className="error-text">{errors.zipCode}</span>}</div>
        <div className="field"><label>Logradouro<input className="input" value={form.street} onChange={e => set({ street: e.target.value })} /></label></div>
        <div className="field"><label>Número<input className="input" value={form.number} onChange={e => set({ number: e.target.value })} /></label></div>
        <div className="field"><label>Complemento<input className="input" value={form.complement} onChange={e => set({ complement: e.target.value })} /></label></div>
        <div className="field"><label>Bairro<input className="input" value={form.district} onChange={e => set({ district: e.target.value })} /></label></div>
        <div className="field"><label>Cidade<input className="input" value={form.city} onChange={e => set({ city: e.target.value })} /></label></div>
        <div className="field"><label>UF<select className="select" value={form.state} onChange={e => set({ state: e.target.value })}>
          <option value="">—</option>{STATES.map(uf => <option key={uf} value={uf}>{uf}</option>)}
        </select></label></div>
      </div>
      <h2 style={{ marginTop: 28 }}>Condições comerciais padrão</h2>
      <div className="form-grid">
        <div className="field"><label>Prazo de pagamento<input className="input" placeholder="ex.: 28/35/42 dias" value={form.paymentTerms}
                                                               onChange={e => set({ paymentTerms: e.target.value })} /></label></div>
        <div className="field"><label>Forma preferencial<input className="input" placeholder="ex.: Boleto" value={form.preferredPaymentMethod}
                                                              onChange={e => set({ preferredPaymentMethod: e.target.value })} /></label></div>
        <div className="field"><label>Dia usual de vencimento<input className="input" inputMode="numeric" value={form.usualDueDay}
                                                                   onChange={e => set({ usualDueDay: e.target.value })} /></label>
          {errors.usualDueDay && <span role="alert" className="error-text">{errors.usualDueDay}</span>}</div>
        <div className="field"><label>Limite de crédito (R$)<input className="input" inputMode="decimal" value={form.creditLimit}
                                                                   onChange={e => set({ creditLimit: e.target.value })} /></label>
          {errors.creditLimit && <span role="alert" className="error-text">{errors.creditLimit}</span>}</div>
        <div className="field full"><label>Instruções comerciais<input className="input" value={form.commercialNotes}
                                                                        onChange={e => set({ commercialNotes: e.target.value })} /></label></div>
      </div>
      <div className="form-actions">
        <button type="button" className="btn secondary" onClick={() => navigate(supplier ? `/compras/fornecedores/${supplier.id}` : '/compras/fornecedores')}>Cancelar</button>
        <button type="button" className="btn" disabled={!valid || mutation.isPending || form.legalName.trim().length < 2} onClick={() => { setError(''); mutation.mutate() }}>
          {supplier ? 'Salvar alterações' : 'Cadastrar fornecedor'}
        </button>
      </div>
    </section>
  </>
}

// ====================================================================== detalhe

export function SupplierDetailPage() {
  const { id = '' } = useParams()
  const supplier = useQuery({ queryKey: purchasingKeys.supplier(id), queryFn: () => purchasingApi.supplier(id) })
  return <QueryState label="o fornecedor" loading={supplier.isLoading} error={supplier.error} retry={supplier.refetch}>
    {supplier.data && <SupplierView supplier={supplier.data} />}
  </QueryState>
}

function SupplierView({ supplier: s }: { supplier: Supplier }) {
  const canManage = useCan(PURCHASE_SUPPLIER_MANAGE)
  const a = s.address
  const t = s.commercialTerms
  const address = [a.street && `${a.street}${a.number ? `, ${a.number}` : ''}`, a.complement, a.district,
    a.city && `${a.city}${a.state ? `/${a.state}` : ''}`, a.zipCode && formatZipCode(a.zipCode)].filter(Boolean).join(' · ')
  return <>
    <PageHeader title={s.legalName} subtitle={`${PERSON_LABELS[s.personType]}${s.document ? ` · ${formatTaxId(s.document)}` : ''}`}>
      <Link className="btn secondary" to="/compras/fornecedores">Voltar</Link>
      {canManage && <Link className="btn" to={`/compras/fornecedores/${s.id}/editar`}>Editar</Link>}
    </PageHeader>
    <div className="detail-grid">
      <div className="stack">
        <section className="card" aria-label="Dados do fornecedor">
          <h2>Dados <Badge tone={s.status === 'ACTIVE' ? 'success' : 'warning'}>{s.status === 'ACTIVE' ? 'Ativo' : 'Inativo'}</Badge>{' '}
            {s.relatedParty && <Badge tone="info">Empresa relacionada</Badge>}</h2>
          {s.documentMasked && <p className="muted">O CPF está parcialmente oculto para o seu perfil.</p>}
          <dl className="summary-list">
            <div><dt>Nome fantasia</dt><dd>{s.tradeName ?? '—'}</dd></div>
            <div><dt>Inscrição estadual</dt><dd>{s.stateRegistration ?? '—'}</dd></div>
            <div><dt>Telefone</dt><dd>{formatPhone(s.phone)}</dd></div>
            <div><dt>E-mail</dt><dd>{s.email ?? '—'}</dd></div>
            <div><dt>Endereço</dt><dd>{address || '—'}</dd></div>
            {s.relatedParty && <div><dt>Relação com a oficina</dt><dd>{s.relatedPartyNote ?? 'Empresa relacionada'}</dd></div>}
          </dl>
          {s.status === 'INACTIVE' && <p className="notice">Inativo desde {s.inactivatedAt ? formatDate(s.inactivatedAt) : '—'}: {s.inactivationReason}</p>}
        </section>
        <section className="card" aria-label="Condições comerciais">
          <h2>Condições comerciais padrão</h2>
          <dl className="summary-list">
            <div><dt>Prazo de pagamento</dt><dd>{t.paymentTerms ?? '—'}</dd></div>
            <div><dt>Forma preferencial</dt><dd>{t.preferredPaymentMethod ?? '—'}</dd></div>
            <div><dt>Dia usual de vencimento</dt><dd>{t.usualDueDay ?? '—'}</dd></div>
            <div><dt>Limite de crédito</dt><dd>{t.creditLimit == null ? '—' : formatBrl(t.creditLimit)}</dd></div>
            <div className="full"><dt>Instruções comerciais</dt><dd>{t.notes ?? '—'}</dd></div>
          </dl>
          <p className="muted">São sugestões: uma compra específica pode usar condições diferentes, e alterar o cadastro não muda compras anteriores.</p>
        </section>
        <ContactsCard supplier={s} canManage={canManage} />
      </div>
      <aside className="stack">
        {canManage && <StatusCard supplier={s} />}
        {canManage && <HistoryCard supplierId={s.id} />}
      </aside>
    </div>
  </>
}

// ---------------------------------------------------------------------- contatos

function ContactsCard({ supplier, canManage }: { supplier: Supplier; canManage: boolean }) {
  const client = useQueryClient()
  const contacts = useQuery({ queryKey: purchasingKeys.contacts(supplier.id), queryFn: () => purchasingApi.contacts(supplier.id) })
  const [editing, setEditing] = useState<SupplierContact | 'new' | null>(null)
  const refresh = async () => {
    await Promise.all([client.invalidateQueries({ queryKey: purchasingKeys.contacts(supplier.id) }),
      client.invalidateQueries({ queryKey: [...purchasingKeys.supplier(supplier.id), 'events'] })])
  }
  return <section className="card" aria-label="Contatos">
    <h2>Contatos comerciais</h2>
    <QueryState label="os contatos" loading={contacts.isLoading} error={contacts.error} retry={contacts.refetch}
                empty={!contacts.data?.length && editing === null} emptyMessage="Nenhum contato cadastrado.">
      {contacts.data && contacts.data.length > 0 && <div className="table-wrap"><table className="table">
        <thead><tr><th>Nome</th><th>Função</th><th>Telefone</th><th>E-mail</th><th /></tr></thead>
        <tbody>{contacts.data.map(c => <tr key={c.id}>
          <td><strong>{c.name}</strong> {c.primary && <Badge tone="info">Principal</Badge>}</td>
          <td>{c.role ?? '—'}</td><td>{formatPhone(c.phone)}</td><td>{c.email ?? '—'}</td>
          <td>{canManage && <div className="actions">
            <button type="button" className="btn secondary" onClick={() => setEditing(c)}>Editar</button>
            <ConfirmAction label="Remover" question={`Remover o contato ${c.name}? Ele deixa de aparecer, mas o histórico é preservado.`}
                           confirmLabel="Confirmar remoção" onConfirm={async () => { await purchasingApi.removeContact(supplier.id, c.id); await refresh() }} />
          </div>}</td>
        </tr>)}</tbody>
      </table></div>}
    </QueryState>
    {canManage && editing === null && <div className="form-actions"><button type="button" className="btn secondary" onClick={() => setEditing('new')}>Adicionar contato</button></div>}
    {canManage && editing !== null && <ContactForm key={editing === 'new' ? 'new' : editing.id} supplierId={supplier.id}
                                                   contact={editing === 'new' ? undefined : editing}
                                                   onDone={async () => { setEditing(null); await refresh() }} onCancel={() => setEditing(null)} />}
  </section>
}

function ContactForm({ supplierId, contact, onDone, onCancel }: {
  supplierId: string; contact?: SupplierContact; onDone: () => Promise<unknown>; onCancel: () => void
}) {
  const [form, setForm] = useState({ name: contact?.name ?? '', role: contact?.role ?? '', phone: contact?.phone ?? '', email: contact?.email ?? '', primary: contact?.primary ?? false })
  const [error, setError] = useState('')
  const emailInvalid = form.email.trim() !== '' && !EMAIL.test(form.email.trim())
  const mutation = useMutation({
    mutationFn: () => {
      const body: ContactInput = { name: form.name.trim(), role: blankToNull(form.role), phone: blankToNull(form.phone), email: blankToNull(form.email), primary: form.primary }
      return contact ? purchasingApi.updateContact(supplierId, contact.id, body) : purchasingApi.addContact(supplierId, body)
    },
    onSuccess: () => onDone(),
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível salvar o contato'),
  })
  return <div className="notice" role="group" aria-label={contact ? 'Editar contato' : 'Novo contato'}>
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="form-grid">
      <div className="field"><label>Nome do contato *<input className="input" value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label></div>
      <div className="field"><label>Função<input className="input" placeholder="vendedor, financeiro, faturamento…" value={form.role} onChange={e => setForm({ ...form, role: e.target.value })} /></label></div>
      <div className="field"><label>Telefone do contato<input className="input" value={form.phone} onChange={e => setForm({ ...form, phone: e.target.value })} /></label></div>
      <div className="field"><label>E-mail do contato<input className="input" value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} /></label>
        {emailInvalid && <span role="alert" className="error-text">E-mail inválido</span>}</div>
      <div className="field full"><label><input type="checkbox" checked={form.primary} onChange={e => setForm({ ...form, primary: e.target.checked })} /> Contato principal</label></div>
    </div>
    <div className="form-actions">
      <button type="button" className="btn secondary" disabled={mutation.isPending} onClick={onCancel}>Cancelar</button>
      <button type="button" className="btn" disabled={form.name.trim().length < 1 || emailInvalid || mutation.isPending} onClick={() => { setError(''); mutation.mutate() }}>
        Salvar contato
      </button>
    </div>
  </div>
}

// ---------------------------------------------------------------------- situação e histórico

function StatusCard({ supplier }: { supplier: Supplier }) {
  const client = useQueryClient()
  const [reason, setReason] = useState('')
  const refresh = () => Promise.all([client.invalidateQueries({ queryKey: purchasingKeys.suppliers })])
  if (supplier.status === 'INACTIVE') {
    return <section className="card" aria-label="Situação do fornecedor">
      <h2>Reativar fornecedor</h2>
      <p className="muted">Reativar permite escolhê-lo de novo em novas compras.</p>
      <ConfirmAction tone="primary" label="Reativar fornecedor" question="Reativar este fornecedor?" confirmLabel="Confirmar reativação"
                     onConfirm={async () => { await purchasingApi.reactivate(supplier.id); await refresh() }} />
    </section>
  }
  return <section className="card" aria-label="Situação do fornecedor">
    <h2>Inativar fornecedor</h2>
    <p className="muted">Compras, cotações e preços já registrados são preservados. O fornecedor deixa de poder ser escolhido em novas compras.</p>
    <ConfirmAction label="Inativar fornecedor" question="Inativar este fornecedor?" confirmLabel="Confirmar inativação"
                   onConfirm={async () => {
                     if (reason.trim().length < 3) throw new Error('Informe o motivo da inativação')
                     await purchasingApi.inactivate(supplier.id, reason.trim())
                     setReason('')
                     await refresh()
                   }}>
      <div className="field"><label>Motivo da inativação *<input className="input" value={reason} onChange={e => setReason(e.target.value)} /></label></div>
    </ConfirmAction>
  </section>
}

const EVENT_LABELS: Record<SupplierEvent['type'], string> = {
  CREATED: 'Cadastro', UPDATED: 'Alteração', INACTIVATED: 'Inativação', REACTIVATED: 'Reativação',
  CONTACT_ADDED: 'Contato adicionado', CONTACT_UPDATED: 'Contato alterado', CONTACT_REMOVED: 'Contato removido',
}
const FIELD_LABELS: Record<string, string> = {
  personType: 'Tipo de pessoa', legalName: 'Nome/razão social', tradeName: 'Nome fantasia', document: 'CPF/CNPJ', stateRegistration: 'Inscrição estadual',
  phone: 'Telefone', email: 'E-mail', 'address.zipCode': 'CEP', 'address.street': 'Logradouro', 'address.number': 'Número', 'address.complement': 'Complemento',
  'address.district': 'Bairro', 'address.city': 'Cidade', 'address.state': 'UF', 'terms.paymentTerms': 'Prazo de pagamento',
  'terms.preferredPaymentMethod': 'Forma preferencial', 'terms.usualDueDay': 'Dia de vencimento', 'terms.creditLimit': 'Limite de crédito',
  'terms.notes': 'Instruções comerciais', relatedParty: 'Empresa relacionada', relatedPartyNote: 'Observação da relação', status: 'Situação',
  contact: 'Contato', 'contact.name': 'Nome do contato', 'contact.role': 'Função do contato', 'contact.phone': 'Telefone do contato',
  'contact.email': 'E-mail do contato', 'contact.primary': 'Contato principal',
}

function HistoryCard({ supplierId }: { supplierId: string }) {
  const [page, setPage] = useState(0)
  const events = useQuery({ queryKey: purchasingKeys.events(supplierId, page), queryFn: () => purchasingApi.events(supplierId, page), placeholderData: keepPreviousData })
  return <section className="card" aria-label="Histórico">
    <h2>Histórico</h2>
    <QueryState label="o histórico" loading={events.isLoading} error={events.error} retry={events.refetch}
                empty={!events.data?.items.length} emptyMessage="Sem registros.">
      <ul className="stack" style={{ listStyle: 'none', padding: 0 }}>
        {events.data?.items.map(event => <li key={event.id}>
          <strong>{EVENT_LABELS[event.type]}</strong> <span className="muted">{formatDate(event.occurredAt)} · usuário {event.actorId.slice(0, 8)}</span>
          {event.reason && <div className="muted">Motivo: {event.reason}</div>}
          {Object.entries(event.changes).map(([field, change]) => <div key={field} className="muted">
            {FIELD_LABELS[field] ?? field}: {change.before ?? '—'} → {change.after ?? '—'}
          </div>)}
        </li>)}
      </ul>
      {events.data && <Pagination page={events.data.page} totalPages={events.data.totalPages} totalItems={events.data.totalItems} onChange={setPage} />}
    </QueryState>
  </section>
}
