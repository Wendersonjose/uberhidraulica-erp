import { todayIso } from '../../api/finance'

export const firstOfMonth = () => `${todayIso().slice(0, 8)}01`

export function daysAgo(days: number) {
  const [year, month, day] = todayIso().split('-').map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  date.setUTCDate(date.getUTCDate() - days)
  return date.toISOString().slice(0, 10)
}

/** `2026-11-18` → `18/11/2026`, sem passar por fuso horário. */
export const formatDay = (iso: string) => { const [y, m, d] = iso.split('-'); return `${d}/${m}/${y}` }
