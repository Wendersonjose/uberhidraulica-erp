import { describe, expect, test, vi } from 'vitest'
import { api, ApiError, statusMessage } from '../api/http'
import { formatQuantity } from '../utils/format'

function respond(status: number, body: string | null, contentType = 'application/json') {
  vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response(body, { status, headers: { 'Content-Type': contentType } }))
}

async function failure(path = '/api/customers') {
  try { await api(path) } catch (error) { return error as ApiError }
  throw new Error('a chamada deveria falhar')
}

describe('mensagens de erro da API', () => {
  test('o contrato {code, message} da API é preservado', async () => {
    respond(409, JSON.stringify({ code: 'INSUFFICIENT_STOCK', message: 'Saldo insuficiente', details: [] }))
    const error = await failure()
    expect(error).toMatchObject({ status: 409, code: 'INSUFFICIENT_STOCK', message: 'Saldo insuficiente' })
  })

  test('o 429 do nginx (corpo não JSON) vira mensagem em português, não "Erro 429"', async () => {
    respond(429, '<html><body>Too Many Requests</body></html>', 'text/html')
    const error = await failure('/api/iam/auth/login')
    expect(error.status).toBe(429)
    expect(error.message).toMatch(/Muitas tentativas/)
  })

  test('o erro padrão do Spring (em inglês) não chega ao usuário', async () => {
    respond(400, JSON.stringify({ timestamp: 'x', status: 400, error: 'Bad Request', path: '/api/work-orders/x' }))
    const error = await failure()
    expect(error.status).toBe(400)
    expect(error.message).not.toMatch(/Bad Request/)
    expect(error.message).toBe('Erro 400')
  })

  test('status sem corpo recebe um texto por faixa', () => {
    expect(statusMessage(403)).toBe('Acesso negado.')
    expect(statusMessage(404)).toBe('Registro não encontrado.')
    expect(statusMessage(500)).toMatch(/Erro interno/)
    expect(statusMessage(503)).toMatch(/Erro interno/)
  })
})

describe('quantidades', () => {
  test('embalagens fechadas têm rótulo próprio em vez do código da unidade', () => {
    expect(formatQuantity(2, 'GALAO_5L')).toBe('2 galão 5 L')
    expect(formatQuantity(1, 'BALDE_20L')).toBe('1 balde 20 L')
    expect(formatQuantity(1.5, 'LITRO')).toBe('1,5 L')
  })
})
