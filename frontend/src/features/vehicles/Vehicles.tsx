import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { customersApi, vehiclesApi, workOrdersApi } from '../../api/resources'
import { queryKeys } from '../../api/queryKeys'
import type { Vehicle } from '../../api/types'
import { Badge, Field, PageHeader, State } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { ConfirmAction, Pagination } from '../../components/controls'
import { useDebouncedValue } from '../../utils/useDebouncedValue'
import { formatDate, formatMileage, vehicleLabel } from '../../utils/format'

const PAGE_SIZE = 20

export function VehiclesPage() {
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('ALL')
  const [page, setPage] = useState(0)
  const q = useDebouncedValue(search.trim())
  const params = { q, active: status === 'ALL' ? undefined : status === 'ACTIVE', page, size: PAGE_SIZE }
  const query = useQuery({
    queryKey: queryKeys.vehicleSearch(params),
    queryFn: () => vehiclesApi.search(params),
    placeholderData: keepPreviousData,
  })
  const items = query.data?.items ?? []

  return <>
    <PageHeader title="Veículos" subtitle="Veículos atendidos e seus proprietários atuais.">
      <Link className="btn" to="/veiculos/novo">Novo veículo</Link>
    </PageHeader>
    <div className="toolbar">
      <input aria-label="Buscar veículos" className="input" placeholder="Buscar por placa, marca, modelo ou proprietário"
             value={search} onChange={e => { setSearch(e.target.value); setPage(0) }} />
      <select aria-label="Status" className="select" value={status} onChange={e => { setStatus(e.target.value); setPage(0) }}>
        <option value="ALL">Todos os status</option><option value="ACTIVE">Ativos</option><option value="INACTIVE">Inativos</option>
      </select>
    </div>
    <State loading={query.isLoading} error={query.error} empty={!items.length}>
      <div className="card table-wrap">
        <table className="table">
          <thead><tr><th>Placa</th><th>Veículo</th><th>Ano</th><th>Cor</th><th>Proprietário</th><th>Status</th><th>Ações</th></tr></thead>
          <tbody>{items.map(({ vehicle: v, customerName }) => <tr key={v.id}>
            <td><strong>{v.plate}</strong></td>
            <td>{v.manufacturer} {v.model}</td>
            <td>{v.modelYear ?? '—'}</td>
            <td>{v.color || '—'}</td>
            <td>{customerName}</td>
            <td><ActiveBadge active={v.active !== false} /></td>
            <td><Link className="btn secondary" to={`/veiculos/${v.id}`} aria-label={`Ver ${v.plate}`}>Ver</Link></td>
          </tr>)}</tbody>
        </table>
      </div>
      {query.data && <Pagination page={query.data.page} totalPages={query.data.totalPages} totalItems={query.data.totalItems} onChange={setPage} />}
    </State>
  </>
}

const ActiveBadge = ({ active }: { active: boolean }) => <Badge tone={active ? 'success' : 'warning'}>{active ? 'Ativo' : 'Inativo'}</Badge>

const optionalNumber = (message: string) => z.union([z.literal(''), z.coerce.number().int(message).nonnegative(message)])
const schema = z.object({
  customerId: z.string(),
  plate: z.string().trim().min(7, 'Informe a placa'),
  manufacturer: z.string().trim().min(1, 'Informe a marca'),
  model: z.string().trim().min(1, 'Informe o modelo'),
  modelYear: z.union([z.literal(''), z.coerce.number().int('Ano inválido').min(1900, 'Ano inválido').max(2100, 'Ano inválido')]),
  mileage: optionalNumber('Quilometragem inválida'),
  steeringGearManufacturer: z.string().trim(),
  color: z.string().trim(),
  notes: z.string().trim(),
})
type FormInput = z.input<typeof schema>
type Form = z.infer<typeof schema>

const vehiclePayload = (values: Form) => ({
  plate: values.plate.toUpperCase(),
  manufacturer: values.manufacturer,
  model: values.model,
  modelYear: values.modelYear === '' ? null : values.modelYear,
  mileage: values.mileage === '' ? null : values.mileage,
  steeringGearManufacturer: values.steeringGearManufacturer || null,
  color: values.color || null,
  notes: values.notes || null,
})

const toFormValues = (v: Vehicle): FormInput => ({
  customerId: v.customerId, plate: v.plate, manufacturer: v.manufacturer, model: v.model,
  modelYear: v.modelYear ?? '', mileage: v.mileage ?? '', steeringGearManufacturer: v.steeringGearManufacturer ?? '',
  color: v.color ?? '', notes: v.notes ?? '',
})

export function VehicleFormPage() {
  const [params] = useSearchParams()
  const defaults: FormInput = {
    customerId: params.get('cliente') ?? '', plate: '', manufacturer: '', model: '', modelYear: '', mileage: '',
    steeringGearManufacturer: '', color: '', notes: '',
  }
  return <>
    <PageHeader title="Cadastrar veículo" subtitle="Vincule o veículo a um cliente ativo." />
    <VehicleForm defaults={defaults} />
  </>
}

export function VehicleEditPage() {
  const { id = '' } = useParams()
  const query = useQuery({ queryKey: queryKeys.vehicle(id), queryFn: () => vehiclesApi.get(id) })
  return <>
    <PageHeader title="Editar veículo" subtitle="A troca de proprietário é feita no detalhe do veículo, preservando o histórico." />
    <QueryState loading={query.isLoading} error={query.error} label="o veículo" retry={query.refetch}>
      {query.data && <VehicleForm vehicleId={id} defaults={toFormValues(query.data)} />}
    </QueryState>
  </>
}

function VehicleForm({ vehicleId, defaults }: { vehicleId?: string; defaults: FormInput }) {
  const navigate = useNavigate()
  const client = useQueryClient()
  const [apiError, setApiError] = useState('')
  const creating = !vehicleId
  const customers = useQuery({ queryKey: queryKeys.customers, queryFn: customersApi.list, enabled: creating })
  const activeCustomers = customers.data?.filter(c => c.status === 'ACTIVE') ?? []
  const { register, handleSubmit, setError, setValue, formState: { errors, isSubmitting } } =
    useForm<FormInput, unknown, Form>({ resolver: zodResolver(schema), defaultValues: defaults })
  const preselected = defaults.customerId
  const preselectedAvailable = activeCustomers.some(c => c.id === preselected)
  // As opções chegam depois da montagem; o valor inicial só "pega" quando a opção existe.
  useEffect(() => { if (preselectedAvailable) setValue('customerId', preselected) }, [preselectedAvailable, preselected, setValue])
  const mutation = useMutation({
    mutationFn: (values: Form) => vehicleId
      ? vehiclesApi.update(vehicleId, vehiclePayload(values))
      : vehiclesApi.create({ customerId: values.customerId, ...vehiclePayload(values) }),
    onSuccess: async vehicle => {
      await Promise.all([
        client.invalidateQueries({ queryKey: queryKeys.vehicles }),
        client.invalidateQueries({ queryKey: queryKeys.customerVehicles(vehicle.customerId) }),
      ])
      navigate(vehicleId ? `/veiculos/${vehicleId}` : '/veiculos')
    },
  })
  const customersUnavailable = creating && (customers.isLoading || !!customers.error)

  return <form className="card" onSubmit={handleSubmit(async values => {
    setApiError('')
    if (creating && !activeCustomers.some(c => c.id === values.customerId)) {
      setError('customerId', { message: 'Selecione o proprietário' })
      return
    }
    try { await mutation.mutateAsync(values) }
    catch (error) { setApiError(error instanceof Error ? error.message : 'Falha ao salvar o veículo') }
  })}>
    {apiError && <div role="alert" className="notice error">{apiError}</div>}
    {creating && <QueryState label="clientes" loading={customers.isLoading} error={customers.error} retry={() => customers.refetch()}
                             empty={!activeCustomers.length} emptyMessage="Nenhum cliente ativo disponível." />}
    <div className="form-grid">
      {creating && <Field label="Proprietário *" error={errors.customerId?.message}>
        <select className="select" disabled={customersUnavailable} {...register('customerId')}>
          <option value="">Selecione</option>
          {activeCustomers.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
      </Field>}
      <Field label="Placa *" error={errors.plate?.message}><input className="input" {...register('plate')} /></Field>
      <Field label="Marca/Fabricante *" error={errors.manufacturer?.message}><input className="input" {...register('manufacturer')} /></Field>
      <Field label="Modelo *" error={errors.model?.message}><input className="input" {...register('model')} /></Field>
      <Field label="Ano modelo" error={errors.modelYear?.message}><input className="input" type="number" {...register('modelYear')} /></Field>
      <Field label="Cor"><input className="input" {...register('color')} /></Field>
      <Field label="Quilometragem" error={errors.mileage?.message}><input className="input" type="number" min="0" {...register('mileage')} /></Field>
      <Field label="Fabricante da caixa de direção"><input className="input" {...register('steeringGearManufacturer')} /></Field>
      <Field label="Observações" className="full"><textarea className="textarea" {...register('notes')} /></Field>
    </div>
    <div className="form-actions">
      <button type="button" className="btn secondary" onClick={() => navigate(vehicleId ? `/veiculos/${vehicleId}` : '/veiculos')}>Cancelar</button>
      <button className="btn" disabled={isSubmitting || mutation.isPending || customersUnavailable}>Salvar veículo</button>
    </div>
  </form>
}

export function VehicleDetailPage() {
  const { id = '' } = useParams()
  const client = useQueryClient()
  const vehicle = useQuery({ queryKey: queryKeys.vehicle(id), queryFn: () => vehiclesApi.get(id) })
  const owner = useQuery({
    queryKey: queryKeys.customer(vehicle.data?.customerId ?? ''),
    queryFn: () => customersApi.get(vehicle.data!.customerId),
    enabled: !!vehicle.data,
  })
  const ownership = useQuery({ queryKey: queryKeys.vehicleOwnership(id), queryFn: () => vehiclesApi.ownership(id) })
  const orders = useQuery({ queryKey: queryKeys.workOrdersFiltered({ vehicleId: id }), queryFn: () => workOrdersApi.list({ vehicleId: id }) })

  if (vehicle.isLoading) return <div className="card state">Carregando veículo…</div>
  if (vehicle.error || !vehicle.data)
    return <QueryState loading={false} error={vehicle.error ?? new Error()} label="o veículo" retry={vehicle.refetch} />
  const v = vehicle.data
  const active = v.active !== false
  const refresh = () => Promise.all([
    client.invalidateQueries({ queryKey: queryKeys.vehicles }),
    client.invalidateQueries({ queryKey: queryKeys.customers }),
  ])

  return <>
    <PageHeader title={vehicleLabel(v)} subtitle="Dados do veículo, proprietário e histórico.">
      <ActiveBadge active={active} />
      <Link className="btn secondary" to={`/veiculos/${id}/editar`}>Editar</Link>
      {active
        ? <ConfirmAction label="Inativar" question="Inativar este veículo? O histórico de serviços continua disponível."
                         onConfirm={async () => { await vehiclesApi.inactivate(id); await refresh() }} />
        : <ConfirmAction label="Reativar" tone="primary" question="Reativar este veículo?"
                         onConfirm={async () => { await vehiclesApi.reactivate(id); await refresh() }} />}
    </PageHeader>
    <div className="detail-grid">
      <div className="stack">
        <section className="card">
          <h2>Histórico de serviços</h2>
          <QueryState label="ordens de serviço" loading={orders.isLoading} error={orders.error} retry={orders.refetch}
                      empty={!orders.data?.length} emptyMessage="Nenhuma OS registrada para este veículo.">
            <ul className="history-list">{orders.data?.map(o => <li key={o.id}>
              <Link to={`/ordens-servico/${o.id}`}>OS #{o.number}</Link>
              <span className="muted">{o.statusInfo?.name ?? o.status} · {formatDate(o.openedAt)}{o.entryMileage == null ? '' : ` · ${formatMileage(o.entryMileage)}`}</span>
            </li>)}</ul>
          </QueryState>
        </section>
        <section className="card">
          <h2>Histórico de proprietários</h2>
          <QueryState label="histórico de proprietários" loading={ownership.isLoading} error={ownership.error} retry={ownership.refetch}>
            <ul className="history-list">{ownership.data?.map(o => <li key={o.id}>
              <Link to={`/clientes/${o.customerId}`}>{o.customerName}</Link>
              <span className="muted">{formatDate(o.startedAt)} — {o.endedAt ? formatDate(o.endedAt) : 'atual'}</span>
            </li>)}</ul>
          </QueryState>
        </section>
      </div>
      <aside className="stack">
        <section className="card">
          <h2>Dados do veículo</h2>
          <dl className="summary-list">
            <div><dt>Proprietário</dt><dd>
              <QueryState label="proprietário" loading={owner.isLoading} error={owner.error} retry={owner.refetch}>
                {owner.data && <Link to={`/clientes/${owner.data.id}`}>{owner.data.name}</Link>}
              </QueryState>
            </dd></div>
            <div><dt>Ano modelo</dt><dd>{v.modelYear ?? '—'}</dd></div>
            <div><dt>Cor</dt><dd>{v.color || '—'}</dd></div>
            <div><dt>Quilometragem</dt><dd>{v.mileage == null ? '—' : formatMileage(v.mileage)}</dd></div>
            <div><dt>Caixa de direção</dt><dd>{v.steeringGearManufacturer || '—'}</dd></div>
            <div><dt>Observações</dt><dd>{v.notes || '—'}</dd></div>
          </dl>
        </section>
        <OwnerTransferCard vehicle={v} onTransferred={refresh} />
      </aside>
    </div>
  </>
}

function OwnerTransferCard({ vehicle, onTransferred }: { vehicle: Vehicle; onTransferred: () => Promise<unknown> }) {
  const [customerId, setCustomerId] = useState('')
  const customers = useQuery({ queryKey: queryKeys.customers, queryFn: customersApi.list })
  const candidates = customers.data?.filter(c => c.status === 'ACTIVE' && c.id !== vehicle.customerId) ?? []
  const selected = candidates.find(c => c.id === customerId)
  return <section className="card">
    <h2>Trocar proprietário</h2>
    <p className="muted">O proprietário anterior fica registrado no histórico e as OS já abertas mantêm o cliente original.</p>
    <QueryState label="clientes" loading={customers.isLoading} error={customers.error} retry={customers.refetch}
                empty={!candidates.length} emptyMessage="Nenhum outro cliente ativo disponível.">
      <div className="field">
        <label htmlFor="new-owner">Novo proprietário</label>
        <select id="new-owner" className="select" value={customerId} onChange={e => setCustomerId(e.target.value)}>
          <option value="">Selecione</option>
          {candidates.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
      </div>
      {selected && <div style={{ marginTop: 12 }}>
        <ConfirmAction label="Trocar proprietário" tone="primary" question={`Transferir ${vehicle.plate} para ${selected.name}?`}
                       onConfirm={async () => { await vehiclesApi.transfer(vehicle.id, selected.id); setCustomerId(''); await onTransferred() }} />
      </div>}
    </QueryState>
  </section>
}
