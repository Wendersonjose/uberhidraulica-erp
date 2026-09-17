# TASK-0009 — Clientes: cadastro completo, busca, detalhe, atualização e inativação

## Identificação

- Status: `REVIEW`
- Prioridade: `HIGH`
- Criada em: `2026-09-17`
- Origem: quadro Trello "Projetos wenderson", lista "A fazer"
- Proprietário principal: CRM — `AG-03`
- Responsável atual: `AG-15` (revisão independente)

## Cartões Trello cobertos

| Cartão | Resultado |
| --- | --- |
| Cadastro de clientes | PF/PJ, nome e telefone obrigatórios, documento/e-mail/endereço opcionais |
| Listagem e busca de clientes | busca paginada por nome, CPF/CNPJ, telefone e placa, com filtros de tipo e status |
| Detalhamento de cliente | dados cadastrais, status, veículos vinculados e histórico de OS |
| Atualização de clientes | todos os dados cadastrais, com unicidade de documento preservada |
| Inativação e reativação de clientes | ações próprias, sem exclusão física e com histórico preservado |

## Decisões

- `DR-0009` — oficina única; "tenant" dos cartões lido como a própria oficina (provisória).
- `DR-0010` — documento opcional, telefone obrigatório, endereço estruturado sem campos obrigatórios (provisória).
- `DR-0005` continua valendo para o documento quando presente.

## Regras implementadas

1. Telefone é normalizado para dígitos (10 a 13) e exigido na criação e na atualização.
2. Documento vazio é persistido como `NULL`; quando presente, é validado contra o tipo e único globalmente.
3. E-mail normalizado em minúsculas; CEP com 8 dígitos; UF com duas letras.
4. Status não muda mais por `PUT`: somente por `POST /inactivate` e `POST /reactivate`, que retornam `409` quando o cliente já está no estado pedido.
5. Cliente inativo não recebe novo veículo (`409 CUSTOMER_INACTIVE`); veículos e OS existentes continuam consultáveis.
6. A busca textual casa com nome (sem diferenciar maiúsculas) e placa; sem letras no termo, também com documento e telefone. Curingas do `LIKE` são escapados.
7. Ordenação previsível por `lower(name), id`; página de 1 a 100 itens.

## Contrato REST

- `GET /api/customers/search?q=&personType=&status=&page=&size=` → `{items,totalItems,page,size,totalPages}`
- `POST /api/customers` e `PUT /api/customers/{id}` aceitam `phone`, `email` e `address{zipCode,street,number,complement,district,city,state}`
- `POST /api/customers/{id}/inactivate` e `POST /api/customers/{id}/reactivate`
- `GET /api/work-orders?customerId=` para o histórico de OS
- `GET /api/customers` permanece como lista completa para seletores

## Persistência

- `V11__customer_contact_address.sql`: `document` anulável com `CHECK` reescrito, colunas de contato e endereço, constraints de formato e índices de busca.

## Frontend

- Lista com busca no servidor (debounce de 300 ms), filtros e paginação.
- Cadastro PF/PJ e edição com endereço; detalhe com veículos, histórico de OS e ações de inativar/reativar com confirmação em dois passos.

## Testes

- `Task0009Task0010CrmIntegrationTest` (PostgreSQL/Testcontainers): criação com contato e endereço, rejeições, unicidade na criação e na atualização, ciclo de inativação, busca por nome/documento/telefone/placa com paginação e filtros, constraints do banco, autenticação e CSRF.
- Testes das Tasks 0004, 0006, 0007 e 0008 atualizados para enviar telefone e usar a ação de inativação.
- Frontend: testes de busca, cadastro e sessão ajustados para a busca no servidor.

## Fora do escopo

Dígito verificador de CPF/CNPJ; consulta automática de CEP; mascaramento de documento; auditoria das ações (cartão próprio de auditoria); permissões granulares (cartão próprio de perfis e permissões).

## Histórico

- 2026-09-17 — Task criada a partir do Trello, DRs provisórias registradas, backend, frontend e testes implementados; movida para `REVIEW`.
