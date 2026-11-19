import { expect, test } from 'vitest'
import { formatCivilDate, formatDate } from '../utils/format'

// Vencimento, emissão e competência chegam como LocalDate ("AAAA-MM-DD"): são dias do calendário, não instantes.
test('data civil sai no dia do XML em America/Sao_Paulo, inclusive na virada de mês e de ano', () => {
  expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('America/Sao_Paulo')
  expect(formatCivilDate('2026-11-19')).toBe('19/11/2026')
  expect(formatCivilDate('2026-12-01')).toBe('01/12/2026')
  expect(formatCivilDate('2027-01-01')).toBe('01/01/2027')
})

test('formatDate trata data civil como dia e instante como data e hora local', () => {
  expect(formatDate('2026-11-19')).toBe('19/11/2026')
  expect(formatDate('2026-11-19T12:00:00Z')).toMatch(/^19\/11\/2026,? 09:00$/)
})

test('formatCivilDate recusa o que não é data civil, para não esconder um instante formatado como dia', () => {
  expect(() => formatCivilDate('2026-11-19T00:00:00Z')).toThrow()
})
