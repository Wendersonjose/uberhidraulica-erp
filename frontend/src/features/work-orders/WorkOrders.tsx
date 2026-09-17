import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { customersApi, servicesApi, vehiclesApi, workflowApi, workOrdersApi } from '../../api/resources'
import { queryKeys } from '../../api/queryKeys'
import { OPERATIONAL_STAGES, PRICE_SOURCE_LABELS, STAGE_LABELS, type Stage, type WorkOrder } from '../../api/types'
import { Badge, Field, PageHeader } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { ConfirmAction } from '../../components/controls'
import { formatBrl, formatDate, formatMileage, vehicleLabel } from '../../utils/format'
import { AddProductCard, WorkOrderProductsCard } from './WorkOrderProducts'
import { productsSubtotal } from './totals'

const stageTone = (stage: Stage): 'success' | 'warning' | 'info' =>
  stage === 'ENTREGUE' || stage === 'FINALIZADA' ? 'success' : stage === 'CANCELADA' || stage === 'REPROVADA' ? 'warning' : 'info'

export const StageBadge = ({ order }: { order: Pick<WorkOrder, 'status' | 'statusInfo'> }) =>
  <Badge tone={stageTone(order.status)}>{order.statusInfo?.name ?? STAGE_LABELS[order.status] ?? order.status}</Badge>

export function WorkOrdersPage() {
  const [closedDays, setClosedDays] = useState(30)
  const board = useQuery({ queryKey: queryKeys.board(closedDays), queryFn: () => workflowApi.board(closedDays) })
  const total = board.data?.reduce((sum, column) => sum + column.orders.length, 0) ?? 0

  return <>
    <PageHeader title="Ordens de serviço" subtitle="Kanban das OS por status; cancelada e entregue permanecem no histórico.">
      <Link className="btn secondary" to="/ordens-servico/status">Configurar status</Link>
      <Link className="btn" to="/ordens-servico/nova">Abrir nova OS</Link>
    </PageHeader>
    <div className="toolbar">
      <label className="muted">Encerradas nos últimos{' '}
        <select aria-label="Período das encerradas" className="select" value={closedDays} onChange={e => setClosedDays(Number(e.target.value))}>
          <option value={7}>7 dias</option><option value={30}>30 dias</option><option value={90}>90 dias</option><option value={365}>365 dias</option>
        </select>
      </label>
    </div>
    <QueryState label="ordens de serviço" loading={board.isLoading} error={board.error} retry={board.refetch}>
      {total === 0 && <div className="card state">Nenhum registro encontrado.</div>}
      <div className="kanban">{board.data?.map(column => <section className="card" key={column.status.id} aria-label={column.status.name}>
        <div className="column-title">
          <strong>{column.status.name}</strong>
          <Badge tone={column.status.active ? 'info' : 'warning'}>{column.orders.length}</Badge>
        </div>
        {column.orders.map(card => <Link className="card work-card" key={card.id} to={`/ordens-servico/${card.id}`}>
          <strong>OS #{card.number}</strong>
          <div>{card.customerName}</div>
          <div className="muted">{card.vehicleLabel}</div>
          {card.complaint && <div className="muted">{card.complaint}</div>}
          <span className="muted">Aberta em {formatDate(card.openedAt)}</span>
        </Link>)}
      </section>)}</div>
    </QueryState>
  </>
}

const optionalMileage = z.preprocess(value => value === '' ? undefined : value,
  z.coerce.number().int('Quilometragem inválida').nonnegative('Quilometragem inválida').optional())

const schema = z.object({
  customerId: z.string().min(1, 'Selecione o cliente'),
  vehicleId: z.string().min(1, 'Selecione o veículo'),
  entryMileage: optionalMileage,
  complaint: z.string().trim().min(3, 'Descreva o defeito ou a reclamação'),
  notes: z.string().trim(),
})
type FormInput = z.input<typeof schema>
type Form = z.infer<typeof schema>

export function WorkOrderFormPage() {
  const navigate = useNavigate()
  const client = useQueryClient()
  const [apiError, setApiError] = useState('')
  const customers = useQuery({ queryKey: queryKeys.customers, queryFn: customersApi.list })
  const { register, handleSubmit, control, setValue, formState: { errors, isSubmitting } } =
    useForm<FormInput, unknown, Form>({ resolver: zodResolver(schema), defaultValues: { customerId: '', vehicleId: '', entryMileage: '', complaint: '', notes: '' } })
  const customerId = useWatch({ control, name: 'customerId' })
  const vehicleId = useWatch({ control, name: 'vehicleId' })
  const vehicles = useQuery({ queryKey: queryKeys.customerVehicles(customerId), queryFn: () => customersApi.vehicles(customerId), enabled: !!customerId })
  const activeCustomers = customers.data?.filter(c => c.status === 'ACTIVE') ?? []
  const activeVehicles = vehicles.data?.filter(v => v.active !== false) ?? []
  const mutation = useMutation({
    mutationFn: (values: Form) => workOrdersApi.create({
      customerId: values.customerId, vehicleId: values.vehicleId, entryMileage: values.entryMileage ?? null,
      complaint: values.complaint, notes: values.notes || null,
    }),
    onSuccess: async order => {
      await client.invalidateQueries({ queryKey: queryKeys.workOrders })
      navigate(`/ordens-servico/${order.id}`)
    },
  })
  const unavailable = customers.isLoading || !!customers.error || vehicles.isLoading || !!vehicles.error

  return <>
    <PageHeader title="Abrir nova OS" subtitle="Cliente, veículo do cliente e defeito relatado são obrigatórios." />
    <form className="card" onSubmit={handleSubmit(async values => {
      setApiError('')
      if (!activeCustomers.some(c => c.id === values.customerId) || !activeVehicles.some(v => v.id === values.vehicleId && v.customerId === values.customerId)) return
      try { await mutation.mutateAsync(values) }
      catch (error) { setApiError(error instanceof Error ? error.message : 'Falha ao abrir a OS') }
    })}>
      {apiError && <div role="alert" className="notice error">{apiError}</div>}
      <QueryState label="clientes" loading={customers.isLoading} error={customers.error} retry={() => customers.refetch()}
                  empty={!activeCustomers.length} emptyMessage="Nenhum cliente ativo disponível." />
      {customerId && <QueryState label="veículos" loading={vehicles.isLoading} error={vehicles.error} retry={() => vehicles.refetch()}
                                 empty={!activeVehicles.length}
                                 emptyMessage={<>O cliente ainda não possui veículo cadastrado. <Link to={`/veiculos/novo?cliente=${customerId}`}>Cadastrar veículo</Link></>} />}
      <div className="form-grid">
        <Field label="Cliente *" error={errors.customerId?.message}>
          <select aria-label="Cliente" className="select" disabled={customers.isLoading || !!customers.error} {...register('customerId')}
                  onChange={e => { setValue('customerId', e.target.value, { shouldValidate: true }); setValue('vehicleId', '') }}>
            <option value="">Selecione</option>
            {activeCustomers.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </Field>
        <Field label="Veículo *" error={errors.vehicleId?.message}>
          <select aria-label="Veículo" className="select" disabled={!customerId || unavailable || !activeVehicles.length} {...register('vehicleId')}>
            <option value="">{customerId ? 'Selecione' : 'Selecione primeiro o cliente'}</option>
            {activeVehicles.map(v => <option key={v.id} value={v.id}>{vehicleLabel(v)}</option>)}
          </select>
        </Field>
        <Field label="Quilometragem de entrada" error={errors.entryMileage?.message}><input className="input" type="number" min="0" {...register('entryMileage')} /></Field>
        <Field label="Defeito/reclamação relatada *" error={errors.complaint?.message} className="full"><textarea className="textarea" {...register('complaint')} /></Field>
        <Field label="Observações" className="full"><textarea className="textarea" {...register('notes')} /></Field>
      </div>
      <div className="form-actions">
        <button type="button" className="btn secondary" onClick={() => navigate('/ordens-servico')}>Cancelar</button>
        <button className="btn" disabled={isSubmitting || mutation.isPending || unavailable || !activeVehicles.some(v => v.id === vehicleId && v.customerId === customerId)}>Criar OS</button>
      </div>
    </form>
  </>
}

export function WorkOrderDetailPage() {
  const { id = '' } = useParams()
  const client = useQueryClient()
  const [serviceId, setServiceId] = useState('')
  const [manualPrice, setManualPrice] = useState('')
  const [apiError, setApiError] = useState('')
  const order = useQuery({ queryKey: queryKeys.workOrder(id), queryFn: () => workOrdersApi.get(id) })
  const catalog = useQuery({ queryKey: queryKeys.services, queryFn: servicesApi.list })
  const customer = useQuery({ queryKey: queryKeys.customer(order.data?.customerId ?? ''), queryFn: () => customersApi.get(order.data!.customerId), enabled: !!order.data })
  const vehicle = useQuery({ queryKey: queryKeys.vehicle(order.data?.vehicleId ?? ''), queryFn: () => vehiclesApi.get(order.data!.vehicleId), enabled: !!order.data })
  const suggestion = useQuery({
    queryKey: queryKeys.priceSuggestion(serviceId, order.data?.vehicleId ?? ''),
    queryFn: () => servicesApi.suggestion(serviceId, order.data!.vehicleId),
    enabled: !!serviceId && !!order.data,
  })
  const add = useMutation({
    mutationFn: () => workOrdersApi.addService(id, serviceId, manualPrice === '' ? undefined : Number(manualPrice)),
    onSuccess: async () => { setServiceId(''); setManualPrice(''); await client.invalidateQueries({ queryKey: queryKeys.workOrder(id) }) },
  })

  if (order.isLoading) return <div className="card state">Carregando OS…</div>
  if (order.error || !order.data) return <div role="alert" className="notice error">Não foi possível consultar a OS.</div>
  const o = order.data
  const operational = OPERATIONAL_STAGES.includes(o.status)
  const subtotal = o.services.reduce((sum, item) => sum + Number(item.basePrice), 0)
  const available = catalog.data?.filter(s => s.active && !o.services.some(x => x.serviceId === s.id)) ?? []

  return <>
    <PageHeader title={`OS #${o.number}`} subtitle={o.complaint ?? 'Detalhes, serviços e itens vinculados.'}>
      <Link className="btn secondary" to={`/ordens-servico/${id}/orcamentos`}>Orçamentos</Link>
      <StageBadge order={o} />
    </PageHeader>
    {apiError && <div role="alert" className="notice error">{apiError}</div>}
    <WorkflowActions order={o} />
    <div className="detail-grid">
      <div>
        <DiagnosisCard order={o} />
        <section className="card">
          <h2>Serviços da OS</h2>
          {o.services.length === 0 ? <div className="state">Nenhum serviço vinculado.</div> : o.services.map(s => <div className="service-row" key={s.id}>
            <div>
              <strong>{s.name}</strong>
              <div className="muted">{[s.description, `garantia ${s.warrantyDays} dias`, s.priceSource && PRICE_SOURCE_LABELS[s.priceSource]].filter(Boolean).join(' · ')}</div>
            </div>
            <strong>{formatBrl(Number(s.basePrice))}</strong>
          </div>)}
          <div className="subtotal"><span>Subtotal de serviços</span><span data-testid="subtotal">{formatBrl(subtotal)}</span></div>
        </section>
        <WorkOrderProductsCard products={o.products} />
        <section className="card" style={{ marginTop: 22 }}>
          <div className="subtotal"><span>Total da OS</span><span data-testid="order-total">{formatBrl(subtotal + productsSubtotal(o.products))}</span></div>
        </section>
        <StatusHistoryCard orderId={id} />
      </div>
      <aside>
        <section className="card">
          <h2>Resumo</h2>
          <dl className="summary-list">
            <div><dt>Cliente</dt><dd><QueryState label="cliente" loading={customer.isLoading} error={customer.error} retry={() => customer.refetch()}>
              {customer.data && <Link to={`/clientes/${customer.data.id}`}>{customer.data.name}</Link>}
            </QueryState></dd></div>
            <div><dt>Veículo</dt><dd><QueryState label="veículo" loading={vehicle.isLoading} error={vehicle.error} retry={() => vehicle.refetch()}>
              {vehicle.data && <Link to={`/veiculos/${vehicle.data.id}`}>{vehicleLabel(vehicle.data)}</Link>}
            </QueryState></dd></div>
            <div><dt>Quilometragem</dt><dd>{o.entryMileage == null ? '—' : formatMileage(o.entryMileage)}</dd></div>
            <div><dt>Abertura</dt><dd>{formatDate(o.openedAt)}</dd></div>
            {o.notes && <div><dt>Observações</dt><dd>{o.notes}</dd></div>}
            {o.lifecycle?.executionStartedAt && <div><dt>Início da execução</dt><dd>{formatDate(o.lifecycle.executionStartedAt)}</dd></div>}
            {o.lifecycle?.finishedAt && <div><dt>Finalização</dt><dd>{formatDate(o.lifecycle.finishedAt)}</dd></div>}
            {o.lifecycle?.deliveredAt && <div><dt>Entrega</dt><dd>{formatDate(o.lifecycle.deliveredAt)}</dd></div>}
            {o.lifecycle?.cancelledAt && <div><dt>Cancelamento</dt><dd>{formatDate(o.lifecycle.cancelledAt)} — {o.lifecycle.cancellationReason}</dd></div>}
          </dl>
        </section>
        {operational && <>
          <section className="card" style={{ marginTop: 16 }}>
            <h2>Adicionar serviço</h2>
            <QueryState label="catálogo de serviços" loading={catalog.isLoading} error={catalog.error} retry={() => catalog.refetch()} empty={!available.length}
                        emptyMessage={catalog.data?.length ? 'Nenhum serviço ativo disponível para inclusão.' : 'Nenhum serviço cadastrado.'} />
            <div className="field">
              <label htmlFor="service">Catálogo ativo</label>
              <select id="service" disabled={catalog.isLoading || !!catalog.error || !available.length || add.isPending} className="select"
                      value={serviceId} onChange={e => setServiceId(e.target.value)}>
                <option value="">Selecione</option>
                {available.map(s => <option value={s.id} key={s.id}>{s.name}{s.basePrice == null ? '' : ` — ${formatBrl(s.basePrice)}`}</option>)}
              </select>
            </div>
            {serviceId && suggestion.data && <p className="muted" data-testid="price-suggestion">
              Sugerido: {suggestion.data.price == null ? 'sem preço definido — informe o preço praticado'
                : `${formatBrl(suggestion.data.price)} (${PRICE_SOURCE_LABELS[suggestion.data.source]})`}
            </p>}
            <div className="field">
              <label htmlFor="manual-price">Preço praticado (opcional)</label>
              <input id="manual-price" className="input" type="number" step="0.01" min="0" value={manualPrice} onChange={e => setManualPrice(e.target.value)} />
            </div>
            <button style={{ width: '100%', marginTop: 14 }} className="btn"
                    disabled={catalog.isLoading || !!catalog.error || !available.some(s => s.id === serviceId) || add.isPending}
                    onClick={async () => {
                      setApiError('')
                      try { await add.mutateAsync() } catch (e) { setApiError(e instanceof Error ? e.message : 'Falha ao adicionar serviço') }
                    }}>Adicionar à OS</button>
          </section>
          <AddProductCard orderId={id} onError={setApiError} />
        </>}
      </aside>
    </div>
  </>
}

/** Ações do fluxo, oferecidas conforme a etapa atual; o backend continua sendo a autoridade das transições. */
function WorkflowActions({ order }: { order: WorkOrder }) {
  const client = useQueryClient()
  const [error, setError] = useState('')
  const [statusId, setStatusId] = useState('')
  const [reason, setReason] = useState('')
  const statuses = useQuery({ queryKey: queryKeys.workflowStatuses, queryFn: workflowApi.statuses, enabled: OPERATIONAL_STAGES.includes(order.status) })
  const refresh = () => Promise.all([
    client.invalidateQueries({ queryKey: queryKeys.workOrders }), client.invalidateQueries({ queryKey: queryKeys.workflowStatuses }),
  ])
  const run = async (action: () => Promise<unknown>) => {
    setError('')
    try { await action(); await refresh() } catch (e) { setError(e instanceof Error ? e.message : 'Não foi possível concluir a ação'); throw e }
  }
  const stage = order.status
  if (stage === 'ENTREGUE' || stage === 'CANCELADA') return null
  const targets = statuses.data?.map(u => u.status).filter(s => s.active && OPERATIONAL_STAGES.includes(s.stage) && s.id !== order.statusInfo?.id) ?? []

  return <section className="card" style={{ marginBottom: 16 }} aria-label="Fluxo da OS">
    <h2>Fluxo</h2>
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="inline-form">
      {OPERATIONAL_STAGES.includes(stage) && <>
        <div className="field">
          <label>Mover para<select className="select" value={statusId} onChange={e => setStatusId(e.target.value)}>
            <option value="">Selecione</option>
            {targets.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select></label>
        </div>
        <button type="button" className="btn secondary" disabled={!statusId}
                onClick={() => run(() => workflowApi.move(order.id, statusId)).then(() => setStatusId(''), () => undefined)}>Mover</button>
        {stage !== 'EM_EXECUCAO' && stage !== 'REPROVADA' &&
          <button type="button" className="btn" onClick={() => run(() => workflowApi.startExecution(order.id)).catch(() => undefined)}>Iniciar execução</button>}
        {stage === 'EM_EXECUCAO' &&
          <ConfirmAction label="Finalizar OS" tone="primary" question="Finalizar a OS? Depois disso ela não aceita novos itens e só pode ser entregue."
                         onConfirm={() => run(() => workflowApi.finish(order.id))} />}
        <ConfirmAction label="Cancelar OS" question="Cancelar a OS? Os dados são preservados, mas ela não volta ao fluxo."
                       onConfirm={() => { if (reason.trim().length < 3) return Promise.reject(new Error('Informe o motivo do cancelamento')); return run(() => workflowApi.cancel(order.id, reason.trim())).then(() => setReason('')) }}>
          <div className="field"><label>Motivo do cancelamento *<textarea className="textarea" value={reason} onChange={e => setReason(e.target.value)} /></label></div>
        </ConfirmAction>
      </>}
      {stage === 'FINALIZADA' &&
        <ConfirmAction label="Registrar entrega" tone="primary" question="Registrar a entrega do veículo ao cliente?"
                       onConfirm={() => run(() => workflowApi.deliver(order.id))} />}
    </div>
  </section>
}

/** Diagnóstico técnico; editável só em etapa operacional, como no backend (DR-0013). */
function DiagnosisCard({ order }: { order: WorkOrder }) {
  const client = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState(order.diagnosis ?? '')
  const [error, setError] = useState('')
  const save = useMutation({
    mutationFn: () => workflowApi.diagnosis(order.id, text.trim()),
    onSuccess: async () => { setEditing(false); await client.invalidateQueries({ queryKey: queryKeys.workOrders }) },
  })
  const editable = OPERATIONAL_STAGES.includes(order.status)
  return <section className="card" style={{ marginBottom: 22 }} aria-label="Diagnóstico">
    <h2>Diagnóstico</h2>
    {error && <div role="alert" className="notice error">{error}</div>}
    {editing
      ? <>
        <div className="field"><label>Diagnóstico técnico<textarea className="textarea" value={text} onChange={e => setText(e.target.value)} /></label></div>
        <div className="form-actions">
          <button type="button" className="btn secondary" onClick={() => { setText(order.diagnosis ?? ''); setEditing(false) }}>Cancelar</button>
          <button type="button" className="btn" disabled={text.trim().length < 3 || save.isPending} onClick={async () => {
            setError('')
            try { await save.mutateAsync() } catch (e) { setError(e instanceof Error ? e.message : 'Falha ao salvar o diagnóstico') }
          }}>Salvar diagnóstico</button>
        </div>
      </>
      : <>
        <p className={order.diagnosis ? undefined : 'muted'}>{order.diagnosis ?? 'Nenhum diagnóstico registrado.'}</p>
        {editable && <button type="button" className="btn secondary" onClick={() => { setText(order.diagnosis ?? ''); setEditing(true) }}>
          {order.diagnosis ? 'Editar diagnóstico' : 'Registrar diagnóstico'}</button>}
      </>}
  </section>
}

function StatusHistoryCard({ orderId }: { orderId: string }) {
  const history = useQuery({ queryKey: queryKeys.statusHistory(orderId), queryFn: () => workflowApi.history(orderId) })
  return <section className="card" style={{ marginTop: 22 }}>
    <h2>Histórico de status</h2>
    <QueryState label="histórico" loading={history.isLoading} error={history.error} retry={history.refetch} empty={!history.data?.length} emptyMessage="Sem mudanças registradas.">
      <ul className="history-list">{history.data?.map(h => <li key={h.id}>
        <span>{h.fromStatusName ? `${h.fromStatusName} → ` : ''}<strong>{h.toStatusName}</strong>{h.reason ? ` — ${h.reason}` : ''}</span>
        <span className="muted">{formatDate(h.changedAt)}{h.automatic ? ' · automático' : ''}</span>
      </li>)}</ul>
    </QueryState>
  </section>
}
