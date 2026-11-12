/**
 * CPF e CNPJ (inclusive o CNPJ alfanumérico da NT Conjunta 2025.001) no navegador.
 *
 * Só dá retorno rápido ao usuário: quem decide é sempre o backend, que repete a mesma validação.
 */
const CNPJ_WEIGHTS_1 = [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]
const CNPJ_WEIGHTS_2 = [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]

/** Remove a máscara e deixa letras em maiúsculas; o CNPJ alfanumérico tem letras nas 12 primeiras posições. */
export const normalizeTaxId = (value: string) => value.replace(/[^0-9A-Za-z]/g, '').toUpperCase()

function cnpjDigit(cnpj: string, length: number, weights: number[]) {
  let sum = 0
  for (let i = 0; i < length; i++) sum += (cnpj.charCodeAt(i) - 48) * weights[i]
  const remainder = sum % 11
  return remainder < 2 ? 0 : 11 - remainder
}

export function isValidCnpj(value: string) {
  const cnpj = normalizeTaxId(value)
  if (!/^[0-9A-Z]{12}[0-9]{2}$/.test(cnpj) || new Set(cnpj).size === 1) return false
  return cnpj.charCodeAt(12) - 48 === cnpjDigit(cnpj, 12, CNPJ_WEIGHTS_1) && cnpj.charCodeAt(13) - 48 === cnpjDigit(cnpj, 13, CNPJ_WEIGHTS_2)
}

function cpfDigit(cpf: string, length: number) {
  let sum = 0
  for (let i = 0; i < length; i++) sum += Number(cpf[i]) * (length + 1 - i)
  const remainder = (sum * 10) % 11
  return remainder === 10 ? 0 : remainder
}

export function isValidCpf(value: string) {
  const cpf = normalizeTaxId(value)
  if (!/^[0-9]{11}$/.test(cpf) || new Set(cpf).size === 1) return false
  return Number(cpf[9]) === cpfDigit(cpf, 9) && Number(cpf[10]) === cpfDigit(cpf, 10)
}

/** Máscara de CPF (11) ou CNPJ (14, numérico ou alfanumérico); qualquer outro texto volta como veio (ex.: CPF mascarado). */
export function formatTaxId(value: string | null | undefined) {
  if (!value) return '—'
  if (/^[0-9]{11}$/.test(value)) return value.replace(/(\d{3})(\d{3})(\d{3})(\d{2})/, '$1.$2.$3-$4')
  if (/^[0-9A-Z]{12}[0-9]{2}$/.test(value)) return value.replace(/^(.{2})(.{3})(.{3})(.{4})(.{2})$/, '$1.$2.$3/$4-$5')
  return value
}
