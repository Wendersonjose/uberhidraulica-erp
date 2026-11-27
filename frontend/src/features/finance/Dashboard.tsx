import { useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { financeApi, financeKeys, todayIso, type FinanceDashboard } from '../../api/finance'
import { reportKeys, reportsApi, type DueSummary, type FinanceOverview } from '../../api/financeReports'
import { DailyFlowChart } from './reportsCommon'
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
  const overview = useQuery({ queryKey: reportKeys.overview(from, to), queryFn: () => reportsApi.overview(from, to), enabled: valid, placeholderData: keepPreviousData })
  const flow = useQuery({ queryKey: financeKeys.cashFlow(from, to, ''), queryFn: () => financeApi.cashFlow(from, to), enabled: valid, placeholderData: keepPreviousData })
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
    <QueryState label="a posição de hoje" loading={overview.isLoading} error={overview.error} retry={overview.refetch}>
      {overview.data && <TodayView data={overview.data} />}
    </QueryState>
    <QueryState label="o resumo financeiro" loading={dashboard.isLoading} error={dashboard.error} retry={dashboard.refetch}>
      {dashboard.data && <DashboardView data={dashboard.data} />}
    </QueryState>
    {flow.data && <section className="card" aria-label="Fluxo de caixa diário">
      <h2>Fluxo de caixa realizado</h2>
      <DailyFlowChart label="Entradas e saídas por dia" days={flow.data.realized.days} />
    </section>}
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
        <div className="metric" data-testid="dashboard-revenue"><span className="muted">Faturamento</span><strong>{formatBrl(data.revenue)}</strong>
          {data.netRevenue !== undefined && data.netRevenue !== data.revenue && <span className="muted" data-testid="dashboard-net-revenue">
            líquido {formatBrl(data.netRevenue)}</span>}</div>
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
      <div className="metrics">
        <div className="metric" data-testid="dashboard-parts-cost"><span className="muted">Custo de peças usadas nas OS</span><strong>{formatBrl(data.partsCost)}</strong></div>
        <div className="metric" data-testid="dashboard-expenses-registered"><span className="muted">Despesas operacionais</span><strong>{formatBrl(data.expensesRegistered)}</strong></div>
        <div className="metric" data-testid="dashboard-stock-purchases"><span className="muted">Compras para estoque</span>
          <strong>{data.stockPurchasesRegistered === undefined ? '—' : formatBrl(data.stockPurchasesRegistered)}</strong></div>
        <div className="metric" data-testid="dashboard-expenses-paid"><span className="muted">Saídas pagas</span><strong>{formatBrl(data.expensesPaid)}</strong></div>
        {(data.cardFees ?? 0) > 0 && <div className="metric" data-testid="dashboard-card-fees"><span className="muted">Taxas de cartão</span>
          <strong>{formatBrl(data.cardFees ?? 0)}</strong></div>}
      </div>
      <p className="muted" data-testid="dashboard-stock-note">
        Compras para estoque (contas das NF-e) não entram no resultado: a peça vira custo quando é usada na OS.
        As contas continuam em Contas a pagar, e o pagamento entra em Saídas pagas e no fluxo de caixa.
      </p>
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

function DueCard({ title, data, testId }: { title: string; data: DueSummary; testId: string }) {
  return <section className="card" aria-label={title} data-testid={testId}>
    <h2>{title}</h2>
    <div className="metrics">
      <div className="metric"><span className="muted">Em aberto ({data.openCount})</span><strong>{formatBrl(data.openAmount)}</strong></div>
      <div className="metric" data-testid={`${testId}-overdue`}><span className="muted">Vencido ({data.overdueCount})</span>
        {data.overdueAmount > 0 ? <Badge tone="warning">{formatBrl(data.overdueAmount)}</Badge> : <strong>{formatBrl(0)}</strong>}</div>
      <div className="metric" data-testid={`${testId}-7`}><span className="muted">Vence em 7 dias ({data.dueIn7Count})</span><strong>{formatBrl(data.dueIn7Amount)}</strong></div>
      <div className="metric"><span className="muted">Vence em 30 dias ({data.dueIn30Count})</span><strong>{formatBrl(data.dueIn30Amount)}</strong></div>
    </div>
  </section>
}

/** Posição de hoje, independente do período: caixa aberto e títulos vencidos ou a vencer. */
function TodayView({ data }: { data: FinanceOverview }) {
  return <div className="stack">
    <section className="card" aria-label="Caixa físico" data-testid="overview-cash">
      <h2>Caixa físico</h2>
      {data.cash.open
        ? <div className="metric"><span className="muted">Saldo esperado do caixa aberto</span><strong className="metric-large">{formatBrl(data.cash.expectedBalance ?? 0)}</strong></div>
        : <p className="muted">Caixa fechado. Abra o caixa em Financeiro → Caixa físico para receber em dinheiro.</p>}
    </section>
    <DueCard title="Contas a receber" data={data.receivables} testId="overview-receivables" />
    <DueCard title="Contas a pagar" data={data.payables} testId="overview-payables" />
  </div>
}
