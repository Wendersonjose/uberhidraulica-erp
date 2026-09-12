import type { ReactNode } from 'react'
export function QueryState({ loading, error, empty = false, label, emptyMessage, retry, children }: {
  loading: boolean; error: unknown; empty?: boolean; label: string
  emptyMessage?: ReactNode; retry: () => unknown; children?: ReactNode
}) {
  if (error) return <div role="alert" className="notice error">Não foi possível carregar {label}. <button type="button" className="btn secondary" onClick={() => { void retry() }}>Tentar novamente: {label}</button></div>
  if (loading) return <div role="status" className="muted">Carregando {label}…</div>
  if (empty) return <div role="status" className="notice">{emptyMessage}</div>
  return <>{children}</>
}
