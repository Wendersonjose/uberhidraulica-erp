import { todayIso } from '../../api/finance'
import { daysAgo, firstOfMonth, formatDay } from './reportsUtils'
import { formatBrl } from '../../utils/format'

export function PeriodFilter({ from, to, valid, onChange }: { from: string; to: string; valid: boolean; onChange: (from: string, to: string) => void }) {
  return <section className="card filters">
    <div className="inline-form">
      <div className="field"><label>De<input className="input" type="date" value={from} onChange={e => onChange(e.target.value, to)} /></label></div>
      <div className="field"><label>Até<input className="input" type="date" value={to} onChange={e => onChange(from, e.target.value)} /></label></div>
      <div className="actions">
        <button type="button" className="btn secondary" onClick={() => onChange(todayIso(), todayIso())}>Hoje</button>
        <button type="button" className="btn secondary" onClick={() => onChange(daysAgo(6), todayIso())}>Últimos 7 dias</button>
        <button type="button" className="btn secondary" onClick={() => onChange(firstOfMonth(), todayIso())}>Mês atual</button>
      </div>
    </div>
    {!valid && <div role="alert" className="notice error">O período final não pode ser anterior ao inicial.</div>}
  </section>
}

/** Barras horizontais em SVG puro (sem biblioteca de gráficos), com rótulo e valor legíveis por leitor de tela. */
export function BarChart({ items, label }: { items: { label: string; value: number }[]; label: string }) {
  const max = Math.max(...items.map(i => Math.abs(i.value)), 0)
  if (items.length === 0 || max === 0) return <p className="muted">Sem valores no período.</p>
  const row = 28, width = 100
  return <figure className="chart" aria-label={label}>
    <svg role="img" viewBox={`0 0 ${width} ${items.length * row}`} preserveAspectRatio="none" width="100%" height={items.length * row}>
      <title>{label}</title>
      {items.map((item, i) => <rect key={item.label} x={0} y={i * row + 6} height={row - 12} rx={2}
        width={Math.max((Math.abs(item.value) / max) * width, 0.5)} className={item.value < 0 ? 'bar negative' : 'bar'} />)}
    </svg>
    <ul className="chart-legend">{items.map(item => <li key={item.label}><span>{item.label}</span><strong>{formatBrl(item.value)}</strong></li>)}</ul>
  </figure>
}

/** Colunas diárias de entradas (acima) e saídas (abaixo) do fluxo de caixa realizado. */
export function DailyFlowChart({ days, label }: { days: { date: string; inflows: number; outflows: number }[]; label: string }) {
  const max = Math.max(...days.flatMap(d => [d.inflows, d.outflows]), 0)
  if (days.length === 0 || max === 0) return <p className="muted">Sem movimentação realizada no período.</p>
  const w = 10, h = 60
  return <figure className="chart" aria-label={label}>
    <svg role="img" viewBox={`0 0 ${days.length * w} ${h * 2}`} preserveAspectRatio="none" width="100%" height={160}>
      <title>{label}</title>
      <line x1={0} x2={days.length * w} y1={h} y2={h} className="axis" />
      {days.map((d, i) => <g key={d.date}>
        <rect x={i * w + 1} width={w - 2} y={h - (d.inflows / max) * h} height={(d.inflows / max) * h} className="bar" />
        <rect x={i * w + 1} width={w - 2} y={h} height={(d.outflows / max) * h} className="bar negative" />
      </g>)}
    </svg>
    <figcaption className="muted">Entradas acima da linha e saídas abaixo, por dia ({formatDay(days[0].date)} a {formatDay(days[days.length - 1].date)}).</figcaption>
  </figure>
}
