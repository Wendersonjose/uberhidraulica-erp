import { api, post } from './http'

export type ProfileCode = 'DONO' | 'GERENTE_ADMINISTRATIVO' | 'GERENTE_FINANCEIRO'
export type UserState = 'ACTIVE' | 'INACTIVE'
export type PermissionResolution = 'INHERIT' | 'ALLOW' | 'DENY'
export type IamUser = { id: string; name: string; email: string; profileCode: ProfileCode; state: UserState; mustChangePassword: boolean }
export type IamUserPage = { content: IamUser[]; page: number; size: number; totalElements: number; totalPages: number }
export type IamProfile = { code: ProfileCode; permissions: string[] }

export const PROFILE_LABELS: Record<ProfileCode, string> = {
  DONO: 'Dono', GERENTE_ADMINISTRATIVO: 'Gerente administrativo', GERENTE_FINANCEIRO: 'Gerente financeiro',
}
export const USER_STATE_LABELS: Record<UserState, string> = { ACTIVE: 'Ativo', INACTIVE: 'Inativo' }

const put = <T>(path: string, body: unknown) => api<T>(path, { method: 'PUT', body: JSON.stringify(body) })
const patch = <T>(path: string, body: unknown) => api<T>(path, { method: 'PATCH', body: JSON.stringify(body) })
const query = (params: Record<string, string | number | undefined | null>) => {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => { if (value !== undefined && value !== null && value !== '') search.set(key, String(value)) })
  const text = search.toString()
  return text ? `?${text}` : ''
}

export const iamApi = {
  users: (page = 0, size = 20) => api<IamUserPage>('/api/iam/users' + query({ page, size })),
  user: (id: string) => api<IamUser>(`/api/iam/users/${id}`),
  createUser: (body: { name: string; email: string; profileCode: ProfileCode }) =>
    post<{ user: IamUser; temporaryPassword: string }>('/api/iam/users', body),
  changeState: (id: string, state: UserState) => patch<IamUser>(`/api/iam/users/${id}`, { state }),
  /** A senha temporária só existe nesta resposta; o backend não a expõe de novo em nenhuma outra chamada. */
  resetPassword: (id: string) => post<{ temporaryPassword: string }>(`/api/iam/users/${id}/password-reset`, {}),
  exceptions: (id: string) => api<Record<string, PermissionResolution>>(`/api/iam/users/${id}/permission-exceptions`),
  setException: (id: string, permissionCode: string, resolution: PermissionResolution) =>
    put<void>(`/api/iam/users/${id}/permission-exceptions/${encodeURIComponent(permissionCode)}`, { resolution }),
  profiles: () => api<IamProfile[]>('/api/iam/profiles'),
  permissions: () => api<string[]>('/api/iam/permissions'),
}

export const iamKeys = {
  users: (page: number) => ['iam', 'users', page] as const,
  user: (id: string) => ['iam', 'users', id] as const,
  exceptions: (id: string) => ['iam', 'users', id, 'exceptions'] as const,
  profiles: ['iam', 'profiles'] as const,
  permissions: ['iam', 'permissions'] as const,
}
