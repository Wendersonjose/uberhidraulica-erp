/**
 * Divisão em parcelas iguais com os centavos que sobram na última — a mesma regra do backend
 * (`Receivable.equalSplit`, DR-0024 Q1). Trabalha em centavos inteiros para não errar com ponto flutuante.
 */
export function splitEqually(total: number, count: number): number[] {
  const cents = Math.round(total * 100)
  const each = Math.floor(cents / count)
  return Array.from({ length: count }, (_, i) => (i < count - 1 ? each : cents - each * (count - 1)) / 100)
}

/** Soma meses a uma data ISO, como `LocalDate.plusMonths`: 31/01 + 1 mês = último dia de fevereiro. */
export function addMonths(iso: string, months: number): string {
  const [year, month, day] = iso.split('-').map(Number)
  const target = new Date(Date.UTC(year, month - 1 + months, 1))
  const last = new Date(Date.UTC(target.getUTCFullYear(), target.getUTCMonth() + 1, 0)).getUTCDate()
  target.setUTCDate(Math.min(day, last))
  return target.toISOString().slice(0, 10)
}
