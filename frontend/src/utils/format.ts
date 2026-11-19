export const digits=(value:string)=>value.replace(/\D/g,'');export function formatDocument(value:string){const d=digits(value);return d.length<=11?d.replace(/(\d{3})(\d{3})(\d{3})(\d{2})/,'$1.$2.$3-$4'):d.replace(/(\d{2})(\d{3})(\d{3})(\d{4})(\d{2})/,'$1.$2.$3/$4-$5')}export const formatBrl=(value:number)=>new Intl.NumberFormat('pt-BR',{style:'currency',currency:'BRL'}).format(value);export const formatDate=(value:string)=>CIVIL_DATE.test(value)?formatCivilDate(value):new Intl.DateTimeFormat('pt-BR',{dateStyle:'short',timeStyle:'short'}).format(new Date(value));export const formatMileage=(value:number)=>`${new Intl.NumberFormat('pt-BR').format(value)} km`;export const vehicleLabel=(v:{manufacturer:string;model:string;plate:string})=>`${v.manufacturer} ${v.model} • ${v.plate}`

/**
 * Data civil (`LocalDate` do backend: vencimento, emissão da NF-e). É um dia do calendário, não um instante: formatar
 * sem `new Date`, porque `new Date('2026-11-19')` é meia-noite UTC, que no Brasil vira 18/11 às 21h.
 */
const CIVIL_DATE = /^\d{4}-\d{2}-\d{2}$/
export function formatCivilDate(value: string) {
  if (!CIVIL_DATE.test(value)) throw new Error(`data civil inválida: ${value}`)
  const [year, month, day] = value.split('-')
  return `${day}/${month}/${year}`
}

const UNIT_SUFFIX: Record<string, string> = { UNIDADE: 'un', LITRO: 'L', METRO: 'm', QUILOGRAMA: 'kg', GALAO_5L: 'galão 5 L', BALDE_20L: 'balde 20 L' }
/** DR-0006: unidades contáveis só aceitam inteiros; as contínuas, até três casas. O backend é a autoridade. */
const COUNTABLE_UNITS = new Set(['UNIDADE', 'GALAO_5L', 'BALDE_20L'])
export const isCountableUnit = (unit: string | undefined) => !!unit && COUNTABLE_UNITS.has(unit)
export const quantityStep = (unit: string | undefined) => (isCountableUnit(unit) ? '1' : '0.001')
export function quantityFitsUnit(value: string, unit: string | undefined) {
  const decimals = value.trim().split('.')[1]?.replace(/0+$/, '').length ?? 0
  return decimals <= (isCountableUnit(unit) ? 0 : 3)
}
export const quantityRuleHint = (unit: string | undefined) =>
  isCountableUnit(unit) ? 'Esta unidade aceita somente quantidade inteira.' : 'Esta unidade aceita até três casas decimais.'

export const formatQuantity = (value: number, unit: string) =>
  `${new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 3 }).format(value)} ${UNIT_SUFFIX[unit] ?? unit}`

export function formatPhone(value: string | null | undefined) {
  if (!value) return '—'
  const d = digits(value)
  if (d.length === 11) return d.replace(/(\d{2})(\d{5})(\d{4})/, '($1) $2-$3')
  if (d.length === 10) return d.replace(/(\d{2})(\d{4})(\d{4})/, '($1) $2-$3')
  return d
}
export const formatZipCode = (value: string) => digits(value).replace(/(\d{5})(\d{3})/, '$1-$2')

/** Pontos percentuais já calculados pelo backend (ex.: 23.5 → "23,50%"), não uma fração 0–1. */
export const formatPercent = (value: number) =>
  `${new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value)}%`
