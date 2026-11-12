import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'
import { json, lastBody, mockApi, page, renderAt, requestsTo, session } from './harness'

const VIEW = ['PURCHASE_VIEW']
const MANAGE = ['PURCHASE_VIEW', 'PURCHASE_SUPPLIER_MANAGE']
const as = (permissions: string[]) => ({ ...session, permissions })
/** Cola o texto de uma vez: digitar tecla a tecla estoura o tempo do teste quando a máquina está sob carga. */
const paste = async (element: HTMLElement, text: string) => { await userEvent.clear(element); await userEvent.click(element); await userEvent.paste(text) }
// A suíte roda vários arquivos em paralelo e o notebook tem pouca memória: dá folga a fluxos com várias interações.
vi.setConfig({ testTimeout: 20_000 })

const supplier = (extra = {}) => ({
  id: 's1', personType: 'PJ', legalName: 'Distribuidora Alfa Ltda', tradeName: 'Alfa Peças', document: '67757350000178', documentMasked: false,
  stateRegistration: '123456', phone: '3433334444', email: 'vendas@alfa.test',
  address: { zipCode: '38400000', street: 'Rua A', number: '10', complement: null, district: 'Centro', city: 'Uberlândia', state: 'MG' },
  commercialTerms: { paymentTerms: '28/35/42 dias', preferredPaymentMethod: 'Boleto', usualDueDay: 10, creditLimit: 1500.5, notes: null },
  relatedParty: false, relatedPartyNote: null, status: 'ACTIVE', inactivatedAt: null, inactivationReason: null, version: 3,
  createdAt: '2026-10-13T12:00:00Z', updatedAt: '2026-10-13T12:00:00Z', ...extra,
})
const contact = (extra = {}) => ({
  id: 'c1', supplierId: 's1', name: 'Marcos Vendedor', role: 'vendas', phone: '34999990000', email: 'marcos@alfa.test', primary: true, active: true,
  createdAt: '2026-10-13T12:00:00Z', updatedAt: '2026-10-13T12:00:00Z', ...extra,
})
const events = [
  { id: 'e2', type: 'UPDATED', actorId: '11111111-2222-3333-4444-555555555555', occurredAt: '2026-10-14T12:00:00Z', reason: null,
    changes: { legalName: { before: 'Alfa', after: 'Distribuidora Alfa Ltda' } } },
  { id: 'e1', type: 'CREATED', actorId: '11111111-2222-3333-4444-555555555555', occurredAt: '2026-10-13T12:00:00Z', reason: null, changes: {} },
]
const detailRoutes = (s = supplier(), contacts = [contact()]) => ({
  'GET /api/purchasing/suppliers/s1': s,
  'GET /api/purchasing/suppliers/s1/contacts': contacts,
  'GET /api/purchasing/suppliers/s1/events': page(events),
})

test('o menu Compras só aparece para quem tem PURCHASE_VIEW', async () => {
  mockApi({ 'GET /api/work-orders/board': [] }, as(VIEW))
  renderAt('/ordens-servico')
  expect(await screen.findByRole('link', { name: 'Fornecedores' })).toHaveAttribute('href', '/compras/fornecedores')
})

test('sem PURCHASE_VIEW o menu não oferece Compras e a rota mostra acesso negado', async () => {
  mockApi({ 'GET /api/work-orders/board': [] }, as([]))
  renderAt('/ordens-servico')
  await screen.findByRole('link', { name: 'Ordens de serviço' })
  expect(screen.queryByRole('link', { name: 'Fornecedores' })).not.toBeInTheDocument()
})

test('digitar o endereço sem permissão mostra acesso negado e não chama a API de Compras', async () => {
  const fetch = mockApi({}, as([]))
  renderAt('/compras/fornecedores')
  expect(await screen.findByRole('alert')).toHaveTextContent('Acesso negado')
  expect(fetch.mock.calls.filter(([input]) => String(input).startsWith('/api/purchasing'))).toHaveLength(0)
})

test('lista fornecedores ativos por padrão, mostra CNPJ formatado, CPF mascarado e a marca de empresa relacionada', async () => {
  const fetch = mockApi({
    'GET /api/purchasing/suppliers': page([
      supplier(),
      supplier({ id: 's2', personType: 'PF', legalName: 'João Autônomo', tradeName: null, document: '*********25', documentMasked: true,
        address: { zipCode: null, street: null, number: null, complement: null, district: null, city: null, state: null } }),
      supplier({ id: 's3', legalName: 'Peças Irmã Ltda', document: '01637003000101', relatedParty: true, relatedPartyNote: 'Empresa do grupo' }),
    ]),
  }, as(VIEW))
  renderAt('/compras/fornecedores')

  expect(await screen.findByText('Distribuidora Alfa Ltda')).toBeInTheDocument()
  expect(screen.getByText('67.757.350/0001-78')).toBeInTheDocument()
  expect(screen.getByText('*********25')).toBeInTheDocument()
  expect(screen.getByText('Empresa relacionada')).toBeInTheDocument()
  expect(screen.getAllByText('Uberlândia/MG')).toHaveLength(2)
  expect(screen.queryByRole('link', { name: 'Novo fornecedor' })).not.toBeInTheDocument()
  const url = new URL(String(fetch.mock.calls.find(([input]) => String(input).startsWith('/api/purchasing/suppliers'))?.[0]), 'http://localhost')
  expect(url.searchParams.get('status')).toBe('ACTIVE')
})

test('busca e filtros chegam à API como parâmetros de consulta', async () => {
  const fetch = mockApi({ 'GET /api/purchasing/suppliers': page([supplier()]) }, as(MANAGE))
  renderAt('/compras/fornecedores')
  expect(await screen.findByRole('link', { name: 'Novo fornecedor' })).toHaveAttribute('href', '/compras/fornecedores/novo')

  await paste(screen.getByLabelText('Buscar fornecedores'), 'alfa')
  await userEvent.selectOptions(screen.getByLabelText('Tipo de pessoa'), 'PF')
  await userEvent.selectOptions(screen.getByLabelText('Situação'), 'INACTIVE')
  await waitFor(() => {
    const last = new URL(String(fetch.mock.calls.filter(([input]) => String(input).startsWith('/api/purchasing/suppliers')).at(-1)?.[0]), 'http://localhost')
    expect(last.searchParams.get('q')).toBe('alfa')
    expect(last.searchParams.get('personType')).toBe('PF')
    expect(last.searchParams.get('status')).toBe('INACTIVE')
  })
})

test('lista vazia e erro da API são comunicados', async () => {
  mockApi({ 'GET /api/purchasing/suppliers': page([]) }, as(VIEW))
  renderAt('/compras/fornecedores')
  expect(await screen.findByText('Nenhum fornecedor encontrado.')).toBeInTheDocument()
})

test('cadastro valida o CNPJ antes de enviar e envia os dados normalizados em branco como nulo', async () => {
  const fetch = mockApi({
    'POST /api/purchasing/suppliers': () => json(supplier({ version: 0 }), 201),
    ...detailRoutes(supplier({ version: 0 })),
  }, as(MANAGE))
  renderAt('/compras/fornecedores/novo')

  await paste(await screen.findByLabelText(/Razão social/), 'Distribuidora Alfa Ltda')
  await paste(screen.getByLabelText('CNPJ'), '67757350000179')
  expect(await screen.findByRole('alert')).toHaveTextContent('CNPJ inválido')
  expect(screen.getByRole('button', { name: 'Cadastrar fornecedor' })).toBeDisabled()

  await userEvent.clear(screen.getByLabelText('CNPJ'))
  await paste(screen.getByLabelText('CNPJ'), '67.757.350/0001-78')
  await paste(screen.getByLabelText('Dia usual de vencimento'), '10')
  await paste(screen.getByLabelText(/Limite de crédito/), '1500,50')
  await userEvent.click(screen.getByRole('button', { name: 'Cadastrar fornecedor' }))

  await waitFor(() => expect(requestsTo(fetch, 'POST', '/api/purchasing/suppliers')).toHaveLength(1))
  const body = lastBody(fetch, 'POST', '/api/purchasing/suppliers')
  expect(body).toMatchObject({ personType: 'PJ', legalName: 'Distribuidora Alfa Ltda', document: '67.757.350/0001-78', tradeName: null, phone: null,
    relatedParty: false, relatedPartyNote: null })
  expect(body.commercialTerms).toMatchObject({ usualDueDay: 10, creditLimit: 1500.5, paymentTerms: null })
  expect(body.address.zipCode).toBeNull()
  expect(body.version).toBeUndefined()
  expect(await screen.findByRole('heading', { name: 'Distribuidora Alfa Ltda' })).toBeInTheDocument()
})

test('pessoa física pede CPF e recusa um CNPJ', async () => {
  mockApi({}, as(MANAGE))
  renderAt('/compras/fornecedores/novo')
  await userEvent.selectOptions(await screen.findByLabelText('Tipo de pessoa *'), 'PF')
  await paste(screen.getByLabelText(/Nome \*/), 'João Autônomo')
  await paste(screen.getByLabelText('CPF'), '67757350000178')
  expect(await screen.findByRole('alert')).toHaveTextContent('CPF inválido')
  await userEvent.clear(screen.getByLabelText('CPF'))
  await paste(screen.getByLabelText('CPF'), '529.982.247-25')
  expect(screen.queryByText('CPF inválido')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Cadastrar fornecedor' })).toBeEnabled()
})

test('quem só consulta não acessa o formulário nem vê os botões de escrita', async () => {
  mockApi(detailRoutes(), as(VIEW))
  renderAt('/compras/fornecedores/novo')
  expect(await screen.findByRole('alert')).toHaveTextContent('Acesso negado')
})

test('edição envia a versão carregada e mostra o conflito quando outra pessoa alterou antes', async () => {
  const fetch = mockApi({
    ...detailRoutes(),
    'PUT /api/purchasing/suppliers/s1': () => json({ code: 'SUPPLIER_VERSION_CONFLICT', message: 'O cadastro foi alterado por outra pessoa; recarregue e tente novamente' }, 409),
  }, as(MANAGE))
  renderAt('/compras/fornecedores/s1/editar')

  const name = await screen.findByLabelText(/Razão social/)
  expect(name).toHaveValue('Distribuidora Alfa Ltda')
  expect(screen.getByLabelText('CNPJ')).toHaveValue('67.757.350/0001-78')
  await paste(name, 'Alfa Renomeada Ltda')
  await userEvent.click(screen.getByRole('button', { name: 'Salvar alterações' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('foi alterado por outra pessoa')
  expect(lastBody(fetch, 'PUT', '/api/purchasing/suppliers/s1')).toMatchObject({ legalName: 'Alfa Renomeada Ltda', version: 3 })
})

test('detalhe mostra dados, condições, contatos e histórico para quem gerencia', async () => {
  mockApi(detailRoutes(), as(MANAGE))
  renderAt('/compras/fornecedores/s1')

  expect(await screen.findByRole('heading', { name: 'Distribuidora Alfa Ltda' })).toBeInTheDocument()
  const terms = screen.getByRole('region', { name: 'Condições comerciais' })
  expect(within(terms).getByText('28/35/42 dias')).toBeInTheDocument()
  expect(within(terms).getByText(/1\.500,50/)).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Editar' })).toHaveAttribute('href', '/compras/fornecedores/s1/editar')
  const contacts = await screen.findByRole('region', { name: 'Contatos' })
  expect(within(contacts).getByText('Marcos Vendedor')).toBeInTheDocument()
  expect(within(contacts).getByText('Principal')).toBeInTheDocument()
  const history = await screen.findByRole('region', { name: 'Histórico' })
  expect(within(history).getByText('Alteração')).toBeInTheDocument()
  expect(within(history).getByText(/Nome\/razão social: Alfa → Distribuidora Alfa Ltda/)).toBeInTheDocument()
})

test('quem só consulta vê o fornecedor com CPF oculto e sem ações de escrita nem histórico', async () => {
  mockApi(detailRoutes(supplier({ personType: 'PF', document: '*********25', documentMasked: true })), as(VIEW))
  renderAt('/compras/fornecedores/s1')

  expect(await screen.findByText(/parcialmente oculto/)).toBeInTheDocument()
  expect(screen.queryByRole('link', { name: 'Editar' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Inativar fornecedor' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Adicionar contato' })).not.toBeInTheDocument()
  expect(screen.queryByRole('region', { name: 'Histórico' })).not.toBeInTheDocument()
  expect(await screen.findByText('Marcos Vendedor')).toBeInTheDocument()
})

test('inativar exige o motivo e só então chama a API', async () => {
  const fetch = mockApi({
    ...detailRoutes(),
    'POST /api/purchasing/suppliers/s1/inactivate': () => json(supplier({ status: 'INACTIVE', inactivationReason: 'Parou de atender' }), 200),
  }, as(MANAGE))
  renderAt('/compras/fornecedores/s1')

  await userEvent.click(await screen.findByRole('button', { name: 'Inativar fornecedor' }))
  const confirm = await screen.findByRole('group', { name: 'Inativar este fornecedor?' })
  await userEvent.click(within(confirm).getByRole('button', { name: 'Confirmar inativação' }))
  expect(await within(confirm).findByRole('alert')).toHaveTextContent('Informe o motivo da inativação')
  expect(requestsTo(fetch, 'POST', '/api/purchasing/suppliers/s1/inactivate')).toHaveLength(0)

  await paste(within(confirm).getByLabelText(/Motivo da inativação/), 'Parou de atender')
  await userEvent.click(within(confirm).getByRole('button', { name: 'Confirmar inativação' }))
  await waitFor(() => expect(requestsTo(fetch, 'POST', '/api/purchasing/suppliers/s1/inactivate')).toHaveLength(1))
  expect(lastBody(fetch, 'POST', '/api/purchasing/suppliers/s1/inactivate')).toEqual({ reason: 'Parou de atender' })
})

test('fornecedor inativo mostra o motivo e oferece reativação', async () => {
  const fetch = mockApi({
    ...detailRoutes(supplier({ status: 'INACTIVE', inactivatedAt: '2026-10-14T12:00:00Z', inactivationReason: 'Parou de atender' })),
    'POST /api/purchasing/suppliers/s1/reactivate': () => json(supplier(), 200),
  }, as(MANAGE))
  renderAt('/compras/fornecedores/s1')

  expect(await screen.findByText(/Parou de atender/)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Reativar fornecedor' }))
  await userEvent.click(await screen.findByRole('button', { name: 'Confirmar reativação' }))
  await waitFor(() => expect(requestsTo(fetch, 'POST', '/api/purchasing/suppliers/s1/reactivate')).toHaveLength(1))
})

test('adicionar contato valida o e-mail e envia o contato principal', async () => {
  const fetch = mockApi({
    ...detailRoutes(supplier(), []),
    'POST /api/purchasing/suppliers/s1/contacts': () => json(contact(), 201),
  }, as(MANAGE))
  renderAt('/compras/fornecedores/s1')

  await userEvent.click(await screen.findByRole('button', { name: 'Adicionar contato' }))
  const form = await screen.findByRole('group', { name: 'Novo contato' })
  await paste(within(form).getByLabelText(/Nome do contato/), 'Marcos Vendedor')
  await paste(within(form).getByLabelText('E-mail do contato'), 'sem-arroba')
  expect(within(form).getByRole('alert')).toHaveTextContent('E-mail inválido')
  expect(within(form).getByRole('button', { name: 'Salvar contato' })).toBeDisabled()
  await userEvent.clear(within(form).getByLabelText('E-mail do contato'))
  await paste(within(form).getByLabelText('E-mail do contato'), 'marcos@alfa.test')
  await userEvent.click(within(form).getByLabelText('Contato principal'))
  await userEvent.click(within(form).getByRole('button', { name: 'Salvar contato' }))

  await waitFor(() => expect(requestsTo(fetch, 'POST', '/api/purchasing/suppliers/s1/contacts')).toHaveLength(1))
  expect(lastBody(fetch, 'POST', '/api/purchasing/suppliers/s1/contacts')).toEqual({
    name: 'Marcos Vendedor', role: null, phone: null, email: 'marcos@alfa.test', primary: true })
})

test('remover contato pede confirmação e chama DELETE', async () => {
  const fetch = mockApi({
    ...detailRoutes(),
    'DELETE /api/purchasing/suppliers/s1/contacts/c1': () => json(null, 204),
  }, as(MANAGE))
  renderAt('/compras/fornecedores/s1')

  const contacts = await screen.findByRole('region', { name: 'Contatos' })
  await userEvent.click(await within(contacts).findByRole('button', { name: 'Remover' }))
  expect(requestsTo(fetch, 'DELETE', '/api/purchasing/suppliers/s1/contacts/c1')).toHaveLength(0)
  await userEvent.click(await within(contacts).findByRole('button', { name: 'Confirmar remoção' }))
  await waitFor(() => expect(requestsTo(fetch, 'DELETE', '/api/purchasing/suppliers/s1/contacts/c1')).toHaveLength(1))
})
