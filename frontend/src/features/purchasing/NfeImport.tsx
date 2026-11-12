import { useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  PRODUCT_MANAGE, PURCHASE_IMPORT_XML, purchaseApi, purchaseKeys,
  type ItemDecision, type NfePreview, type PreviewItem, type PreviewSelection,
  type OrderDifferenceKind,
} from '../../api/purchasing'
import { financeApi, financeKeys, newIdempotencyKey } from '../../api/finance'
import { productsApi, workOrdersApi } from '../../api/resources'
import { queryKeys } from '../../api/queryKeys'
import { PRODUCT_TYPE_LABELS, PRODUCT_UNIT_LABELS, type Product, type ProductType, type ProductUnit } from '../../api/types'
import { Badge, PageHeader } from '../../components/ui'
import { useCan } from '../finance/hooks'
import { useDebouncedValue } from '../../utils/useDebouncedValue'
import { formatBrl, formatDate } from '../../utils/format'
import { formatTaxId } from '../../utils/taxId'
import { suggestCatalogUnit } from './nfeUnits'

/** Decisão do operador por linha da nota, antes de confirmar. */
type Decision = { productId: string; factor: string; saveLink: boolean }

/**
 * O backend lista como bloqueio da prévia a falta de duplicata (D11), mas na tela isso não é bloqueio: o formulário
 * de parcelas está logo abaixo. Este prefixo só serve para não mostrar essa dica como erro vermelho.
 */
const TERMS_HINT = 'Nota sem duplicata'

const RECIPIENT_LABEL: Record<NfePreview['recipientCheck'], string> = {
  AUTHORIZED: 'Destinatário confere com o estabelecimento autorizado',
  OWN_TAX_ID_NOT_CONFIGURED: 'CNPJ da oficina não configurado em Compras › Configurações',
  RECIPIENT_MISMATCH: 'Nota destinada a outro CNPJ',
  RECIPIENT_MISSING: 'Nota sem CNPJ de destinatário legível',
}

/** Rótulo de cada diferença entre o pedido e a nota (DR-0023 C2-A/C3-A). */
const DIFFERENCE_LABEL: Record<OrderDifferenceKind, string> = {
  TOTAL: 'Total a pagar',
  QUANTITY: 'Quantidade',
  UNIT_PRICE: 'Preço unitário',
  ITEM_NOT_IN_INVOICE: 'Item do pedido que não veio na nota',
  ITEM_NOT_IN_ORDER: 'Item da nota que não estava no pedido',
}

const differenceValue = (kind: OrderDifferenceKind, value: number | null) =>
  value == null ? '—' : kind === 'TOTAL' || kind === 'UNIT_PRICE' ? formatBrl(value) : String(value)

const parseFactor = (text: string) => {
  const value = Number(text.trim().replace(',', '.'))
  return text.trim() !== '' && Number.isFinite(value) ? value : undefined
}

/**
 * Só vão como "escolha" ao servidor as linhas que o operador mudou em relação ao que o próprio servidor já tinha
 * resolvido (vínculo ou GTIN). Reenviar o que o servidor resolveu transformaria tudo em escolha manual e perderia
 * a informação de como o item foi casado.
 */
function selectionsOf(preview: NfePreview | null, decisions: Record<number, Decision>,
                      baseline: Record<number, Decision>): PreviewSelection[] {
  if (!preview) return []
  const result: PreviewSelection[] = []
  for (const item of preview.items) {
    const decision = decisions[item.lineNo]
    if (!decision?.productId) continue
    const original = baseline[item.lineNo]
    const factor = parseFactor(decision.factor)
    const changedProduct = decision.productId !== original?.productId
    const changedFactor = factor !== undefined && decision.factor.trim() !== (original?.factor ?? '').trim()
    if (changedProduct || changedFactor) {
      result.push({ lineNo: item.lineNo, productId: decision.productId, ...(factor === undefined ? {} : { unitConversionFactor: factor }) })
    }
  }
  return result
}

export function NfeImportPage() {
  const navigate = useNavigate()
  const client = useQueryClient()
  const canImport = useCan(PURCHASE_IMPORT_XML)
  // "Comprar para esta OS" chega com ?os=<id>: a compra já nasce ligada à OS.
  const [params] = useSearchParams()

  const [xml, setXml] = useState('')
  const [fileName, setFileName] = useState('')
  // Prévia do arquivo como o servidor a leu; a exibida (`preview`) incorpora as escolhas do operador.
  const [initial, setInitial] = useState<NfePreview | null>(null)
  const [baseline, setBaseline] = useState<Record<number, Decision>>({})
  const [decisions, setDecisions] = useState<Record<number, Decision>>({})
  const [categoryId, setCategoryId] = useState('')
  const [notes, setNotes] = useState('')
  const [createSupplier, setCreateSupplier] = useState(true)
  const [count, setCount] = useState('1')
  const [firstDueDate, setFirstDueDate] = useState('')
  const [interval, setInterval] = useState('30')
  const [error, setError] = useState('')
  const [workOrderId, setWorkOrderId] = useState(params.get('os') ?? '')
  // DR-0023: pedido aprovado que esta nota atende, e a justificativa quando a nota difere dele.
  const [orderId, setOrderId] = useState('')
  const [justification, setJustification] = useState('')
  // A mesma intenção de confirmar usa a mesma chave, inclusive depois de uma falha de rede: é isso que impede um
  // retry de criar duas compras e duas vezes as contas a pagar. A chave só muda com um arquivo novo ou após o sucesso.
  const confirmKey = useRef(newIdempotencyKey())

  const categories = useQuery({ queryKey: financeKeys.categories, queryFn: financeApi.categories })
  const activeCategories = useMemo(() => (categories.data ?? []).filter(c => c.active), [categories.data])
  const catalog = useQuery({ queryKey: queryKeys.products, queryFn: productsApi.list, enabled: initial != null })
  const activeProducts = useMemo(() => (catalog.data ?? []).filter(product => product.active), [catalog.data])

  // Escolhas do operador que diferem do que o servidor já resolveu. A reprévia roda quando elas assentam (debounce),
  // e é somente leitura. Enquanto não chega, custo, quantidade e fator mostrados estão defasados e a confirmação fica travada.
  const selections = useMemo(() => selectionsOf(initial, decisions, baseline), [initial, decisions, baseline])
  const selectionKey = JSON.stringify(selections)
  const settledKey = useDebouncedValue(selectionKey, 350)
  const refined = useQuery({
    queryKey: [...purchaseKeys.purchases, 'preview', initial?.xmlSha256, settledKey],
    queryFn: () => purchaseApi.previewWith(xml, JSON.parse(settledKey) as PreviewSelection[]),
    enabled: initial != null && settledKey !== '[]',
    placeholderData: keepPreviousData,
  })
  const preview = settledKey === '[]' ? initial : (refined.data ?? initial)
  const stale = selectionKey !== settledKey || refined.isFetching

  const workOrders = useQuery({ queryKey: queryKeys.workOrders, queryFn: () => workOrdersApi.list(), enabled: initial != null })
  const supplierId = initial?.supplier.supplierId ?? null
  const linkable = useQuery({
    queryKey: purchaseKeys.linkableOrders(supplierId ?? ''),
    queryFn: () => purchaseApi.linkableOrders(supplierId as string),
    enabled: supplierId != null,
  })
  // A comparação só faz sentido com todos os itens resolvidos: é com eles que o servidor monta a compra da nota.
  const resolved = useMemo(() => (preview?.items ?? []).map(item => {
    const decision = decisions[item.lineNo]
    const factor = decision ? parseFactor(decision.factor) : undefined
    return { lineNo: item.lineNo, productId: decision?.productId ?? '', ...(factor === undefined ? {} : { unitConversionFactor: factor }) }
  }), [preview, decisions])
  const allResolved = resolved.length > 0 && resolved.every(line => line.productId)
    && (preview?.items ?? []).every(item => !item.needsFactor || Boolean(decisions[item.lineNo]?.factor.trim()))
  const resolvedNow = JSON.stringify(resolved)
  const resolvedKey = useDebouncedValue(resolvedNow, 350)
  // Só compara quando as escolhas assentaram, e mantém a comparação anterior na tela enquanto a nova chega: sem isso
  // a consulta era refeita com uma chave defasada e o campo de justificativa era remontado, perdendo o que o operador
  // já tinha digitado.
  const comparison = useQuery({
    queryKey: [...purchaseKeys.purchases, 'order-comparison', initial?.xmlSha256, orderId, resolvedKey],
    queryFn: () => purchaseApi.orderComparison(xml, JSON.parse(resolvedKey) as PreviewSelection[], orderId),
    enabled: Boolean(orderId) && allResolved && !stale && resolvedKey === resolvedNow,
    placeholderData: keepPreviousData,
    retry: false,
  })
  const differences = comparison.data?.differences ?? []

  const previewMutation = useMutation({
    mutationFn: (content: string) => purchaseApi.preview(content),
    onSuccess: result => {
      // Pré-carrega o que o backend já resolveu: vínculo confirmado ou GTIN. Nada por descrição (D6).
      const preloaded: Record<number, Decision> = {}
      result.items.forEach(item => {
        preloaded[item.lineNo] = {
          productId: item.productId ?? '',
          factor: item.unitConversionFactor != null ? String(item.unitConversionFactor) : '',
          saveLink: Boolean(item.supplierCode),
        }
      })
      setInitial(result)
      setBaseline(preloaded)
      setDecisions(preloaded)
      setError('')
    },
    onError: (failure: Error) => { setInitial(null); setError(failure.message) },
  })

  const confirmMutation = useMutation({
    mutationFn: () => {
      const items: ItemDecision[] = (preview?.items ?? []).map(item => {
        const decision = decisions[item.lineNo]
        const factor = decision ? parseFactor(decision.factor) : undefined
        return {
          lineNo: item.lineNo,
          productId: decision?.productId ?? '',
          ...(factor === undefined ? {} : { unitConversionFactor: factor }),
          saveLink: Boolean(decision?.saveLink),
        }
      })
      return purchaseApi.confirm({
        xml, createSupplier, expenseCategoryId: categoryId, decisions: items,
        ...(preview?.installmentsFromInvoice ? {} : {
          installments: { count: Number(count) || 1, firstDueDate, intervalDays: Number(interval) || 30 },
        }),
        notes: notes.trim() || null,
        workOrderId: workOrderId || null,
        ...(orderId ? { orderId, divergenceJustification: justification.trim() || null } : {}),
      }, confirmKey.current)
    },
    onSuccess: result => {
      confirmKey.current = newIdempotencyKey()
      client.invalidateQueries({ queryKey: purchaseKeys.purchases })
      navigate(`/compras/notas/${result.purchaseId}`)
    },
    onError: (failure: Error) => setError(failure.message),
  })

  function readFile(file: File | undefined) {
    if (!file) return
    setFileName(file.name)
    confirmKey.current = newIdempotencyKey()
    const reader = new FileReader()
    reader.onload = () => {
      const content = String(reader.result ?? '')
      setXml(content)
      previewMutation.mutate(content)
    }
    reader.onerror = () => setError('Não foi possível ler o arquivo escolhido.')
    reader.readAsText(file, 'UTF-8')
  }

  const unresolved = (preview?.items ?? []).filter(item => {
    const decision = decisions[item.lineNo]
    if (!decision?.productId) return true
    // D7: unidade que não equivale à do catálogo só confirma com fator informado.
    return item.needsFactor && !decision.factor.trim()
  })
  const missingTerms = preview != null && !preview.installmentsFromInvoice && !firstDueDate
  const serverBlockers = (preview?.blockers ?? []).filter(blocker => !blocker.startsWith(TERMS_HINT))
  const blocked = preview == null || stale || preview.recipientCheck !== 'AUTHORIZED'
    || preview.alreadyImportedPurchaseId != null
    || preview.issues.some(issue => issue.severity === 'BLOCKING')
    || serverBlockers.length > 0 || unresolved.length > 0 || !categoryId || missingTerms
    // DR-0023 C2-A: com pedido escolhido, a diferença precisa ter sido vista e justificada antes de confirmar.
    || (Boolean(orderId) && (!comparison.data || comparison.isFetching || comparison.isPlaceholderData
      || comparison.data.orderId !== orderId || resolvedKey !== resolvedNow
      || (differences.length > 0 && (!comparison.data.mayAccept || justification.trim().length < 3))))

  if (!canImport) {
    return <>
      <PageHeader title="Importar NF-e" subtitle="Importação de nota fiscal de compra." />
      <div role="note" className="notice">Você não tem permissão para importar NF-e de compra.</div>
    </>
  }

  return <>
    <PageHeader title="Importar NF-e de compra"
                subtitle="A prévia não grava nada. A compra, o documento e as contas a pagar só nascem na confirmação." />

    <section className="card">
      <label className="field">
        <span>Arquivo XML da NF-e</span>
        <input type="file" accept=".xml,text/xml,application/xml" className="input" aria-label="Arquivo XML da NF-e"
               onChange={e => readFile(e.target.files?.[0])} />
      </label>
      {fileName && <p className="muted">Arquivo: {fileName}</p>}
      {previewMutation.isPending && <p className="muted">Lendo a nota…</p>}
      {(error || refined.error) && <p className="error" role="alert">{error || (refined.error as Error).message}</p>}
    </section>

    {preview && <>
      <section className="card" aria-label="Nota">
        <h2>Nota</h2>
        <dl className="detail-grid">
          <div><dt>Número / série</dt><dd>{preview.invoiceNumber ?? '—'} / {preview.invoiceSeries ?? '—'}</dd></div>
          <div><dt>Emissão</dt><dd>{preview.issuedOn ? formatDate(preview.issuedOn) : '—'}</dd></div>
          <div><dt>Emitente</dt><dd>{preview.issuerName ?? '—'}<br />{preview.issuerDocument ? formatTaxId(preview.issuerDocument) : ''}</dd></div>
          <div><dt>Destinatário</dt><dd>{preview.recipientDocument ? formatTaxId(preview.recipientDocument) : '—'}</dd></div>
          <div><dt>Total da nota</dt><dd>{formatBrl(preview.totals.invoice)}</dd></div>
          <div><dt>Chave de acesso</dt><dd className="mono">{preview.accessKey ?? '—'}</dd></div>
        </dl>
        <p className={preview.recipientCheck === 'AUTHORIZED' ? 'muted' : 'error'} role={preview.recipientCheck === 'AUTHORIZED' ? undefined : 'alert'}>
          {RECIPIENT_LABEL[preview.recipientCheck]}
        </p>
        {!preview.officialCheckPerformed &&
          <p className="muted">A autorização e a situação são as <strong>declaradas no XML</strong>. A consulta aos
            serviços oficiais da SEFAZ e a validade criptográfica da assinatura não foram verificadas.</p>}
        {preview.alreadyImportedPurchaseId &&
          <p className="error" role="alert">Esta nota já foi importada.{' '}
            <Link to={`/compras/notas/${preview.alreadyImportedPurchaseId}`}>Abrir a compra existente</Link></p>}
        {!preview.totals.closes &&
          <p className="error" role="alert">Os itens e as despesas da nota não somam o total informado nela.</p>}
        {preview.issues.filter(issue => issue.severity === 'BLOCKING').map(issue =>
          <p key={`${issue.code}-${issue.itemNumber ?? 0}`} className="error" role="alert">{issue.message}</p>)}
      </section>

      <section className="card" aria-label="Fornecedor">
        <h2>Fornecedor</h2>
        {preview.supplier.supplierId
          ? <p>{preview.supplier.legalName} — {preview.supplier.document ? formatTaxId(preview.supplier.document) : ''}
              {preview.supplier.relatedParty && <> <Badge tone="info">Empresa relacionada</Badge></>}
              {preview.supplier.inactive && <> <Badge tone="warning">Inativo</Badge></>}</p>
          : <>
              <p>O emitente <strong>{preview.supplier.legalName}</strong>{' '}
                ({preview.supplier.document ? formatTaxId(preview.supplier.document) : '—'}) ainda não está cadastrado.</p>
              <label className="check">
                <input type="checkbox" checked={createSupplier} onChange={e => setCreateSupplier(e.target.checked)} />
                <span>Cadastrar o fornecedor a partir dos dados do emitente</span>
              </label>
            </>}
      </section>

      <section className="card" aria-label="Itens da nota">
        <h2>Itens</h2>
        <p className="muted">Cada item precisa de um produto do catálogo. Sugestão por descrição é apenas lista:
          a escolha é sua, e o custo que entra no estoque aparece assim que o produto e o fator estão definidos.
          Unidade que não equivale à do catálogo exige fator de conversão (por exemplo, 1 CX = 12 UN).</p>
        {stale && <p role="status" className="muted">Atualizando custos…</p>}
        <div className="table-wrap"><table className="table">
          <thead><tr>
            <th>#</th><th>Descrição na nota</th><th>Un.</th><th>Qtd.</th><th>Produto</th>
            <th>Fator</th><th>Qtd. no estoque</th><th>Custo unit.</th><th>Guardar vínculo</th>
          </tr></thead>
          <tbody>
            {preview.items.map(item => <ItemRow key={item.lineNo} item={item}
              decision={decisions[item.lineNo]} catalog={activeProducts}
              onChange={next => setDecisions(current => ({ ...current, [item.lineNo]: next }))} />)}
          </tbody>
        </table></div>
      </section>

      <section className="card" aria-label="Pedido e OS">
        <h2>Pedido e ordem de serviço</h2>
        <div className="form-grid">
          <label className="field"><span>OS vinculada (opcional)</span>
            <select className="select" aria-label="OS vinculada" value={workOrderId} onChange={e => setWorkOrderId(e.target.value)}>
              <option value="">Nenhuma — compra para estoque ou uso e consumo</option>
              {(workOrders.data ?? []).map(order => <option key={order.id} value={order.id}>OS #{order.number}</option>)}
            </select>
          </label>
          <label className="field"><span>Pedido que esta nota atende (opcional)</span>
            <select className="select" aria-label="Pedido que esta nota atende" value={orderId}
                    disabled={!supplierId || (linkable.data ?? []).length === 0}
                    onChange={e => {
                      const next = e.target.value
                      setOrderId(next); setJustification('')
                      // A OS do pedido vale para a nota, a menos que o operador já tenha escolhido outra.
                      const chosen = (linkable.data ?? []).find(order => order.id === next)
                      if (chosen?.workOrderId && !workOrderId) setWorkOrderId(chosen.workOrderId)
                    }}>
              <option value="">Nenhum</option>
              {(linkable.data ?? []).map(order => <option key={order.id} value={order.id}>
                {order.origin === 'DIRECT' ? 'Compra direta' : 'Pedido'} #{order.number} — {formatBrl(order.payableTotal)} ({order.itemCount} item(ns))
              </option>)}
            </select>
          </label>
        </div>
        {!supplierId && <p className="muted">O emitente ainda não é fornecedor cadastrado: não há pedido dele para vincular.</p>}
        {supplierId && linkable.data?.length === 0 &&
          <p className="muted">Este fornecedor não tem pedido aprovado aguardando nota.</p>}
        {orderId && <>
          <p className="muted">Ao confirmar, o pedido fica <strong>encerrado por esta nota</strong>: a mercadoria entra
            uma única vez, pelo recebimento da compra da nota, ao custo real.</p>
          {!allResolved && <p className="muted">Escolha o produto de todos os itens para comparar a nota com o pedido.</p>}
          {comparison.error && <p className="error" role="alert">{(comparison.error as Error).message}</p>}
          {comparison.data && differences.length === 0 &&
            <p role="status">A nota confere com o pedido #{comparison.data.orderNumber}: mesmos itens, quantidades, preços e total.</p>}
          {comparison.data && differences.length > 0 && <>
            <p className="error" role="alert">A nota difere do pedido #{comparison.data.orderNumber} em {differences.length} ponto(s).
              Vincular exige justificativa.</p>
            <div className="table-wrap"><table className="table" aria-label="Diferenças entre o pedido e a nota">
              <thead><tr><th>Diferença</th><th>Item</th><th>No pedido</th><th>Na nota</th></tr></thead>
              <tbody>{differences.map((difference, index) => <tr key={index}>
                <td>{DIFFERENCE_LABEL[difference.kind]}</td>
                <td>{difference.kind === 'TOTAL' ? '—' : difference.description ?? '—'}</td>
                <td>{differenceValue(difference.kind, difference.orderValue)}</td>
                <td>{differenceValue(difference.kind, difference.invoiceValue)}</td>
              </tr>)}</tbody>
            </table></div>
            {comparison.data.mayAccept
              ? <label className="field full"><span>Justificativa da divergência *</span>
                  <input className="input" maxLength={500} aria-label="Justificativa da divergência" value={justification}
                         onChange={e => setJustification(e.target.value)} /></label>
              : <p className="error" role="alert">Você não tem permissão para aceitar divergência entre pedido e nota.</p>}
          </>}
        </>}
      </section>

      <section className="card" aria-label="Parcelas">
        <h2>Parcelas</h2>
        {preview.installmentsFromInvoice
          ? <>
              <p className="muted">Vêm das duplicatas da nota e não são recalculadas.</p>
              <table className="table">
                <thead><tr><th>Parcela</th><th>Duplicata</th><th>Vencimento</th><th>Valor</th></tr></thead>
                <tbody>{preview.installments.map(installment => <tr key={installment.number}>
                  <td>{installment.number}</td><td>{installment.reference ?? '—'}</td>
                  <td>{formatDate(installment.dueDate)}</td><td>{formatBrl(installment.amount)}</td>
                </tr>)}</tbody>
                <tfoot><tr><th colSpan={3}>Soma</th>
                  <th>{formatBrl(preview.installments.reduce((total, i) => total + i.amount, 0))}</th></tr></tfoot>
              </table>
            </>
          : <>
              <p className="muted">A nota não traz duplicata. Nada é presumido: informe o vencimento e as condições.</p>
              <div className="form-grid">
                <label className="field"><span>Parcelas</span>
                  <input className="input" type="number" min={1} value={count} onChange={e => setCount(e.target.value)} /></label>
                <label className="field"><span>Primeiro vencimento</span>
                  <input className="input" type="date" value={firstDueDate} onChange={e => setFirstDueDate(e.target.value)} /></label>
                <label className="field"><span>Intervalo (dias)</span>
                  <input className="input" type="number" min={1} value={interval} onChange={e => setInterval(e.target.value)} /></label>
              </div>
            </>}
      </section>

      <section className="card" aria-label="Confirmação">
        <h2>Confirmação</h2>
        <div className="form-grid">
          <label className="field"><span>Categoria de despesa</span>
            <select className="select" value={categoryId} onChange={e => setCategoryId(e.target.value)}>
              <option value="">Selecione…</option>
              {activeCategories.map(category => <option key={category.id} value={category.id}>{category.name}</option>)}
            </select>
          </label>
          <label className="field"><span>Observação</span>
            <input className="input" maxLength={1000} value={notes} onChange={e => setNotes(e.target.value)} /></label>
        </div>
        {serverBlockers.length > 0 && <ul className="error" role="alert">
          {serverBlockers.map(blocker => <li key={blocker}>{blocker}</li>)}
        </ul>}
        {unresolved.length > 0 && <p className="muted">
          Faltam {unresolved.length} item(ns): escolha o produto e, quando a unidade exigir, informe o fator.</p>}
        {!categoryId && <p className="muted">Escolha a categoria de despesa para as contas a pagar.</p>}
        <p className="muted">Confirmar cria a compra, grava o documento fiscal e abre uma conta a pagar por
          parcela. <strong>Nenhuma conta nasce paga</strong> e o estoque só entra no recebimento.</p>
        <button className="btn" disabled={blocked || confirmMutation.isPending}
                onClick={() => { setError(''); confirmMutation.mutate() }}>
          {confirmMutation.isPending ? 'Confirmando…' : 'Confirmar importação'}
        </button>
      </section>
    </>}
  </>
}

function ItemRow({ item, decision, catalog, onChange }: {
  item: PreviewItem
  decision: Decision | undefined
  catalog: Product[]
  onChange: (next: Decision) => void
}) {
  const canCreateProduct = useCan(PRODUCT_MANAGE)
  const [search, setSearch] = useState('')
  const [creating, setCreating] = useState(false)
  const current: Decision = decision ?? { productId: '', factor: '', saveLink: Boolean(item.supplierCode) }
  const chosen = current.productId
  // Só cobra fator quando já há produto escolhido: antes disso o servidor não conhece a unidade de destino.
  const factorMissing = Boolean(chosen) && item.needsFactor && !current.factor.trim()

  const term = search.trim().toLowerCase()
  const found = term.length < 2 ? [] : catalog.filter(product =>
    product.description.toLowerCase().includes(term) || (product.internalCode ?? '').toLowerCase().includes(term)).slice(0, 20)
  const known = new Map<string, { label: string }>()
  const label = (description: string, code: string | null, unit: string) => `${description}${code ? ` · ${code}` : ''} (${unit})`
  if (item.productId) {
    const source = item.matchedBy === 'LINK' ? ' · vínculo' : item.matchedBy === 'GTIN' ? ' · GTIN' : ''
    known.set(item.productId, { label: `${item.productDescription ?? 'Produto'}${source}` })
  }
  item.candidates.forEach(c => { if (!known.has(c.productId)) known.set(c.productId, { label: label(c.description, c.internalCode, c.unit) }) })
  found.forEach(p => { if (!known.has(p.id)) known.set(p.id, { label: label(p.description, p.internalCode, p.unit) }) })
  // O produto escolhido (por busca ou cadastro assistido) tem de continuar como opção, senão o select o perderia.
  if (chosen && !known.has(chosen)) {
    const product = catalog.find(p => p.id === chosen)
    known.set(chosen, { label: product ? label(product.description, product.internalCode, product.unit) : 'Produto escolhido' })
  }

  return <>
    <tr>
      <td>{item.lineNo}</td>
      <td>{item.description}<br /><small className="muted">{item.supplierCode ?? 'sem código'}{item.gtin ? ` · GTIN ${item.gtin}` : ''}</small></td>
      <td>{item.unitOriginal ?? '—'}</td>
      <td>{item.quantityOriginal}</td>
      <td>
        <select className="select" aria-label={`Produto do item ${item.lineNo}`} value={chosen}
                onChange={e => onChange({ ...current, productId: e.target.value, factor: e.target.value === current.productId ? current.factor : '' })}>
          <option value="">Selecione…</option>
          {[...known.entries()].map(([id, option]) => <option key={id} value={id}>{option.label}</option>)}
        </select>
        <input className="input" aria-label={`Buscar produto do item ${item.lineNo}`} placeholder="Buscar no catálogo"
               value={search} onChange={e => setSearch(e.target.value)} />
        {!chosen && canCreateProduct && <button type="button" className="btn secondary"
                                                 onClick={() => setCreating(open => !open)}>Cadastrar produto</button>}
        {!chosen && !canCreateProduct && item.candidates.length === 0 &&
          <small className="muted">Nenhuma sugestão. Peça o cadastro do produto a quem gerencia o catálogo.</small>}
      </td>
      <td>
        <input className="input" aria-label={`Fator de conversão do item ${item.lineNo}`} inputMode="decimal"
               placeholder={factorMissing ? 'obrigatório' : '1'} value={current.factor}
               onChange={e => onChange({ ...current, factor: e.target.value })} />
        {factorMissing && <small className="error">
          {item.unitOriginal} não equivale a {item.productUnit ?? 'unidade do produto'}
        </small>}
      </td>
      <td>{item.convertedQuantity ?? '—'}</td>
      <td>{item.cost ? formatBrl(item.cost.unitCost) : '—'}</td>
      <td>
        <input type="checkbox" aria-label={`Guardar vínculo do item ${item.lineNo}`} checked={current.saveLink}
               disabled={!item.supplierCode}
               onChange={e => onChange({ ...current, saveLink: e.target.checked })} />
      </td>
    </tr>
    {creating && !chosen && <tr>
      <td colSpan={9}>
        <NewProductForm item={item} onCancel={() => setCreating(false)}
                        onCreated={product => { setCreating(false); onChange({ ...current, productId: product.id, factor: '' }) }} />
      </td>
    </tr>}
  </>
}

/**
 * Cadastro assistido de produto a partir da linha da nota (AG-05, brief da Fase 1). Preenche o que a nota sabe
 * (descrição e unidade equivalente) e deixa de fora o que ela não pode saber: custo de referência e preço de venda
 * ficam em branco, porque preço de venda não é custo de aquisição. GTIN e NCM são aprendidos na confirmação.
 */
function NewProductForm({ item, onCancel, onCreated }: {
  item: PreviewItem
  onCancel: () => void
  onCreated: (product: Product) => void
}) {
  const client = useQueryClient()
  const [description, setDescription] = useState(item.description.slice(0, 200))
  const [unit, setUnit] = useState<ProductUnit>(suggestCatalogUnit(item.unitOriginal))
  const [type, setType] = useState<ProductType>('PART')
  const [internalCode, setInternalCode] = useState('')
  const [error, setError] = useState('')
  const mutation = useMutation({
    mutationFn: () => productsApi.create({
      description: description.trim(), internalCode: internalCode.trim() || null, category: null, type, unit,
      referenceCost: null, salePrice: null, minimumStock: null,
    }),
    onSuccess: async product => {
      await client.invalidateQueries({ queryKey: queryKeys.products })
      onCreated(product)
    },
    onError: (failure: Error) => setError(failure.message),
  })
  return <div className="notice" role="group" aria-label={`Cadastrar produto do item ${item.lineNo}`}>
    {error && <div role="alert" className="notice error">{error}</div>}
    <div className="form-grid">
      <label className="field full"><span>Descrição do produto *</span>
        <input className="input" value={description} onChange={e => setDescription(e.target.value)} /></label>
      <label className="field"><span>Unidade no catálogo *</span>
        <select className="select" value={unit} onChange={e => setUnit(e.target.value as ProductUnit)}>
          {(Object.keys(PRODUCT_UNIT_LABELS) as ProductUnit[]).map(code =>
            <option key={code} value={code}>{PRODUCT_UNIT_LABELS[code]}</option>)}
        </select></label>
      <label className="field"><span>Tipo *</span>
        <select className="select" value={type} onChange={e => setType(e.target.value as ProductType)}>
          {(Object.keys(PRODUCT_TYPE_LABELS) as ProductType[]).map(code =>
            <option key={code} value={code}>{PRODUCT_TYPE_LABELS[code]}</option>)}
        </select></label>
      <label className="field"><span>Código interno (opcional)</span>
        <input className="input" value={internalCode} onChange={e => setInternalCode(e.target.value.toUpperCase())} /></label>
    </div>
    <p className="muted">Custo de referência e preço de venda ficam em branco: preço de venda não é o custo de aquisição.
      A unidade da nota ({item.unitOriginal ?? '—'}) pode exigir fator de conversão depois de criado.</p>
    <div className="form-actions">
      <button type="button" className="btn secondary" disabled={mutation.isPending} onClick={onCancel}>Cancelar</button>
      <button type="button" className="btn" disabled={description.trim().length < 2 || mutation.isPending}
              onClick={() => { setError(''); mutation.mutate() }}>Cadastrar produto</button>
    </div>
  </div>
}
