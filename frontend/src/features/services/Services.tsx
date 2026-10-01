import { useState } from 'react'
import { Link, NavLink, useNavigate, useParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { serviceCategoriesApi, servicesApi, vehicleGroupsApi, vehiclesApi } from '../../api/resources'
import { queryKeys } from '../../api/queryKeys'
import type { Service } from '../../api/types'
import { Badge, Field, PageHeader, State } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { ConfirmAction, Pagination } from '../../components/controls'
import { useDebouncedValue } from '../../utils/useDebouncedValue'
import { formatBrl, vehicleLabel } from '../../utils/format'

const PAGE_SIZE = 20

export function ServicesTabs() {
  return <nav className="toolbar" aria-label="Seções de serviços">
    <NavLink end className="btn secondary" to="/servicos">Serviços</NavLink>
    <NavLink className="btn secondary" to="/servicos/categorias">Categorias</NavLink>
    <NavLink className="btn secondary" to="/servicos/grupos">Grupos de veículos</NavLink>
  </nav>
}

const ActiveBadge = ({ active }: { active: boolean }) => <Badge tone={active ? 'success' : 'warning'}>{active ? 'Ativo' : 'Inativo'}</Badge>
const price = (value: number | null) => value == null ? '—' : formatBrl(value)

export function ServicesPage() {
  const [search, setSearch] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [status, setStatus] = useState('ALL')
  const [page, setPage] = useState(0)
  const q = useDebouncedValue(search.trim())
  const params = { q, categoryId: categoryId || undefined, active: status === 'ALL' ? undefined : status === 'ACTIVE', page, size: PAGE_SIZE }
  const query = useQuery({ queryKey: queryKeys.serviceSearch(params), queryFn: () => servicesApi.search(params), placeholderData: keepPreviousData })
  const categories = useQuery({ queryKey: queryKeys.serviceCategories, queryFn: serviceCategoriesApi.list })
  const items = query.data?.items ?? []

  return <>
    <PageHeader title="Serviços" subtitle="Catálogo de serviços, categorias e preços por veículo ou grupo.">
      <Link className="btn" to="/servicos/novo">Novo serviço</Link>
    </PageHeader>
    <ServicesTabs />
    <div className="toolbar">
      <input aria-label="Buscar serviços" className="input" placeholder="Buscar por nome ou descrição" value={search}
             onChange={e => { setSearch(e.target.value); setPage(0) }} />
      <select aria-label="Categoria" className="select" value={categoryId} onChange={e => { setCategoryId(e.target.value); setPage(0) }}>
        <option value="">Todas as categorias</option>
        {categories.data?.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
      </select>
      <select aria-label="Status" className="select" value={status} onChange={e => { setStatus(e.target.value); setPage(0) }}>
        <option value="ALL">Todos os status</option><option value="ACTIVE">Ativos</option><option value="INACTIVE">Inativos</option>
      </select>
    </div>
    <State loading={query.isLoading} error={query.error} empty={!items.length}>
      <div className="card table-wrap">
        <table className="table">
          <thead><tr><th>Serviço</th><th>Categoria</th><th>Preço base</th><th>Garantia</th><th>Status</th><th>Ações</th></tr></thead>
          <tbody>{items.map(s => <tr key={s.id}>
            <td><strong>{s.name}</strong>{s.description && <div className="muted">{s.description}</div>}</td>
            <td>{s.category || '—'}</td>
            <td>{price(s.basePrice)}</td>
            <td>{s.defaultWarrantyDays} dias</td>
            <td><ActiveBadge active={s.active} /></td>
            <td><Link className="btn secondary" to={`/servicos/${s.id}`} aria-label={`Ver ${s.name}`}>Ver</Link></td>
          </tr>)}</tbody>
        </table>
      </div>
      {query.data && <Pagination page={query.data.page} totalPages={query.data.totalPages} totalItems={query.data.totalItems} onChange={setPage} />}
    </State>
  </>
}

const schema = z.object({
  name: z.string().trim().min(2, 'Informe o nome'),
  description: z.string().trim(),
  categoryId: z.string(),
  basePrice: z.union([z.literal(''), z.coerce.number().nonnegative('Preço inválido')]),
  defaultWarrantyDays: z.coerce.number().int('Garantia inválida').nonnegative('Garantia inválida'),
})
type FormInput = z.input<typeof schema>
type Form = z.infer<typeof schema>

const toFormValues = (s: Service): FormInput => ({
  name: s.name, description: s.description ?? '', categoryId: s.categoryId ?? '', basePrice: s.basePrice ?? '', defaultWarrantyDays: s.defaultWarrantyDays,
})

export function ServiceFormPage() {
  const { id } = useParams()
  const query = useQuery({ queryKey: queryKeys.service(id ?? ''), queryFn: () => servicesApi.get(id!), enabled: !!id })
  if (!id) return <>
    <PageHeader title="Cadastrar serviço" subtitle="Somente o nome é obrigatório; o preço pode variar por veículo ou grupo." />
    <ServiceForm defaults={{ name: '', description: '', categoryId: '', basePrice: '', defaultWarrantyDays: 90 }} />
  </>
  return <>
    <PageHeader title="Editar serviço" subtitle="Alterações não modificam valores já registrados em OS." />
    <QueryState loading={query.isLoading} error={query.error} label="o serviço" retry={query.refetch}>
      {query.data && <ServiceForm serviceId={id} defaults={toFormValues(query.data)} />}
    </QueryState>
  </>
}

function ServiceForm({ serviceId, defaults }: { serviceId?: string; defaults: FormInput }) {
  const navigate = useNavigate()
  const client = useQueryClient()
  const [apiError, setApiError] = useState('')
  const categories = useQuery({ queryKey: queryKeys.serviceCategories, queryFn: serviceCategoriesApi.list })
  const { register, handleSubmit, formState: { errors, isSubmitting } } =
    useForm<FormInput, unknown, Form>({ resolver: zodResolver(schema), defaultValues: defaults })
  const mutation = useMutation({
    mutationFn: (values: Form) => {
      const body = {
        name: values.name, description: values.description || null, categoryId: values.categoryId || null,
        basePrice: values.basePrice === '' ? null : values.basePrice, defaultWarrantyDays: values.defaultWarrantyDays,
      }
      return serviceId ? servicesApi.update(serviceId, body) : servicesApi.create({ ...body, active: true })
    },
    onSuccess: async saved => {
      await client.invalidateQueries({ queryKey: queryKeys.services })
      navigate(saved?.id ? `/servicos/${saved.id}` : '/servicos')
    },
  })
  const usable = categories.data?.filter(c => c.active || c.id === defaults.categoryId) ?? []

  return <form className="card" onSubmit={handleSubmit(async values => {
    setApiError('')
    try { await mutation.mutateAsync(values) }
    catch (error) { setApiError(error instanceof Error ? error.message : 'Falha ao salvar o serviço') }
  })}>
    {apiError && <div role="alert" className="notice error">{apiError}</div>}
    <div className="form-grid">
      <Field label="Nome *" error={errors.name?.message}><input className="input" {...register('name')} /></Field>
      <Field label="Categoria">
        <select className="select" {...register('categoryId')}>
          <option value="">Sem categoria</option>
          {usable.map(c => <option key={c.id} value={c.id}>{c.name}{c.active ? '' : ' (inativa)'}</option>)}
        </select>
      </Field>
      <Field label="Preço base" error={errors.basePrice?.message}><input className="input" type="number" step="0.01" min="0" {...register('basePrice')} /></Field>
      <Field label="Garantia padrão em dias" error={errors.defaultWarrantyDays?.message}><input className="input" type="number" {...register('defaultWarrantyDays')} /></Field>
      <Field label="Descrição" error={errors.description?.message} className="full"><textarea className="textarea" {...register('description')} /></Field>
    </div>
    <div className="form-actions">
      <button type="button" className="btn secondary" onClick={() => navigate(serviceId ? `/servicos/${serviceId}` : '/servicos')}>Cancelar</button>
      <button className="btn" disabled={isSubmitting || mutation.isPending}>Salvar serviço</button>
    </div>
  </form>
}

export function ServiceDetailPage() {
  const { id = '' } = useParams()
  const client = useQueryClient()
  const service = useQuery({ queryKey: queryKeys.service(id), queryFn: () => servicesApi.get(id) })
  const prices = useQuery({ queryKey: queryKeys.servicePrices(id), queryFn: () => servicesApi.prices(id) })
  const groups = useQuery({ queryKey: queryKeys.vehicleGroups, queryFn: vehicleGroupsApi.list })
  if (service.isLoading) return <div className="card state">Carregando serviço…</div>
  if (service.error || !service.data) return <QueryState loading={false} error={service.error ?? new Error()} label="o serviço" retry={service.refetch} />
  const s = service.data
  const refresh = () => client.invalidateQueries({ queryKey: queryKeys.services })
  const groupPrices = prices.data?.filter(p => p.vehicleGroupId) ?? []
  const vehiclePrices = prices.data?.filter(p => p.vehicleId) ?? []

  return <>
    <PageHeader title={s.name} subtitle={s.category ? `Categoria: ${s.category}` : 'Sem categoria'}>
      <ActiveBadge active={s.active} />
      <Link className="btn secondary" to={`/servicos/${id}/editar`}>Editar</Link>
      {s.active
        ? <ConfirmAction label="Inativar" question="Inativar este serviço? Ele deixa de ser oferecido em novas OS; o histórico é preservado."
                         onConfirm={async () => { await servicesApi.inactivate(id); await refresh() }} />
        : <ConfirmAction label="Reativar" tone="primary" question="Reativar este serviço?"
                         onConfirm={async () => { await servicesApi.reactivate(id); await refresh() }} />}
    </PageHeader>
    <div className="detail-grid">
      <div className="stack">
        <section className="card">
          <h2>Prioridade de preço</h2>
          <p className="muted">Na OS, o preço sugerido segue: preço do veículo → preço do grupo do veículo → preço base. O valor pode ser alterado manualmente no lançamento.</p>
          <dl className="summary-list">
            <div><dt>Preço base</dt><dd>{price(s.basePrice)}</dd></div>
            <div><dt>Garantia padrão</dt><dd>{s.defaultWarrantyDays} dias</dd></div>
            <div><dt>Descrição</dt><dd>{s.description || '—'}</dd></div>
          </dl>
        </section>
        <section className="card">
          <h2>Preços por grupo de veículos</h2>
          <QueryState label="preços" loading={prices.isLoading} error={prices.error} retry={prices.refetch} empty={!groupPrices.length}
                      emptyMessage="Nenhum preço por grupo.">
            <ul className="history-list">{groupPrices.map(p => <li key={p.id}>
              <span>{p.vehicleGroupName}</span>
              <span><strong>{formatBrl(p.price)}</strong> <RemovePrice serviceId={id} priceId={p.id} /></span>
            </li>)}</ul>
          </QueryState>
          <PriceForm label="Grupo" options={(groups.data ?? []).filter(g => g.active).map(g => ({ id: g.id, label: g.name }))}
                     onSave={async (groupId, value) => { await servicesApi.setGroupPrice(id, groupId, value); await client.invalidateQueries({ queryKey: queryKeys.servicePrices(id) }) }} />
        </section>
        <section className="card">
          <h2>Preços por veículo</h2>
          <QueryState label="preços" loading={prices.isLoading} error={prices.error} retry={prices.refetch} empty={!vehiclePrices.length}
                      emptyMessage="Nenhum preço específico por veículo.">
            <ul className="history-list">{vehiclePrices.map(p => <li key={p.id}>
              <VehicleName id={p.vehicleId!} />
              <span><strong>{formatBrl(p.price)}</strong> <RemovePrice serviceId={id} priceId={p.id} /></span>
            </li>)}</ul>
          </QueryState>
          <VehiclePriceForm onSave={async (vehicleId, value) => { await servicesApi.setVehiclePrice(id, vehicleId, value); await client.invalidateQueries({ queryKey: queryKeys.servicePrices(id) }) }} />
        </section>
      </div>
    </div>
  </>
}

function VehicleName({ id }: { id: string }) {
  const vehicle = useQuery({ queryKey: queryKeys.vehicle(id), queryFn: () => vehiclesApi.get(id) })
  return <Link to={`/veiculos/${id}`}>{vehicle.data ? vehicleLabel(vehicle.data) : 'Veículo'}</Link>
}

function RemovePrice({ serviceId, priceId }: { serviceId: string; priceId: string }) {
  const client = useQueryClient()
  const remove = useMutation({
    mutationFn: () => servicesApi.deletePrice(serviceId, priceId),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.servicePrices(serviceId) }),
  })
  return <button type="button" className="btn secondary" disabled={remove.isPending} onClick={() => remove.mutate()}>Remover</button>
}

function PriceForm({ label, options, onSave }: { label: string; options: { id: string; label: string }[]; onSave: (id: string, value: number) => Promise<void> }) {
  const [target, setTarget] = useState('')
  const [value, setValue] = useState('')
  const [error, setError] = useState('')
  const [pending, setPending] = useState(false)
  return <div className="inline-form" style={{ marginTop: 14 }}>
    <div className="field"><label>{label}<select className="select" value={target} onChange={e => setTarget(e.target.value)}>
      <option value="">Selecione</option>{options.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}
    </select></label></div>
    <div className="field"><label>Preço<input className="input" type="number" step="0.01" min="0" value={value} onChange={e => setValue(e.target.value)} /></label></div>
    <button type="button" className="btn" disabled={!target || value === '' || Number(value) < 0 || pending} onClick={async () => {
      setPending(true); setError('')
      try { await onSave(target, Number(value)); setTarget(''); setValue('') }
      catch (e) { setError(e instanceof Error ? e.message : 'Falha ao salvar o preço') }
      finally { setPending(false) }
    }}>Salvar preço</button>
    {error && <div role="alert" className="notice error">{error}</div>}
  </div>
}

function VehiclePriceForm({ onSave }: { onSave: (vehicleId: string, value: number) => Promise<void> }) {
  const [search, setSearch] = useState('')
  const q = useDebouncedValue(search.trim())
  const vehicles = useQuery({ queryKey: queryKeys.vehicleSearch({ q, size: 10 }), queryFn: () => vehiclesApi.search({ q, size: 10, active: true }), enabled: q.length >= 2 })
  return <>
    <div className="field" style={{ marginTop: 14 }}>
      <label>Buscar veículo por placa, modelo ou proprietário<input className="input" value={search} onChange={e => setSearch(e.target.value)} /></label>
    </div>
    <PriceForm label="Veículo" options={(vehicles.data?.items ?? []).map(({ vehicle, customerName }) => ({ id: vehicle.id, label: `${vehicleLabel(vehicle)} — ${customerName}` }))} onSave={onSave} />
  </>
}

export function ServiceCategoriesPage() {
  const client = useQueryClient()
  const categories = useQuery({ queryKey: queryKeys.serviceCategories, queryFn: serviceCategoriesApi.list })
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  const refresh = () => Promise.all([
    client.invalidateQueries({ queryKey: queryKeys.serviceCategories }), client.invalidateQueries({ queryKey: queryKeys.services }),
  ])
  const create = useMutation({ mutationFn: () => serviceCategoriesApi.create(name.trim()), onSuccess: async () => { setName(''); await refresh() } })
  return <>
    <PageHeader title="Categorias de serviços" subtitle="Organize o catálogo; categorias inativas permanecem nos serviços que já as usam." />
    <ServicesTabs />
    <form className="card inline-form" onSubmit={async e => {
      e.preventDefault(); setError('')
      try { await create.mutateAsync() } catch (err) { setError(err instanceof Error ? err.message : 'Falha ao criar categoria') }
    }}>
      <div className="field"><label>Nova categoria<input className="input" value={name} onChange={e => setName(e.target.value)} /></label></div>
      <button className="btn" disabled={name.trim().length < 2 || create.isPending}>Adicionar categoria</button>
      {error && <div role="alert" className="notice error">{error}</div>}
    </form>
    <State loading={categories.isLoading} error={categories.error} empty={!categories.data?.length}>
      <div className="card table-wrap" style={{ marginTop: 16 }}>
        <table className="table">
          <thead><tr><th>Categoria</th><th>Status</th><th>Ações</th></tr></thead>
          <tbody>{categories.data?.map(c => <tr key={c.id}>
            <td>{c.name}</td>
            <td><ActiveBadge active={c.active} /></td>
            <td>{c.active
              ? <ConfirmAction label="Inativar" question={`Inativar a categoria ${c.name}?`} onConfirm={async () => { await serviceCategoriesApi.inactivate(c.id); await refresh() }} />
              : <ConfirmAction label="Reativar" tone="primary" question={`Reativar a categoria ${c.name}?`} onConfirm={async () => { await serviceCategoriesApi.reactivate(c.id); await refresh() }} />}
            </td>
          </tr>)}</tbody>
        </table>
      </div>
    </State>
  </>
}

export function VehicleGroupsPage() {
  const client = useQueryClient()
  const groups = useQuery({ queryKey: queryKeys.vehicleGroups, queryFn: vehicleGroupsApi.list })
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState('')
  const refresh = () => client.invalidateQueries({ queryKey: queryKeys.vehicleGroups })
  const create = useMutation({
    mutationFn: () => vehicleGroupsApi.create({ name: name.trim(), description: description.trim() || null }),
    onSuccess: async () => { setName(''); setDescription(''); await refresh() },
  })
  return <>
    <PageHeader title="Grupos de veículos" subtitle="Cada veículo pertence a no máximo um grupo; o preço do grupo vale quando o veículo não tem preço próprio." />
    <ServicesTabs />
    <form className="card inline-form" onSubmit={async e => {
      e.preventDefault(); setError('')
      try { await create.mutateAsync() } catch (err) { setError(err instanceof Error ? err.message : 'Falha ao criar grupo') }
    }}>
      <div className="field"><label>Novo grupo<input className="input" value={name} onChange={e => setName(e.target.value)} /></label></div>
      <div className="field"><label>Descrição<input className="input" value={description} onChange={e => setDescription(e.target.value)} /></label></div>
      <button className="btn" disabled={name.trim().length < 2 || create.isPending}>Adicionar grupo</button>
      {error && <div role="alert" className="notice error">{error}</div>}
    </form>
    <State loading={groups.isLoading} error={groups.error} empty={!groups.data?.length}>
      <div className="card table-wrap" style={{ marginTop: 16 }}>
        <table className="table">
          <thead><tr><th>Grupo</th><th>Veículos</th><th>Status</th><th>Ações</th></tr></thead>
          <tbody>{groups.data?.map(g => <tr key={g.id}>
            <td><strong>{g.name}</strong>{g.description && <div className="muted">{g.description}</div>}</td>
            <td>{g.vehicleCount}</td>
            <td><ActiveBadge active={g.active} /></td>
            <td><Link className="btn secondary" to={`/servicos/grupos/${g.id}`} aria-label={`Ver ${g.name}`}>Ver</Link></td>
          </tr>)}</tbody>
        </table>
      </div>
    </State>
  </>
}

export function VehicleGroupDetailPage() {
  const { id = '' } = useParams()
  const client = useQueryClient()
  const groups = useQuery({ queryKey: queryKeys.vehicleGroups, queryFn: vehicleGroupsApi.list })
  const members = useQuery({ queryKey: queryKeys.groupVehicles(id), queryFn: () => vehicleGroupsApi.vehicles(id) })
  const [search, setSearch] = useState('')
  const [error, setError] = useState('')
  const q = useDebouncedValue(search.trim())
  const candidates = useQuery({ queryKey: queryKeys.vehicleSearch({ q, size: 10, group: id }), queryFn: () => vehiclesApi.search({ q, size: 10, active: true }), enabled: q.length >= 2 })
  const group = groups.data?.find(g => g.id === id)
  const refresh = () => Promise.all([
    client.invalidateQueries({ queryKey: queryKeys.vehicleGroups }),
  ])
  if (groups.isLoading) return <div className="card state">Carregando grupo…</div>
  if (!group) return <QueryState loading={false} error={groups.error ?? new Error()} label="o grupo" retry={groups.refetch} />

  return <>
    <PageHeader title={group.name} subtitle={group.description || 'Grupo de veículos'}>
      <ActiveBadge active={group.active} />
      {group.active
        ? <ConfirmAction label="Inativar" question="Inativar este grupo? Seus preços deixam de ser sugeridos." onConfirm={async () => { await vehicleGroupsApi.inactivate(id); await refresh() }} />
        : <ConfirmAction label="Reativar" tone="primary" question="Reativar este grupo?" onConfirm={async () => { await vehicleGroupsApi.reactivate(id); await refresh() }} />}
    </PageHeader>
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="detail-grid">
      <section className="card">
        <h2>Veículos do grupo</h2>
        <QueryState label="veículos do grupo" loading={members.isLoading} error={members.error} retry={members.refetch} empty={!members.data?.length}
                    emptyMessage="Nenhum veículo neste grupo.">
          <ul className="history-list">{members.data?.map(v => <li key={v.id}>
            <Link to={`/veiculos/${v.id}`}>{vehicleLabel(v)}</Link>
            <button type="button" className="btn secondary" onClick={async () => {
              setError('')
              try { await vehicleGroupsApi.remove(id, v.id); await refresh() }
              catch (e) { setError(e instanceof Error ? e.message : 'Falha ao remover') }
            }}>Remover</button>
          </li>)}</ul>
        </QueryState>
      </section>
      {group.active && <aside className="card">
        <h2>Adicionar veículo</h2>
        <p className="muted">Se o veículo estiver em outro grupo, ele será movido para este.</p>
        <div className="field"><label>Buscar por placa, modelo ou proprietário<input className="input" value={search} onChange={e => setSearch(e.target.value)} /></label></div>
        <ul className="history-list" style={{ marginTop: 12 }}>{candidates.data?.items.filter(item => !members.data?.some(m => m.id === item.vehicle.id)).map(({ vehicle, customerName }) => <li key={vehicle.id}>
          <span>{vehicleLabel(vehicle)} <span className="muted">— {customerName}</span></span>
          <button type="button" className="btn" onClick={async () => {
            setError('')
            try { await vehicleGroupsApi.assign(id, vehicle.id); await refresh() }
            catch (e) { setError(e instanceof Error ? e.message : 'Falha ao adicionar') }
          }}>Adicionar</button>
        </li>)}</ul>
      </aside>}
    </div>
  </>
}
