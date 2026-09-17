import { useState, type ReactNode } from 'react'

export function Pagination({ page, totalPages, totalItems, onChange }: {
  page: number; totalPages: number; totalItems: number; onChange: (page: number) => void
}) {
  if (totalPages <= 1) return <div className="pagination muted">{totalItems} registro(s)</div>
  return <nav className="pagination" aria-label="Paginação">
    <span className="muted">{totalItems} registro(s) · página {page + 1} de {totalPages}</span>
    <button type="button" className="btn secondary" disabled={page === 0} onClick={() => onChange(page - 1)}>Anterior</button>
    <button type="button" className="btn secondary" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>Próxima</button>
  </nav>
}

/**
 * Ação que exige confirmação explícita em dois passos, sem `window.confirm`.
 *
 * <p>O erro da API aparece junto da ação, e o botão fica indisponível enquanto a chamada ocorre.</p>
 */
export function ConfirmAction({ label, question, confirmLabel, tone = 'danger', onConfirm, children }: {
  label: string; question: string; confirmLabel?: string; tone?: 'danger' | 'primary'
  onConfirm: () => Promise<unknown>; children?: ReactNode
}) {
  const [asking, setAsking] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  if (!asking) return <button type="button" className={tone === 'danger' ? 'btn danger' : 'btn'} onClick={() => { setError(''); setAsking(true) }}>{label}</button>
  return <div className="notice" role="group" aria-label={question}>
    <p>{question}</p>
    {children}
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="form-actions">
      <button type="button" className="btn secondary" disabled={pending} onClick={() => setAsking(false)}>Voltar</button>
      <button type="button" className={tone === 'danger' ? 'btn danger' : 'btn'} disabled={pending} onClick={async () => {
        setPending(true); setError('')
        try { await onConfirm(); setAsking(false) }
        catch (e) { setError(e instanceof Error ? e.message : 'Não foi possível concluir a operação') }
        finally { setPending(false) }
      }}>{confirmLabel ?? label}</button>
    </div>
  </div>
}
