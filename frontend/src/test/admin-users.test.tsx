import { cleanup, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { json, lastBody, mockApi, renderAt, requestsTo, session } from './harness'

const admin = {
  ...session,
  permissions: ['IAM_USERS_READ', 'IAM_USERS_MANAGE', 'IAM_PASSWORD_RESET', 'IAM_USER_EXCEPTIONS_MANAGE', 'IAM_CATALOG_READ'],
}

const user1 = { id: 'u1', name: 'Ana Souza', email: 'ana@uberhidraulica.com', profileCode: 'GERENTE_ADMINISTRATIVO', state: 'ACTIVE', mustChangePassword: true }
const dono = { id: 'u2', name: 'Carlos Dono', email: 'carlos@uberhidraulica.com', profileCode: 'DONO', state: 'ACTIVE', mustChangePassword: false }
const userPage = (items: object[] = [user1, dono]) => ({ content: items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0 })

const profiles = [
  { code: 'DONO', permissions: ['IAM_USERS_READ', 'IAM_USERS_MANAGE', 'FINANCE_VIEW'] },
  { code: 'GERENTE_ADMINISTRATIVO', permissions: ['FINANCE_VIEW'] },
  { code: 'GERENTE_FINANCEIRO', permissions: ['FINANCE_VIEW', 'FINANCE_RECEIVE'] },
]
const permissionCatalog = ['FINANCE_VIEW', 'FINANCE_RECEIVE', 'IAM_USERS_READ', 'IAM_USERS_MANAGE']

/** Rotas do catálogo de exceções que toda visita ao detalhe do usuário dispara. */
const catalogRoutes = {
  'GET /api/iam/permissions': () => json(permissionCatalog),
  'GET /api/iam/profiles': () => json(profiles),
}

test('lista usuários com perfil e situação em português', async () => {
  mockApi({ 'GET /api/iam/users': () => json(userPage()) }, admin)
  renderAt('/configuracoes/usuarios')

  const row = (await screen.findByText('Ana Souza')).closest('tr')!
  expect(row).toHaveTextContent('ana@uberhidraulica.com')
  expect(row).toHaveTextContent('Gerente administrativo')
  expect(row).toHaveTextContent('Ativo')

  const donoRow = screen.getByText('Carlos Dono').closest('tr')!
  expect(donoRow).toHaveTextContent('Dono')
})

test('cadastro exibe a senha temporária uma única vez e ela não sobrevive a um remonte da tela', async () => {
  const fetch = mockApi({
    'GET /api/iam/users': () => json(userPage([])),
    'POST /api/iam/users': () => json({ user: user1, temporaryPassword: 'Temp#1234' }, 201),
  }, admin)
  renderAt('/configuracoes/usuarios/novo')

  await userEvent.type(await screen.findByLabelText('Nome *'), 'Ana Souza')
  await userEvent.type(screen.getByLabelText('E-mail *'), 'ana@uberhidraulica.com')
  await userEvent.selectOptions(screen.getByLabelText('Perfil *'), 'GERENTE_ADMINISTRATIVO')
  await userEvent.click(screen.getByRole('button', { name: 'Cadastrar usuário' }))

  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/iam/users', expect.objectContaining({ method: 'POST' })))
  expect(lastBody(fetch, 'POST', '/api/iam/users')).toEqual({ name: 'Ana Souza', email: 'ana@uberhidraulica.com', profileCode: 'GERENTE_ADMINISTRATIVO' })
  expect(await screen.findByText('Temp#1234')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Ir para o usuário' })).toHaveAttribute('href', '/configuracoes/usuarios/u1')

  // Sem persistência além do componente: ao remontar a tela de cadastro, a senha não aparece em lugar nenhum.
  cleanup()
  renderAt('/configuracoes/usuarios/novo')
  await screen.findByLabelText('Nome *')
  expect(screen.queryByText('Temp#1234')).not.toBeInTheDocument()
})

test('inativação e reativação chamam PATCH com o novo estado', async () => {
  let current: typeof user1 = user1
  const fetch = mockApi({
    'GET /api/iam/users/u1': () => json(current),
    'PATCH /api/iam/users/u1': (init?: RequestInit) => { current = { ...current, state: JSON.parse(String(init?.body)).state }; return json(current) },
    ...catalogRoutes,
    'GET /api/iam/users/u1/permission-exceptions': () => json({}),
  }, admin)
  renderAt('/configuracoes/usuarios/u1')

  await userEvent.click(await screen.findByRole('button', { name: 'Inativar' }))
  await userEvent.click(screen.getAllByRole('button', { name: 'Inativar' }).at(-1)!)
  await waitFor(() => expect(lastBody(fetch, 'PATCH', '/api/iam/users/u1')).toEqual({ state: 'INACTIVE' }))
  expect(await screen.findByRole('button', { name: 'Reativar' })).toBeInTheDocument()

  await userEvent.click(screen.getByRole('button', { name: 'Reativar' }))
  await userEvent.click(screen.getAllByRole('button', { name: 'Reativar' }).at(-1)!)
  await waitFor(() => expect(lastBody(fetch, 'PATCH', '/api/iam/users/u1')).toEqual({ state: 'ACTIVE' }))
})

test('erro do backend ao inativar o último Dono é exibido, não tratado como sucesso', async () => {
  const fetch = mockApi({
    'GET /api/iam/users/u2': () => json(dono),
    'PATCH /api/iam/users/u2': () => json({ code: 'CANNOT_INACTIVATE_LAST_OWNER', message: 'Não é possível inativar o último Dono ativo' }, 409),
    ...catalogRoutes,
    'GET /api/iam/users/u2/permission-exceptions': () => json({}),
  }, admin)
  renderAt('/configuracoes/usuarios/u2')

  await userEvent.click(await screen.findByRole('button', { name: 'Inativar' }))
  await userEvent.click(screen.getAllByRole('button', { name: 'Inativar' }).at(-1)!)
  expect(await screen.findByRole('alert')).toHaveTextContent('Não é possível inativar o último Dono ativo')
  expect(requestsTo(fetch, 'PATCH', '/api/iam/users/u2')).toHaveLength(1)
  expect(screen.getByText('Ativo', { selector: '.badge' })).toBeInTheDocument()
})

test('redefinição de senha mostra a nova senha temporária em destaque', async () => {
  const fetch = mockApi({
    'GET /api/iam/users/u1': () => json(user1),
    'POST /api/iam/users/u1/password-reset': () => json({ temporaryPassword: 'Nova#5678' }),
    ...catalogRoutes,
    'GET /api/iam/users/u1/permission-exceptions': () => json({}),
  }, admin)
  renderAt('/configuracoes/usuarios/u1')

  await userEvent.click(await screen.findByRole('button', { name: 'Redefinir senha' }))
  await userEvent.click(screen.getAllByRole('button', { name: 'Redefinir senha' }).at(-1)!)
  expect(await screen.findByText('Nova#5678')).toBeInTheDocument()
  expect(requestsTo(fetch, 'POST', '/api/iam/users/u1/password-reset')).toHaveLength(1)
})

test('exceção de permissão muda de herdar para negar e envia o PUT correto', async () => {
  const fetch = mockApi({
    'GET /api/iam/users/u1': () => json(user1),
    ...catalogRoutes,
    'GET /api/iam/users/u1/permission-exceptions': () => json({}),
    'PUT /api/iam/users/u1/permission-exceptions/FINANCE_RECEIVE': () => json(null, 204),
  }, admin)
  renderAt('/configuracoes/usuarios/u1')

  const select = await screen.findByLabelText('Exceção para FINANCE_RECEIVE')
  expect(select.closest('tr')).toHaveTextContent('Não concedida pelo perfil')
  expect(select).toHaveValue('INHERIT')
  await userEvent.selectOptions(select, 'DENY')

  await waitFor(() => expect(lastBody(fetch, 'PUT', '/api/iam/users/u1/permission-exceptions/FINANCE_RECEIVE')).toEqual({ resolution: 'DENY' }))
})
