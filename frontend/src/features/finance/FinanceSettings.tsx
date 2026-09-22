import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FINANCE_CONFIG, financeApi, financeKeys, type PaymentMethod } from '../../api/finance'
import { Badge, PageHeader } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { useCan } from './hooks'

/** Formas de pagamento, categorias de despesa e prazo padrão. Nada é excluído: é inativado. */
export function FinanceSettingsPage() {
  const canConfigure = useCan(FINANCE_CONFIG)
  return <>
    <PageHeader title="Configurações financeiras" subtitle="Formas e categorias usadas não são excluídas; o histórico guarda o nome do lançamento." />
    <div className="detail-grid">
      <div className="stack">
        <CatalogCard title="Formas de pagamento" kind="methods" canConfigure={canConfigure} />
        <CatalogCard title="Categorias de despesa" kind="categories" canConfigure={canConfigure} />
      </div>
      <aside className="stack"><DueDaysCard canConfigure={canConfigure} /></aside>
    </div>
  </>
}

function CatalogCard({ title, kind, canConfigure }: { title: string; kind: 'methods' | 'categories'; canConfigure: boolean }) {
  const client = useQueryClient()
  const key = kind === 'methods' ? financeKeys.methods : financeKeys.categories
  const list = useQuery({ queryKey: key, queryFn: () => (kind === 'methods' ? financeApi.paymentMethods() : financeApi.categories()) })
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  const refresh = () => client.invalidateQueries({ queryKey: key })
  const create = useMutation({
    mutationFn: () => (kind === 'methods' ? financeApi.createPaymentMethod(name.trim()) : financeApi.createCategory(name.trim())),
    onSuccess: async () => { setName(''); setError(''); await refresh() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível cadastrar'),
  })
  const toggle = useMutation({
    mutationFn: (item: { id: string; name: string; active: boolean }) => (kind === 'methods'
      ? financeApi.updatePaymentMethod(item.id, item.name, !item.active) : financeApi.updateCategory(item.id, item.name, !item.active)),
    onSuccess: refresh,
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível alterar'),
  })
  return <section className="card" aria-label={title}>
    <h2>{title}</h2>
    {error && <div role="alert" className="notice error">{error}</div>}
    <QueryState label={title.toLowerCase()} loading={list.isLoading} error={list.error} retry={list.refetch}
                empty={!list.data?.length} emptyMessage="Nenhum cadastro.">
      <ul className="history">{list.data?.map(item => <li key={item.id}>
        {item.name} <Badge tone={item.active ? 'success' : 'warning'}>{item.active ? 'Ativo' : 'Inativo'}</Badge>
        {kind === 'methods' && (item as PaymentMethod).cashSessionRequired && <span className="muted"> · exige sessão de caixa</span>}
        {canConfigure && <button type="button" className="btn secondary" onClick={() => toggle.mutate(item)}>{item.active ? 'Inativar' : 'Reativar'}</button>}
      </li>)}</ul>
    </QueryState>
    {canConfigure && <div className="inline-form">
      <div className="field"><label>Nome<input className="input" value={name} onChange={e => setName(e.target.value)} /></label></div>
      <button type="button" className="btn" disabled={name.trim().length < 2 || create.isPending} onClick={() => create.mutate()}>Cadastrar</button>
    </div>}
  </section>
}

function DueDaysCard({ canConfigure }: { canConfigure: boolean }) {
  const client = useQueryClient()
  const settings = useQuery({ queryKey: financeKeys.settings, queryFn: financeApi.settings })
  const [days, setDays] = useState<string | null>(null)
  const [error, setError] = useState('')
  const current = days ?? String(settings.data?.defaultReceivableDueDays ?? '')
  const save = useMutation({
    mutationFn: () => financeApi.changeSettings(Number(current)),
    onSuccess: async () => { setDays(null); setError(''); await client.invalidateQueries({ queryKey: financeKeys.settings }) },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível salvar'),
  })
  const valid = /^\d{1,3}$/.test(current) && Number(current) <= 365
  return <section className="card" aria-label="Prazo padrão do recebível">
    <h2>Prazo padrão do recebível</h2>
    <p className="muted">Dias somados à data de finalização da OS para o vencimento. Zero significa à vista.</p>
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="field"><label>Dias<input className="input" inputMode="numeric" value={current} disabled={!canConfigure}
                                             onChange={e => setDays(e.target.value.replace(/\D/g, ''))} /></label></div>
    {canConfigure && <button type="button" className="btn" disabled={!valid || save.isPending} onClick={() => save.mutate()}>Salvar prazo</button>}
  </section>
}
