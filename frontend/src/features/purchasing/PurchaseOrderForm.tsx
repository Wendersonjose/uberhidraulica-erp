import { useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  purchaseApi, purchaseKeys, purchasingApi, purchasingKeys,
  type OrderInput, type PurchaseDetail, type PurchaseOrigin,
} from '../../api/purchasing'
import { newIdempotencyKey } from '../../api/finance'
import { productsApi, workOrdersApi } from '../../api/resources'
import { queryKeys } from '../../api/queryKeys'
import type { Product } from '../../api/types'
import { PageHeader } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { useDebouncedValue } from '../../utils/useDebouncedValue'
import { formatBrl } from '../../utils/format'
import { evaluateOrder, newLine, toOrderBody, type ExpenseDraft, type LineDraft } from './orderRules'

const NEW_ORIGINS: Extract<PurchaseOrigin, 'ORDER' | 'DIRECT'>[] = ['ORDER', 'DIRECT']
const ORIGIN_HELP: Record<string, string> = {
  ORDER: 'Pedido de compra: pede ao fornecedor antes da mercadoria chegar. Passa por aprovação e só então pode ser recebido.',
  DIRECT: 'Compra direta: registro de compra feita sem pedido prévio (balcão, urgência). Segue o mesmo fluxo de aprovação.',
}

const text = (value: number | null | undefined) => (value == null || value === 0 ? '' : String(value).replace('.', ','))

/** Cria o rascunho (`/compras/pedidos/novo`) ou edita o rascunho existente (`/compras/pedidos/:id/editar`). */
export function PurchaseOrderFormPage() {
  const { id } = useParams()
  const existing = useQuery({
    queryKey: purchaseKeys.purchase(id ?? ''), queryFn: () => purchaseApi.purchase(id as string), enabled: Boolean(id),
  })
  if (!id) return <OrderForm />
  return <QueryState label="o pedido" loading={existing.isLoading} error={existing.error} retry={existing.refetch}>
    {existing.data && (existing.data.status === 'DRAFT' && existing.data.origin !== 'NFE_IMPORT'
      // A chave inclui a versão: se a tela for recarregada com outra versão, o formulário recomeça dela.
      ? <OrderForm key={existing.data.version} purchase={existing.data} />
      : <div role="alert" className="notice error">
          Só o rascunho de um pedido pode ser alterado. Este está em outra situação:{' '}
          <Link to={`/compras/notas/${existing.data.id}`}>voltar para a compra #{existing.data.number}</Link>.
        </div>)}
  </QueryState>
}

function OrderForm({ purchase }: { purchase?: PurchaseDetail }) {
  const navigate = useNavigate()
  const client = useQueryClient()
  const editing = Boolean(purchase)
  const [origin, setOrigin] = useState<'ORDER' | 'DIRECT'>('ORDER')
  const [supplierId, setSupplierId] = useState(purchase?.supplierId ?? '')
  const [workOrderId, setWorkOrderId] = useState(purchase?.workOrderId ?? '')
  const [notes, setNotes] = useState(purchase?.notes ?? '')
  const [expenses, setExpenses] = useState<ExpenseDraft>({
    freight: text(purchase?.freightTotal), insurance: text(purchase?.insuranceTotal), other: text(purchase?.otherTotal),
  })
  const [lines, setLines] = useState<LineDraft[]>(() => purchase
    ? purchase.items.map(item => ({
      ...newLine(), productId: item.productId ?? '', quantity: text(item.quantity), unitPrice: text(item.unitPrice),
      discount: text(item.discount), supplierCode: item.supplierCode ?? '',
    }))
    : [newLine()])
  const [error, setError] = useState('')
  // A mesma intenção de criar usa a mesma chave, inclusive após falha de rede: um retry não duplica o pedido.
  const createKey = useRef(newIdempotencyKey())

  const catalog = useQuery({ queryKey: queryKeys.products, queryFn: productsApi.list })
  const activeProducts = useMemo(() => (catalog.data ?? []).filter(product => product.active), [catalog.data])
  const workOrders = useQuery({ queryKey: queryKeys.workOrders, queryFn: () => workOrdersApi.list() })

  const evaluation = useMemo(() => evaluateOrder(lines, expenses), [lines, expenses])
  const patch = (key: string, change: Partial<LineDraft>) =>
    setLines(current => current.map(line => line.key === key ? { ...line, ...change } : line))

  const mutation = useMutation({
    mutationFn: () => {
      const base = toOrderBody(lines, expenses)
      const body: OrderInput = {
        supplierId, workOrderId: workOrderId || null, notes: notes.trim() || null, ...base,
      }
      return purchase
        ? purchaseApi.updateOrder(purchase.id, purchase.version, body)
        : purchaseApi.createOrder({ ...body, origin }, createKey.current)
    },
    onSuccess: async saved => {
      await client.invalidateQueries({ queryKey: purchaseKeys.purchases })
      navigate(`/compras/notas/${saved.id}`)
    },
    onError: (failure: Error) => setError(failure.message),
  })

  const blocked = !supplierId || !evaluation.valid || mutation.isPending

  return <>
    <PageHeader title={editing ? `Editar pedido #${purchase?.number}` : 'Novo pedido de compra'}
                subtitle="Pedido não gera conta a pagar nem movimenta estoque: o estoque entra no recebimento, e a obrigação financeira nasce com a nota fiscal." />
    {error && <div role="alert" className="notice error">{error}</div>}

    <section className="card" aria-label="Dados do pedido">
      <h2>Dados do pedido</h2>
      <div className="form-grid">
        {!editing && <label className="field"><span>Tipo</span>
          <select className="select" aria-label="Tipo de compra" value={origin}
                  onChange={e => setOrigin(e.target.value as 'ORDER' | 'DIRECT')}>
            {NEW_ORIGINS.map(value => <option key={value} value={value}>{value === 'ORDER' ? 'Pedido de compra' : 'Compra direta'}</option>)}
          </select>
          <small className="muted">{ORIGIN_HELP[origin]}</small>
        </label>}
        <SupplierPicker value={supplierId} onChange={setSupplierId} />
        <label className="field"><span>OS vinculada (opcional)</span>
          <select className="select" aria-label="OS vinculada" value={workOrderId} onChange={e => setWorkOrderId(e.target.value)}>
            <option value="">Sem vínculo com OS</option>
            {(workOrders.data ?? []).map(order => <option key={order.id} value={order.id}>OS #{order.number}</option>)}
          </select>
        </label>
        <label className="field"><span>Observação</span>
          <input className="input" maxLength={1000} value={notes} onChange={e => setNotes(e.target.value)} /></label>
      </div>
    </section>

    <section className="card" aria-label="Itens do pedido">
      <h2>Itens</h2>
      <QueryState label="o catálogo de produtos" loading={catalog.isLoading} error={catalog.error} retry={catalog.refetch}>
        <div className="table-wrap"><table className="table">
          <thead><tr>
            <th>#</th><th>Produto</th><th>Qtd.</th><th>Preço unit. (R$)</th><th>Desconto (R$)</th>
            <th>Cód. do fornecedor</th><th>Custo unit.</th><th />
          </tr></thead>
          <tbody>{lines.map((line, index) => {
            const priced = evaluation.lines[index]
            const lineError = evaluation.lineErrors[line.key]
            return <tr key={line.key}>
              <td>{index + 1}</td>
              <td><ProductPicker catalog={activeProducts} number={index + 1} value={line.productId}
                                 onChange={productId => patch(line.key, { productId })} />
                {lineError && <small role="alert" className="error-text">{lineError}</small>}</td>
              <td><input className="input" inputMode="decimal" aria-label={`Quantidade do item ${index + 1}`}
                         value={line.quantity} onChange={e => patch(line.key, { quantity: e.target.value })} /></td>
              <td><input className="input" inputMode="decimal" aria-label={`Preço unitário do item ${index + 1}`}
                         value={line.unitPrice} onChange={e => patch(line.key, { unitPrice: e.target.value })} /></td>
              <td><input className="input" inputMode="decimal" aria-label={`Desconto do item ${index + 1}`}
                         value={line.discount} onChange={e => patch(line.key, { discount: e.target.value })} /></td>
              <td><input className="input" maxLength={60} aria-label={`Código do fornecedor do item ${index + 1}`}
                         value={line.supplierCode} onChange={e => patch(line.key, { supplierCode: e.target.value })} /></td>
              <td>{priced && !lineError && line.productId ? formatBrl(priced.unitCost) : '—'}</td>
              <td><button type="button" className="btn secondary" disabled={lines.length === 1}
                          aria-label={`Remover o item ${index + 1}`}
                          onClick={() => setLines(current => current.filter(l => l.key !== line.key))}>Remover</button></td>
            </tr>
          })}</tbody>
        </table></div>
        <div className="form-actions">
          <button type="button" className="btn secondary" onClick={() => setLines(current => [...current, newLine()])}>
            Adicionar item</button>
        </div>
      </QueryState>
    </section>

    <section className="card" aria-label="Despesas e totais">
      <h2>Despesas e totais</h2>
      <p className="muted">Frete, seguro e outras despesas são rateados entre os itens proporcionalmente ao valor, e entram no
        custo de cada um. Imposto (IPI, ICMS-ST) só existe na nota fiscal: este custo é uma estimativa até ela chegar.</p>
      <div className="form-grid">
        <label className="field"><span>Frete (R$)</span>
          <input className="input" inputMode="decimal" value={expenses.freight}
                 onChange={e => setExpenses({ ...expenses, freight: e.target.value })} /></label>
        <label className="field"><span>Seguro (R$)</span>
          <input className="input" inputMode="decimal" value={expenses.insurance}
                 onChange={e => setExpenses({ ...expenses, insurance: e.target.value })} /></label>
        <label className="field"><span>Outras despesas (R$)</span>
          <input className="input" inputMode="decimal" value={expenses.other}
                 onChange={e => setExpenses({ ...expenses, other: e.target.value })} /></label>
      </div>
      <dl className="summary-list" aria-label="Totais do pedido">
        <div><dt>Mercadorias</dt><dd>{formatBrl(evaluation.totals.goods)}</dd></div>
        <div><dt>Desconto</dt><dd>{formatBrl(evaluation.totals.discount)}</dd></div>
        <div><dt>Despesas</dt><dd>{formatBrl(evaluation.totals.freight + evaluation.totals.insurance + evaluation.totals.other)}</dd></div>
        <div><dt>Total do pedido</dt><dd><strong>{formatBrl(evaluation.totals.payable)}</strong></dd></div>
      </dl>
      {evaluation.errors.length > 0 && !lines.every(l => !l.productId && !l.quantity && !l.unitPrice) &&
        <ul className="error-text" aria-label="Pendências">{evaluation.errors.map(message => <li key={message}>{message}</li>)}</ul>}
    </section>

    <div className="form-actions">
      <Link className="btn secondary" to={purchase ? `/compras/notas/${purchase.id}` : '/compras/notas'}>Cancelar</Link>
      <button type="button" className="btn" disabled={blocked}
              onClick={() => { setError(''); mutation.mutate() }}>
        {mutation.isPending ? 'Salvando…' : editing ? 'Salvar alterações' : 'Salvar rascunho'}
      </button>
    </div>
  </>
}

/** Fornecedor ativo: busca por nome/documento e seleção. O escolhido continua como opção mesmo fora da busca. */
function SupplierPicker({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const [search, setSearch] = useState('')
  const q = useDebouncedValue(search.trim())
  const list = useQuery({
    queryKey: purchasingKeys.supplierList({ pick: true, q }),
    queryFn: () => purchasingApi.suppliers({ q: q || undefined, status: 'ACTIVE', size: 20 }),
  })
  const chosen = useQuery({
    queryKey: purchasingKeys.supplier(value), queryFn: () => purchasingApi.supplier(value), enabled: Boolean(value),
  })
  const options = new Map<string, string>()
  list.data?.items.forEach(supplier => options.set(supplier.id, supplier.legalName))
  if (value && !options.has(value)) options.set(value, chosen.data?.legalName ?? 'Fornecedor escolhido')

  return <div className="field">
    <label><span>Fornecedor *</span>
      <select className="select" aria-label="Fornecedor" value={value} onChange={e => onChange(e.target.value)}>
        <option value="">Selecione…</option>
        {[...options.entries()].map(([id, name]) => <option key={id} value={id}>{name}</option>)}
      </select></label>
    <input className="input" aria-label="Buscar fornecedor" placeholder="Buscar fornecedor por nome ou CNPJ"
           value={search} onChange={e => setSearch(e.target.value)} />
    {chosen.data?.status === 'INACTIVE' && <small role="alert" className="error-text">Fornecedor inativo.</small>}
  </div>
}

/** Produto ativo do catálogo: busca por descrição ou código interno (mínimo de 2 letras) e seleção. */
function ProductPicker({ catalog, number, value, onChange }: {
  catalog: Product[]; number: number; value: string; onChange: (id: string) => void
}) {
  const [search, setSearch] = useState('')
  const term = search.trim().toLowerCase()
  const found = term.length < 2 ? [] : catalog.filter(product =>
    product.description.toLowerCase().includes(term) || (product.internalCode ?? '').toLowerCase().includes(term)).slice(0, 20)
  const label = (product: Product) => `${product.description}${product.internalCode ? ` · ${product.internalCode}` : ''} (${product.unit})`
  const options = new Map<string, string>()
  found.forEach(product => options.set(product.id, label(product)))
  if (value && !options.has(value)) {
    const product = catalog.find(p => p.id === value)
    options.set(value, product ? label(product) : 'Produto escolhido')
  }
  return <>
    <select className="select" aria-label={`Produto do item ${number}`} value={value} onChange={e => onChange(e.target.value)}>
      <option value="">Selecione…</option>
      {[...options.entries()].map(([id, name]) => <option key={id} value={id}>{name}</option>)}
    </select>
    <input className="input" aria-label={`Buscar produto do item ${number}`} placeholder="Buscar no catálogo"
           value={search} onChange={e => setSearch(e.target.value)} />
  </>
}
