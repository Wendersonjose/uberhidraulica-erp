import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  PURCHASE_IMPORT_XML, PURCHASE_ORDER_MANAGE, PURCHASE_VIEW, purchaseApi, purchaseKeys,
  type PurchaseOrigin, type PurchaseStatus,
} from '../../api/purchasing'
import { Badge } from '../../components/ui'
import { useCan } from '../finance/hooks'
import { formatBrl, formatDate } from '../../utils/format'
import { RECEIPT_STATUS_LABEL } from '../purchasing/receivingRules'

const ORIGIN_LABEL: Record<PurchaseOrigin, string> = { NFE_IMPORT: 'NF-e', DIRECT: 'Compra direta', ORDER: 'Pedido' }
const STATUS_LABEL: Record<PurchaseStatus, string> = {
  DRAFT: 'Rascunho', AWAITING_APPROVAL: 'Aguardando aprovação', APPROVED: 'Aprovada', CANCELLED: 'Cancelada',
}

/**
 * Compras ligadas à OS: o que foi comprado para este serviço, de quem, por quanto e se já chegou. Os atalhos levam a
 * importação da NF-e e o pedido já com a OS preenchida. Só aparece para quem pode consultar Compras.
 */
export function WorkOrderPurchasesCard({ orderId, operational }: { orderId: string; operational: boolean }) {
  const canView = useCan(PURCHASE_VIEW)
  const canImport = useCan(PURCHASE_IMPORT_XML)
  const canOrder = useCan(PURCHASE_ORDER_MANAGE)
  const purchases = useQuery({
    queryKey: purchaseKeys.workOrderPurchases(orderId),
    queryFn: () => purchaseApi.purchases({ workOrderId: orderId, size: 100 }),
    enabled: canView,
  })
  if (!canView) return null
  const items = purchases.data?.items ?? []
  const total = items.filter(purchase => purchase.status !== 'CANCELLED' && purchase.origin === 'NFE_IMPORT')
    .reduce((sum, purchase) => sum + Number(purchase.payableTotal), 0)

  return <section className="card" style={{ marginTop: 22 }} aria-label="Compras desta OS">
    <h2>Compras desta OS</h2>
    {purchases.isLoading && <div className="state">Carregando compras…</div>}
    {purchases.error && <div role="alert" className="notice error">Não foi possível consultar as compras desta OS.</div>}
    {purchases.data && items.length === 0 && <div className="state">Nenhuma compra ligada a esta OS.</div>}
    {items.map(purchase => <div className="service-row" key={purchase.id}>
      <div>
        <Link to={`/compras/notas/${purchase.id}`}><strong>Compra #{purchase.number}</strong></Link>
        {' '}<Badge tone={purchase.status === 'CANCELLED' ? 'warning' : purchase.status === 'APPROVED' ? 'success' : 'info'}>
          {STATUS_LABEL[purchase.status]}</Badge>
        <div className="muted">
          {[ORIGIN_LABEL[purchase.origin], purchase.supplierName,
            purchase.invoiceNumber != null ? `nota ${purchase.invoiceNumber}` : null,
            formatDate(purchase.issuedOn ?? purchase.createdAt),
            purchase.origin === 'NFE_IMPORT' ? `recebimento: ${RECEIPT_STATUS_LABEL[purchase.receiptStatus]}` : null,
          ].filter(Boolean).join(' · ')}
        </div>
      </div>
      <strong>{formatBrl(Number(purchase.payableTotal))}</strong>
    </div>)}
    {items.length > 0 && <div className="subtotal">
      <span>Comprado com nota para esta OS</span>
      <span data-testid="work-order-purchases-total">{formatBrl(total)}</span>
    </div>}
    {operational && (canImport || canOrder) && <div className="form-actions" style={{ marginTop: 14 }}>
      {canImport && <Link className="btn secondary" to={`/compras/notas/importar?os=${orderId}`}>Importar NF-e para esta OS</Link>}
      {canOrder && <Link className="btn secondary" to={`/compras/pedidos/novo?os=${orderId}`}>Novo pedido para esta OS</Link>}
    </div>}
  </section>
}
