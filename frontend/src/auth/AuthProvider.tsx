import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api, ApiError, onSessionIssue, post, resetApiSession } from '../api/http'
import type { Session } from '../api/types'
import { AuthContext } from './useAuth'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null)
  const [loading, setLoading] = useState(true)
  const revision = useRef(0)
  const client = useQueryClient()
  const clearSessionData = useCallback(() => {
    revision.current++
    resetApiSession()
    void client.cancelQueries()
    client.clear()
  }, [client])
  const endSession = useCallback(() => {
    clearSessionData()
    setSession(null)
    setLoading(false)
  }, [clearSessionData])
  const refresh = useCallback(async () => {
    const current = revision.current
    try {
      const next = await api<Session>('/api/iam/session')
      if (current === revision.current) setSession(next)
    } catch (error) {
      if (current !== revision.current) return
      if (error instanceof ApiError && error.status === 401) endSession()
      else throw error
    } finally {
      if (current === revision.current) setLoading(false)
    }
  }, [endSession])

  useEffect(() => {
    const unsubscribe = onSessionIssue(issue => {
      if (issue === 'expired') endSession()
      else setSession(current => current ? { ...current, mustChangePassword: true } : null)
    })
    let active = true
    const current = revision.current
    void api<Session>('/api/iam/session').then(next => {
      if (active && current === revision.current) setSession(next)
    }).catch(() => {
      if (active && current === revision.current) endSession()
    }).finally(() => {
      if (active && current === revision.current) setLoading(false)
    })
    return () => { active = false; unsubscribe() }
  }, [endSession])

  async function login(email: string, password: string) {
    clearSessionData()
    const next = await post<Session>('/api/iam/auth/login', { email, password })
    setSession(next)
    return next
  }
  async function logout() {
    try { await post<void>('/api/iam/auth/logout', {}) }
    catch (error) {
      if (!(error instanceof ApiError && error.status === 401)) throw error
    }
    endSession()
  }
  return <AuthContext.Provider value={{ session, loading, login, logout, refresh }}>{children}</AuthContext.Provider>
}
