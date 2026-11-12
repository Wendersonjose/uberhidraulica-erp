import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { productsApi, workOrdersApi } from '../../api/resources'
import { queryKeys } from '../../api/queryKeys'
import { PRODUCT_UNIT_LABELS, type ProductUnit, type WorkOrderProduct } from '../../api/types'
import { QueryState } from '../../components/QueryState'
import { Badge } from '../../components/ui'
import { ConfirmAction } from '../../components/controls'
import { formatBrl, formatQuantity, quantityFitsUnit, quantityRuleHint, quantityStep } from '../../utils/format'
import { isConsumed, productsSubtotal } from './totals'

const unitLabel = (unit: string) => PRODUCT_UNIT_LABELS[unit as ProductUnit] ?? unit

/**
 * Itens físicos da OS. Item removido ou rejeitado pelo cliente continua listado, como histórico, mas fora do subtotal
 * (DR-0019). `orderId` habilita a remoção de item não aprovado; sem ele o cartão é só leitura.
 */
export function WorkOrderProductsCard({ products, orderId }: { products: WorkOrderProduct[]; orderId?: string }) {
  const client = useQueryClient()
  const [reasons, setReasons] = useState<Record<string, string>>({})
  const remove = async (itemId: string) => {
    await workOrdersApi.removeProduct(orderId!, itemId, (reasons[itemId] ?? '').trim())
    await client.invalidateQueries({ queryKey: ['work-orders', orderId] })
    await client.invalidateQueries({ queryKey: queryKeys.workOrders })
  }
  return <section className="card" style={{ marginTop: 22 }}>
    <h2>Itens físicos da OS</h2>
    {products.length === 0
      ? <div className="state">Nenhum item físico lançado.</div>
      : products.map(item => <div className="service-row" key={item.id} data-testid={`product-item-${item.id}`}>
        <div>
          <strong style={isConsumed(item) ? undefined : { textDecoration: 'line-through' }}>{item.description}</strong>
          {item.removedAt && <> <Badge tone="warning">Removido da OS</Badge></>}
          {!item.removedAt && item.customerDecision === 'REJECTED' && <> <Badge tone="warning">Rejeitado pelo cliente</Badge></>}
          {!item.removedAt && item.customerDecision === 'APPROVED' && <> <Badge>Aprovado pelo cliente</Badge></>}
          <div className="muted">
            {[item.internalCode, unitLabel(item.unit)].filter(Boolean).join(' • ')}
            {' · '}{formatQuantity(Number(item.quantity), item.unit)} × {formatBrl(Number(item.unitPrice))}
          </div>
          {item.removedAt && <div className="muted">Motivo: {item.removalReason}. O estoque baixado para este item foi devolvido.</div>}
          {!item.removedAt && item.customerDecision === 'REJECTED' &&
            <div className="muted">O estoque baixado para este item foi devolvido e ele não entra na cobrança.</div>}
          {orderId && !item.removedAt && item.customerDecision !== 'APPROVED' &&
            <ConfirmAction label="Remover da OS" confirmLabel="Remover item"
              question={`Remover "${item.description}" da OS? O estoque baixado para ele volta.`}
              onConfirm={() => remove(item.id)}>
              <label className="field"><span>Motivo da remoção *</span>
                <input className="input" maxLength={500} aria-label={`Motivo da remoção de ${item.description}`}
                  value={reasons[item.id] ?? ''}
                  onChange={event => setReasons(current => ({ ...current, [item.id]: event.target.value }))} /></label>
            </ConfirmAction>}
        </div>
        <strong>{isConsumed(item) ? formatBrl(Number(item.quantity) * Number(item.unitPrice)) : '—'}</strong>
      </div>)}
    <div className="subtotal">
      <span>Subtotal de itens</span>
      <span data-testid="products-subtotal">{formatBrl(productsSubtotal(products))}</span>
    </div>
  </section>
}

export function AddProductCard({ orderId, onError }: { orderId: string; onError: (message: string) => void }) {
  const client = useQueryClient()
  const [productId, setProductId] = useState('')
  const [quantity, setQuantity] = useState('')
  const catalog = useQuery({ queryKey: queryKeys.products, queryFn: productsApi.list })

  // Espelha as regras do backend: item inativo ou sem preço de venda não pode ser lançado.
  const available = catalog.data?.filter(product => product.active && product.salePrice !== null) || []
  const selected = available.find(product => product.id === productId)
  const amount = Number(quantity)
  const fitsUnit = quantityFitsUnit(quantity, selected?.unit)
  const validQuantity = quantity !== '' && Number.isFinite(amount) && amount > 0 && fitsUnit

  const add = useMutation({
    mutationFn: () => workOrdersApi.addProduct(orderId, productId, amount),
    onSuccess: async () => {
      setProductId('')
      setQuantity('')
      await client.invalidateQueries({ queryKey: ['work-orders', orderId] })
      await client.invalidateQueries({ queryKey: queryKeys.workOrders })
    },
  })

  return <section className="card" style={{ marginTop: 16 }}>
    <h2>Adicionar item físico</h2>
    <QueryState label="catálogo de produtos" loading={catalog.isLoading} error={catalog.error}
      retry={() => catalog.refetch()} empty={!available.length}
      emptyMessage={catalog.data?.length
        ? 'Nenhum produto ativo com preço de venda definido.'
        : 'Nenhum produto cadastrado.'} />
    <div className="field">
      <label htmlFor="product">Produto do catálogo</label>
      <select id="product" className="select" value={productId} disabled={!available.length || add.isPending}
        onChange={event => setProductId(event.target.value)}>
        <option value="">Selecione</option>
        {available.map(product => <option key={product.id} value={product.id}>
          {product.description} — {formatBrl(product.salePrice!)} / {unitLabel(product.unit)}
        </option>)}
      </select>
    </div>
    <div className="field" style={{ marginTop: 14 }}>
      <label htmlFor="quantity">Quantidade{selected ? ` (${unitLabel(selected.unit)})` : ''}</label>
      <input id="quantity" className="input" type="number" step={quantityStep(selected?.unit)} min="0" value={quantity}
        disabled={!selected || add.isPending} onChange={event => setQuantity(event.target.value)} />
      {!fitsUnit && <span className="error-text">{quantityRuleHint(selected?.unit)}</span>}
    </div>
    <button style={{ width: '100%', marginTop: 14 }} className="btn"
      disabled={!selected || !validQuantity || add.isPending}
      onClick={async () => {
        onError('')
        try { await add.mutateAsync() }
        catch (error) { onError(error instanceof Error ? error.message : 'Falha ao adicionar item físico') }
      }}>Adicionar item à OS</button>
  </section>
}
