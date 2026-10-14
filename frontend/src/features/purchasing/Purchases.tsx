import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  PURCHASE_APPROVE, PURCHASE_APPROVE_OWN, PURCHASE_CANCEL, PURCHASE_IMPORT_XML, PURCHASE_ORDER_MANAGE, purchaseApi,
  purchaseKeys, type PurchaseDetail, type PurchaseOrigin, type PurchaseStatus, type PurchasingSettings,
} from '../../api/purchasing'
import { useAuth } from '../../auth/useAuth'
import { Badge, PageHeader } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { ConfirmAction, Pagination } from '../../components/controls'
import { useCan } from '../finance/hooks'
import { useDebouncedValue } from '../../utils/useDebouncedValue'
import { formatBrl, formatDate } from '../../utils/format'
import { formatTaxId } from '../../utils/taxId'
import { ReceivingSection } from './Receiving'
import { RECEIPT_STATUS_LABEL, receiptTone } from './receivingRules'
import { canApprove } from './orderRules'

const PAGE_SIZE = 20

const STATUS_LABEL: Record<PurchaseStatus, string> = {
  DRAFT: 'Rascunho', AWAITING_APPROVAL: 'Aguardando aprovação', APPROVED: 'Aprovada', CANCELLED: 'Cancelada',
}
const ORIGIN_LABEL: Record<PurchaseOrigin, string> = {
  NFE_IMPORT: 'NF-e importada', DIRECT: 'Compra direta', ORDER: 'Pedido de compra',
}
const statusTone = (status: PurchaseStatus) =>
  (status === 'CANCELLED' ? 'warning' : status === 'APPROVED' ? 'success' : 'info') as 'warning' | 'success' | 'info'
const EVENT_LABEL: Record<string, string> = {
  IMPORTED: 'NF-e importada', CREATED: 'Criada', CANCELLED: 'Cancelada', DOCUMENT_VOIDED: 'Documento anulado',
  PAYABLES_REGISTERED: 'Contas a pagar criadas', PAYABLES_CANCELLED: 'Contas a pagar canceladas',
  ITEM_LINK_CONFIRMED: 'Vínculo de item confirmado', SUPPLIER_CREATED_FROM_NFE: 'Fornecedor cadastrado pela nota',
  RECEIPT_REGISTERED: 'Recebimento registrado', RECEIPT_REVERSED: 'Recebimento estornado',
  ORDER_UPDATED: 'Rascunho alterado', ORDER_SUBMITTED: 'Enviado para aprovação',
  ORDER_RETURNED: 'Devolvido para correção', ORDER_APPROVED: 'Aprovado',
}

// ====================================================================== lista

export function PurchasesPage() {
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('')
  const [origin, setOrigin] = useState('')
  const [page, setPage] = useState(0)
  const canImport = useCan(PURCHASE_IMPORT_XML)
  const canOrder = useCan(PURCHASE_ORDER_MANAGE)
  const q = useDebouncedValue(search.trim())
  const params = {
    q: q || undefined, status: (status || undefined) as PurchaseStatus | undefined,
    origin: (origin || undefined) as PurchaseOrigin | undefined, page, size: PAGE_SIZE,
  }
  const list = useQuery({
    queryKey: purchaseKeys.purchaseList(params),
    queryFn: () => purchaseApi.purchases(params),
    placeholderData: keepPreviousData,
  })

  return <>
    <PageHeader title="Compras"
                subtitle="Pedidos, compras diretas e notas importadas. A situação de cada parcela é do Financeiro; o estoque entra no recebimento.">
      {canOrder && <Link className="btn secondary" to="/compras/pedidos/novo">Novo pedido</Link>}
      {canImport && <Link className="btn" to="/compras/notas/importar">Importar NF-e</Link>}
    </PageHeader>
    <div className="toolbar">
      <input aria-label="Buscar compras" className="input"
             placeholder="Buscar por fornecedor, número da nota ou chave de acesso"
             value={search} onChange={e => { setSearch(e.target.value); setPage(0) }} />
      <select aria-label="Situação" className="select" value={status} onChange={e => { setStatus(e.target.value); setPage(0) }}>
        <option value="">Todas as situações</option>
        <option value="DRAFT">Rascunho</option>
        <option value="AWAITING_APPROVAL">Aguardando aprovação</option>
        <option value="APPROVED">Aprovada</option>
        <option value="CANCELLED">Cancelada</option>
      </select>
      <select aria-label="Origem" className="select" value={origin} onChange={e => { setOrigin(e.target.value); setPage(0) }}>
        <option value="">Todas as origens</option>
        <option value="ORDER">Pedido de compra</option>
        <option value="DIRECT">Compra direta</option>
        <option value="NFE_IMPORT">NF-e importada</option>
      </select>
    </div>
    <QueryState label="as compras" loading={list.isLoading} error={list.error} retry={list.refetch}
                empty={!list.data?.items.length} emptyMessage="Nenhuma compra encontrada.">
      <div className="card table-wrap"><table className="table">
        <thead><tr>
          <th>Compra</th><th>Fornecedor</th><th>Origem</th><th>Nota</th><th>Emissão</th>
          <th>Parcelas</th><th>Total</th><th>Situação</th><th>Recebimento</th>
        </tr></thead>
        <tbody>{list.data?.items.map(purchase => <tr key={purchase.id}>
          <td><Link to={`/compras/notas/${purchase.id}`}>#{purchase.number}</Link></td>
          <td>{purchase.supplierName}</td>
          <td>{ORIGIN_LABEL[purchase.origin]}</td>
          <td>{purchase.invoiceNumber ?? '—'}</td>
          <td>{purchase.issuedOn ? formatDate(purchase.issuedOn) : '—'}</td>
          <td>{purchase.installmentCount}</td>
          <td>{formatBrl(purchase.payableTotal)}</td>
          <td><Badge tone={statusTone(purchase.status)}>{STATUS_LABEL[purchase.status]}</Badge></td>
          <td><Badge tone={receiptTone(purchase.receiptStatus)}>{RECEIPT_STATUS_LABEL[purchase.receiptStatus]}</Badge></td>
        </tr>)}</tbody>
      </table></div>
      <Pagination page={list.data?.page ?? 0} totalPages={list.data?.totalPages ?? 0}
                  totalItems={list.data?.totalItems ?? 0} onChange={setPage} />
    </QueryState>
  </>
}

// ====================================================================== detalhe

export function PurchaseDetailPage() {
  const { id = '' } = useParams()
  const detail = useQuery({ queryKey: purchaseKeys.purchase(id), queryFn: () => purchaseApi.purchase(id) })

  return <QueryState label="a compra" loading={detail.isLoading} error={detail.error} retry={detail.refetch}>
    {detail.data && <PurchaseView purchase={detail.data} />}
  </QueryState>
}

function PurchaseView({ purchase }: { purchase: PurchaseDetail }) {
  const client = useQueryClient()
  const canCancel = useCan(PURCHASE_CANCEL)
  const [cancelReason, setCancelReason] = useState('')
  const [voidReason, setVoidReason] = useState('')
  const events = useQuery({
    queryKey: purchaseKeys.purchaseEvents(purchase.id, 0),
    queryFn: () => purchaseApi.purchaseEvents(purchase.id, 0),
  })

  async function refresh() {
    await client.invalidateQueries({ queryKey: purchaseKeys.purchase(purchase.id) })
    await client.invalidateQueries({ queryKey: purchaseKeys.purchaseEvents(purchase.id, 0) })
    await client.invalidateQueries({ queryKey: purchaseKeys.purchases })
  }

  const installmentSum = purchase.installments.reduce((total, i) => total + i.amount, 0)

  return <>
    <PageHeader title={`Compra #${purchase.number}`}
                subtitle={`${ORIGIN_LABEL[purchase.origin]} · ${STATUS_LABEL[purchase.status]}`} />
    {purchase.cancelledAt && <div className="notice">
      Cancelada em {formatDate(purchase.cancelledAt)}. Motivo: {purchase.cancellationReason}</div>}

    {purchase.origin !== 'NFE_IMPORT' && <OrderWorkflow purchase={purchase} onChanged={refresh} />}

    <section className="card" aria-label="Totais">
      <h2>Totais</h2>
      <dl className="summary-list">
        <div><dt>Mercadorias</dt><dd>{formatBrl(purchase.goodsTotal)}</dd></div>
        <div><dt>Desconto</dt><dd>{formatBrl(purchase.discountTotal)}</dd></div>
        <div><dt>Frete</dt><dd>{formatBrl(purchase.freightTotal)}</dd></div>
        <div><dt>Seguro</dt><dd>{formatBrl(purchase.insuranceTotal)}</dd></div>
        <div><dt>Outras despesas</dt><dd>{formatBrl(purchase.otherTotal)}</dd></div>
        <div><dt>Tributos somados</dt><dd>{formatBrl(purchase.taxAddedTotal)}</dd></div>
        <div><dt>Total a pagar</dt><dd><strong>{formatBrl(purchase.payableTotal)}</strong></dd></div>
      </dl>
    </section>

    <section className="card" aria-label="Documento fiscal">
      <h2>Documento fiscal</h2>
      {purchase.documents.length === 0 ? <p className="muted">Sem documento fiscal.</p>
        : <div className="table-wrap"><table className="table">
            <thead><tr><th>Tipo</th><th>Número</th><th>Chave de acesso</th><th>Protocolo</th><th>Hash do XML</th><th>Situação</th><th /></tr></thead>
            <tbody>{purchase.documents.map(document => <tr key={document.id}>
              <td>{document.kind}</td>
              <td>{document.number ?? '—'}{document.series ? `/${document.series}` : ''}</td>
              <td>{document.accessKey ?? '—'}</td>
              <td>{document.protocolNumber ?? '—'}{document.protocolStatus ? ` · ${document.protocolStatus}` : ''}</td>
              <td>{document.xmlSha256 ? `${document.xmlSha256.slice(0, 12)}…` : '—'}</td>
              <td>{document.voidedAt ? <Badge tone="warning">Anulado</Badge> : <Badge tone="success">Ativo</Badge>}</td>
              <td>{canCancel && !document.voidedAt && purchase.status === 'CANCELLED' &&
                <ConfirmAction label="Anular" confirmLabel="Confirmar anulação"
                               question="Anular este documento? A linha anulada permanece no histórico e a chave de acesso volta a poder ser importada."
                               onConfirm={async () => {
                                 if (voidReason.trim().length < 3) throw new Error('Informe o motivo da anulação')
                                 await purchaseApi.voidDocument(document.id, voidReason.trim())
                                 setVoidReason('')
                                 await refresh()
                               }}>
                  <div className="field"><label>Motivo da anulação *
                    <input className="input" value={voidReason} onChange={e => setVoidReason(e.target.value)} /></label></div>
                </ConfirmAction>}</td>
            </tr>)}</tbody>
          </table></div>}
      <p className="muted">A autorização registrada é a <strong>declarada no XML</strong>: a consulta oficial à
        SEFAZ e a validade criptográfica da assinatura não foram verificadas.</p>
    </section>

    <section className="card" aria-label="Itens da compra">
      <h2>Itens</h2>
      <div className="table-wrap"><table className="table">
        <thead><tr>
          <th>#</th><th>Descrição</th><th>Un. da nota</th><th>Qtd. na nota</th><th>Fator</th>
          <th>Qtd. no estoque</th><th>Preço unit.</th><th>Custo unit.</th><th>Composição do custo</th>
        </tr></thead>
        <tbody>{purchase.items.map(item => <tr key={item.id}>
          <td>{item.lineNo}</td>
          <td>{item.description}<br /><small className="muted">{item.supplierCode ?? 'sem código'}</small></td>
          <td>{item.unitOriginal ?? '—'}</td>
          <td>{item.quantityOriginal ?? '—'}</td>
          <td>{item.unitConversionFactor}</td>
          <td>{item.quantity}</td>
          <td>{formatBrl(item.unitPrice)}</td>
          <td>{formatBrl(item.unitCost)}</td>
          <td>{item.cost
            ? <small className="muted">
                produto {formatBrl(item.cost.baseAmount)}
                {item.cost.ipiAmount > 0 && <> + IPI {formatBrl(item.cost.ipiAmount)}</>}
                {item.cost.icmsStAmount > 0 && <> + ICMS-ST {formatBrl(item.cost.icmsStAmount)}</>}
                {item.cost.icmsNormalAmount > 0 && <> + ICMS {formatBrl(item.cost.icmsNormalAmount)}</>}
                {item.cost.freightShare > 0 && <> + frete {formatBrl(item.cost.freightShare)}</>}
                {item.cost.insuranceShare > 0 && <> + seguro {formatBrl(item.cost.insuranceShare)}</>}
                {item.cost.otherShare > 0 && <> + outras {formatBrl(item.cost.otherShare)}</>}
                {' = '}<strong>{formatBrl(item.cost.totalAmount)}</strong>
              </small>
            : '—'}</td>
        </tr>)}</tbody>
      </table></div>
    </section>

    <ReceivingSection purchase={purchase} />

    <section className="card" aria-label="Parcelas">
      <h2>Parcelas</h2>
      {purchase.installments.length === 0 && purchase.origin !== 'NFE_IMPORT'
        ? <p className="muted">Pedido e compra direta não geram contas a pagar. A obrigação financeira nasce com a nota
            fiscal do fornecedor, importada em Compras.</p>
        : purchase.installments.length === 0 ? <p className="muted">Sem parcelas.</p>
        : <div className="table-wrap"><table className="table">
            <thead><tr><th>Parcela</th><th>Duplicata</th><th>Vencimento</th><th>Valor</th><th>Conta a pagar</th></tr></thead>
            <tbody>{purchase.installments.map(installment => <tr key={installment.payableId}>
              <td>{installment.installmentNo}</td>
              <td>{installment.invoiceReference ?? '—'}</td>
              <td>{formatDate(installment.dueDate)}</td>
              <td>{formatBrl(installment.amount)}</td>
              <td><Link to={`/financeiro/contas-a-pagar/${installment.payableId}`}>Abrir no Financeiro</Link></td>
            </tr>)}</tbody>
            <tfoot><tr><th colSpan={3}>Soma</th><th>{formatBrl(installmentSum)}</th><th /></tr></tfoot>
          </table></div>}
      <p className="muted">Pagar, baixar ou cancelar conta é no Financeiro, com as permissões dele. Compras cria
        a obrigação e nunca marca nada como pago.</p>
    </section>

    {canCancel && purchase.status !== 'CANCELLED' && <section className="card" aria-label="Cancelar compra">
      <h2>Cancelar compra</h2>
      <p className="muted">As contas a pagar desta compra também são canceladas, com o histórico preservado.
        Conta já paga impede o cancelamento — nesse caso resolva no Financeiro primeiro.</p>
      <ConfirmAction label="Cancelar compra" question="Cancelar esta compra e as contas a pagar dela?"
                     confirmLabel="Confirmar cancelamento"
                     onConfirm={async () => {
                       if (cancelReason.trim().length < 3) throw new Error('Informe o motivo do cancelamento')
                       await purchaseApi.cancelPurchase(purchase.id, cancelReason.trim())
                       setCancelReason('')
                       await refresh()
                     }}>
        <div className="field"><label>Motivo do cancelamento *
          <input className="input" value={cancelReason} onChange={e => setCancelReason(e.target.value)} /></label></div>
      </ConfirmAction>
    </section>}

    <section className="card" aria-label="Histórico da compra">
      <h2>Histórico</h2>
      <QueryState label="o histórico" loading={events.isLoading} error={events.error} retry={events.refetch}
                  empty={!events.data?.items.length} emptyMessage="Sem eventos.">
        <ul className="timeline">{events.data?.items.map(event => <li key={event.id}>
          <strong>{EVENT_LABEL[event.type] ?? event.type}</strong>{' '}
          <span className="muted">{formatDate(event.occurredAt)}</span>
          {event.reason && <p className="muted">{event.reason}</p>}
        </li>)}</ul>
      </QueryState>
    </section>
  </>
}

// ====================================================================== fluxo do pedido (I5)

/**
 * Edição do rascunho, envio, aprovação e devolução (DR-0022 D8). Os botões orientam; quem decide é o backend, que
 * também confere a versão: aprovar é aprovar o que esta tela mostra.
 */
function OrderWorkflow({ purchase, onChanged }: { purchase: PurchaseDetail; onChanged: () => Promise<void> }) {
  const { session } = useAuth()
  const canManage = useCan(PURCHASE_ORDER_MANAGE)
  const hasApprove = useCan(PURCHASE_APPROVE)
  const hasApproveOwn = useCan(PURCHASE_APPROVE_OWN)
  const [returnReason, setReturnReason] = useState('')
  const mine = purchase.createdBy === session?.id
  const mayApprove = canApprove(hasApprove, hasApproveOwn, purchase.createdBy, session?.id)

  return <section className="card" aria-label="Aprovação do pedido">
    <h2>Aprovação</h2>

    {purchase.status === 'DRAFT' && <>
      <p className="muted">Rascunho: ainda pode ser alterado. Envie para aprovação quando estiver pronto.</p>
      {canManage ? <div className="form-actions">
        <Link className="btn secondary" to={`/compras/pedidos/${purchase.id}/editar`}>Editar rascunho</Link>
        <ConfirmAction label="Enviar para aprovação" tone="primary" confirmLabel="Confirmar envio"
                       question="Enviar o pedido para aprovação? Depois de enviado, ele não pode mais ser alterado a menos que seja devolvido."
                       onConfirm={async () => {
                         await purchaseApi.submitOrder(purchase.id, purchase.version)
                         await onChanged()
                       }} />
      </div> : <p className="muted">Você não tem permissão para alterar nem enviar pedidos.</p>}
    </>}

    {purchase.status === 'AWAITING_APPROVAL' && <>
      <p>Aguardando aprovação. O pedido está bloqueado para edição.</p>
      {mayApprove ? <div className="form-actions">
        <ConfirmAction label="Aprovar pedido" tone="primary" confirmLabel="Confirmar aprovação"
                       question={`Aprovar o pedido de ${formatBrl(purchase.payableTotal)}? Aprovado, ele passa a poder ser recebido.`}
                       onConfirm={async () => {
                         await purchaseApi.approveOrder(purchase.id, purchase.version)
                         await onChanged()
                       }} />
        <ConfirmAction label="Devolver para correção" confirmLabel="Confirmar devolução" tone="primary"
                       question="Devolver o pedido para o rascunho? O autor poderá corrigi-lo e enviá-lo de novo."
                       onConfirm={async () => {
                         if (returnReason.trim().length < 3) throw new Error('Informe o motivo da devolução')
                         await purchaseApi.returnOrder(purchase.id, purchase.version, returnReason.trim())
                         setReturnReason('')
                         await onChanged()
                       }}>
          <div className="field"><label>Motivo da devolução *
            <input className="input" value={returnReason} onChange={e => setReturnReason(e.target.value)} /></label></div>
        </ConfirmAction>
      </div> : hasApprove && mine
        ? <div role="note" className="notice">Você criou este pedido e não pode aprová-lo: a segregação de funções
            pede outra pessoa com permissão de aprovar.</div>
        : <p className="muted">Quem tem permissão de aprovar pedidos fará a análise.</p>}
    </>}

    {purchase.status === 'APPROVED' && <>
      <p>Aprovado{purchase.approvedAt ? ` em ${formatDate(purchase.approvedAt)}` : ''}.
        {purchase.approvedBy && purchase.approvedBy === purchase.createdBy &&
          <> Aprovado pelo próprio autor, por exceção do proprietário.</>}</p>
      <div role="note" className="notice">
        Ainda não existe ligação entre este pedido e a NF-e do fornecedor (decisão pendente, DR-0023). Registre o
        recebimento <strong>neste pedido</strong> <em>ou</em> na compra da nota importada — <strong>nunca nos dois</strong> —
        para o estoque não entrar em dobro.</div>
    </>}

    {purchase.status === 'CANCELLED' && <p className="muted">Pedido cancelado: o histórico foi preservado.</p>}
  </section>
}

// ====================================================================== configuração

export function PurchasingSettingsPage() {
  const settings = useQuery({ queryKey: purchaseKeys.settings, queryFn: purchaseApi.settings })
  return <QueryState label="as configurações de Compras" loading={settings.isLoading} error={settings.error}
                     retry={settings.refetch}>
    {settings.data && <SettingsView current={settings.data} />}
  </QueryState>
}

function SettingsView({ current }: { current: PurchasingSettings }) {
  const client = useQueryClient()
  const canManage = useCan(PURCHASE_ORDER_MANAGE)
  const [taxId, setTaxId] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  async function change(key: string, value: string) {
    setSaving(true)
    setError('')
    try {
      await purchaseApi.changeSetting(key, value)
      await client.invalidateQueries({ queryKey: purchaseKeys.settings })
      setTaxId('')
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Não foi possível salvar a configuração.')
    } finally {
      setSaving(false)
    }
  }

  return <>
    <PageHeader title="Configurações de Compras"
                subtitle="CNPJ do estabelecimento e composição do custo de aquisição." />
    {error && <div role="alert" className="notice error">{error}</div>}

    <section className="card" aria-label="CNPJ do estabelecimento">
      <h2>CNPJ do estabelecimento</h2>
      <p className="muted">{current.descriptions.OWN_TAX_ID}</p>
      <p>Atual: <strong>{current.ownTaxIdConfigured ? formatTaxId(current.ownTaxId) : 'não configurado'}</strong></p>
      {canManage && <div className="toolbar">
        <input className="input" aria-label="Novo CNPJ do estabelecimento" placeholder="00.000.000/0000-00"
               value={taxId} onChange={e => setTaxId(e.target.value)} />
        <button type="button" className="btn" disabled={!taxId.trim() || saving}
                onClick={() => { void change('OWN_TAX_ID', taxId) }}>Salvar</button>
      </div>}
    </section>

    <section className="card" aria-label="Composição do custo de aquisição">
      <h2>Composição do custo de aquisição</h2>
      {!current.costParametersValidatedByAccountant && <div role="note" className="notice error">
        Estes parâmetros <strong>não foram validados pelo contador</strong>. Alterá-los muda o custo das compras
        futuras e <strong>não recalcula</strong> o custo já gravado.
      </div>}
      {([
        ['COST_INCLUDE_IPI', 'Somar IPI ao custo', current.costIncludeIpi],
        ['COST_INCLUDE_ICMS_ST', 'Somar ICMS-ST ao custo', current.costIncludeIcmsSt],
        ['COST_INCLUDE_ICMS_NORMAL', 'Somar ICMS normal ao custo', current.costIncludeIcmsNormal],
      ] as const).map(([key, label, value]) => <div className="field" key={key}>
        <label>
          <input type="checkbox" checked={value} disabled={!canManage || saving}
                 onChange={e => { void change(key, String(e.target.checked)) }} />
          {' '}{label}
        </label>
        <span className="muted">{current.descriptions[key]}</span>
      </div>)}
    </section>

    <section className="card" aria-label="Rateio de frete e despesas">
      <h2>Rateio de frete e despesas</h2>
      <p className="muted">{current.descriptions.FREIGHT_ALLOCATION}</p>
      <select className="select" aria-label="Método de rateio" value={current.freightAllocation}
              disabled={!canManage || saving}
              onChange={e => { void change('FREIGHT_ALLOCATION', e.target.value) }}>
        <option value="VALUE">Proporcional ao valor</option>
        <option value="QUANTITY">Proporcional à quantidade</option>
      </select>
    </section>
  </>
}
