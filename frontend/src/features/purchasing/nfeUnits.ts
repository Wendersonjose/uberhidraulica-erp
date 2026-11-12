import type { ProductUnit } from '../../api/types'

/**
 * Equivalências diretas entre a unidade da NF-e e a do catálogo (DR-0022 D7). Espelha `UnitConversion` do
 * backend, que é quem decide: aqui serve só para sugerir a unidade ao cadastrar um produto a partir da nota.
 * Qualquer outra unidade (CX, PAR, JG, KIT…) não tem equivalência e exige fator de conversão.
 */
const DIRECT: Record<string, ProductUnit> = {
  UN: 'UNIDADE', UND: 'UNIDADE', UNID: 'UNIDADE', UNIDADE: 'UNIDADE', PC: 'UNIDADE', 'PÇ': 'UNIDADE', PCS: 'UNIDADE', PEC: 'UNIDADE',
  L: 'LITRO', LT: 'LITRO', LTS: 'LITRO', LITRO: 'LITRO',
  M: 'METRO', MT: 'METRO', MTS: 'METRO', METRO: 'METRO',
  KG: 'QUILOGRAMA', QUILO: 'QUILOGRAMA', QUILOGRAMA: 'QUILOGRAMA',
}

export const normalizeInvoiceUnit = (unit: string | null | undefined) => (unit ?? '').trim().toUpperCase().replace(/\./g, '')

/** Unidade do catálogo equivalente à da nota, ou `null` quando a conversão depende de fator. */
export const directCatalogUnit = (unit: string | null | undefined): ProductUnit | null => DIRECT[normalizeInvoiceUnit(unit)] ?? null

/** Sugestão para o cadastro assistido: a equivalente, ou `UNIDADE` quando não há (o fator cobre a diferença). */
export const suggestCatalogUnit = (unit: string | null | undefined): ProductUnit => directCatalogUnit(unit) ?? 'UNIDADE'
