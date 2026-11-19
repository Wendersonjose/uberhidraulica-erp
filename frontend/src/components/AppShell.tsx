import { LogoutButton } from '../auth/LogoutButton'
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'

type Item = readonly [label: string, to: string]
type Section = {
  title: string
  /** Página que o título abre: sempre uma rota que já existe. */
  home: string
  /** Prefixos de rota que pertencem à seção, para o estado ativo do título. */
  prefixes: readonly string[]
  /** Permissão exigida para a seção aparecer (título e submenu); sem ela, a seção inteira some. */
  permission?: string
  items: readonly Item[]
}

const sections: readonly Section[] = [
  {
    // "/" já redireciona para Ordens de serviço: é o painel principal da oficina.
    title: 'Oficina', home: '/ordens-servico',
    prefixes: ['/clientes', '/veiculos', '/ordens-servico', '/servicos', '/produtos', '/estoque'],
    items: [['Clientes', '/clientes'], ['Veículos', '/veiculos'], ['Ordens de serviço', '/ordens-servico'],
      ['Serviços', '/servicos'], ['Produtos', '/produtos'], ['Estoque', '/estoque']],
  },
  {
    title: 'Financeiro', home: '/financeiro/dashboard', prefixes: ['/financeiro'], permission: 'FINANCE_VIEW',
    items: [['Resumo financeiro', '/financeiro/dashboard'], ['Caixa físico', '/financeiro/caixa'],
      ['Contas a receber', '/financeiro/recebiveis'], ['Contas a pagar', '/financeiro/contas-a-pagar'],
      ['Fluxo de caixa', '/financeiro/fluxo-de-caixa'], ['Configurações financeiras', '/financeiro/configuracoes']],
  },
  {
    title: 'Compras', home: '/compras/notas', prefixes: ['/compras'], permission: 'PURCHASE_VIEW',
    items: [['Fornecedores', '/compras/fornecedores'], ['Compras e NF-e', '/compras/notas'],
      ['Configurações de Compras', '/compras/configuracoes']],
  },
  {
    // Não há página central de configurações: o título abre Usuários, a única página desta seção.
    title: 'Configurações', home: '/configuracoes/usuarios', prefixes: ['/configuracoes'], permission: 'IAM_USERS_READ',
    items: [['Usuários', '/configuracoes/usuarios']],
  },
]

const inSection = (pathname: string, prefixes: readonly string[]) =>
  prefixes.some(prefix => pathname === prefix || pathname.startsWith(`${prefix}/`))

export function AppShell() {
  const { session } = useAuth()
  const { pathname } = useLocation()
  const permissions = session?.permissions ?? []
  return <div className="app-layout">
    <aside className="sidebar">
      <div className="brand">UBERHIDRÁULICA<small>Gestão de Oficina</small></div>
      <nav className="nav" aria-label="Principal">
        {sections.filter(s => !s.permission || permissions.includes(s.permission)).map(section => {
          const active = inSection(pathname, section.prefixes)
          return <div key={section.title} className="nav-group" role="group" aria-labelledby={`nav-${section.home}`}>
            <Link id={`nav-${section.home}`} to={section.home} className={active ? 'nav-section active' : 'nav-section'}
              aria-current={active ? 'true' : undefined}>{section.title}</Link>
            {section.items.map(([label, to]) => <NavLink key={to} to={to}>{label}</NavLink>)}
          </div>
        })}
      </nav>
      <LogoutButton className="logout">Sair · {session?.name}</LogoutButton>
    </aside>
    <main className="content"><Outlet /></main>
  </div>
}
