import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { mockApi, page, renderAt, session } from './harness'

// Os títulos de seção do menu são links: cada um abre uma página que já existe, sem rota nova.
const DONO = ['FINANCE_VIEW', 'PURCHASE_VIEW', 'IAM_USERS_READ']
const asDono = () => ({ ...session, permissions: DONO })
const nav = () => screen.findByRole('navigation', { name: 'Principal' })

test.each([
  ['Oficina', '/ordens-servico', 'Ordens de serviço'],
  ['Financeiro', '/financeiro/dashboard', 'Resumo financeiro'],
  ['Compras', '/compras/notas', 'Compras'],
  ['Configurações', '/configuracoes/usuarios', 'Usuários'],
])('o título %s é um link para %s e o clique abre a página', async (title, href, heading) => {
  mockApi({ 'GET /api/customers': page([]) }, asDono())
  renderAt('/clientes')

  const link = within(await nav()).getByRole('link', { name: title })
  expect(link).toHaveAttribute('href', href)
  await userEvent.click(link)
  expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeInTheDocument()
})

test('pelo teclado: Tab chega ao título e Enter abre a página', async () => {
  mockApi({ 'GET /api/customers': page([]) }, asDono())
  renderAt('/clientes')
  const finance = within(await nav()).getByRole('link', { name: 'Financeiro' })

  for (let i = 0; i < 40 && document.activeElement !== finance; i++) await userEvent.tab()
  expect(finance).toHaveFocus()
  await userEvent.keyboard('{Enter}')
  expect(await screen.findByRole('heading', { level: 1, name: 'Resumo financeiro' })).toBeInTheDocument()
})

test('o título da seção atual fica ativo e o item da página atual continua marcado', async () => {
  mockApi({}, asDono())
  renderAt('/financeiro/contas-a-pagar')
  const menu = await nav()

  expect(within(menu).getByRole('link', { name: 'Financeiro' })).toHaveAttribute('aria-current', 'true')
  expect(within(menu).getByRole('link', { name: 'Contas a pagar' })).toHaveAttribute('aria-current', 'page')
  for (const other of ['Oficina', 'Compras', 'Configurações'])
    expect(within(menu).getByRole('link', { name: other })).not.toHaveAttribute('aria-current')
  // Os submenus continuam no grupo da seção.
  const group = within(menu).getByRole('group', { name: 'Financeiro' })
  expect(within(group).getAllByRole('link')).toHaveLength(7)
})

test('subpáginas ativam o título da seção certa (Compras), não o de outra que comece igual', async () => {
  mockApi({}, asDono())
  renderAt('/compras/fornecedores')
  const menu = await nav()
  expect(within(menu).getByRole('link', { name: 'Compras' })).toHaveAttribute('aria-current', 'true')
  expect(within(menu).getByRole('link', { name: 'Oficina' })).not.toHaveAttribute('aria-current')
})

test('sem FINANCE_VIEW, PURCHASE_VIEW e IAM_USERS_READ as seções somem com título e submenu; Oficina continua', async () => {
  mockApi({ 'GET /api/customers': page([]) }, { ...session, permissions: [] })
  renderAt('/clientes')
  const menu = await nav()

  expect(within(menu).getByRole('link', { name: 'Oficina' })).toHaveAttribute('href', '/ordens-servico')
  for (const hidden of ['Financeiro', 'Compras', 'Configurações', 'Contas a pagar', 'Compras e NF-e', 'Usuários'])
    expect(within(menu).queryByRole('link', { name: hidden })).not.toBeInTheDocument()
})
