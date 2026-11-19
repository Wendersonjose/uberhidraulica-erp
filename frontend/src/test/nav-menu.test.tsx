import { screen, within } from '@testing-library/react'
import { expect, test } from 'vitest'
import { mockApi, page, renderAt, session } from './harness'

// Regressão (produção, 2026-11-19): os títulos de seção do menu ("Visão geral", "Financeiro", "Compras",
// "Configurações") eram <span> com o mesmo recuo dos links e 50% de opacidade, e pareciam módulos desabilitados.
const DONO = ['FINANCE_VIEW', 'PURCHASE_VIEW', 'IAM_USERS_READ']

test('o Dono vê todos os módulos como links clicáveis e os títulos de seção não parecem itens', async () => {
  mockApi({ 'GET /api/customers': page([]) }, { ...session, permissions: DONO })
  renderAt('/clientes')

  const nav = await screen.findByRole('navigation', { name: 'Principal' })
  const expected: [string, string][] = [
    ['Clientes', '/clientes'], ['Estoque', '/estoque'],
    ['Resumo financeiro', '/financeiro/dashboard'], ['Contas a pagar', '/financeiro/contas-a-pagar'],
    ['Configurações financeiras', '/financeiro/configuracoes'],
    ['Fornecedores', '/compras/fornecedores'], ['Compras e NF-e', '/compras/notas'],
    ['Configurações de Compras', '/compras/configuracoes'], ['Usuários', '/configuracoes/usuarios'],
  ]
  for (const [name, href] of expected) expect(within(nav).getByRole('link', { name })).toHaveAttribute('href', href)

  for (const title of ['Oficina', 'Financeiro', 'Compras', 'Configurações']) {
    const element = within(nav).getByText(title, { selector: '.nav-section' })
    expect(element.tagName).not.toBe('A')
    expect(element.closest('a')).toBeNull()
  }
  expect(within(nav).queryByText('Visão geral')).not.toBeInTheDocument()
  expect(nav.querySelectorAll('span')).toHaveLength(0)
})

test('sem FINANCE_VIEW e PURCHASE_VIEW as seções inteiras somem, em vez de aparecer bloqueadas', async () => {
  mockApi({ 'GET /api/customers': page([]) }, { ...session, permissions: [] })
  renderAt('/clientes')

  const nav = await screen.findByRole('navigation', { name: 'Principal' })
  expect(within(nav).queryByText('Financeiro')).not.toBeInTheDocument()
  expect(within(nav).queryByText('Compras')).not.toBeInTheDocument()
  expect(within(nav).queryByRole('link', { name: 'Contas a pagar' })).not.toBeInTheDocument()
})
