import { useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { publicQuotesApi } from '../../api/resources'
import { queryKeys } from '../../api/queryKeys'
import { PUBLIC_STATUS_LABELS, type PublicQuoteItem } from '../../api/types'
import { Badge } from '../../components/ui'
import { formatBrl, formatDate } from '../../utils/format'
import { digits } from '../../utils/format'

type Choice = 'PENDING' | 'APPROVE' | 'REJECT'

const statusTone = (item: PublicQuoteItem) =>
  item.decisionStatus === 'APPROVED' ? 'success' : item.decisionStatus === 'REJECTED' ? 'warning' : 'info'

/**
 * Página do cliente.
 *
 * <p>Não exige conta, não usa sessão e não envia cookie: a autorização é o token do endereço. Abrir
 * a página não decide nada — só o envio explícito, com nome, documento e aceite, cria decisão.</p>
 */
export function PublicQuotePage() {
  const { token = '' } = useParams()
  const client = useQueryClient()
  const quote = useQuery({ queryKey: queryKeys.publicQuote(token), queryFn: () => publicQuotesApi.view(token) })

  const [choices, setChoices] = useState<Record<string, Choice>>({})
  const [name, setName] = useState('')
  const [documentType, setDocumentType] = useState<'CPF' | 'CNPJ'>('CPF')
  const [documentNumber, setDocumentNumber] = useState('')
  const [accepted, setAccepted] = useState(false)
  const [apiError, setApiError] = useState('')
  const [sent, setSent] = useState('')

  // Um identificador por formulário: reenviar depois de uma falha de rede repete o mesmo envio em
  // vez de criar um segundo, que é justamente o que a idempotência do backend espera.
  const requestId = useMemo(() => crypto.randomUUID(), [])

  const decidable = quote.data?.items.filter(item => item.decisionAvailability === 'DECIDABLE') ?? []
  const selected = decidable.filter(item => (choices[item.itemReference] ?? 'PENDING') !== 'PENDING')

  const submit = useMutation({
    mutationFn: () => publicQuotesApi.decide(token, {
      revisionReference: quote.data!.revisionReference,
      requestId,
      customer: { name: name.trim(), documentType, documentNumber: digits(documentNumber) },
      explicitAcceptance: accepted,
      decisions: selected.map(item => ({
        itemReference: item.itemReference,
        decision: choices[item.itemReference],
      })),
    }),
    onSuccess: async result => {
      setSent(result.replayed ? 'Este envio já havia sido registrado.' : 'Decisão registrada. Obrigado!')
      await client.invalidateQueries({ queryKey: queryKeys.publicQuote(token) })
    },
  })

  if (quote.isLoading) return <main className="center-state">Carregando orçamento…</main>
  if (quote.error) return <main className="center-state">
    <div role="alert" className="notice error">
      {quote.error instanceof Error ? quote.error.message : 'Este orçamento não está disponível.'}
    </div>
  </main>

  const data = quote.data!
  const canSubmit = selected.length > 0 && accepted && name.trim().length >= 2
    && digits(documentNumber).length === (documentType === 'CPF' ? 11 : 14) && !submit.isPending

  return <main className="content" style={{ margin: '0 auto' }}>
    <header className="page-header">
      <div>
        <h1>Orçamento {data.revisionNumber > 1 ? `· revisão ${data.revisionNumber}` : ''}</h1>
        <p>Apresentado em {formatDate(data.presentedAt)} · válido até {formatDate(data.validUntil)}</p>
      </div>
    </header>

    {sent && <div role="status" className="notice success">{sent}</div>}
    {apiError && <div role="alert" className="notice error">{apiError}</div>}

    <section className="card table-wrap">
      <table className="table">
        <thead><tr>
          <th>Item</th><th>Qtd.</th><th>Valor unitário</th><th>Total</th><th>Situação</th><th>Sua decisão</th>
        </tr></thead>
        <tbody>{data.items.map(item => <tr key={item.itemReference}>
          <td><strong>{item.description}</strong></td>
          <td>{Number(item.quantity)}</td>
          <td>{formatBrl(Number(item.unitPrice))}</td>
          <td>{formatBrl(Number(item.totalPrice))}
            {Number(item.discountAmount ?? 0) > 0 && <div className="muted">desconto de {formatBrl(Number(item.discountAmount))}</div>}</td>
          <td><Badge tone={statusTone(item)}>{PUBLIC_STATUS_LABELS[item.decisionStatus]}</Badge></td>
          <td>{item.decisionAvailability === 'DECIDABLE'
            ? <select className="select" aria-label={`Decisão para ${item.description}`}
              value={choices[item.itemReference] ?? 'PENDING'}
              onChange={event => setChoices({ ...choices, [item.itemReference]: event.target.value as Choice })}>
              <option value="PENDING">Decidir depois</option>
              <option value="APPROVE">Aprovar</option>
              <option value="REJECT">Recusar</option>
            </select>
            : <span className="muted">{item.decisionAvailability === 'SUPERSEDED'
              ? 'Substituído por uma versão mais recente'
              : 'Já decidido'}</span>}</td>
        </tr>)}</tbody>
      </table>
      <div className="subtotal">
        <span>Total apresentado</span>
        <span data-testid="public-total">{formatBrl(Number(data.total))}</span>
      </div>
    </section>

    {decidable.length > 0 && <section className="card" style={{ marginTop: 22 }}>
      <h2>Confirmar decisão</h2>
      <p className="muted">
        Você pode aprovar apenas parte dos itens. O que ficar como “decidir depois” continua pendente.
      </p>
      <div className="form-grid">
        <div className="field">
          <label htmlFor="name">Seu nome completo</label>
          <input id="name" className="input" value={name} onChange={event => setName(event.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="documentType">Tipo de documento</label>
          <select id="documentType" className="select" value={documentType}
            onChange={event => setDocumentType(event.target.value as 'CPF' | 'CNPJ')}>
            <option value="CPF">CPF</option>
            <option value="CNPJ">CNPJ</option>
          </select>
        </div>
        <div className="field">
          <label htmlFor="documentNumber">{documentType}</label>
          <input id="documentNumber" className="input" value={documentNumber}
            onChange={event => setDocumentNumber(event.target.value)} />
        </div>
        <div className="field full">
          <label htmlFor="accept">
            <input id="accept" type="checkbox" checked={accepted}
              onChange={event => setAccepted(event.target.checked)} />
            {' '}Declaro que li e aceito os itens que marquei acima.
          </label>
        </div>
      </div>
      <div className="form-actions">
        <button className="btn" disabled={!canSubmit} onClick={async () => {
          setApiError('')
          setSent('')
          try { await submit.mutateAsync() }
          catch (error) {
            setApiError(error instanceof Error ? error.message : 'Não foi possível registrar sua decisão')
          }
        }}>Enviar decisão</button>
      </div>
    </section>}
  </main>
}
