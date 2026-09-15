import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { quotesApi, type QuoteItemInput } from '../../api/resources'
import { queryKeys } from '../../api/queryKeys'
import { AVAILABILITY_LABELS, QUOTE_PRESENT, type Quote, type QuoteItemRevision } from '../../api/types'
import { useAuth } from '../../auth/useAuth'
import { Badge, PageHeader, State } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { formatBrl, formatDate } from '../../utils/format'

const availabilityTone = (availability: QuoteItemRevision['availability']) =>
  availability === 'AVAILABLE' ? 'success' : availability === 'NOT_PRESENTED' ? 'info' : 'warning'

export function QuotesPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const client = useQueryClient()
  const [apiError, setApiError] = useState('')
  const quotes = useQuery({ queryKey: queryKeys.quotes(id), queryFn: () => quotesApi.list(id) })

  const open = useMutation({
    mutationFn: () => quotesApi.open(id),
    onSuccess: async quote => {
      await client.invalidateQueries({ queryKey: queryKeys.quotes(id) })
      navigate(`/ordens-servico/${id}/orcamentos/${quote.id}`)
    },
  })

  return <>
    <PageHeader title="Orçamentos da OS" subtitle="Cada apresentação ao cliente é uma revisão própria e preservada.">
      <Link className="btn secondary" to={`/ordens-servico/${id}`}>Voltar para a OS</Link>
      <button className="btn" disabled={open.isPending} onClick={async () => {
        setApiError('')
        try { await open.mutateAsync() }
        catch (error) { setApiError(error instanceof Error ? error.message : 'Falha ao abrir orçamento') }
      }}>Novo orçamento</button>
    </PageHeader>
    {apiError && <div role="alert" className="notice error">{apiError}</div>}
    <State loading={quotes.isLoading} error={quotes.error} empty={!quotes.data?.length}>
      <div className="card table-wrap">
        <table className="table">
          <thead><tr><th>Orçamento</th><th>Criado em</th><th>Revisões</th><th>Última apresentação</th><th>Ações</th></tr></thead>
          <tbody>{quotes.data?.map(quote => {
            const presented = [...quote.revisions].reverse().find(revision => revision.status === 'PRESENTED')
            return <tr key={quote.id}>
              <td><strong>{quote.id.slice(0, 8)}</strong></td>
              <td>{formatDate(quote.createdAt)}</td>
              <td>{quote.revisions.length}</td>
              <td>{presented
                ? <>R{presented.revisionNumber} · {formatDate(presented.presentedAt!)}{' '}
                  <Badge tone={presented.expired ? 'warning' : 'success'}>
                    {presented.expired ? 'Validade encerrada' : 'Dentro da validade'}
                  </Badge></>
                : <span className="muted">Nenhuma apresentação</span>}</td>
              <td><Link className="btn secondary" to={`/ordens-servico/${id}/orcamentos/${quote.id}`}>Abrir</Link></td>
            </tr>
          })}</tbody>
        </table>
      </div>
    </State>
  </>
}

export function QuoteDetailPage() {
  const { id = '', quoteId = '' } = useParams()
  const { session } = useAuth()
  const canPresent = Boolean(session?.permissions?.includes(QUOTE_PRESENT))
  const client = useQueryClient()
  const [apiError, setApiError] = useState('')
  const quote = useQuery({
    queryKey: queryKeys.quote(id, quoteId),
    queryFn: () => quotesApi.get(id, quoteId),
  })

  const invalidate = async () => {
    await client.invalidateQueries({ queryKey: queryKeys.quote(id, quoteId) })
    await client.invalidateQueries({ queryKey: queryKeys.quotes(id) })
  }

  const present = useMutation({
    mutationFn: (revisionId: string) => quotesApi.present(id, quoteId, revisionId),
    onSuccess: invalidate,
  })

  const run = async (action: () => Promise<unknown>, fallback: string) => {
    setApiError('')
    try { await action() }
    catch (error) { setApiError(error instanceof Error ? error.message : fallback) }
  }

  return <>
    <PageHeader title="Orçamento" subtitle="Alterar preço, descrição ou quantidade cria uma nova versão comercial.">
      <Link className="btn secondary" to={`/ordens-servico/${id}/orcamentos`}>Orçamentos da OS</Link>
    </PageHeader>
    {apiError && <div role="alert" className="notice error">{apiError}</div>}
    <QueryState label="o orçamento" loading={quote.isLoading} error={quote.error} retry={quote.refetch}>
      {quote.data && <>
        <RevisionsCard quote={quote.data} presenting={present.isPending} canPresent={canPresent}
          onPresent={revisionId => run(() => present.mutateAsync(revisionId), 'Falha ao apresentar a revisão')} />
        <ItemsCard quote={quote.data} />
        <NewRevisionCard workOrderId={id} quote={quote.data} onDone={invalidate} onError={setApiError} />
      </>}
    </QueryState>
  </>
}

/**
 * `canPresent` é conveniência de interface, não proteção: o endpoint exige `QUOTE_PRESENT` de
 * qualquer forma, e esconder o botão não substitui a verificação do backend.
 */
function RevisionsCard({ quote, presenting, canPresent, onPresent }: {
  quote: Quote; presenting: boolean; canPresent: boolean; onPresent: (revisionId: string) => void
}) {
  const highestPresented = quote.revisions
    .filter(revision => revision.status === 'PRESENTED')
    .reduce((highest, revision) => Math.max(highest, revision.revisionNumber), 0)
  return <section className="card table-wrap">
    <h2>Apresentações</h2>
    {quote.revisions.length === 0
      ? <div className="state">Nenhuma revisão criada.</div>
      : <table className="table">
        <thead><tr><th>Revisão</th><th>Situação</th><th>Apresentada em</th><th>Válida até</th><th>Itens</th><th>Total</th><th>Ações</th></tr></thead>
        <tbody>{quote.revisions.map(revision => <tr key={revision.id}>
          <td><strong>R{revision.revisionNumber}</strong></td>
          <td>{revision.status === 'PRESENTED'
            ? <Badge tone={revision.expired ? 'warning' : 'success'}>
              {revision.expired ? 'Validade encerrada' : 'Apresentada'}</Badge>
            : <Badge tone="info">Rascunho</Badge>}</td>
          <td>{revision.presentedAt ? formatDate(revision.presentedAt) : '—'}</td>
          <td>{revision.validUntil ? formatDate(revision.validUntil) : '—'}</td>
          <td>{revision.items.length}</td>
          <td>{formatBrl(Number(revision.total))}</td>
          <td>{revision.status === 'DRAFT' && revision.revisionNumber >= highestPresented
            ? <button className="btn" disabled={presenting || !canPresent}
              title={canPresent ? undefined : 'Seu perfil não pode apresentar orçamento'}
              onClick={() => onPresent(revision.id)}>Apresentar</button>
            : <span className="muted">—</span>}</td>
        </tr>)}</tbody>
      </table>}
  </section>
}

function ItemsCard({ quote }: { quote: Quote }) {
  return <section className="card table-wrap" style={{ marginTop: 22 }}>
    <h2>Itens comerciais e versões</h2>
    {quote.items.length === 0
      ? <div className="state">Nenhum item comercial.</div>
      : <table className="table">
        <thead><tr><th>Versão</th><th>Descrição</th><th>Qtd.</th><th>Preço unitário</th><th>Total</th><th>Situação</th></tr></thead>
        <tbody>{quote.items.flatMap(item => item.revisions.map(revision => <tr key={revision.id}>
          <td><strong>{item.id.slice(0, 8)}-v{revision.revisionSequence}</strong></td>
          <td>{revision.description}</td>
          <td>{Number(revision.quantity)}</td>
          <td>{formatBrl(Number(revision.unitPrice))}</td>
          <td>{formatBrl(Number(revision.totalPrice))}</td>
          <td><Badge tone={availabilityTone(revision.availability)}>
            {AVAILABILITY_LABELS[revision.availability]}</Badge></td>
        </tr>))}</tbody>
      </table>}
    <div className="subtotal">
      <span>Total decidível apresentado</span>
      <span data-testid="quote-available-total">{formatBrl(Number(quote.availableTotal))}</span>
    </div>
  </section>
}

type Row = { quoteItemId: string | null; description: string; quantity: string; unitPrice: string }

/** Semeia a nova revisão com a última apresentação para que o complemento reaproveite versões. */
function seedRows(quote: Quote): Row[] {
  const latest = quote.revisions.at(-1)
  if (!latest) return []
  const byRevisionId = new Map(quote.items.flatMap(item =>
    item.revisions.map(revision => [revision.id, { item, revision }] as const)))
  return latest.items
    .map(entry => byRevisionId.get(entry.quoteItemRevisionId))
    .filter((found): found is NonNullable<typeof found> => Boolean(found))
    .map(({ item, revision }) => ({
      quoteItemId: item.id,
      description: revision.description,
      quantity: String(Number(revision.quantity)),
      unitPrice: String(Number(revision.unitPrice)),
    }))
}

function NewRevisionCard({ workOrderId, quote, onDone, onError }: {
  workOrderId: string; quote: Quote; onDone: () => Promise<unknown>; onError: (message: string) => void
}) {
  const [rows, setRows] = useState<Row[]>(() => seedRows(quote))
  const [seededFrom, setSeededFrom] = useState(quote.revisions.at(-1)?.id ?? '')
  const latestId = quote.revisions.at(-1)?.id ?? ''
  if (latestId !== seededFrom) {
    setSeededFrom(latestId)
    setRows(seedRows(quote))
  }

  const create = useMutation({
    mutationFn: (items: QuoteItemInput[]) => quotesApi.createRevision(workOrderId, quote.id, items),
    onSuccess: onDone,
  })

  const update = (index: number, patch: Partial<Row>) =>
    setRows(rows.map((row, position) => (position === index ? { ...row, ...patch } : row)))

  const valid = rows.length > 0 && rows.every(row =>
    row.description.trim().length > 0 && Number(row.quantity) > 0 && Number(row.unitPrice) >= 0
    && row.quantity !== '' && row.unitPrice !== '')

  return <section className="card" style={{ marginTop: 22 }}>
    <h2>Nova revisão</h2>
    <p className="muted">
      Itens com os mesmos termos reaproveitam a versão atual. Qualquer mudança de descrição,
      quantidade ou preço cria uma nova versão comercial daquele item.
    </p>
    {rows.map((row, index) => <div className="form-grid" key={index} style={{ marginTop: 14 }}>
      <div className="field full">
        <label htmlFor={`description-${index}`}>Descrição {index + 1}</label>
        <input id={`description-${index}`} className="input" value={row.description}
          onChange={event => update(index, { description: event.target.value })} />
      </div>
      <div className="field">
        <label htmlFor={`quantity-${index}`}>Quantidade {index + 1}</label>
        <input id={`quantity-${index}`} className="input" type="number" step="0.0001" min="0"
          value={row.quantity} onChange={event => update(index, { quantity: event.target.value })} />
      </div>
      <div className="field">
        <label htmlFor={`unitPrice-${index}`}>Preço unitário {index + 1}</label>
        <input id={`unitPrice-${index}`} className="input" type="number" step="0.0001" min="0"
          value={row.unitPrice} onChange={event => update(index, { unitPrice: event.target.value })} />
      </div>
      <div className="field">
        <label htmlFor={`remove-${index}`}>&nbsp;</label>
        <button id={`remove-${index}`} type="button" className="btn secondary"
          onClick={() => setRows(rows.filter((_, position) => position !== index))}>
          Remover item {index + 1}
        </button>
      </div>
    </div>)}
    <div className="form-actions">
      <button type="button" className="btn secondary" onClick={() =>
        setRows([...rows, { quoteItemId: null, description: '', quantity: '1', unitPrice: '' }])}>
        Adicionar item
      </button>
      <button type="button" className="btn" disabled={!valid || create.isPending} onClick={async () => {
        onError('')
        try {
          await create.mutateAsync(rows.map(row => ({
            quoteItemId: row.quoteItemId,
            description: row.description.trim(),
            quantity: Number(row.quantity),
            unitPrice: Number(row.unitPrice),
          })))
        } catch (error) {
          onError(error instanceof Error ? error.message : 'Falha ao criar a revisão')
        }
      }}>Criar revisão</button>
    </div>
  </section>
}
