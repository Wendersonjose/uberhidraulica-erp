import { useState, type ReactNode } from 'react'
import { useAuth } from './useAuth'
export function LogoutButton({ children = 'Sair', className = 'btn secondary' }: { children?: ReactNode; className?: string }) {
  const { logout } = useAuth()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  async function submit() {
    setPending(true)
    setError('')
    try { await logout() }
    catch { setError('Não foi possível sair. Tente novamente.') }
    finally { setPending(false) }
  }
  return <><button type="button" className={className} disabled={pending} onClick={submit}>{pending ? 'Saindo…' : children}</button>{error && <div role="alert" className="notice error">{error}</div>}</>
}
