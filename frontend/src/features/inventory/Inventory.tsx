import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { inventoryApi, productsApi } from '../../api/resources'
import { queryKeys } from '../../api/queryKeys'
import { MOVEMENT_LABELS, PRODUCT_UNIT_LABELS, WRITE_OFF_LABELS, type ProductUnit, type WriteOffMode } from '../../api/types'
import { Badge, Field, PageHeader, State } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { Pagination } from '../../components/controls'
import { useDebouncedValue } from '../../utils/useDebouncedValue'
import { formatBrl, formatDate, formatQuantity, quantityFitsUnit, quantityRuleHint, quantityStep } from '../../utils/format'

const PAGE_SIZE = 20
const unitLabel = (unit: string) => PRODUCT_UNIT_LABELS[unit as ProductUnit] ?? unit

export function StockPage() {
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('ALL')
  const [belowMinimum, setBelowMinimum] = useState(false)
  const [page, setPage] = useState(0)
  const q = useDebouncedValue(search.trim())
  const params = { q, active: status === 'ALL' ? undefined : status === 'ACTIVE', belowMinimum: belowMinimum || undefined, page, size: PAGE_SIZE }
  const query = useQuery({ queryKey: queryKeys.stock(params), queryFn: () => inventoryApi.stock(params), placeholderData: keepPreviousData })
  const items = query.data?.items ?? []

  return <>
    <PageHeader title="Estoque" subtitle="Saldo por item, custo médio ponderado e situação em relação ao estoque mínimo.">
      <Link className="btn secondary" to="/produtos/novo">Novo produto</Link>
    </PageHeader>
    <WriteOffCard />
    <div className="toolbar">
      <input aria-label="Buscar itens" className="input" placeholder="Buscar por descrição ou código" value={search}
             onChange={e => { setSearch(e.target.value); setPage(0) }} />
      <select aria-label="Status" className="select" value={status} onChange={e => { setStatus(e.target.value); setPage(0) }}>
        <option value="ALL">Todos os status</option><option value="ACTIVE">Ativos</option><option value="INACTIVE">Inativos</option>
      </select>
      <label className="muted">
        <input type="checkbox" checked={belowMinimum} onChange={e => { setBelowMinimum(e.target.checked); setPage(0) }} /> Abaixo do mínimo
      </label>
    </div>
    <State loading={query.isLoading} error={query.error} empty={!items.length}>
      <div className="card table-wrap">
        <table className="table">
          <thead><tr><th>Item</th><th>Unidade</th><th>Saldo</th><th>Mínimo</th><th>Custo médio</th><th>Situação</th><th>Ações</th></tr></thead>
          <tbody>{items.map(line => <tr key={line.productId}>
            <td><strong>{line.description}</strong><div className="muted">{[line.internalCode, line.category].filter(Boolean).join(' • ') || '—'}</div></td>
            <td>{unitLabel(line.unit)}</td>
            <td>{formatQuantity(line.quantity, line.unit)}</td>
            <td>{line.minimumStock == null ? '—' : formatQuantity(line.minimumStock, line.unit)}</td>
            <td>{line.averageCost == null ? '—' : formatBrl(line.averageCost)}</td>
            <td>{!line.active ? <Badge tone="warning">Inativo</Badge>
              : line.belowMinimum ? <Badge tone="warning">Abaixo do mínimo</Badge> : <Badge tone="success">Normal</Badge>}</td>
            <td><Link className="btn secondary" to={`/estoque/${line.productId}`} aria-label={`Movimentar ${line.description}`}>Movimentar</Link></td>
          </tr>)}</tbody>
        </table>
      </div>
      {query.data && <Pagination page={query.data.page} totalPages={query.data.totalPages} totalItems={query.data.totalItems} onChange={setPage} />}
    </State>
  </>
}

/** Regra de baixa pela OS (DR-0014); o backend continua sendo quem decide e recusa. */
function WriteOffCard() {
  const client = useQueryClient()
  const settings = useQuery({ queryKey: queryKeys.inventorySettings, queryFn: inventoryApi.settings })
  const [error, setError] = useState('')
  const mode = settings.data?.WORK_ORDER_WRITE_OFF as WriteOffMode | undefined
  return <section className="card inline-form" aria-label="Baixa pela ordem de serviço">
    <div className="field">
      <label htmlFor="write-off">Baixa pela OS
        <select id="write-off" className="select" value={mode ?? ''} disabled={!mode} onChange={async e => {
          setError('')
          try { await inventoryApi.setWriteOff(e.target.value as WriteOffMode); await client.invalidateQueries({ queryKey: queryKeys.inventorySettings }) }
          catch (err) { setError(err instanceof Error ? err.message : 'Falha ao alterar a regra') }
        }}>
          {Object.entries(WRITE_OFF_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
    </div>
    {error && <div role="alert" className="notice error">{error}</div>}
  </section>
}

export function StockProductPage() {
  const { productId = '' } = useParams()
  const client = useQueryClient()
  const [page, setPage] = useState(0)
  const [error, setError] = useState('')
  const product = useQuery({ queryKey: queryKeys.product(productId), queryFn: () => productsApi.get(productId) })
  const stock = useQuery({ queryKey: queryKeys.stock({ productId }), queryFn: () => inventoryApi.stock({ q: '', size: 100 }) })
  const movements = useQuery({
    queryKey: queryKeys.stockMovements(productId, page),
    queryFn: () => inventoryApi.movements(productId, page, PAGE_SIZE),
    placeholderData: keepPreviousData,
  })
  const line = stock.data?.items.find(item => item.productId === productId)
  const refresh = () => Promise.all([
    client.invalidateQueries({ queryKey: ['inventory'] }), client.invalidateQueries({ queryKey: queryKeys.products }),
  ])
  const run = async (action: () => Promise<unknown>) => {
    setError('')
    try { await action(); await refresh() }
    catch (e) { setError(e instanceof Error ? e.message : 'Não foi possível registrar a movimentação'); throw e }
  }

  return <>
    <PageHeader title={product.data?.description ?? 'Item de estoque'}
                subtitle="Movimentações são imutáveis: correções entram como estorno, nunca como edição.">
      <Link className="btn secondary" to="/estoque">Voltar ao estoque</Link>
    </PageHeader>
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="detail-grid">
      <div className="stack">
        <section className="card">
          <h2>Saldo</h2>
          <QueryState label="o saldo" loading={stock.isLoading} error={stock.error} retry={stock.refetch}>
            {line && <dl className="summary-list">
              <div><dt>Saldo atual</dt><dd data-testid="stock-balance">{formatQuantity(line.quantity, line.unit)}</dd></div>
              <div><dt>Estoque mínimo</dt><dd>{line.minimumStock == null ? '—' : formatQuantity(line.minimumStock, line.unit)}</dd></div>
              <div><dt>Custo médio</dt><dd>{line.averageCost == null ? '—' : formatBrl(line.averageCost)}</dd></div>
            </dl>}
          </QueryState>
        </section>
        <section className="card">
          <h2>Histórico de movimentações</h2>
          <QueryState label="as movimentações" loading={movements.isLoading} error={movements.error} retry={movements.refetch}
                      empty={!movements.data?.items.length} emptyMessage="Nenhuma movimentação registrada.">
            <div className="table-wrap"><table className="table">
              <thead><tr><th>Quando</th><th>Tipo</th><th>Quantidade</th><th>Saldo após</th><th>Motivo</th><th>Ações</th></tr></thead>
              <tbody>{movements.data?.items.map(movement => <tr key={movement.id}>
                <td>{formatDate(movement.occurredAt)}</td>
                <td>{MOVEMENT_LABELS[movement.type]}</td>
                <td>{movement.incoming ? '+' : '−'}{formatQuantity(movement.quantity, line?.unit ?? '')}</td>
                <td>{formatQuantity(movement.balanceAfter, line?.unit ?? '')}</td>
                <td>{movement.reason ?? (movement.workOrderId ? <Link to={`/ordens-servico/${movement.workOrderId}`}>OS vinculada</Link> : '—')}</td>
                <td>{movement.type === 'REVERSAL' || movement.source === 'WORK_ORDER' ? <span className="muted">—</span>
                  : <button type="button" className="btn secondary" onClick={() => run(() => inventoryApi.reverse(movement.id, 'Estorno manual')).catch(() => undefined)}>Estornar</button>}</td>
              </tr>)}</tbody>
            </table></div>
            {movements.data && <Pagination page={movements.data.page} totalPages={movements.data.totalPages}
                                           totalItems={movements.data.totalItems} onChange={setPage} />}
          </QueryState>
        </section>
      </div>
      <aside className="stack">
        <MovementForm title="Registrar entrada" action="entry" productId={productId} unit={line?.unit} onRun={run} />
        <MovementForm title="Registrar saída" action="exit" productId={productId} unit={line?.unit} onRun={run} />
        <MovementForm title="Ajustar saldo" action="adjustment" productId={productId} unit={line?.unit} onRun={run} />
      </aside>
    </div>
  </>
}

function MovementForm({ title, action, productId, unit, onRun }: {
  title: string; action: 'entry' | 'exit' | 'adjustment'; productId: string; unit: string | undefined
  onRun: (run: () => Promise<unknown>) => Promise<unknown>
}) {
  const [quantity, setQuantity] = useState('')
  const [unitCost, setUnitCost] = useState('')
  const [reason, setReason] = useState('')
  const [direction, setDirection] = useState<'IN' | 'OUT'>('OUT')
  const reasonRequired = action !== 'entry'
  const mutation = useMutation({
    mutationFn: () => {
      const amount = Number(quantity)
      if (action === 'entry') return inventoryApi.entry(productId, { quantity: amount, unitCost: unitCost === '' ? null : Number(unitCost), reason: reason.trim() || null })
      if (action === 'exit') return inventoryApi.exit(productId, { quantity: amount, reason: reason.trim() })
      return inventoryApi.adjustment(productId, { quantity: amount, direction, reason: reason.trim() })
    },
    onSuccess: () => { setQuantity(''); setUnitCost(''); setReason('') },
  })
  const fitsUnit = quantityFitsUnit(quantity, unit)
  const valid = quantity !== '' && Number(quantity) > 0 && fitsUnit && (!reasonRequired || reason.trim().length >= 3)

  return <section className="card" aria-label={title}>
    <h2>{title}</h2>
    <Field label="Quantidade" error={fitsUnit ? undefined : quantityRuleHint(unit)}>
      <input className="input" type="number" step={quantityStep(unit)} min="0" value={quantity} onChange={e => setQuantity(e.target.value)} />
    </Field>
    {action === 'entry' && <Field label="Custo unitário">
      <input className="input" type="number" step="0.0001" min="0" value={unitCost} onChange={e => setUnitCost(e.target.value)} />
    </Field>}
    {action === 'adjustment' && <Field label="Direção">
      <select className="select" value={direction} onChange={e => setDirection(e.target.value as 'IN' | 'OUT')}>
        <option value="OUT">Reduzir saldo</option><option value="IN">Aumentar saldo</option>
      </select>
    </Field>}
    <Field label={reasonRequired ? 'Motivo *' : 'Motivo'}><input className="input" value={reason} onChange={e => setReason(e.target.value)} /></Field>
    <button type="button" className="btn" style={{ width: '100%', marginTop: 12 }} disabled={!valid || mutation.isPending}
            onClick={() => onRun(() => mutation.mutateAsync()).catch(() => undefined)}>{title}</button>
  </section>
}
