import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { FINANCE_VIEW, RECEIVABLE_STATUS_LABELS, financeApi, financeKeys, statusTone } from '../../api/finance'
import { Badge } from '../../components/ui'
import { formatBrl } from '../../utils/format'
import { formatDay, useCan } from './hooks'

/** Recebível da OS finalizada, para quem pode ver o Financeiro. Finalizar não é receber: nasce em aberto. */
export function WorkOrderReceivableCard({ workOrderId }: { workOrderId: string }) {
  const canView = useCan(FINANCE_VIEW)
  const receivable = useQuery({ queryKey: financeKeys.workOrderReceivable(workOrderId), queryFn: () => financeApi.workOrderReceivable(workOrderId), enabled: canView })
  if (!canView || !receivable.data) return null
  const r = receivable.data
  return <section className="card" style={{ marginBottom: 16 }} aria-label="Recebível da OS">
    <h2>Recebível <Badge tone={statusTone(r.status)}>{RECEIVABLE_STATUS_LABELS[r.status]}</Badge></h2>
    <dl className="summary-list">
      <div><dt>Valor original</dt><dd>{formatBrl(r.originalAmount)}</dd></div>
      <div><dt>Saldo em aberto</dt><dd>{formatBrl(r.outstandingBalance)}</dd></div>
      <div><dt>Vencimento</dt><dd>{formatDay(r.dueDate)}</dd></div>
    </dl>
    <Link className="btn secondary" to={`/financeiro/recebiveis/${r.id}`}>Abrir recebível</Link>
  </section>
}
