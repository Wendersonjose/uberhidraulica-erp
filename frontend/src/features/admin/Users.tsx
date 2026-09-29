import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'
import {
  PROFILE_LABELS, USER_STATE_LABELS, iamApi, iamKeys, type IamUser, type PermissionResolution,
} from '../../api/iam'
import { Badge, Field, PageHeader } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { ConfirmAction, Pagination } from '../../components/controls'

export function UsersPage() {
  const [page, setPage] = useState(0)
  const query = useQuery({ queryKey: iamKeys.users(page), queryFn: () => iamApi.users(page) })
  return <>
    <PageHeader title="Usuários" subtitle="Funcionários com acesso ao sistema e seus perfis de permissão.">
      <Link className="btn" to="/configuracoes/usuarios/novo">Novo usuário</Link>
    </PageHeader>
    <QueryState label="os usuários" loading={query.isLoading} error={query.error} retry={query.refetch}
                empty={!query.data?.content.length} emptyMessage="Nenhum usuário cadastrado.">
      <div className="card table-wrap"><table className="table">
        <thead><tr><th>Nome</th><th>E-mail</th><th>Perfil</th><th>Situação</th><th /></tr></thead>
        <tbody>{query.data?.content.map(user => <tr key={user.id}>
          <td>{user.name}</td>
          <td>{user.email}</td>
          <td>{PROFILE_LABELS[user.profileCode]}</td>
          <td><Badge tone={user.state === 'ACTIVE' ? 'success' : 'warning'}>{USER_STATE_LABELS[user.state]}</Badge></td>
          <td><Link className="btn secondary" to={`/configuracoes/usuarios/${user.id}`} aria-label={`Ver ${user.name}`}>Ver</Link></td>
        </tr>)}</tbody>
      </table></div>
      {query.data && <Pagination page={query.data.page} totalPages={query.data.totalPages} totalItems={query.data.totalElements} onChange={setPage} />}
    </QueryState>
  </>
}

const schema = z.object({
  name: z.string().trim().min(2, 'Informe o nome'),
  email: z.email('E-mail inválido'),
  profileCode: z.enum(['DONO', 'GERENTE_ADMINISTRATIVO', 'GERENTE_FINANCEIRO']),
})
type Form = z.infer<typeof schema>

/**
 * Senha temporária de exibição única: existe só em estado local do componente, nunca em `localStorage`/
 * `sessionStorage` nem no console — some ao sair da tela ou remontar.
 */
function TemporaryPasswordCard({ password }: { password: string }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try { await navigator.clipboard?.writeText(password); setCopied(true) }
    catch { /* sem área de transferência disponível (ex.: ambiente de teste); a senha continua visível na tela */ }
  }
  return <section className="card" aria-label="Senha temporária" style={{ borderLeft: '4px solid var(--primary)' }}>
    <h2>Senha temporária</h2>
    <p className="notice error">Esta senha aparece apenas agora e não pode ser recuperada depois. Copie-a ou anote-a antes de sair desta tela.</p>
    <p style={{ fontSize: 22, fontWeight: 700, letterSpacing: 1 }}>{password}</p>
    <button type="button" className="btn secondary" onClick={() => { void copy() }}>{copied ? 'Copiado!' : 'Copiar'}</button>
  </section>
}

export function UserFormPage() {
  const client = useQueryClient()
  const [apiError, setApiError] = useState('')
  const [created, setCreated] = useState<{ user: IamUser; temporaryPassword: string } | null>(null)
  const { register, handleSubmit, formState: { errors, isSubmitting } } =
    useForm<Form>({ resolver: zodResolver(schema), defaultValues: { name: '', email: '', profileCode: 'GERENTE_ADMINISTRATIVO' } })
  const mutation = useMutation({
    mutationFn: (values: Form) => iamApi.createUser(values),
    onSuccess: async result => { await client.invalidateQueries({ queryKey: ['iam', 'users'] }); setCreated(result) },
  })

  if (created) return <>
    <PageHeader title="Usuário cadastrado" subtitle="Repasse a senha temporária ao funcionário por um canal seguro." />
    <TemporaryPasswordCard password={created.temporaryPassword} />
    <div className="form-actions">
      <Link className="btn" to={`/configuracoes/usuarios/${created.user.id}`}>Ir para o usuário</Link>
    </div>
  </>

  return <>
    <PageHeader title="Novo usuário" subtitle="O funcionário recebe uma senha temporária e deve trocá-la no primeiro acesso." />
    <form className="card" onSubmit={handleSubmit(async values => {
      setApiError('')
      try { await mutation.mutateAsync(values) }
      catch (error) { setApiError(error instanceof Error ? error.message : 'Falha ao cadastrar o usuário') }
    })}>
      {apiError && <div role="alert" className="notice error">{apiError}</div>}
      <div className="form-grid">
        <Field label="Nome *" error={errors.name?.message}><input className="input" {...register('name')} /></Field>
        <Field label="E-mail *" error={errors.email?.message}><input className="input" type="email" {...register('email')} /></Field>
        <Field label="Perfil *" error={errors.profileCode?.message}>
          <select className="select" {...register('profileCode')}>
            {Object.entries(PROFILE_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </Field>
      </div>
      <div className="form-actions">
        <Link className="btn secondary" to="/configuracoes/usuarios">Cancelar</Link>
        <button className="btn" disabled={isSubmitting || mutation.isPending}>Cadastrar usuário</button>
      </div>
    </form>
  </>
}

export function UserDetailPage() {
  const { id = '' } = useParams()
  const client = useQueryClient()
  const user = useQuery({ queryKey: iamKeys.user(id), queryFn: () => iamApi.user(id) })
  const refresh = () => client.invalidateQueries({ queryKey: iamKeys.user(id) })

  return <QueryState label="o usuário" loading={user.isLoading} error={user.error} retry={user.refetch}>
    {user.data && <UserView user={user.data} onChange={refresh} />}
  </QueryState>
}

function UserView({ user, onChange }: { user: IamUser; onChange: () => Promise<unknown> }) {
  const [resetPassword, setResetPassword] = useState<string | null>(null)

  return <>
    <PageHeader title={user.name} subtitle={user.email}>
      <Badge tone={user.state === 'ACTIVE' ? 'success' : 'warning'}>{USER_STATE_LABELS[user.state]}</Badge>
      <Link className="btn secondary" to="/configuracoes/usuarios">Voltar</Link>
    </PageHeader>
    {resetPassword && <TemporaryPasswordCard password={resetPassword} />}
    <div className="detail-grid">
      <div className="stack">
        <section className="card">
          <h2>Dados cadastrais</h2>
          <dl className="summary-list">
            <div><dt>Nome</dt><dd>{user.name}</dd></div>
            <div><dt>E-mail</dt><dd>{user.email}</dd></div>
            <div><dt>Perfil</dt><dd>{PROFILE_LABELS[user.profileCode]}</dd></div>
            <div><dt>Situação</dt><dd>{USER_STATE_LABELS[user.state]}</dd></div>
            <div><dt>Troca de senha obrigatória</dt><dd>{user.mustChangePassword ? 'Sim' : 'Não'}</dd></div>
          </dl>
        </section>
        <PermissionExceptions user={user} />
      </div>
      <aside className="stack">
        <section className="card">
          <h2>Ações</h2>
          <div className="stack">
            {user.state === 'ACTIVE'
              ? <ConfirmAction label="Inativar" question="Inativar este usuário? Ele perde o acesso ao sistema imediatamente."
                               onConfirm={async () => { await iamApi.changeState(user.id, 'INACTIVE'); await onChange() }} />
              : <ConfirmAction label="Reativar" tone="primary" question="Reativar este usuário?"
                               onConfirm={async () => { await iamApi.changeState(user.id, 'ACTIVE'); await onChange() }} />}
            <ConfirmAction label="Redefinir senha"
                           question="Gerar uma nova senha temporária para este usuário? A senha atual deixa de funcionar assim que a nova for gerada."
                           onConfirm={async () => { const result = await iamApi.resetPassword(user.id); setResetPassword(result.temporaryPassword) }} />
          </div>
        </section>
      </aside>
    </div>
  </>
}

/** Exceções por usuário, sobre o que o perfil dele já concede por padrão (`GET /api/iam/profiles`). */
function PermissionExceptions({ user }: { user: IamUser }) {
  const client = useQueryClient()
  const permissions = useQuery({ queryKey: iamKeys.permissions, queryFn: iamApi.permissions })
  const profiles = useQuery({ queryKey: iamKeys.profiles, queryFn: iamApi.profiles })
  const exceptions = useQuery({ queryKey: iamKeys.exceptions(user.id), queryFn: () => iamApi.exceptions(user.id) })
  const [error, setError] = useState('')
  const mutation = useMutation({
    mutationFn: ({ code, resolution }: { code: string; resolution: PermissionResolution }) => iamApi.setException(user.id, code, resolution),
    onSuccess: async () => { setError(''); await client.invalidateQueries({ queryKey: iamKeys.exceptions(user.id) }) },
    onError: e => setError(e instanceof Error ? e.message : 'Não foi possível salvar a exceção'),
  })
  const grantedByProfile = new Set(profiles.data?.find(p => p.code === user.profileCode)?.permissions ?? [])
  const loading = permissions.isLoading || profiles.isLoading || exceptions.isLoading
  const anyError = permissions.error ?? profiles.error ?? exceptions.error
  const retry = () => { void permissions.refetch(); void profiles.refetch(); void exceptions.refetch() }

  return <section className="card" aria-label="Exceções de permissão">
    <h2>Exceções de permissão</h2>
    <p className="muted">Permissões concedidas ou negadas individualmente para este usuário, além do que o perfil já concede por padrão.</p>
    {error && <div role="alert" className="notice error">{error}</div>}
    <QueryState label="o catálogo de permissões" loading={loading} error={anyError} retry={retry}
                empty={!permissions.data?.length} emptyMessage="Nenhuma permissão cadastrada no catálogo.">
      <div className="table-wrap"><table className="table">
        <thead><tr><th>Permissão</th><th>Concedida pelo perfil</th><th>Exceção</th></tr></thead>
        <tbody>{permissions.data?.map(code => {
          const resolution = exceptions.data?.[code] ?? 'INHERIT'
          return <tr key={code}>
            <td>{code}</td>
            <td>{grantedByProfile.has(code) ? 'Concedida pelo perfil' : 'Não concedida pelo perfil'}</td>
            <td><select className="select" aria-label={`Exceção para ${code}`} value={resolution} disabled={mutation.isPending}
                        onChange={e => mutation.mutate({ code, resolution: e.target.value as PermissionResolution })}>
              <option value="INHERIT">Herdar do perfil</option>
              <option value="ALLOW">Permitir</option>
              <option value="DENY">Negar</option>
            </select></td>
          </tr>
        })}</tbody>
      </table></div>
    </QueryState>
  </section>
}
