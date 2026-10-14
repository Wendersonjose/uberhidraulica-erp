/**
 * Valorização do pedido no navegador (TASK-0021 I5). Espelha `PurchaseOrder.price` do backend, que é quem decide:
 * aqui serve só para o operador ver o total e o custo por unidade antes de salvar. O servidor recalcula tudo.
 *
 * Tudo em inteiros (BigInt): quantidade em milésimos, preço unitário em décimos de milésimo e dinheiro em centavos.
 * Ponto flutuante não entra em conta de dinheiro (DR-0007), nem para exibir.
 */

export const MAX_LINES = 200
const MAX_QUANTITY = 1_000_000n * 1000n
const MAX_UNIT_PRICE = 1_000_000n * 10_000n
const MAX_EXPENSE = 100_000_000n * 100n

export type LineDraft = {
  key: string; productId: string; quantity: string; unitPrice: string; discount: string; supplierCode: string
}
export type ExpenseDraft = { freight: string; insurance: string; other: string }

let sequence = 0
export const newLine = (): LineDraft => ({
  key: `line-${++sequence}`, productId: '', quantity: '', unitPrice: '', discount: '', supplierCode: '',
})

/**
 * Número digitado (aceita vírgula) como inteiro na escala dada. `null` quando vazio; `undefined` quando inválido
 * (negativo, letras, casas demais). Sem passar por `Number`, então 0,1 + 0,2 não vira 0,30000000000000004.
 */
export function parseScaled(text: string, decimals: number): bigint | null | undefined {
  const normalized = text.trim().replace(',', '.')
  if (normalized === '') return null
  const match = new RegExp(`^(\\d+)(?:\\.(\\d{1,${decimals}}))?$`).exec(normalized)
  if (!match) return undefined
  return BigInt(match[1] + (match[2] ?? '').padEnd(decimals, '0'))
}

export type PricedLine = {
  gross: number; discount: number; net: number; freight: number; insurance: number; other: number
  total: number; unitCost: number
}
export type OrderTotals = { goods: number; discount: number; freight: number; insurance: number; other: number; payable: number }
export type OrderEvaluation = {
  errors: string[]
  /** Erro por linha (chave da linha), para marcar o campo certo. */
  lineErrors: Record<string, string>
  lines: PricedLine[]
  totals: OrderTotals
  valid: boolean
}

const reais = (cents: bigint) => Number(cents) / 100

/** Distribui `total` proporcionalmente a `bases`; o centavo residual vai para a maior base (a primeira, no empate). */
export function allocate(bases: bigint[], total: bigint): bigint[] {
  if (total === 0n) return bases.map(() => 0n)
  const sum = bases.reduce((a, b) => a + b, 0n)
  const shares = sum === 0n
    ? bases.map(() => total / BigInt(bases.length))
    : bases.map(base => (total * base) / sum)
  let anchor = 0
  bases.forEach((base, index) => { if (base > bases[anchor]) anchor = index })
  shares[anchor] += total - shares.reduce((a, b) => a + b, 0n)
  return shares
}

export function evaluateOrder(lines: LineDraft[], expenses: ExpenseDraft): OrderEvaluation {
  const errors: string[] = []
  const lineErrors: Record<string, string> = {}
  const empty: OrderTotals = { goods: 0, discount: 0, freight: 0, insurance: 0, other: 0, payable: 0 }

  if (lines.length === 0) errors.push('Informe ao menos um item')
  if (lines.length > MAX_LINES) errors.push(`Um pedido aceita até ${MAX_LINES} itens`)

  const expense = (text: string, label: string) => {
    const value = parseScaled(text, 2)
    if (value === undefined || (value ?? 0n) > MAX_EXPENSE) { errors.push(`${label} inválido`); return 0n }
    return value ?? 0n
  }
  const freight = expense(expenses.freight, 'Frete')
  const insurance = expense(expenses.insurance, 'Seguro')
  const other = expense(expenses.other, 'Outras despesas')

  const quantities: bigint[] = [], prices: bigint[] = [], grosses: bigint[] = [], discounts: bigint[] = []
  let lineProblem = false
  lines.forEach((line, index) => {
    const label = `Item ${index + 1}`
    const quantity = parseScaled(line.quantity, 3)
    const price = parseScaled(line.unitPrice, 4)
    const discount = parseScaled(line.discount, 2)
    let problem: string | null = null
    if (!line.productId) problem = 'escolha o produto'
    else if (quantity == null || quantity === 0n || quantity > MAX_QUANTITY) problem = 'quantidade inválida (até 3 casas, maior que zero)'
    else if (price == null || price > MAX_UNIT_PRICE) problem = 'preço unitário inválido (até 4 casas)'
    else if (discount === undefined) problem = 'desconto inválido'

    const q = quantity ?? 0n, p = price ?? 0n
    const gross = (q * p + 50_000n) / 100_000n
    const off = discount ?? 0n
    if (!problem && off > gross) problem = 'desconto maior que o valor do item'

    if (problem) { lineErrors[line.key] = `${label}: ${problem}`; lineProblem = true }
    quantities.push(q); prices.push(p); grosses.push(gross); discounts.push(off)
  })
  if (lineProblem) errors.push('Corrija os itens destacados')

  if (errors.length > 0 && lines.length === 0) {
    return { errors, lineErrors, lines: [], totals: empty, valid: false }
  }

  const nets = grosses.map((gross, index) => gross - discounts[index])
  const freightShares = allocate(nets, freight)
  const insuranceShares = allocate(nets, insurance)
  const otherShares = allocate(nets, other)

  const priced: PricedLine[] = lines.map((_, index) => {
    const total = nets[index] + freightShares[index] + insuranceShares[index] + otherShares[index]
    const quantity = quantities[index]
    const unitCost = quantity > 0n ? (total * 100_000n + quantity / 2n) / quantity : 0n
    return {
      gross: reais(grosses[index]), discount: reais(discounts[index]), net: reais(nets[index]),
      freight: reais(freightShares[index]), insurance: reais(insuranceShares[index]),
      other: reais(otherShares[index]), total: reais(total), unitCost: Number(unitCost) / 10_000,
    }
  })

  const goods = grosses.reduce((a, b) => a + b, 0n)
  const discount = discounts.reduce((a, b) => a + b, 0n)
  const payable = goods - discount + freight + insurance + other
  if (!lineProblem && lines.length > 0 && payable <= 0n) errors.push('O total do pedido deve ser maior que zero')

  return {
    errors, lineErrors, lines: priced, valid: errors.length === 0,
    totals: { goods: reais(goods), discount: reais(discount), freight: reais(freight), insurance: reais(insurance),
      other: reais(other), payable: reais(payable) },
  }
}

/** Conteúdo enviado ao servidor; só chamar com a avaliação válida. */
export function toOrderBody(lines: LineDraft[], expenses: ExpenseDraft) {
  const number = (text: string, decimals: number) => {
    const scaled = parseScaled(text, decimals)
    return scaled == null ? undefined : Number(scaled) / 10 ** decimals
  }
  return {
    freight: number(expenses.freight, 2) ?? 0, insurance: number(expenses.insurance, 2) ?? 0,
    other: number(expenses.other, 2) ?? 0,
    lines: lines.map(line => ({
      productId: line.productId, quantity: number(line.quantity, 3) as number,
      unitPrice: number(line.unitPrice, 4) as number, discount: number(line.discount, 2) ?? 0,
      supplierCode: line.supplierCode.trim() || null,
    })),
  }
}

/**
 * Segregação de funções (DR-0022 D8) como a tela a entende: orienta o botão, mas quem decide é o backend.
 * Quem criou só aprova com `PURCHASE_APPROVE_OWN`.
 */
export function canApprove(hasApprove: boolean, hasApproveOwn: boolean, createdBy: string, me: string | undefined) {
  if (!hasApprove) return false
  return createdBy !== me || hasApproveOwn
}
