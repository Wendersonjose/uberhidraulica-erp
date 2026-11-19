import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ACCOUNT_TYPE_LABELS, FINANCE_CONFIG, financeApi, financeKeys, type FinanceSettings, type FinancialAccountType, type PaymentMethod,
  type PaymentMethodFee,
} from '../../api/finance'
import { Badge, PageHeader } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { formatBrl } from '../../utils/format'
import { useCan } from './hooks'

type CatalogKind = 'methods' | 'categories' | 'revenue'

/** Formas de pagamento, categorias, prazos, juros e multa, aprovação, taxas e contas. Nada é excluído: é inativado. */
export function FinanceSettingsPage() {
  const canConfigure = useCan(FINANCE_CONFIG)
  return <>
    <PageHeader title="Configurações financeiras" subtitle="Formas e categorias usadas não são excluídas; o histórico guarda o nome do lançamento." />
    <div className="detail-grid">
      <div className="stack">
        <CatalogCard title="Formas de pagamento" kind="methods" canConfigure={canConfigure} />
        <FeesCard canConfigure={canConfigure} />
        <CatalogCard title="Categorias de despesa" kind="categories" canConfigure={canConfigure} />
        <CatalogCard title="Categorias de receita" kind="revenue" canConfigure={canConfigure} />
        <AccountsCard canConfigure={canConfigure} />
      </div>
      <aside className="stack"><SettingsCard canConfigure={canConfigure} /></aside>
    </div>
  </>
}

type CatalogApi = {
  key: readonly string[]
  list: () => Promise<{ id: string; name: string; active: boolean }[]>
  create: (name: string, cash: boolean) => Promise<unknown>
  update: (id: string, name: string, active: boolean) => Promise<unknown>
}

function catalog(kind: CatalogKind): CatalogApi {
  if (kind === 'methods') return { key: financeKeys.methods, list: financeApi.paymentMethods, create: (name: string, cash: boolean) => financeApi.createPaymentMethod(name, cash),
    update: financeApi.updatePaymentMethod }
  if (kind === 'categories') return { key: financeKeys.categories, list: financeApi.categories, create: (name: string) => financeApi.createCategory(name),
    update: financeApi.updateCategory }
  return { key: financeKeys.revenueCategories, list: financeApi.revenueCategories, create: (name: string) => financeApi.createRevenueCategory(name),
    update: financeApi.updateRevenueCategory }
}

function CatalogCard({ title, kind, canConfigure }: { title: string; kind: CatalogKind; canConfigure: boolean }) {
  const client = useQueryClient()
  const api = catalog(kind)
  const list = useQuery({ queryKey: api.key, queryFn: () => api.list() })
  const [name, setName] = useState('')
  const [cash, setCash] = useState<'' | 'yes' | 'no'>('')
  const [error, setError] = useState('')
  const refresh = () => client.invalidateQueries({ queryKey: api.key })
  const create = useMutation({
    mutationFn: () => api.create(name.trim(), cash === 'yes'),
    onSuccess: async () => { setName(''); setCash(''); setError(''); await refresh() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível cadastrar'),
  })
  const toggle = useMutation({
    mutationFn: (item: { id: string; name: string; active: boolean }) => api.update(item.id, item.name, !item.active),
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
        {kind === 'methods' && (item as PaymentMethod).cashSessionRequired && <span className="muted"> · exige sessão de caixa / movimenta dinheiro físico</span>}
        {canConfigure && <button type="button" className="btn secondary" onClick={() => toggle.mutate(item)}>{item.active ? 'Inativar' : 'Reativar'}</button>}
      </li>)}</ul>
    </QueryState>
    {canConfigure && <div className="inline-form">
      <div className="field"><label>Nome<input className="input" value={name} onChange={e => setName(e.target.value)} /></label></div>
      {kind === 'methods' && <fieldset className="field" aria-label="Natureza da forma">
        <legend>Exige sessão de caixa / movimenta dinheiro físico? *</legend>
        <label><input type="radio" name="cash-nature" checked={cash === 'yes'} onChange={() => setCash('yes')} /> Sim — fica indisponível até existir sessão de caixa</label>
        <label><input type="radio" name="cash-nature" checked={cash === 'no'} onChange={() => setCash('no')} /> Não — forma eletrônica ou sem dinheiro físico</label>
        <span className="muted">Definido agora e não pode ser alterado depois.</span>
      </fieldset>}
      <button type="button" className="btn" disabled={name.trim().length < 2 || (kind === 'methods' && cash === '') || create.isPending}
              onClick={() => create.mutate()}>Cadastrar</button>
    </div>}
  </section>
}

/** Taxa por número de parcelas e prazo de repasse (DR-0024, Q4). Sem integração com adquirente. */
function FeesCard({ canConfigure }: { canConfigure: boolean }) {
  const methods = useQuery({ queryKey: financeKeys.methods, queryFn: financeApi.paymentMethods })
  const eligible = methods.data?.filter(m => !m.cashSessionRequired) ?? []
  const [methodId, setMethodId] = useState('')
  return <section className="card" aria-label="Taxas de cartão">
    <h2>Taxas e parcelamento</h2>
    <p className="muted">Taxa sobre o valor bruto e prazo de repasse, por número de parcelas. O recebimento guarda a taxa do dia: mudar a tabela não reescreve o que já foi recebido.</p>
    <div className="field"><label>Forma de pagamento<select className="select" value={methodId} onChange={e => setMethodId(e.target.value)}>
      <option value="">Selecione</option>
      {eligible.map(m => <option key={m.id} value={m.id}>{m.name}</option>)}
    </select></label></div>
    {methodId && <FeesEditor key={methodId} methodId={methodId} canConfigure={canConfigure} />}
  </section>
}

function FeesEditor({ methodId, canConfigure }: { methodId: string; canConfigure: boolean }) {
  const client = useQueryClient()
  const fees = useQuery({ queryKey: financeKeys.fees(methodId), queryFn: () => financeApi.fees(methodId) })
  const [rows, setRows] = useState<{ installments: string; feePercent: string; settlementDays: string }[] | null>(null)
  const [error, setError] = useState('')
  const current = rows ?? (fees.data ?? []).map(f => ({ installments: String(f.installments), feePercent: String(f.feePercent), settlementDays: String(f.settlementDays) }))
  const save = useMutation({
    mutationFn: () => financeApi.replaceFees(methodId, current.map<PaymentMethodFee>(r => ({ installments: Number(r.installments),
      feePercent: Number(r.feePercent), settlementDays: Number(r.settlementDays) }))),
    onSuccess: async () => { setRows(null); setError(''); await client.invalidateQueries({ queryKey: financeKeys.fees(methodId) }) },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível salvar as taxas'),
  })
  const update = (index: number, patch: Partial<(typeof current)[number]>) => setRows(current.map((r, i) => (i === index ? { ...r, ...patch } : r)))
  const valid = current.every(r => /^\d{1,2}$/.test(r.installments) && Number(r.installments) >= 1 && Number(r.installments) <= 24
    && /^\d{1,2}(\.\d{1,2})?$/.test(r.feePercent) && /^\d{1,3}$/.test(r.settlementDays))
    && new Set(current.map(r => r.installments)).size === current.length
  return <QueryState label="as taxas" loading={fees.isLoading} error={fees.error} retry={fees.refetch}>
    {error && <div role="alert" className="notice error">{error}</div>}
    {!current.length && <p className="muted">Sem taxa: o recebimento é à vista e pelo valor bruto.</p>}
    {current.length > 0 && <div className="table-wrap"><table className="table">
      <thead><tr><th>Parcelas</th><th>Taxa (%)</th><th>Repasse (dias)</th><th /></tr></thead>
      <tbody>{current.map((r, i) => <tr key={i}>
        <td><input className="input" aria-label={`Parcelas da linha ${i + 1}`} inputMode="numeric" value={r.installments} disabled={!canConfigure}
                   onChange={e => update(i, { installments: e.target.value.replace(/\D/g, '') })} /></td>
        <td><input className="input" aria-label={`Taxa da linha ${i + 1}`} inputMode="decimal" value={r.feePercent} disabled={!canConfigure}
                   onChange={e => update(i, { feePercent: e.target.value.replace(',', '.') })} /></td>
        <td><input className="input" aria-label={`Repasse da linha ${i + 1}`} inputMode="numeric" value={r.settlementDays} disabled={!canConfigure}
                   onChange={e => update(i, { settlementDays: e.target.value.replace(/\D/g, '') })} /></td>
        <td>{canConfigure && <button type="button" className="btn secondary" onClick={() => setRows(current.filter((_, j) => j !== i))}>Remover</button>}</td>
      </tr>)}</tbody>
    </table></div>}
    {canConfigure && <div className="actions">
      <button type="button" className="btn secondary" disabled={current.length >= 24}
              onClick={() => setRows([...current, { installments: String(current.length + 1), feePercent: '0', settlementDays: '30' }])}>Adicionar linha</button>
      <button type="button" className="btn" disabled={!valid || rows === null || save.isPending} onClick={() => save.mutate()}>Salvar taxas</button>
    </div>}
  </QueryState>
}

function AccountsCard({ canConfigure }: { canConfigure: boolean }) {
  const client = useQueryClient()
  const accounts = useQuery({ queryKey: financeKeys.accounts, queryFn: financeApi.accounts })
  const [form, setForm] = useState({ name: '', type: 'BANK' as FinancialAccountType, bankName: '', branch: '', accountNumber: '' })
  const [error, setError] = useState('')
  const refresh = () => client.invalidateQueries({ queryKey: financeKeys.accounts })
  const create = useMutation({
    mutationFn: () => financeApi.createAccount({ name: form.name.trim(), type: form.type, bankName: form.bankName.trim() || null,
      branch: form.branch.trim() || null, accountNumber: form.accountNumber.trim() || null }),
    onSuccess: async () => { setForm({ ...form, name: '', bankName: '', branch: '', accountNumber: '' }); setError(''); await refresh() },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível cadastrar a conta'),
  })
  const toggle = useMutation({
    mutationFn: (a: { id: string; name: string; bankName: string | null; branch: string | null; accountNumber: string | null; active: boolean }) =>
      financeApi.updateAccount(a.id, { name: a.name, bankName: a.bankName, branch: a.branch, accountNumber: a.accountNumber, active: !a.active }),
    onSuccess: refresh,
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível alterar a conta'),
  })
  return <section className="card" aria-label="Contas financeiras">
    <h2>Contas financeiras</h2>
    <p className="muted">Caixa, banco e cartão usados na conciliação. Não guarde senha nem dados de acesso ao banco.</p>
    {error && <div role="alert" className="notice error">{error}</div>}
    <QueryState label="as contas" loading={accounts.isLoading} error={accounts.error} retry={accounts.refetch}
                empty={!accounts.data?.length} emptyMessage="Nenhuma conta cadastrada.">
      <ul className="history">{accounts.data?.map(a => <li key={a.id}>
        {a.name} <span className="muted">· {ACCOUNT_TYPE_LABELS[a.type]}{a.bankName ? ` · ${a.bankName}` : ''}{a.accountNumber ? ` ${a.accountNumber}` : ''}</span>
        {' '}<Badge tone={a.active ? 'success' : 'warning'}>{a.active ? 'Ativa' : 'Inativa'}</Badge>
        {canConfigure && <button type="button" className="btn secondary" onClick={() => toggle.mutate(a)}>{a.active ? 'Inativar' : 'Reativar'}</button>}
      </li>)}</ul>
    </QueryState>
    {canConfigure && <div className="inline-form">
      <div className="field"><label>Nome da conta<input className="input" value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label></div>
      <div className="field"><label>Tipo<select className="select" value={form.type} onChange={e => setForm({ ...form, type: e.target.value as FinancialAccountType })}>
        {Object.entries(ACCOUNT_TYPE_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select></label></div>
      {form.type === 'BANK' && <>
        <div className="field"><label>Banco<input className="input" value={form.bankName} onChange={e => setForm({ ...form, bankName: e.target.value })} /></label></div>
        <div className="field"><label>Agência<input className="input" value={form.branch} onChange={e => setForm({ ...form, branch: e.target.value })} /></label></div>
        <div className="field"><label>Conta<input className="input" value={form.accountNumber} onChange={e => setForm({ ...form, accountNumber: e.target.value })} /></label></div>
      </>}
      <button type="button" className="btn" disabled={form.name.trim().length < 2 || create.isPending} onClick={() => create.mutate()}>Cadastrar conta</button>
    </div>}
  </section>
}

/** Prazo padrão, juros e multa sugeridos (Q3) e limite de aprovação de contas a pagar (Q5). */
function SettingsCard({ canConfigure }: { canConfigure: boolean }) {
  const client = useQueryClient()
  const settings = useQuery({ queryKey: financeKeys.settings, queryFn: financeApi.settings })
  const [draft, setDraft] = useState<Partial<Record<keyof FinanceSettings, string>>>({})
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  const value = (key: keyof FinanceSettings) => draft[key] ?? String(settings.data?.[key] ?? '')
  const set = (key: keyof FinanceSettings, text: string) => { setSaved(false); setDraft({ ...draft, [key]: text.replace(',', '.') }) }
  const save = useMutation({
    mutationFn: () => financeApi.changeSettings({ defaultReceivableDueDays: Number(value('defaultReceivableDueDays')),
      lateFeePercent: Number(value('lateFeePercent')), lateInterestMonthlyPercent: Number(value('lateInterestMonthlyPercent')),
      payableApprovalThreshold: Number(value('payableApprovalThreshold')) }),
    onSuccess: async () => { setDraft({}); setError(''); setSaved(true); await client.invalidateQueries({ queryKey: financeKeys.settings }) },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível salvar'),
  })
  const decimal = /^\d{1,9}(\.\d{1,2})?$/
  const valid = /^\d{1,3}$/.test(value('defaultReceivableDueDays')) && Number(value('defaultReceivableDueDays')) <= 365
    && decimal.test(value('lateFeePercent')) && Number(value('lateFeePercent')) <= 20
    && decimal.test(value('lateInterestMonthlyPercent')) && Number(value('lateInterestMonthlyPercent')) <= 10
    && decimal.test(value('payableApprovalThreshold'))
  const threshold = Number(value('payableApprovalThreshold'))
  return <QueryState label="as configurações" loading={settings.isLoading} error={settings.error} retry={settings.refetch}>
    <section className="card" aria-label="Prazos, juros e aprovação">
      <h2>Prazos, juros e aprovação</h2>
      {error && <div role="alert" className="notice error">{error}</div>}
      {saved && <div role="status" className="notice">Configurações salvas.</div>}
      <div className="field"><label>Prazo padrão do recebível (dias)<input className="input" inputMode="numeric" value={value('defaultReceivableDueDays')}
        disabled={!canConfigure} onChange={e => set('defaultReceivableDueDays', e.target.value.replace(/\D/g, ''))} /></label>
        <span className="muted">Somado à data de finalização da OS. Zero significa à vista.</span></div>
      <div className="field"><label>Multa por atraso (%)<input className="input" inputMode="decimal" value={value('lateFeePercent')}
        disabled={!canConfigure} onChange={e => set('lateFeePercent', e.target.value)} /></label></div>
      <div className="field"><label>Juros por atraso (% ao mês)<input className="input" inputMode="decimal" value={value('lateInterestMonthlyPercent')}
        disabled={!canConfigure} onChange={e => set('lateInterestMonthlyPercent', e.target.value)} /></label>
        <span className="muted">Sugeridos no recebimento; o operador aplica ou dispensa com motivo.</span></div>
      <div className="field"><label>Aprovar contas a pagar acima de (R$)<input className="input" inputMode="decimal" value={value('payableApprovalThreshold')}
        disabled={!canConfigure} onChange={e => set('payableApprovalThreshold', e.target.value)} /></label>
        <span className="muted">{threshold > 0 ? `Contas acima de ${formatBrl(threshold)} precisam de aprovação de outro usuário.` : 'Zero desliga a aprovação.'}</span></div>
      {canConfigure && <button type="button" className="btn" disabled={!valid || save.isPending} onClick={() => save.mutate()}>Salvar configurações</button>}
    </section>
  </QueryState>
}
