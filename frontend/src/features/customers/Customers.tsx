import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { customersApi, workOrdersApi } from '../../api/resources'
import { queryKeys } from '../../api/queryKeys'
import type { Customer } from '../../api/types'
import { Badge, Field, PageHeader, State } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { ConfirmAction, Pagination } from '../../components/controls'
import { useDebouncedValue } from '../../utils/useDebouncedValue'
import { digits, formatDate, formatDocument, formatPhone, formatZipCode, vehicleLabel } from '../../utils/format'

const PAGE_SIZE = 20

export function CustomersPage() {
  const [search, setSearch] = useState('')
  const [type, setType] = useState('ALL')
  const [status, setStatus] = useState('ALL')
  const [page, setPage] = useState(0)
  const q = useDebouncedValue(search.trim())
  const params = { q, personType: type === 'ALL' ? undefined : type, status: status === 'ALL' ? undefined : status, page, size: PAGE_SIZE }
  const query = useQuery({
    queryKey: queryKeys.customerSearch(params),
    queryFn: () => customersApi.search(params),
    placeholderData: keepPreviousData,
  })
  const items = query.data?.items ?? []

  return <>
    <PageHeader title="Clientes" subtitle="Pessoas físicas e jurídicas atendidas pela oficina.">
      <Link className="btn secondary" to="/clientes/novo/pj">Nova PJ</Link>
      <Link className="btn" to="/clientes/novo/pf">Novo cliente</Link>
    </PageHeader>
    <div className="toolbar">
      <input aria-label="Buscar clientes" className="input" placeholder="Buscar por nome, CPF/CNPJ, telefone ou placa"
             value={search} onChange={e => { setSearch(e.target.value); setPage(0) }} />
      <select aria-label="Tipo" className="select" value={type} onChange={e => { setType(e.target.value); setPage(0) }}>
        <option value="ALL">PF e PJ</option><option>PF</option><option>PJ</option>
      </select>
      <select aria-label="Status" className="select" value={status} onChange={e => { setStatus(e.target.value); setPage(0) }}>
        <option value="ALL">Todos os status</option><option value="ACTIVE">Ativos</option><option value="INACTIVE">Inativos</option>
      </select>
    </div>
    <State loading={query.isLoading} error={query.error} empty={!items.length}>
      <div className="card table-wrap">
        <table className="table">
          <thead><tr><th>Cliente</th><th>Tipo</th><th>Documento</th><th>Telefone</th><th>Status</th><th>Ações</th></tr></thead>
          <tbody>{items.map(c => <tr key={c.id}>
            <td>{c.name}</td>
            <td><Badge tone="info">{c.personType}</Badge></td>
            <td>{c.document ? formatDocument(c.document) : '—'}</td>
            <td>{formatPhone(c.phone)}</td>
            <td><StatusBadge status={c.status} /></td>
            <td><Link className="btn secondary" to={`/clientes/${c.id}`} aria-label={`Ver ${c.name}`}>Ver</Link></td>
          </tr>)}</tbody>
        </table>
      </div>
      {query.data && <Pagination page={query.data.page} totalPages={query.data.totalPages} totalItems={query.data.totalItems} onChange={setPage} />}
    </State>
  </>
}

const StatusBadge = ({ status }: { status: Customer['status'] }) =>
  <Badge tone={status === 'ACTIVE' ? 'success' : 'warning'}>{status === 'ACTIVE' ? 'Ativo' : 'Inativo'}</Badge>

const optionalText = z.string().trim()
const schema = z.object({
  personType: z.enum(['PF', 'PJ']),
  name: z.string().trim().min(2, 'Informe o nome'),
  document: optionalText,
  phone: z.string().refine(value => digits(value).length >= 10 && digits(value).length <= 13, 'Informe o telefone com DDD'),
  email: z.union([z.literal(''), z.email('E-mail inválido')]),
  zipCode: z.string().refine(value => value.trim() === '' || digits(value).length === 8, 'CEP deve ter 8 dígitos'),
  street: optionalText, number: optionalText, complement: optionalText, district: optionalText, city: optionalText,
  state: z.string().trim().refine(value => value === '' || /^[A-Za-z]{2}$/.test(value), 'UF com duas letras'),
}).superRefine((values, context) => {
  const document = digits(values.document)
  const expected = values.personType === 'PF' ? 11 : 14
  if (document && document.length !== expected)
    context.addIssue({ code: 'custom', path: ['document'], message: `${values.personType === 'PF' ? 'CPF' : 'CNPJ'} deve ter ${expected} dígitos` })
})
type FormInput = z.input<typeof schema>
type Form = z.infer<typeof schema>

const emptyForm = (personType: 'PF' | 'PJ'): FormInput => ({
  personType, name: '', document: '', phone: '', email: '',
  zipCode: '', street: '', number: '', complement: '', district: '', city: '', state: '',
})

const toFormValues = (c: Customer): FormInput => ({
  personType: c.personType, name: c.name, document: c.document ?? '', phone: c.phone ?? '', email: c.email ?? '',
  zipCode: c.address?.zipCode ?? '', street: c.address?.street ?? '', number: c.address?.number ?? '',
  complement: c.address?.complement ?? '', district: c.address?.district ?? '', city: c.address?.city ?? '', state: c.address?.state ?? '',
})

/** Endereço vazio vai como `null`: nenhuma parte é obrigatória (DR-0010). */
function toPayload(values: Form) {
  const address = {
    zipCode: digits(values.zipCode) || null, street: values.street || null, number: values.number || null,
    complement: values.complement || null, district: values.district || null, city: values.city || null,
    state: values.state ? values.state.toUpperCase() : null,
  }
  return {
    personType: values.personType, name: values.name, document: digits(values.document) || null,
    phone: digits(values.phone), email: values.email || null,
    address: Object.values(address).some(Boolean) ? address : null,
  }
}

export function CustomerFormPage({ type }: { type: 'PF' | 'PJ' }) {
  return <>
    <PageHeader title={type === 'PF' ? 'Cadastrar cliente PF' : 'Cadastrar cliente PJ'} subtitle="Nome e telefone são obrigatórios; documento e endereço são opcionais." />
    <CustomerForm defaults={emptyForm(type)} />
  </>
}

export function CustomerEditPage() {
  const { id = '' } = useParams()
  const query = useQuery({ queryKey: queryKeys.customer(id), queryFn: () => customersApi.get(id) })
  return <>
    <PageHeader title="Editar cliente" subtitle="O status é alterado pelas ações de inativar e reativar no detalhe do cliente." />
    <QueryState loading={query.isLoading} error={query.error} label="o cliente" retry={query.refetch}>
      {query.data && <CustomerForm customerId={id} defaults={toFormValues(query.data)} />}
    </QueryState>
  </>
}

function CustomerForm({ customerId, defaults }: { customerId?: string; defaults: FormInput }) {
  const navigate = useNavigate()
  const client = useQueryClient()
  const [apiError, setApiError] = useState('')
  const { register, handleSubmit, control, formState: { errors, isSubmitting } } =
    useForm<FormInput, unknown, Form>({ resolver: zodResolver(schema), defaultValues: defaults })
  const personType = useWatch({ control, name: 'personType' })
  const mutation = useMutation({
    mutationFn: (values: Form) => customerId ? customersApi.update(customerId, toPayload(values)) : customersApi.create(toPayload(values)),
    onSuccess: async saved => {
      await client.invalidateQueries({ queryKey: queryKeys.customers })
      navigate(saved?.id ? `/clientes/${saved.id}` : '/clientes')
    },
  })

  return <form className="card" onSubmit={handleSubmit(async values => {
    setApiError('')
    try { await mutation.mutateAsync(values) }
    catch (error) { setApiError(error instanceof Error ? error.message : 'Falha ao salvar o cliente') }
  })}>
    {apiError && <div role="alert" className="notice error">{apiError}</div>}
    <div className="form-grid">
      {customerId && <Field label="Tipo de pessoa">
        <select className="select" {...register('personType')}><option value="PF">Pessoa física</option><option value="PJ">Pessoa jurídica</option></select>
      </Field>}
      <Field label={personType === 'PF' ? 'Nome completo *' : 'Razão social *'} error={errors.name?.message}>
        <input className="input" {...register('name')} />
      </Field>
      <Field label={personType === 'PF' ? 'CPF' : 'CNPJ'} error={errors.document?.message}>
        <input className="input" inputMode="numeric" {...register('document')} />
      </Field>
      <Field label="Telefone *" error={errors.phone?.message}><input className="input" inputMode="tel" {...register('phone')} /></Field>
      <Field label="E-mail" error={errors.email?.message}><input className="input" type="email" {...register('email')} /></Field>
      <h2 className="form-section">Endereço</h2>
      <Field label="CEP" error={errors.zipCode?.message}><input className="input" inputMode="numeric" {...register('zipCode')} /></Field>
      <Field label="Logradouro"><input className="input" {...register('street')} /></Field>
      <Field label="Número"><input className="input" {...register('number')} /></Field>
      <Field label="Complemento"><input className="input" {...register('complement')} /></Field>
      <Field label="Bairro"><input className="input" {...register('district')} /></Field>
      <Field label="Cidade"><input className="input" {...register('city')} /></Field>
      <Field label="UF" error={errors.state?.message}><input className="input" maxLength={2} {...register('state')} /></Field>
    </div>
    <div className="form-actions">
      <button type="button" className="btn secondary" onClick={() => navigate(customerId ? `/clientes/${customerId}` : '/clientes')}>Cancelar</button>
      <button className="btn" disabled={isSubmitting || mutation.isPending}>Salvar cliente</button>
    </div>
  </form>
}

export function CustomerDetailPage() {
  const { id = '' } = useParams()
  const client = useQueryClient()
  const customer = useQuery({ queryKey: queryKeys.customer(id), queryFn: () => customersApi.get(id) })
  const vehicles = useQuery({ queryKey: queryKeys.customerVehicles(id), queryFn: () => customersApi.vehicles(id) })
  const orders = useQuery({ queryKey: queryKeys.workOrdersFiltered({ customerId: id }), queryFn: () => workOrdersApi.list({ customerId: id }) })

  if (customer.isLoading) return <div className="card state">Carregando cliente…</div>
  if (customer.error || !customer.data)
    return <QueryState loading={false} error={customer.error ?? new Error()} label="o cliente" retry={customer.refetch} />
  const c = customer.data
  const refresh = async () => {
    await client.invalidateQueries({ queryKey: queryKeys.customers })
  }
  const address = c.address && [
    [c.address.street, c.address.number].filter(Boolean).join(', '), c.address.complement, c.address.district,
    [c.address.city, c.address.state].filter(Boolean).join('/'), c.address.zipCode && formatZipCode(c.address.zipCode),
  ].filter(Boolean).join(' · ')

  return <>
    <PageHeader title={c.name} subtitle={c.personType === 'PF' ? 'Pessoa física' : 'Pessoa jurídica'}>
      <StatusBadge status={c.status} />
      <Link className="btn secondary" to={`/clientes/${id}/editar`}>Editar</Link>
      {c.status === 'ACTIVE'
        ? <ConfirmAction label="Inativar" question="Inativar este cliente? Os dados, veículos e o histórico serão preservados."
                         onConfirm={async () => { await customersApi.inactivate(id); await refresh() }} />
        : <ConfirmAction label="Reativar" tone="primary" question="Reativar este cliente?"
                         onConfirm={async () => { await customersApi.reactivate(id); await refresh() }} />}
    </PageHeader>
    <div className="detail-grid">
      <div className="stack">
        <section className="card">
          <h2>Veículos vinculados</h2>
          <QueryState label="veículos" loading={vehicles.isLoading} error={vehicles.error} retry={vehicles.refetch}
                      empty={!vehicles.data?.length} emptyMessage="Nenhum veículo vinculado.">
            <ul className="history-list">{vehicles.data?.map(v => <li key={v.id}>
              <Link to={`/veiculos/${v.id}`}>{vehicleLabel(v)}</Link>
              <span className="muted">{v.modelYear ?? '—'}{v.active === false ? ' · inativo' : ''}</span>
            </li>)}</ul>
          </QueryState>
          {c.status === 'ACTIVE' && <Link className="btn secondary" style={{ marginTop: 14 }} to={`/veiculos/novo?cliente=${id}`}>Cadastrar veículo</Link>}
        </section>
        <section className="card">
          <h2>Histórico de ordens de serviço</h2>
          <QueryState label="ordens de serviço" loading={orders.isLoading} error={orders.error} retry={orders.refetch}
                      empty={!orders.data?.length} emptyMessage="Nenhuma OS registrada para este cliente.">
            <ul className="history-list">{orders.data?.map(o => <li key={o.id}>
              <Link to={`/ordens-servico/${o.id}`}>OS #{o.number}</Link>
              <span className="muted">{o.statusInfo?.name ?? o.status} · {formatDate(o.openedAt)}</span>
            </li>)}</ul>
          </QueryState>
        </section>
      </div>
      <aside>
        <section className="card">
          <h2>Dados cadastrais</h2>
          <dl className="summary-list">
            <div><dt>{c.personType === 'PF' ? 'CPF' : 'CNPJ'}</dt><dd>{c.document ? formatDocument(c.document) : '—'}</dd></div>
            <div><dt>Telefone</dt><dd>{formatPhone(c.phone)}</dd></div>
            <div><dt>E-mail</dt><dd>{c.email || '—'}</dd></div>
            <div><dt>Endereço</dt><dd>{address || '—'}</dd></div>
            <div><dt>Cadastrado em</dt><dd>{c.createdAt ? formatDate(c.createdAt) : '—'}</dd></div>
          </dl>
        </section>
      </aside>
    </div>
  </>
}
