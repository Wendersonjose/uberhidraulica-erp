import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { vi } from 'vitest'
import { AuthProvider } from '../auth/AuthProvider'
import App from '../App'

export const session = {
  id: 'u1', name: 'Operador', email: 'op@teste.local', profileCode: 'DONO', state: 'ACTIVE',
  mustChangePassword: false, permissions: [] as string[],
}

export function json(body: unknown, status = 200) {
  return new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

type Route = unknown | ((init: RequestInit | undefined, url: URL) => Response | Promise<Response>)

/**
 * Simula a API por "MÉTODO caminho". A chave pode incluir a query string exata ou só o caminho;
 * valores que são funções recebem o `init` e a URL e devolvem a resposta.
 */
export function mockApi(routes: Record<string, Route>, currentSession: typeof session | null = session) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const raw = String(input), method = init?.method || 'GET', url = new URL(raw, 'http://localhost')
    if (raw === '/api/iam/csrf') return json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'test' })
    if (raw === '/api/iam/session') return currentSession ? json(currentSession) : json({}, 401)
    const route = routes[`${method} ${raw}`] ?? routes[`${method} ${url.pathname}`]
    if (route === undefined) return json({ message: 'Falha simulada' }, 500)
    if (typeof route === 'function') return route(init, url)
    return json(route, method === 'POST' ? 201 : 200)
  })
}

export function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}>
    <AuthProvider><App /></AuthProvider>
  </MemoryRouter></QueryClientProvider>)
  return client
}

export const page = <T,>(items: T[]) => ({ items, totalItems: items.length, page: 0, size: 20, totalPages: items.length ? 1 : 0 })

export function requestsTo(fetch: ReturnType<typeof mockApi>, method: string, path: string) {
  return fetch.mock.calls.filter(([input, init]) => String(input) === path && (init?.method || 'GET') === method)
}

export function lastBody(fetch: ReturnType<typeof mockApi>, method: string, path: string) {
  const call = requestsTo(fetch, method, path).at(-1)
  return call?.[1]?.body ? JSON.parse(String(call[1].body)) : undefined
}
