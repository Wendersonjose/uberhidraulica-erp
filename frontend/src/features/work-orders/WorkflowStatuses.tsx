import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { workflowApi } from '../../api/resources'
import { queryKeys } from '../../api/queryKeys'
import { STAGE_LABELS, STAGES, type Stage, type WorkflowStatus } from '../../api/types'
import { Badge, PageHeader, State } from '../../components/ui'
import { ConfirmAction } from '../../components/controls'

/** Colunas do Kanban: nome, etapa, ordem, padrão da etapa e ativação (cartão "Configuração do fluxo e status da OS"). */
export function WorkflowStatusesPage() {
  const client = useQueryClient()
  const statuses = useQuery({ queryKey: queryKeys.workflowStatuses, queryFn: workflowApi.statuses })
  const [name, setName] = useState('')
  const [stage, setStage] = useState<Stage>('EM_EXECUCAO')
  const [error, setError] = useState('')
  const refresh = () => Promise.all([
    client.invalidateQueries({ queryKey: queryKeys.workflowStatuses }), client.invalidateQueries({ queryKey: queryKeys.workOrders }),
  ])
  const guard = async (action: () => Promise<unknown>) => {
    setError('')
    try { await action(); await refresh() } catch (e) { setError(e instanceof Error ? e.message : 'Não foi possível concluir a operação') }
  }
  const create = useMutation({ mutationFn: () => workflowApi.createStatus(name.trim(), stage), onSuccess: async () => { setName(''); await refresh() } })
  const list = statuses.data ?? []

  const move = (index: number, delta: number) => {
    const ids = list.map(u => u.status.id)
    const target = index + delta
    if (target < 0 || target >= ids.length) return
    ;[ids[index], ids[target]] = [ids[target], ids[index]]
    return guard(() => workflowApi.reorder(ids))
  }

  return <>
    <PageHeader title="Status das ordens de serviço" subtitle="Cada status pertence a uma etapa. O padrão da etapa recebe as OS nas regras automáticas; status usados são inativados, nunca excluídos." />
    {error && <div role="alert" className="notice error">{error}</div>}
    <form className="card inline-form" onSubmit={async e => {
      e.preventDefault(); setError('')
      try { await create.mutateAsync() } catch (err) { setError(err instanceof Error ? err.message : 'Falha ao criar status') }
    }}>
      <div className="field"><label>Novo status<input className="input" value={name} onChange={e => setName(e.target.value)} /></label></div>
      <div className="field"><label>Etapa<select className="select" value={stage} onChange={e => setStage(e.target.value as Stage)}>
        {STAGES.map(s => <option key={s} value={s}>{STAGE_LABELS[s]}</option>)}
      </select></label></div>
      <button className="btn" disabled={name.trim().length < 2 || create.isPending}>Adicionar status</button>
    </form>
    <State loading={statuses.isLoading} error={statuses.error} empty={!list.length}>
      <div className="card table-wrap" style={{ marginTop: 16 }}>
        <table className="table">
          <thead><tr><th>Ordem</th><th>Status</th><th>Etapa</th><th>OS atuais</th><th>Situação</th><th>Ações</th></tr></thead>
          <tbody>{list.map(({ status, orderCount }, index) => <tr key={status.id}>
            <td>
              <button type="button" className="btn secondary" aria-label={`Subir ${status.name}`} disabled={index === 0} onClick={() => move(index, -1)}>↑</button>{' '}
              <button type="button" className="btn secondary" aria-label={`Descer ${status.name}`} disabled={index === list.length - 1} onClick={() => move(index, 1)}>↓</button>
            </td>
            <td><RenameField status={status} onSave={newName => guard(() => workflowApi.renameStatus(status.id, newName))} /></td>
            <td>{STAGE_LABELS[status.stage]}{status.stageDefault && <> <Badge tone="info">Padrão</Badge></>}</td>
            <td>{orderCount}</td>
            <td><Badge tone={status.active ? 'success' : 'warning'}>{status.active ? 'Ativo' : 'Inativo'}</Badge></td>
            <td className="actions">
              {!status.stageDefault && status.active &&
                <button type="button" className="btn secondary" onClick={() => guard(() => workflowApi.makeDefault(status.id))}>Tornar padrão</button>}
              {!status.stageDefault && (status.active
                ? <ConfirmAction label="Inativar" question={`Inativar ${status.name}? OS atuais permanecem nele até serem movidas.`}
                                 onConfirm={async () => { await workflowApi.inactivateStatus(status.id); await refresh() }} />
                : <button type="button" className="btn secondary" onClick={() => guard(() => workflowApi.reactivateStatus(status.id))}>Reativar</button>)}
            </td>
          </tr>)}</tbody>
        </table>
      </div>
    </State>
  </>
}

function RenameField({ status, onSave }: { status: WorkflowStatus; onSave: (name: string) => Promise<void> }) {
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState(status.name)
  if (!editing) return <button type="button" className="btn secondary" aria-label={`Renomear ${status.name}`} onClick={() => { setValue(status.name); setEditing(true) }}>{status.name}</button>
  return <span className="inline-form">
    <input aria-label="Nome do status" className="input" value={value} onChange={e => setValue(e.target.value)} />
    <button type="button" className="btn" disabled={value.trim().length < 2} onClick={async () => { await onSave(value.trim()); setEditing(false) }}>Salvar</button>
  </span>
}
