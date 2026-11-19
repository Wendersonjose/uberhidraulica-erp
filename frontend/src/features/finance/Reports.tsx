import { useState, type ReactNode } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { PAYABLE_STATUS_LABELS, RECEIVABLE_STATUS_LABELS, statusTone, todayIso, type FinancialStatus } from '../../api/finance'
import { downloadCsv, reportKeys, reportsApi, toCsv, type AgingBucketCode } from '../../api/financeReports'
import { Badge, PageHeader } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { formatBrl, formatPercent } from '../../utils/format'
import { BarChart, PeriodFilter } from './reportsCommon'
import { firstOfMonth, formatDay } from './reportsUtils'

type Tab = 'resultado' | 'os' | 'despesas' | 'inadimplencia' | 'receber' | 'pagar'
const TABS: [Tab, string][] = [
  ['resultado', 'Demonstrativo gerencial'], ['os', 'Resultado por OS'], ['despesas', 'Despesas por categoria'],
  ['inadimplencia', 'Inadimplência'], ['receber', 'Contas a receber'], ['pagar', 'Contas a pagar'],
]
const AGING_LABELS: Record<AgingBucketCode, string> = {
  A_VENCER: 'A vencer', ATE_30: '1 a 30 dias', DE_31_A_60: '31 a 60 dias', DE_61_A_90: '61 a 90 dias', ACIMA_90: 'Acima de 90 dias',
}

/** Relatórios gerenciais (TASK-0024, etapa A). Leitura pura: nenhuma ação desta tela grava dados. */
export function FinanceReportsPage() {
  const [tab, setTab] = useState<Tab>('resultado')
  const [from, setFrom] = useState(firstOfMonth)
  const [to, setTo] = useState(todayIso)
  const valid = Boolean(from && to && from <= to)
  return <>
    <PageHeader title="Relatórios financeiros" subtitle="Leitura gerencial. Não substitui a contabilidade; mão de obra não é custeada." />
    <nav className="card tabs" aria-label="Relatórios">
      {TABS.map(([key, label]) => <button key={key} type="button" role="tab" aria-selected={tab === key}
        className={`btn ${tab === key ? '' : 'secondary'}`} onClick={() => setTab(key)}>{label}</button>)}
    </nav>
    {tab !== 'inadimplencia' && <PeriodFilter from={from} to={to} onChange={(f, t) => { setFrom(f); setTo(t) }} valid={valid} />}
    {tab === 'resultado' && valid && <IncomeStatementReport from={from} to={to} />}
    {tab === 'os' && valid && <WorkOrderReport from={from} to={to} />}
    {tab === 'despesas' && valid && <ExpensesReport from={from} to={to} />}
    {tab === 'inadimplencia' && <AgingReport />}
    {tab === 'receber' && valid && <ReceivablesReport from={from} to={to} />}
    {tab === 'pagar' && valid && <PayablesReport from={from} to={to} />}
  </>
}

function Row({ label, value, strong, testId }: { label: string; value: ReactNode; strong?: boolean; testId?: string }) {
  return <tr data-testid={testId}><td>{strong ? <strong>{label}</strong> : label}</td><td className="num">{strong ? <strong>{value}</strong> : value}</td></tr>
}

function IncomeStatementReport({ from, to }: { from: string; to: string }) {
  const q = useQuery({ queryKey: reportKeys.report('income', from, to), queryFn: () => reportsApi.incomeStatement(from, to), placeholderData: keepPreviousData })
  return <QueryState label="o demonstrativo" loading={q.isLoading} error={q.error} retry={q.refetch}>
    {q.data && <div className="stack">
      <section className="card table-wrap" aria-label="Demonstrativo por competência">
        <h2>Competência (OS faturadas e despesas cadastradas no período)</h2>
        <table className="table"><tbody>
          <Row label="Receita bruta das OS" value={formatBrl(q.data.grossRevenue)} testId="is-gross" />
          {(q.data.otherRevenue ?? 0) > 0 && <Row label="(+) Outras receitas (avulsas)" value={formatBrl(q.data.otherRevenue ?? 0)} testId="is-other" />}
          <Row label="(−) Descontos financeiros" value={formatBrl(q.data.discounts)} />
          <Row label="(+) Acréscimos" value={formatBrl(q.data.surcharges)} />
          <Row label="Receita líquida" value={formatBrl(q.data.netRevenue)} strong testId="is-net" />
          <Row label="(−) Custo de peças" value={formatBrl(q.data.partsCost)} />
          <Row label="Lucro bruto" value={formatBrl(q.data.grossProfit)} strong testId="is-gross-profit" />
          {q.data.expenses.map(e => <Row key={e.categoryId} label={`(−) ${e.categoryName}`} value={formatBrl(e.registered)} />)}
          <Row label="Total de despesas" value={formatBrl(q.data.totalExpenses)} />
          {(q.data.financialExpenses ?? 0) > 0 && <Row label="(−) Despesas financeiras (taxas de cartão)" value={formatBrl(q.data.financialExpenses ?? 0)} testId="is-financial" />}
          <Row label="Resultado operacional" value={formatBrl(q.data.operatingResult)} strong testId="is-operating" />
          <Row label="Margem operacional" value={formatPercent(q.data.operatingMargin)} />
        </tbody></table>
      </section>
      <section className="card table-wrap" aria-label="Demonstrativo por caixa">
        <h2>Caixa (recebido × pago no período)</h2>
        <table className="table"><tbody>
          <Row label="Recebido" value={formatBrl(q.data.cashReceived)} />
          <Row label="Pago" value={formatBrl(q.data.cashPaid)} />
          <Row label="Saldo de caixa do período" value={formatBrl(q.data.cashResult)} strong testId="is-cash" />
        </tbody></table>
      </section>
    </div>}
  </QueryState>
}

function WorkOrderReport({ from, to }: { from: string; to: string }) {
  const q = useQuery({ queryKey: reportKeys.report('work-orders', from, to), queryFn: () => reportsApi.workOrders(from, to), placeholderData: keepPreviousData })
  const exportCsv = () => q.data && downloadCsv(`resultado-por-os-${from}-a-${to}.csv`, toCsv(
    ['OS', 'Cliente', 'Emissão', 'Situação', 'Receita líquida', 'Recebido', 'Em aberto', 'Custo de peças', 'Resultado'],
    q.data.workOrders.map(l => [l.workOrderNumber, l.customerName, l.issuedOn, l.status, l.netRevenue, l.received, l.outstanding, l.partsCost, l.result])))
  return <QueryState label="o resultado por OS" loading={q.isLoading} error={q.error} retry={q.refetch}
    empty={q.data?.workOrders.length === 0} emptyMessage="Nenhuma OS faturada no período.">
    {q.data && <section className="card table-wrap">
      <div className="actions"><button type="button" className="btn secondary" onClick={exportCsv}>Exportar CSV</button></div>
      <table className="table">
        <thead><tr><th>OS</th><th>Cliente</th><th>Emissão</th><th>Situação</th><th className="num">Receita líquida</th><th className="num">Recebido</th>
          <th className="num">Em aberto</th><th className="num">Custo de peças</th><th className="num">Resultado</th><th className="num">Margem</th></tr></thead>
        <tbody>{q.data.workOrders.map(l => <tr key={l.receivableId}>
          <td>#{l.workOrderNumber}</td><td>{l.customerName ?? '—'}</td><td>{formatDay(l.issuedOn)}</td>
          <td><Badge tone={statusTone(l.status as FinancialStatus)}>{RECEIVABLE_STATUS_LABELS[l.status as FinancialStatus] ?? l.status}</Badge></td>
          <td className="num">{formatBrl(l.netRevenue)}</td><td className="num">{formatBrl(l.received)}</td><td className="num">{formatBrl(l.outstanding)}</td>
          <td className="num">{formatBrl(l.partsCost)}</td><td className="num"><strong>{formatBrl(l.result)}</strong></td><td className="num">{formatPercent(l.margin)}</td>
        </tr>)}</tbody>
        <tfoot><tr data-testid="os-total"><th colSpan={4}>Total</th><th className="num">{formatBrl(q.data.netRevenue)}</th><th /><th />
          <th className="num">{formatBrl(q.data.partsCost)}</th><th className="num">{formatBrl(q.data.result)}</th><th /></tr></tfoot>
      </table>
    </section>}
  </QueryState>
}

function ExpensesReport({ from, to }: { from: string; to: string }) {
  const q = useQuery({ queryKey: reportKeys.report('expenses', from, to), queryFn: () => reportsApi.expensesByCategory(from, to), placeholderData: keepPreviousData })
  return <QueryState label="as despesas por categoria" loading={q.isLoading} error={q.error} retry={q.refetch}
    empty={q.data?.categories.length === 0} emptyMessage="Nenhuma despesa no período.">
    {q.data && <div className="stack">
      <section className="card" aria-label="Gráfico de despesas por categoria">
        <h2>Cadastrado por categoria</h2>
        <BarChart label="Despesas por categoria" items={q.data.categories.map(c => ({ label: c.categoryName, value: c.registered }))} />
      </section>
      <section className="card table-wrap"><table className="table">
        <thead><tr><th>Categoria</th><th className="num">Contas</th><th className="num">Cadastrado</th><th className="num">Pago</th></tr></thead>
        <tbody>{q.data.categories.map(c => <tr key={c.categoryId}><td>{c.categoryName}</td><td className="num">{c.registeredCount}</td>
          <td className="num">{formatBrl(c.registered)}</td><td className="num">{formatBrl(c.paid)}</td></tr>)}</tbody>
        <tfoot><tr data-testid="expenses-total"><th>Total</th><th /><th className="num">{formatBrl(q.data.registered)}</th><th className="num">{formatBrl(q.data.paid)}</th></tr></tfoot>
      </table></section>
    </div>}
  </QueryState>
}

function AgingReport() {
  const q = useQuery({ queryKey: reportKeys.aging, queryFn: reportsApi.aging })
  return <QueryState label="a inadimplência" loading={q.isLoading} error={q.error} retry={q.refetch}>
    {q.data && <div className="stack">
      <section className="card" aria-label="Saldo em aberto por faixa de atraso">
        <h2>Saldo em aberto: {formatBrl(q.data.totalOutstanding)}</h2>
        <div className="metrics">{q.data.buckets.map(b => <div key={b.code} className="metric" data-testid={`aging-${b.code}`}>
          <span className="muted">{AGING_LABELS[b.code]} ({b.count})</span>
          {b.code !== 'A_VENCER' && b.amount > 0 ? <Badge tone="warning">{formatBrl(b.amount)}</Badge> : <strong>{formatBrl(b.amount)}</strong>}
        </div>)}</div>
      </section>
      <section className="card table-wrap" aria-label="Clientes com saldo em aberto">
        <h2>Clientes com saldo em aberto</h2>
        {q.data.debtors.length === 0 ? <p className="muted">Nenhum cliente com saldo em aberto.</p> : <table className="table">
          <thead><tr><th>Cliente</th><th className="num">Títulos</th><th className="num">Em aberto</th><th className="num">Vencido</th><th>Vencimento mais antigo</th></tr></thead>
          <tbody>{q.data.debtors.map(d => <tr key={d.customerId}><td>{d.customerName ?? '—'}</td><td className="num">{d.count}</td>
            <td className="num">{formatBrl(d.outstanding)}</td><td className="num">{d.overdue > 0 ? <Badge tone="warning">{formatBrl(d.overdue)}</Badge> : formatBrl(0)}</td>
            <td>{formatDay(d.oldestDueDate)}</td></tr>)}</tbody>
        </table>}
      </section>
    </div>}
  </QueryState>
}

function ReceivablesReport({ from, to }: { from: string; to: string }) {
  const q = useQuery({ queryKey: reportKeys.report('receivables', from, to), queryFn: () => reportsApi.receivables(from, to), placeholderData: keepPreviousData })
  const total = (q.data ?? []).filter(r => r.status !== 'CANCELADO').reduce((sum, r) => sum + r.outstanding, 0)
  const exportCsv = () => q.data && downloadCsv(`contas-a-receber-${from}-a-${to}.csv`, toCsv(
    ['OS', 'Cliente', 'Emissão', 'Vencimento', 'Situação', 'Valor', 'Desconto', 'Acréscimo', 'Recebido', 'Em aberto'],
    q.data.map(r => [r.workOrderNumber, r.customerName, r.issuedOn, r.dueDate, r.status, r.original, r.discount, r.surcharge, r.received, r.outstanding])))
  return <QueryState label="as contas a receber" loading={q.isLoading} error={q.error} retry={q.refetch}
    empty={q.data?.length === 0} emptyMessage="Nenhuma conta a receber com vencimento no período.">
    {q.data && <section className="card table-wrap">
      <div className="actions"><span className="muted" data-testid="receivables-open-total">Em aberto: {formatBrl(total)}</span>
        <button type="button" className="btn secondary" onClick={exportCsv}>Exportar CSV</button></div>
      <table className="table">
        <thead><tr><th>OS</th><th>Cliente</th><th>Vencimento</th><th>Situação</th><th className="num">Valor</th><th className="num">Recebido</th><th className="num">Em aberto</th></tr></thead>
        <tbody>{q.data.map(r => <tr key={r.id}><td>#{r.workOrderNumber}</td><td>{r.customerName ?? '—'}</td><td>{formatDay(r.dueDate)}</td>
          <td><Badge tone={statusTone(r.status as FinancialStatus)}>{RECEIVABLE_STATUS_LABELS[r.status as FinancialStatus] ?? r.status}</Badge></td>
          <td className="num">{formatBrl(r.original - r.discount + r.surcharge)}</td><td className="num">{formatBrl(r.received)}</td><td className="num">{formatBrl(r.outstanding)}</td></tr>)}</tbody>
      </table>
    </section>}
  </QueryState>
}

function PayablesReport({ from, to }: { from: string; to: string }) {
  const q = useQuery({ queryKey: reportKeys.report('payables', from, to), queryFn: () => reportsApi.payables(from, to), placeholderData: keepPreviousData })
  const total = (q.data ?? []).filter(r => r.status !== 'CANCELADO').reduce((sum, r) => sum + r.outstanding, 0)
  const exportCsv = () => q.data && downloadCsv(`contas-a-pagar-${from}-a-${to}.csv`, toCsv(
    ['Descrição', 'Fornecedor', 'Categoria', 'Cadastro', 'Vencimento', 'Situação', 'Valor', 'Pago', 'Em aberto'],
    q.data.map(r => [r.description, r.supplier, r.categoryName, r.registeredOn, r.dueDate, r.status, r.amount, r.paid, r.outstanding])))
  return <QueryState label="as contas a pagar" loading={q.isLoading} error={q.error} retry={q.refetch}
    empty={q.data?.length === 0} emptyMessage="Nenhuma conta a pagar com vencimento no período.">
    {q.data && <section className="card table-wrap">
      <div className="actions"><span className="muted" data-testid="payables-open-total">Em aberto: {formatBrl(total)}</span>
        <button type="button" className="btn secondary" onClick={exportCsv}>Exportar CSV</button></div>
      <table className="table">
        <thead><tr><th>Descrição</th><th>Fornecedor</th><th>Categoria</th><th>Vencimento</th><th>Situação</th><th className="num">Valor</th><th className="num">Em aberto</th></tr></thead>
        <tbody>{q.data.map(r => <tr key={r.id}><td>{r.description}</td><td>{r.supplier ?? '—'}</td><td>{r.categoryName}</td><td>{formatDay(r.dueDate)}</td>
          <td><Badge tone={statusTone(r.status as FinancialStatus)}>{PAYABLE_STATUS_LABELS[r.status as FinancialStatus] ?? r.status}</Badge></td>
          <td className="num">{formatBrl(r.amount)}</td><td className="num">{formatBrl(r.outstanding)}</td></tr>)}</tbody>
      </table>
    </section>}
  </QueryState>
}
