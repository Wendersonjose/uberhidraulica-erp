import { createContext, useContext } from 'react'
import type { Session } from '../api/types'
type Auth = {
  session: Session | null
  loading: boolean
  login: (email: string, password: string) => Promise<Session>
  logout: () => Promise<void>
  refresh: () => Promise<void>
}
export const AuthContext = createContext<Auth | null>(null)
export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth fora de AuthProvider')
  return value
}
