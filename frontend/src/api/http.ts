export class ApiError extends Error {
  constructor(message: string, public status: number, public code?: string) { super(message) }
}
type Csrf = { headerName: string; parameterName: string; token: string }
type SessionIssue = 'expired' | 'password-change-required'
const listeners = new Set<(issue: SessionIssue) => void>()
let sessionVersion = 0
export function onSessionIssue(listener: (issue: SessionIssue) => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}
// Discard responses belonging to an earlier session, including late 401s.
export function resetApiSession() { sessionVersion++ }
/** Mensagem em português quando a resposta não traz uma do contrato da API (proxy, framework, rede). */
export function statusMessage(status: number) {
  if (status === 429) return 'Muitas tentativas em pouco tempo. Aguarde um instante e tente novamente.'
  if (status === 401) return 'Sessão expirada. Entre novamente.'
  if (status === 403) return 'Acesso negado.'
  if (status === 404) return 'Registro não encontrado.'
  if (status === 409) return 'A operação conflita com o estado atual do registro.'
  if (status >= 500) return 'Erro interno. Tente novamente em instantes.'
  return `Erro ${status}`
}
async function parseError(response: Response) {
  let message = statusMessage(response.status), code: string | undefined
  try {
    const body = await response.json()
    // Só o contrato da API ({code, message, details}) traz texto para o usuário; o `error` do Spring é em inglês.
    message = body.message || body.detail || message
    code = body.code
  } catch { /* Non-JSON errors (por exemplo, o 429 do nginx) ainda carregam o status HTTP. */ }
  return new ApiError(message, response.status, code)
}
export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const version = sessionVersion
  const checkSession = () => {
    if (version !== sessionVersion) throw new DOMException('Sessão alterada', 'AbortError')
  }
  const method = (options.method || 'GET').toUpperCase()
  const headers = new Headers(options.headers)
  const sessionProbe = path === '/api/iam/session' || path === '/api/iam/auth/login'
  async function checkResponse(response: Response) {
    checkSession()
    if (!response.ok) {
      const error = await parseError(response)
      checkSession()
      if (!sessionProbe && error.status === 401) listeners.forEach(listener => listener('expired'))
      if (!sessionProbe && error.status === 403 && error.code === 'PASSWORD_CHANGE_REQUIRED') {
        listeners.forEach(listener => listener('password-change-required'))
      }
      throw error
    }
  }
  if (options.body) headers.set('Content-Type', 'application/json')
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const response = await fetch('/api/iam/csrf', { credentials: 'include', signal: options.signal })
    await checkResponse(response)
    const csrf: Csrf = await response.json()
    checkSession()
    headers.set(csrf.headerName, csrf.token)
  }
  const response = await fetch(path, { ...options, headers, credentials: 'include' })
  await checkResponse(response)
  if (response.status === 204) return undefined as T
  const data: T = await response.json()
  checkSession()
  return data
}
export const post = <T>(path: string, body: unknown) => api<T>(path, { method: 'POST', body: JSON.stringify(body) })

/**
 * Chamada da superfície pública do orçamento.
 *
 * <p>Não envia cookie nem token CSRF, e não participa do controle de sessão: o cliente externo não
 * tem conta, e mandar credencial de sessão junto só criaria autoridade ambiente onde não deve haver.
 */
export async function publicApi<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body) headers.set('Content-Type', 'application/json')
  const response = await fetch(path, { ...options, headers, credentials: 'omit' })
  if (!response.ok) throw await parseError(response)
  return response.json() as Promise<T>
}
