import { useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { financeApi, financeKeys, todayIso, type FinanceDashboard } from '../../api/finance'
import { Badge, PageHeader } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { formatBrl, formatPercent } from '../../utils/format'

const firstOfMonth = () => `${todayIso().slice(0, 8)}01`
function daysAgo(days: number) {
  const [year, month, day] = todayIso().split('-').map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  date.setUTCDate(date.getUTCDate() - days)
  return date.toISOString().slice(0, 10)
}

/**
 * Resumo financeiro gerencial: leitura rápida de faturamento, custo e lucro do período.
 *
 * <p>Não é uma DRE contábil — os indicadores vêm prontos do backend, sem gráficos nem animação, para
 * decisão operacional rápida (ex.: "a oficina está indo bem neste período?").</p>
 */
export function FinanceDashboardPage() {
  const [from, setFrom] = useState(firstOfMonth)
  const [to, setTo] = useState(todayIso)
  const valid = Boolean(from && to && from <= to)
  const dashboard = useQuery({
    queryKey: financeKeys.dashboard(from, to), queryFn: () => financeApi.dashboard(from, to),
    enabled: valid, placeholderData: keepPreviousData,
  })
  const setRange = (nextFrom: string, nextTo: string) => { setFrom(nextFrom); setTo(nextTo) }

  return <>
    <PageHeader title="Resumo financeiro" subtitle="Leitura gerencial de faturamento, custo e lucro do período — não substitui uma DRE contábil." />
    <section className="card filters">
      <div className="inline-form">
        <div className="field"><label>De<input className="input" type="date" value={from} onChange={e => setFrom(e.target.value)} /></label></div>
        <div className="field"><label>Até<input className="input" type="date" value={to} onChange={e => setTo(e.target.value)} /></label></div>
        <div className="actions">
          <button type="button" className="btn secondary" onClick={() => setRange(todayIso(), todayIso())}>Hoje</button>
          <button type="button" className="btn secondary" onClick={() => setRange(daysAgo(6), todayIso())}>Últimos 7 dias</button>
          <button type="button" className="btn secondary" onClick={() => setRange(firstOfMonth(), todayIso())}>Mês atual</button>
        </div>
      </div>
      {!valid && <div role="alert" className="notice error">O período final não pode ser anterior ao inicial.</div>}
    </section>
    <QueryState label="o resumo financeiro" loading={dashboard.isLoading} error={dashboard.error} retry={dashboard.refetch}>
      {dashboard.data && <DashboardView data={dashboard.data} />}
    </QueryState>
  </>
}

function DashboardView({ data }: { data: FinanceDashboard }) {
  return <div className="stack">
    <section className="card" aria-label="Resultado do período">
      <h2>Resultado do período</h2>
      <div className="metrics metrics-2">
        <div className="metric" data-testid="dashboard-gross-profit">
          <span className="muted">Lucro bruto</span>
          <strong className="metric-large">{formatBrl(data.grossProfit)}</strong>
        </div>
        <div className="metric" data-testid="dashboard-operating-result">
          <span className="muted">Resultado operacional</span>
          <strong className="metric-large">{formatBrl(data.operatingResult)}</strong>
        </div>
      </div>
    </section>
    <section className="card" aria-label="Faturamento e recebimentos">
      <h2>Faturamento e recebimentos</h2>
      <div className="metrics">
        <div className="metric" data-testid="dashboard-revenue"><span className="muted">Faturamento</span><strong>{formatBrl(data.revenue)}</strong></div>
        <div className="metric" data-testid="dashboard-received"><span className="muted">Recebido</span><strong>{formatBrl(data.received)}</strong></div>
        <div className="metric" data-testid="dashboard-receivable-open"><span className="muted">A receber</span><strong>{formatBrl(data.receivableOpen)}</strong></div>
        <div className="metric" data-testid="dashboard-overdue">
          <span className="muted">Vencido</span>
          {data.overdue > 0 ? <Badge tone="warning">{formatBrl(data.overdue)}</Badge> : <strong>{formatBrl(data.overdue)}</strong>}
        </div>
      </div>
    </section>
    <section className="card" aria-label="Custos e despesas">
      <h2>Custos e despesas</h2>
      <div className="metrics metrics-3">
        <div className="metric" data-testid="dashboard-parts-cost"><span className="muted">Custo de peças</span><strong>{formatBrl(data.partsCost)}</strong></div>
        <div className="metric" data-testid="dashboard-expenses-registered"><span className="muted">Despesas registradas</span><strong>{formatBrl(data.expensesRegistered)}</strong></div>
        <div className="metric" data-testid="dashboard-expenses-paid"><span className="muted">Despesas pagas</span><strong>{formatBrl(data.expensesPaid)}</strong></div>
      </div>
    </section>
    <section className="card" aria-label="Margens">
      <h2>Margens</h2>
      <div className="metrics metrics-2">
        <div className="metric" data-testid="dashboard-gross-margin"><span className="muted">Margem bruta</span><strong>{formatPercent(data.grossMargin)}</strong></div>
        <div className="metric" data-testid="dashboard-operating-margin"><span className="muted">Margem operacional</span><strong>{formatPercent(data.operatingMargin)}</strong></div>
      </div>
    </section>
  </div>
}
