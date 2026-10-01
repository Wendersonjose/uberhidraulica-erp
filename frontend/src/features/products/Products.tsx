import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { productsApi } from '../../api/resources'
import { queryKeys } from '../../api/queryKeys'
import { PRODUCT_TYPE_LABELS, PRODUCT_UNIT_LABELS, type Product } from '../../api/types'
import { Badge, Field, PageHeader, State } from '../../components/ui'
import { QueryState } from '../../components/QueryState'
import { formatBrl, formatQuantity, quantityFitsUnit, quantityStep } from '../../utils/format'

export function ProductsPage() {
  const query = useQuery({ queryKey: queryKeys.products, queryFn: productsApi.list })
  return <>
    <PageHeader title="Produtos" subtitle="Catálogo de peças, fluidos e insumos utilizados pela oficina.">
      <Link className="btn" to="/produtos/novo">Novo produto</Link>
    </PageHeader>
    <State loading={query.isLoading} error={query.error} empty={!query.data?.length}>
      <div className="card table-wrap">
        <table className="table">
          <thead><tr>
            <th>Produto</th><th>Tipo</th><th>Unidade</th><th>Custo ref.</th><th>Preço de venda</th>
            <th>Estoque mínimo</th><th>Status</th><th>Ações</th>
          </tr></thead>
          <tbody>{query.data?.map(product => <tr key={product.id}>
            <td>
              <strong>{product.description}</strong>
              <div className="muted">{[product.internalCode, product.category].filter(Boolean).join(' • ') || '—'}</div>
            </td>
            <td>{PRODUCT_TYPE_LABELS[product.type]}</td>
            <td>{PRODUCT_UNIT_LABELS[product.unit]}</td>
            <td>{product.referenceCost === null ? '—' : formatBrl(product.referenceCost)}</td>
            <td>{product.salePrice === null ? '—' : formatBrl(product.salePrice)}</td>
            <td>{product.minimumStock === null ? '—' : formatQuantity(product.minimumStock, product.unit)}</td>
            <td><Badge tone={product.active ? 'success' : 'warning'}>{product.active ? 'Ativo' : 'Inativo'}</Badge></td>
            <td><Link className="btn secondary" to={`/produtos/${product.id}`}>Editar</Link></td>
          </tr>)}</tbody>
        </table>
      </div>
    </State>
  </>
}

const optionalAmount = z.union([z.literal(''), z.coerce.number().nonnegative('Valor não pode ser negativo')])
  .transform(value => (value === '' ? null : value))

const schema = z.object({
  description: z.string().trim().min(2, 'Informe a descrição'),
  internalCode: z.string().trim(),
  category: z.string().trim(),
  type: z.enum(['PART', 'SUPPLY', 'COMPONENT', 'KIT', 'INTERNAL_USE_MATERIAL']),
  unit: z.enum(['UNIDADE', 'LITRO', 'METRO', 'QUILOGRAMA', 'GALAO_5L', 'BALDE_20L']),
  referenceCost: optionalAmount,
  salePrice: optionalAmount,
  minimumStock: optionalAmount,
  active: z.enum(['true', 'false']),
}).refine(values => values.minimumStock === null || quantityFitsUnit(String(values.minimumStock), values.unit), {
  path: ['minimumStock'], message: 'Estoque mínimo deve respeitar a precisão da unidade',
})
type Form = z.infer<typeof schema>

const emptyForm = {
  description: '', internalCode: '', category: '', type: 'PART', unit: 'UNIDADE',
  referenceCost: '', salePrice: '', minimumStock: '', active: 'true',
} satisfies z.input<typeof schema>

/** A criação não envia `active`: o backend define que todo produto nasce ativo. */
const createPayload = (values: Form) => ({
  description: values.description,
  internalCode: values.internalCode || null,
  category: values.category || null,
  type: values.type,
  unit: values.unit,
  referenceCost: values.referenceCost,
  salePrice: values.salePrice,
  minimumStock: values.minimumStock,
})

export function ProductFormPage() {
  const { id } = useParams()
  return id ? <ProductEditPage id={id} /> : <ProductForm title="Cadastrar produto" defaults={emptyForm} />
}

function ProductEditPage({ id }: { id: string }) {
  const query = useQuery({ queryKey: queryKeys.product(id), queryFn: () => productsApi.get(id) })
  return <>
    <PageHeader title="Editar produto" subtitle="Alterações no catálogo não modificam registros históricos já emitidos." />
    <QueryState loading={query.isLoading} error={query.error} label="o produto" retry={query.refetch}>
      {query.data && <ProductForm title="" productId={id} defaults={toFormValues(query.data)} />}
    </QueryState>
  </>
}

const toFormValues = (product: Product): z.input<typeof schema> => ({
  description: product.description,
  internalCode: product.internalCode ?? '',
  category: product.category ?? '',
  type: product.type,
  unit: product.unit,
  referenceCost: product.referenceCost ?? '',
  salePrice: product.salePrice ?? '',
  minimumStock: product.minimumStock ?? '',
  active: product.active ? 'true' : 'false',
})

function ProductForm({ title, productId, defaults }: {
  title: string; productId?: string; defaults: z.input<typeof schema>
}) {
  const navigate = useNavigate()
  const client = useQueryClient()
  const [apiError, setApiError] = useState('')
  const { register, handleSubmit, control, formState: { errors, isSubmitting } } =
    useForm<z.input<typeof schema>, unknown, Form>({ resolver: zodResolver(schema), defaultValues: defaults })

  const unit = useWatch({ control, name: 'unit' })

  const mutation = useMutation({
    mutationFn: (values: Form) => productId
      ? productsApi.update(productId, { ...createPayload(values), active: values.active === 'true' })
      : productsApi.create(createPayload(values)),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: queryKeys.products })
      if (productId) await client.invalidateQueries({ queryKey: queryKeys.product(productId) })
      navigate('/produtos')
    },
  })

  return <>
    {title && <PageHeader title={title} subtitle="Descrição e unidade são obrigatórias; valores comerciais são opcionais." />}
    <form className="card" onSubmit={handleSubmit(async values => {
      setApiError('')
      try { await mutation.mutateAsync(values) }
      catch (error) { setApiError(error instanceof Error ? error.message : 'Falha ao salvar o produto') }
    })}>
      {apiError && <div role="alert" className="notice error">{apiError}</div>}
      <div className="form-grid">
        <Field label="Descrição *" error={errors.description?.message} className="full">
          <input className="input" {...register('description')} />
        </Field>
        <Field label="Código interno" error={errors.internalCode?.message}>
          <input className="input" {...register('internalCode')} />
        </Field>
        <Field label="Categoria"><input className="input" {...register('category')} /></Field>
        <Field label="Tipo *" error={errors.type?.message}>
          <select className="select" {...register('type')}>
            {Object.entries(PRODUCT_TYPE_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </Field>
        <Field label="Unidade *" error={errors.unit?.message}>
          <select className="select" {...register('unit')}>
            {Object.entries(PRODUCT_UNIT_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </Field>
        <Field label="Custo de referência" error={errors.referenceCost?.message}>
          <input className="input" type="number" step="0.01" min="0" {...register('referenceCost')} />
        </Field>
        <Field label="Preço de venda" error={errors.salePrice?.message}>
          <input className="input" type="number" step="0.01" min="0" {...register('salePrice')} />
        </Field>
        <Field label="Estoque mínimo" error={errors.minimumStock?.message}>
          <input className="input" type="number" step={quantityStep(unit)} min="0" {...register('minimumStock')} />
        </Field>
        {productId && <Field label="Status">
          <select className="select" {...register('active')}>
            <option value="true">Ativo</option>
            <option value="false">Inativo</option>
          </select>
        </Field>}
      </div>
      <div className="form-actions">
        <button type="button" className="btn secondary" onClick={() => navigate('/produtos')}>Cancelar</button>
        <button className="btn" disabled={isSubmitting || mutation.isPending}>Salvar produto</button>
      </div>
    </form>
  </>
}
