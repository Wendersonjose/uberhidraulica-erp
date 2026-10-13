import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  PURCHASE_IMPORT_XML, purchaseApi, purchaseKeys,
  type ItemDecision, type NfePreview, type PreviewItem,
} from '../../api/purchasing'
import { financeApi, financeKeys } from '../../api/finance'
import { Badge, PageHeader } from '../../components/ui'
import { useCan } from '../finance/hooks'
import { formatBrl, formatDate } from '../../utils/format'
import { formatTaxId } from '../../utils/taxId'

/** Decisão do operador por linha da nota, antes de confirmar. */
type Decision = { productId: string; factor: string; saveLink: boolean }

const RECIPIENT_LABEL: Record<NfePreview['recipientCheck'], string> = {
  AUTHORIZED: 'Destinatário confere com o estabelecimento autorizado',
  OWN_TAX_ID_NOT_CONFIGURED: 'CNPJ da oficina não configurado em Compras › Configurações',
  RECIPIENT_MISMATCH: 'Nota destinada a outro CNPJ',
  RECIPIENT_MISSING: 'Nota sem CNPJ de destinatário legível',
}

export function NfeImportPage() {
  const navigate = useNavigate()
  const client = useQueryClient()
  const canImport = useCan(PURCHASE_IMPORT_XML)

  const [xml, setXml] = useState('')
  const [fileName, setFileName] = useState('')
  const [preview, setPreview] = useState<NfePreview | null>(null)
  const [decisions, setDecisions] = useState<Record<number, Decision>>({})
  const [categoryId, setCategoryId] = useState('')
  const [notes, setNotes] = useState('')
  const [createSupplier, setCreateSupplier] = useState(true)
  const [count, setCount] = useState('1')
  const [firstDueDate, setFirstDueDate] = useState('')
  const [interval, setInterval] = useState('30')
  const [error, setError] = useState('')

  const categories = useQuery({ queryKey: financeKeys.categories, queryFn: financeApi.categories })
  const activeCategories = useMemo(() => (categories.data ?? []).filter(c => c.active), [categories.data])

  const previewMutation = useMutation({
    mutationFn: (content: string) => purchaseApi.preview(content),
    onSuccess: result => {
      setPreview(result)
      // Pré-carrega o que o backend já resolveu: vínculo confirmado ou GTIN. Nada por descrição (D6).
      const initial: Record<number, Decision> = {}
      result.items.forEach(item => {
        initial[item.lineNo] = {
          productId: item.productId ?? '',
          factor: item.unitConversionFactor != null ? String(item.unitConversionFactor) : '',
          saveLink: Boolean(item.supplierCode),
        }
      })
      setDecisions(initial)
      setError('')
    },
    onError: (failure: Error) => { setPreview(null); setError(failure.message) },
  })

  const confirmMutation = useMutation({
    mutationFn: () => {
      const items: ItemDecision[] = (preview?.items ?? []).map(item => {
        const decision = decisions[item.lineNo]
        const factor = decision?.factor.trim()
        return {
          lineNo: item.lineNo,
          productId: decision?.productId ?? '',
          ...(factor ? { unitConversionFactor: Number(factor.replace(',', '.')) } : {}),
          saveLink: Boolean(decision?.saveLink),
        }
      })
      return purchaseApi.confirm({
        xml, createSupplier, expenseCategoryId: categoryId, decisions: items,
        ...(preview?.installmentsFromInvoice ? {} : {
          installments: { count: Number(count) || 1, firstDueDate, intervalDays: Number(interval) || 30 },
        }),
        notes: notes.trim() || null,
        // Chave por tentativa de confirmação: um retry de rede reaproveita a mesma e não duplica a compra.
      }, crypto.randomUUID())
    },
    onSuccess: result => {
      client.invalidateQueries({ queryKey: purchaseKeys.purchases })
      navigate(`/compras/notas/${result.purchaseId}`)
    },
    onError: (failure: Error) => setError(failure.message),
  })

  function readFile(file: File | undefined) {
    if (!file) return
    setFileName(file.name)
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
  const blocked = preview == null || preview.recipientCheck !== 'AUTHORIZED'
    || preview.alreadyImportedPurchaseId != null
    || preview.issues.some(issue => issue.severity === 'BLOCKING')
    || unresolved.length > 0 || !categoryId || missingTerms

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
        <input type="file" accept=".xml,text/xml,application/xml" className="input"
               onChange={e => readFile(e.target.files?.[0])} />
      </label>
      {fileName && <p className="muted">Arquivo: {fileName}</p>}
      {previewMutation.isPending && <p className="muted">Lendo a nota…</p>}
      {error && <p className="error" role="alert">{error}</p>}
    </section>

    {preview && <>
      <section className="card">
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
            <a href={`/compras/notas/${preview.alreadyImportedPurchaseId}`}>Abrir a compra existente</a></p>}
        {!preview.totals.closes &&
          <p className="error" role="alert">Os itens e as despesas da nota não somam o total informado nela.</p>}
      </section>

      <section className="card">
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

      <section className="card">
        <h2>Itens</h2>
        <p className="muted">Cada item precisa de um produto do catálogo. Sugestão por descrição é apenas lista:
          a escolha é sua. Unidade que não equivale à do catálogo exige fator de conversão
          (por exemplo, 1 CX = 12 UN).</p>
        <table className="table">
          <thead><tr>
            <th>#</th><th>Descrição na nota</th><th>Un.</th><th>Qtd.</th><th>Produto</th>
            <th>Fator</th><th>Qtd. no estoque</th><th>Custo unit.</th><th>Guardar vínculo</th>
          </tr></thead>
          <tbody>
            {preview.items.map(item => <ItemRow key={item.lineNo} item={item}
              decision={decisions[item.lineNo]}
              onChange={next => setDecisions(current => ({ ...current, [item.lineNo]: next }))} />)}
          </tbody>
        </table>
      </section>

      <section className="card">
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

      <section className="card">
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
        {preview.blockers.length > 0 && <ul className="error" role="alert">
          {preview.blockers.map(blocker => <li key={blocker}>{blocker}</li>)}
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

function ItemRow({ item, decision, onChange }: {
  item: PreviewItem
  decision: Decision | undefined
  onChange: (next: Decision) => void
}) {
  const current: Decision = decision ?? { productId: '', factor: '', saveLink: Boolean(item.supplierCode) }
  const needsFactor = item.needsFactor && !current.factor.trim()
  const options = item.candidates
  const chosen = current.productId
  // Quando o produto veio por vínculo ou GTIN ele não está na lista de candidatos: mostra como opção fixa.
  const resolvedLabel = item.productId && item.productId === chosen
    ? `${item.productDescription ?? 'Produto vinculado'}${item.matchedBy === 'LINK' ? ' (vínculo)' : item.matchedBy === 'GTIN' ? ' (GTIN)' : ''}`
    : null

  return <tr>
    <td>{item.lineNo}</td>
    <td>{item.description}<br /><small className="muted">{item.supplierCode ?? 'sem código'}{item.gtin ? ` · GTIN ${item.gtin}` : ''}</small></td>
    <td>{item.unitOriginal ?? '—'}</td>
    <td>{item.quantityOriginal}</td>
    <td>
      <select className="select" aria-label={`Produto do item ${item.lineNo}`} value={chosen}
              onChange={e => onChange({ ...current, productId: e.target.value })}>
        <option value="">Selecione…</option>
        {resolvedLabel && <option value={item.productId ?? ''}>{resolvedLabel}</option>}
        {options.filter(option => option.productId !== item.productId).map(option =>
          <option key={option.productId} value={option.productId}>
            {option.description}{option.internalCode ? ` · ${option.internalCode}` : ''} ({option.unit})
          </option>)}
      </select>
      {item.matchedBy === 'NONE' && options.length === 0 &&
        <small className="muted">Nenhuma sugestão. Cadastre o produto ou escolha em Produtos.</small>}
    </td>
    <td>
      <input className="input" aria-label={`Fator de conversão do item ${item.lineNo}`} inputMode="decimal"
             placeholder={item.needsFactor ? 'obrigatório' : '1'} value={current.factor}
             onChange={e => onChange({ ...current, factor: e.target.value })} />
      {needsFactor && <small className="error">
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
}
