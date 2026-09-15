import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { quoteAccessApi } from '../../api/resources'
import { queryKeys } from '../../api/queryKeys'
import type { Quote } from '../../api/types'
import { Badge } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { formatDate } from '../../utils/format'

/**
 * Gestão do link público de uma apresentação.
 *
 * <p>O token aparece uma única vez, logo após ser emitido: o backend guarda apenas o digest, então
 * nem esta tela nem ninguém consegue recuperá-lo depois. Perdido o link, emite-se outro.</p>
 */
export function QuotePublicAccessCard({ workOrderId, quote, canManage, onError }: {
  workOrderId: string; quote: Quote; canManage: boolean; onError: (message: string) => void
}) {
  const client = useQueryClient()
  const [issued, setIssued] = useState<{ accessId: string; url: string } | null>(null)
  const accesses = useQuery({
    queryKey: queryKeys.quoteAccess(workOrderId, quote.id),
    queryFn: () => quoteAccessApi.list(workOrderId, quote.id),
    enabled: canManage,
  })

  const invalidate = () => client.invalidateQueries({ queryKey: queryKeys.quoteAccess(workOrderId, quote.id) })

  const issue = useMutation({
    mutationFn: (revisionId: string) => quoteAccessApi.issue(workOrderId, quote.id, revisionId),
    onSuccess: async result => {
      setIssued({ accessId: result.accessId, url: `${window.location.origin}/orcamento/${result.token}` })
      await invalidate()
    },
  })

  const revoke = useMutation({
    mutationFn: (accessId: string) => quoteAccessApi.revoke(workOrderId, quote.id, accessId),
    onSuccess: async () => { setIssued(null); await invalidate() },
  })

  const presented = quote.revisions.filter(revision => revision.status === 'PRESENTED' && !revision.expired)

  if (!canManage) return null

  return <section className="card" style={{ marginTop: 22 }}>
    <h2>Link do cliente</h2>
    <p className="muted">
      O link dá acesso apenas a uma apresentação e expira junto com a validade dela. O endereço é
      secreto: quem o tiver poderá decidir os itens.
    </p>

    {issued && <div role="status" className="notice success">
      Link gerado. Ele aparece somente agora — copie antes de sair desta tela.
      <div style={{ marginTop: 8 }}><code data-testid="issued-link">{issued.url}</code></div>
    </div>}

    {presented.length === 0
      ? <div className="state">Apresente uma revisão para poder gerar o link.</div>
      : <div className="form-actions" style={{ justifyContent: 'flex-start' }}>
        {presented.map(revision => <button key={revision.id} type="button" className="btn"
          disabled={issue.isPending}
          onClick={async () => {
            onError('')
            try { await issue.mutateAsync(revision.id) }
            catch (error) { onError(error instanceof Error ? error.message : 'Falha ao gerar o link') }
          }}>Gerar link da R{revision.revisionNumber}</button>)}
      </div>}

    <QueryState label="os links" loading={accesses.isLoading} error={accesses.error}
      retry={() => accesses.refetch()} empty={!accesses.data?.length}
      emptyMessage="Nenhum link gerado ainda.">
      <div className="table-wrap" style={{ marginTop: 14 }}>
        <table className="table">
          <thead><tr><th>Link</th><th>Gerado em</th><th>Válido até</th><th>Situação</th><th>Ações</th></tr></thead>
          <tbody>{accesses.data?.map(access => <tr key={access.id}>
            <td><strong>{access.id.slice(0, 8)}</strong></td>
            <td>{formatDate(access.createdAt)}</td>
            <td>{formatDate(access.validUntil)}</td>
            <td><Badge tone={access.active ? 'success' : 'warning'}>
              {access.revokedAt ? 'Revogado' : access.active ? 'Ativo' : 'Expirado'}</Badge></td>
            <td>{access.active
              ? <button type="button" className="btn secondary" disabled={revoke.isPending}
                onClick={async () => {
                  onError('')
                  try { await revoke.mutateAsync(access.id) }
                  catch (error) { onError(error instanceof Error ? error.message : 'Falha ao revogar o link') }
                }}>Revogar</button>
              : <span className="muted">—</span>}</td>
          </tr>)}</tbody>
        </table>
      </div>
    </QueryState>
  </section>
}
