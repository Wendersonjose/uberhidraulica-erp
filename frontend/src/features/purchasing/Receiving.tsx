import { useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  PURCHASE_CANCEL, PURCHASE_DIVERGENCE_ACCEPT, PURCHASE_RECEIVE, purchaseApi, purchaseKeys,
  type GoodsReceipt, type PurchaseDetail, type ReceiptInput, type ReceivingLine,
} from '../../api/purchasing'
import { newIdempotencyKey, todayIso } from '../../api/finance'
import { Badge } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { ConfirmAction } from '../../components/controls'
import { formatDay, useCan } from '../finance/hooks'
import { DIVERGENCE_LABELS, RECEIPT_STATUS_LABEL, evaluateLine, parseQuantity, receiptTone } from './receivingRules'

type Entry = { quantity: string; close: boolean; justification: string }
const EMPTY: Entry = { quantity: '', close: false, justification: '' }

/**
 * Recebimento físico (TASK-0021 I4, DR-0022 D3). Pedido e importação não movimentam estoque: só o que é conferido aqui
 * entra, na quantidade que chegou. Receber menos é parcial e não exige nada; chegar a mais, ou encerrar o saldo que
 * não virá, é divergência e exige justificativa e a permissão de aceitá-la.
 */
export function ReceivingSection({ purchase }: { purchase: PurchaseDetail }) {
  const client = useQueryClient()
  const canReceive = useCan(PURCHASE_RECEIVE)
  const canAccept = useCan(PURCHASE_DIVERGENCE_ACCEPT)
  const canReverse = useCan(PURCHASE_CANCEL)
  const sheet = useQuery({ queryKey: purchaseKeys.receiving(purchase.id), queryFn: () => purchaseApi.receiving(purchase.id) })
  const receipts = useQuery({ queryKey: purchaseKeys.receipts(purchase.id), queryFn: () => purchaseApi.receipts(purchase.id) })

  const [entries, setEntries] = useState<Record<string, Entry>>({})
  const [receivedOn, setReceivedOn] = useState(todayIso())
  const [notes, setNotes] = useState('')
  const [error, setError] = useState('')
  // A mesma intenção de receber usa a mesma chave, inclusive após falha de rede; só muda depois do sucesso.
  const receiveKey = useRef(newIdempotencyKey())
  const reverseKeys = useRef<Record<string, string>>({})
  const [reason, setReason] = useState('')

  const entryOf = (line: ReceivingLine): Entry => entries[line.purchaseItemId] ?? EMPTY
  const patch = (line: ReceivingLine, change: Partial<Entry>) =>
    setEntries(current => ({ ...current, [line.purchaseItemId]: { ...(current[line.purchaseItemId] ?? EMPTY), ...change } }))

  const lines = sheet.data?.lines ?? []
  const evaluated = lines.map(line => {
    const entry = entryOf(line)
    const quantity = parseQuantity(entry.quantity)
    return { line, entry, quantity, invalid: entry.quantity.trim() !== '' && quantity == null,
      result: quantity == null ? null : evaluateLine(line.pending, quantity, entry.close) }
  })
  const filled = evaluated.filter(item => item.quantity != null)
  const divergent = filled.filter(item => item.result?.divergence !== 'NONE')
  const missingJustification = divergent.some(item => item.entry.justification.trim().length < 3)
  const hasInvalid = evaluated.some(item => item.invalid)
  const blocked = filled.length === 0 || hasInvalid || missingJustification || (divergent.length > 0 && !canAccept)

  const mutation = useMutation({
    mutationFn: () => {
      const document = purchase.documents.find(d => !d.voidedAt)
      const body: ReceiptInput = {
        receivedOn, ...(notes.trim() ? { notes: notes.trim() } : {}), ...(document ? { documentId: document.id } : {}),
        lines: filled.map(item => ({
          purchaseItemId: item.line.purchaseItemId, quantity: item.quantity as number,
          ...(item.result?.divergence === 'SHORT_CLOSED' ? { closeRemaining: true } : {}),
          ...(item.result?.divergence !== 'NONE' ? { justification: item.entry.justification.trim() } : {}),
        })),
      }
      return purchaseApi.registerReceipt(purchase.id, body, receiveKey.current)
    },
    onSuccess: async () => {
      receiveKey.current = newIdempotencyKey()
      setEntries({})
      setNotes('')
      setError('')
      await refresh()
    },
    onError: (failure: Error) => setError(failure.message),
  })

  async function refresh() {
    await Promise.all([
      client.invalidateQueries({ queryKey: purchaseKeys.receiving(purchase.id) }),
      client.invalidateQueries({ queryKey: purchaseKeys.receipts(purchase.id) }),
      client.invalidateQueries({ queryKey: purchaseKeys.purchase(purchase.id) }),
      client.invalidateQueries({ queryKey: purchaseKeys.purchases }),
    ])
  }

  // DR-0023: só a compra nascida de NF-e tem identidade (chave de acesso única). Pedido e compra direta não recebem
  // enquanto não houver vínculo com a nota; senão o mesmo material entraria duas vezes no estoque.
  const hasIdentity = purchase.origin === 'NFE_IMPORT'
  const open = purchase.status === 'APPROVED' && hasIdentity

  return <section className="card" aria-label="Recebimento">
    <h2>Recebimento físico {sheet.data && <Badge tone={receiptTone(sheet.data.status)}>{RECEIPT_STATUS_LABEL[sheet.data.status]}</Badge>}</h2>
    <p className="muted">O estoque só recebe o que é conferido aqui, na quantidade que chegou e ao custo de aquisição da compra.
      Pedido, importação da nota e pagamento não movimentam estoque.</p>

    <QueryState label="a conferência" loading={sheet.isLoading} error={sheet.error} retry={sheet.refetch}
                empty={!lines.length} emptyMessage="A compra não tem itens para receber.">
      <div className="table-wrap"><table className="table">
        <thead><tr>
          <th>#</th><th>Item</th><th>Pedido</th><th>Recebido</th><th>Encerrado</th><th>Pendente</th>
          {open && canReceive && <th>Receber agora</th>}
        </tr></thead>
        <tbody>{evaluated.map(({ line, entry, invalid, result }) => <tr key={line.purchaseItemId}>
          <td>{line.lineNo}</td>
          <td>{line.description}<br /><small className="muted">{line.productUnit ?? '—'}</small></td>
          <td>{line.ordered}</td><td>{line.received}</td><td>{line.closed}</td>
          <td>{line.pending < 0 ? <><strong>{line.pending}</strong> <Badge tone="warning">a mais</Badge></> : line.pending}</td>
          {open && canReceive && <td>
            <input className="input" inputMode="decimal" aria-label={`Quantidade recebida do item ${line.lineNo}`}
                   placeholder={line.pending > 0 ? String(line.pending) : '0'} value={entry.quantity}
                   onChange={e => patch(line, { quantity: e.target.value })} />
            {invalid && <small role="alert" className="error-text">Quantidade inválida (até 3 casas, maior que zero)</small>}
            {result && result.divergence !== 'OVER' && line.pending > 0 && (parseQuantity(entry.quantity) ?? 0) < line.pending &&
              <label className="check"><input type="checkbox" aria-label={`Encerrar saldo do item ${line.lineNo}`} checked={entry.close}
                                              onChange={e => patch(line, { close: e.target.checked })} />
                <span>O fornecedor não enviará o restante</span></label>}
            {result && result.divergence !== 'NONE' && <>
              <div><Badge tone="warning">{DIVERGENCE_LABELS[result.divergence]}</Badge>
                {result.divergence === 'SHORT_CLOSED' && <small className="muted"> cancela {result.cancelled}</small>}</div>
              <input className="input" aria-label={`Justificativa do item ${line.lineNo}`} placeholder="Justificativa (obrigatória)"
                     value={entry.justification} onChange={e => patch(line, { justification: e.target.value })} />
            </>}
          </td>}
        </tr>)}</tbody>
      </table></div>
    </QueryState>

    {!open && purchase.status === 'APPROVED' && !hasIdentity && <div role="note" className="notice">
      {purchase.origin === 'ORDER' ? 'Pedido' : 'Compra direta'} ainda não recebe mercadoria: sem ligação com a NF-e do
      fornecedor (decisão pendente, DR-0023) o mesmo material poderia entrar duas vezes no estoque.
      Receba na <strong>compra da nota importada</strong>.</div>}
    {!open && purchase.status !== 'APPROVED' && <p className="notice">Compra {purchase.status === 'CANCELLED' ? 'cancelada' : 'não aprovada'}: não recebe mercadoria.</p>}

    {open && canReceive && lines.length > 0 && <>
      {error && <div role="alert" className="notice error">{error}</div>}
      {divergent.length > 0 && !canAccept && <div role="alert" className="notice error">
        Há divergência no recebimento e você não tem permissão para aceitá-la ({PURCHASE_DIVERGENCE_ACCEPT}).
        Receba só o que chegou conforme o pendente, ou peça a quem pode aceitar.</div>}
      <div className="form-grid">
        <label className="field"><span>Data do recebimento</span>
          <input className="input" type="date" max={todayIso()} value={receivedOn} onChange={e => setReceivedOn(e.target.value)} /></label>
        <label className="field"><span>Observação</span>
          <input className="input" maxLength={1000} value={notes} onChange={e => setNotes(e.target.value)} /></label>
      </div>
      <div className="form-actions">
        <button type="button" className="btn" disabled={blocked || mutation.isPending || !receivedOn}
                onClick={() => { setError(''); mutation.mutate() }}>
          {mutation.isPending ? 'Registrando…' : 'Registrar recebimento'}
        </button>
      </div>
    </>}

    <h3>Recebimentos registrados</h3>
    <QueryState label="os recebimentos" loading={receipts.isLoading} error={receipts.error} retry={receipts.refetch}
                empty={!receipts.data?.length} emptyMessage="Nenhum recebimento registrado.">
      <div className="table-wrap"><table className="table">
        <thead><tr><th>Nº</th><th>Data</th><th>Itens</th><th>Divergências</th><th>Situação</th><th /></tr></thead>
        <tbody>{receipts.data?.map(receipt => <ReceiptRow key={receipt.id} receipt={receipt} lines={lines}>
          {canReverse && !receipt.reversal && <ConfirmAction label="Estornar" confirmLabel="Confirmar estorno"
              question={`Estornar o recebimento ${receipt.number}? A entrada no estoque é desfeita e o saldo volta a ficar pendente.`}
              onConfirm={async () => {
                if (reason.trim().length < 3) throw new Error('Informe o motivo do estorno')
                reverseKeys.current[receipt.id] ??= newIdempotencyKey()
                await purchaseApi.reverseReceipt(receipt.id, reason.trim(), reverseKeys.current[receipt.id])
                delete reverseKeys.current[receipt.id]
                setReason('')
                await refresh()
              }}>
            <div className="field"><label>Motivo do estorno *
              <input className="input" value={reason} onChange={e => setReason(e.target.value)} /></label></div>
          </ConfirmAction>}
        </ReceiptRow>)}</tbody>
      </table></div>
    </QueryState>
  </section>
}

function ReceiptRow({ receipt, lines, children }: { receipt: GoodsReceipt; lines: ReceivingLine[]; children: React.ReactNode }) {
  const total = receipt.items.reduce((sum, item) => sum + item.quantityReceived, 0)
  const divergent = receipt.items.filter(item => item.divergence !== 'NONE')
  const lineOf = (purchaseItemId: string) => lines.find(line => line.purchaseItemId === purchaseItemId)?.lineNo
  return <tr>
    <td>{receipt.number}</td>
    <td>{formatDay(receipt.receivedOn)}</td>
    <td>{receipt.items.length} item(ns), {total} un.</td>
    <td>{divergent.length === 0 ? '—' : divergent.map(item => <div key={item.id}>
      <Badge tone="warning">{DIVERGENCE_LABELS[item.divergence]}</Badge>{' '}
      <small className="muted">item {lineOf(item.purchaseItemId) ?? '?'}: {item.justification}</small></div>)}</td>
    <td>{receipt.reversal ? <Badge tone="warning">Estornado</Badge> : <Badge tone="success">Efetivo</Badge>}
      {receipt.reversal && <div className="muted">{receipt.reversal.reason}</div>}</td>
    <td>{children}</td>
  </tr>
}
