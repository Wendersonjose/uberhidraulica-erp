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
async function parseError(response: Response) {
  let message = `Erro ${response.status}`, code: string | undefined
  try {
    const body = await response.json()
    message = body.message || body.detail || body.error || message
    code = body.code
  } catch { /* Non-JSON errors still carry an HTTP status. */ }
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
