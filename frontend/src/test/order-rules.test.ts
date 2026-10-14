import { describe, expect, test } from 'vitest'
import {
  allocate, canApprove, evaluateOrder, newLine, parseScaled, toOrderBody, type ExpenseDraft, type LineDraft,
} from '../features/purchasing/orderRules'

const none: ExpenseDraft = { freight: '', insurance: '', other: '' }
const line = (quantity: string, unitPrice: string, discount = '', productId = 'prod'): LineDraft =>
  ({ ...newLine(), productId, quantity, unitPrice, discount })

describe('parseScaled', () => {
  test('aceita vírgula e ponto, sem passar por ponto flutuante', () => {
    expect(parseScaled('1,5', 3)).toBe(1500n)
    expect(parseScaled('1.5', 3)).toBe(1500n)
    expect(parseScaled('0,0001', 4)).toBe(1n)
    expect(parseScaled(' 12 ', 2)).toBe(1200n)
  })
  test('vazio é nulo; negativo, letra e casas demais são inválidos', () => {
    expect(parseScaled('', 2)).toBeNull()
    expect(parseScaled('   ', 2)).toBeNull()
    expect(parseScaled('-1', 2)).toBeUndefined()
    expect(parseScaled('abc', 2)).toBeUndefined()
    expect(parseScaled('1,234', 2)).toBeUndefined()
    expect(parseScaled('1,2,3', 2)).toBeUndefined()
  })
})

describe('allocate', () => {
  test('o centavo residual vai para a maior base e a soma fecha exatamente', () => {
    const shares = allocate([10000n, 5000n], 1000n)
    expect(shares).toEqual([667n, 333n])
    expect(shares.reduce((a, b) => a + b, 0n)).toBe(1000n)
  })
  test('empate pelo menor índice; bases zeradas dividem por igual', () => {
    expect(allocate([100n, 100n, 100n], 100n)).toEqual([34n, 33n, 33n])
    expect(allocate([0n, 0n], 101n)).toEqual([51n, 50n])
    expect(allocate([5n, 5n], 0n)).toEqual([0n, 0n])
  })
})

describe('evaluateOrder', () => {
  test('espelha o backend: frete rateado por valor, custo unitário e total a pagar', () => {
    const result = evaluateOrder([line('10', '10,00'), line('5', '10,00')], { ...none, freight: '10,00' })

    expect(result.valid).toBe(true)
    expect(result.lines.map(l => l.freight)).toEqual([6.67, 3.33])
    expect(result.lines.map(l => l.unitCost)).toEqual([10.667, 10.666])
    expect(result.totals).toMatchObject({ goods: 150, discount: 0, freight: 10, payable: 160 })
  })

  test('desconto reduz o líquido mas não o total de mercadorias', () => {
    const result = evaluateOrder([line('4', '25,50', '2,00')], none)

    expect(result.totals).toMatchObject({ goods: 102, discount: 2, payable: 100 })
    expect(result.lines[0].net).toBe(100)
  })

  test('o bruto é arredondado ao centavo (meio para cima)', () => {
    expect(evaluateOrder([line('3', '3,3333')], none).totals.goods).toBe(10)
    expect(evaluateOrder([line('1', '0,005')], { ...none, other: '1' }).lines[0].gross).toBe(0.01)
  })

  test('0,1 + 0,2 fecha em 0,30, sem sobra de ponto flutuante', () => {
    const result = evaluateOrder([line('1', '0,1'), line('1', '0,2')], none)

    expect(result.totals.payable).toBe(0.3)
    expect(result.lines.map(l => l.total)).toEqual([0.1, 0.2])
  })

  test('recusa produto ausente, quantidade inválida, desconto maior que o item e total zero', () => {
    expect(evaluateOrder([line('1', '1', '', '')], none).valid).toBe(false)
    expect(evaluateOrder([line('0', '1')], none).valid).toBe(false)
    expect(evaluateOrder([line('1,2345', '1')], none).valid).toBe(false)
    const discount = evaluateOrder([line('1', '10', '10,01')], none)
    expect(discount.valid).toBe(false)
    expect(Object.values(discount.lineErrors)[0]).toContain('desconto maior')
    const free = evaluateOrder([line('2', '0')], none)
    expect(free.valid).toBe(false)
    expect(free.errors).toContain('O total do pedido deve ser maior que zero')
    expect(evaluateOrder([], none).valid).toBe(false)
  })

  test('despesa inválida ou acima do limite é recusada', () => {
    expect(evaluateOrder([line('1', '1')], { ...none, freight: '-1' }).valid).toBe(false)
    expect(evaluateOrder([line('1', '1')], { ...none, insurance: '100000001' }).valid).toBe(false)
    expect(evaluateOrder([line('1', '1')], { ...none, other: '100000000' }).valid).toBe(true)
  })

  test('o total das linhas é exatamente o total do pedido', () => {
    const result = evaluateOrder(
      [line('7', '13,37', '0,50'), line('2', '99,99'), line('1', '0,01')], { freight: '7,31', insurance: '1,11', other: '0,99' })

    const sumCents = result.lines.reduce((total, l) => total + Math.round(l.total * 100), 0)
    expect(result.valid).toBe(true)
    expect(sumCents).toBe(Math.round(result.totals.payable * 100))
  })
})

describe('toOrderBody', () => {
  test('envia números no formato que o servidor lê, com vírgula convertida', () => {
    const body = toOrderBody([{ ...line('1,5', '3,3333', '0,50'), supplierCode: '  ABC-1 ' }],
      { freight: '10,5', insurance: '', other: '' })

    expect(body).toEqual({
      freight: 10.5, insurance: 0, other: 0,
      lines: [{ productId: 'prod', quantity: 1.5, unitPrice: 3.3333, discount: 0.5, supplierCode: 'ABC-1' }],
    })
  })
})

describe('canApprove (D8)', () => {
  test('quem criou só aprova com a exceção; outra pessoa aprova com a permissão comum', () => {
    expect(canApprove(true, false, 'u1', 'u1')).toBe(false)
    expect(canApprove(true, true, 'u1', 'u1')).toBe(true)
    expect(canApprove(true, false, 'u1', 'u2')).toBe(true)
    expect(canApprove(false, true, 'u1', 'u2')).toBe(false)
  })
})
