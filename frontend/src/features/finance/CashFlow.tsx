import { useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { financeApi, financeKeys, todayIso, type CashFlowBlock } from '../../api/finance'
import { PageHeader } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { formatBrl } from '../../utils/format'
import { formatDay } from './hooks'

const firstOfMonth = () => `${todayIso().slice(0, 8)}01`

/**
 * Fluxo de caixa (DR-0015, F-11): realizado e previsto lado a lado, nunca somados. Sem saldo inicial, o
 * resultado é a variação líquida do período — não um saldo de caixa ou bancário.
 */
export function CashFlowPage() {
  const [from, setFrom] = useState(firstOfMonth)
  const [to, setTo] = useState(todayIso)
  const [categoryId, setCategoryId] = useState('')
  const categories = useQuery({ queryKey: financeKeys.categories, queryFn: financeApi.categories })
  const valid = Boolean(from && to && from <= to)
  const flow = useQuery({ queryKey: financeKeys.cashFlow(from, to, categoryId), queryFn: () => financeApi.cashFlow(from, to, categoryId || undefined),
    enabled: valid, placeholderData: keepPreviousData })

  return <>
    <PageHeader title="Fluxo de caixa" subtitle="Entradas, saídas e variação líquida do período. Não representa saldo bancário nem de caixa." />
    <section className="card filters"><div className="inline-form">
      <div className="field"><label>De<input className="input" type="date" value={from} onChange={e => setFrom(e.target.value)} /></label></div>
      <div className="field"><label>Até<input className="input" type="date" value={to} onChange={e => setTo(e.target.value)} /></label></div>
      <div className="field"><label>Categoria das saídas<select className="select" value={categoryId} onChange={e => setCategoryId(e.target.value)}>
        <option value="">Todas</option>
        {categories.data?.map(category => <option key={category.id} value={category.id}>{category.name}</option>)}
      </select></label></div>
    </div>
    {!valid && <div role="alert" className="notice error">O período final não pode ser anterior ao inicial.</div>}
    </section>
    <QueryState label="o fluxo de caixa" loading={flow.isLoading} error={flow.error} retry={flow.refetch}>
      {flow.data && <div className="detail-grid">
        <Block title="Realizado" hint="Recebimentos e pagamentos efetivos, sem estornados, pela data do lançamento." block={flow.data.realized} />
        <Block title="Previsto" hint="Saldo ainda em aberto de recebíveis e contas a pagar, pela data de vencimento." block={flow.data.forecast} />
      </div>}
    </QueryState>
  </>
}

function Block({ title, hint, block }: { title: string; hint: string; block: CashFlowBlock }) {
  return <section className="card" aria-label={title}>
    <h2>{title}</h2>
    <p className="muted">{hint}</p>
    <dl className="summary-list">
      <div><dt>Entradas</dt><dd data-testid={`${title}-inflows`}>{formatBrl(block.inflows)}</dd></div>
      <div><dt>Saídas</dt><dd data-testid={`${title}-outflows`}>{formatBrl(block.outflows)}</dd></div>
      <div><dt>Variação líquida</dt><dd data-testid={`${title}-net`}>{formatBrl(block.net)}</dd></div>
    </dl>
    {block.days.length > 0 && <div className="table-wrap"><table className="table">
      <thead><tr><th>Dia</th><th>Entradas</th><th>Saídas</th><th>Variação</th></tr></thead>
      <tbody>{block.days.map(day => <tr key={day.date}>
        <td>{formatDay(day.date)}</td><td>{formatBrl(day.inflows)}</td><td>{formatBrl(day.outflows)}</td><td>{formatBrl(day.net)}</td>
      </tr>)}</tbody>
    </table></div>}
  </section>
}
