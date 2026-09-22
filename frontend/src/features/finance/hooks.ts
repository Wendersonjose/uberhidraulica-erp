import { useState } from 'react'
import { newIdempotencyKey } from '../../api/finance'
import { useAuth } from '../../auth/useAuth'

/** Reflete a autorização na interface; quem decide é sempre o backend. */
export function useCan(permission: string) {
  const { session } = useAuth()
  return Boolean(session?.permissions?.includes(permission))
}

export const formatDay = (iso: string) => iso.split('-').reverse().join('/')

/**
 * Chave de idempotência da intenção em curso: continua a mesma em um novo clique ou retry depois de erro de
 * rede, e é trocada quando o lançamento é concluído — então repetir não duplica e o próximo é novo.
 */
export function useIdempotencyKey() {
  const [key, setKey] = useState(newIdempotencyKey)
  return { key, renew: () => setKey(newIdempotencyKey()) }
}

/** Valor monetário digitado: positivo e com no máximo duas casas, como o backend exige. */
export const validAmount = (value: string) => /^\d+(\.\d{1,2})?$/.test(value.trim()) && Number(value) > 0
