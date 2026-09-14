# TASK-0005 — Catálogo mínimo de produtos, peças e fluidos

## Identificação

- Status: `IN_PROGRESS`
- Prioridade: `HIGH`
- Criada em: `2026-09-14`
- Proprietário principal: Catálogo & Estoque — `AG-04`
- Responsável atual: `AG-11`

## Objetivo

Criar a fundação executável do catálogo físico da oficina: o cadastro próprio de peças, fluidos, insumos, componentes e kits, com identidade interna, classificação, unidade-base e referências comerciais opcionais, disponível por API autenticada e por contrato público para consumo futuro pelo módulo Oficina.

## Escopo

- Produto físico com identidade própria (UUID), descrição obrigatória, código interno opcional, categoria opcional, tipo funcional obrigatório, unidade-base obrigatória, custo de referência opcional, preço de venda opcional, estoque mínimo opcional, ativo/inativo, `createdAt` e `updatedAt`.
- Operações `POST /api/products`, `GET /api/products`, `GET /api/products/{id}` e `PUT /api/products/{id}`.
- Migration Flyway `V6`, schema `productcatalog`, constraints e índices reais em PostgreSQL.
- Contrato público `ProductCatalogQuery` para referência e snapshot por outros módulos.
- Tela de catálogo de produtos no frontend existente: listagem, cadastro e edição.
- Testes de integração com PostgreSQL/Testcontainers e verificação Spring Modulith.

## Fora do escopo

Saldo físico, reservado e disponível; movimentações; reservas; inventário; perdas; ajustes; custo médio; compras; fornecedores; códigos de fornecedor; aplicações veiculares; equivalências; estrutura de componentes (BOM); grupos de veículos; sugestões de peças por serviço; alerta de estoque mínimo; multi-tenancy; exclusão física.

## Regras existentes utilizadas

1. A oficina possui catálogo próprio de itens, com identidade interna distinta do código de qualquer fornecedor (AG-04, seções 6 a 9).
2. Cada item possui um tipo funcional; os tipos iniciais aprovados são `PART`, `SUPPLY`, `COMPONENT`, `KIT` e `INTERNAL_USE_MATERIAL` (AG-04, seção 10).
3. Tipo e categoria são dimensões diferentes; categoria é agrupamento gerencial (AG-04, seção 11).
4. Item pode ser ativo ou inativo e item historicamente utilizado não sofre exclusão física (AG-04, seções 12 e 13).
5. Inativar item não altera OS antigas, compras antigas, estoque histórico, movimentações nem relatórios (AG-04, seção 13).
6. Cada item possui unidade-base coerente; para fluidos a unidade-base representa a quantidade efetivamente controlada (AG-04, seções 21 e 22).
7. Cada item pode possuir estoque mínimo; o alerta correspondente depende de saldo disponível e, portanto, pertence ao módulo Estoque (AG-04, seções 106, 107 e 109).
8. Catálogo e saldo de estoque são responsabilidades distintas; o catálogo não representa saldo.
9. Controller → Application → Domain → Repository Port → Persistence Adapter.
10. Módulos não acessam internals de outros módulos; a integração ocorre por contrato público.
11. PostgreSQL, Flyway e `ddl-auto=validate`; UUID, `TIMESTAMPTZ`, `BigDecimal`/`NUMERIC`, constraints reais.
12. Endpoints internos exigem sessão autenticada e mutações preservam CSRF/IAM.
13. Persistência PostgreSQL é testada com Testcontainers; H2 não é aceito.
14. O MVP é de oficina única: nenhum conceito de tenant é introduzido.

## Regras definidas nesta Task

1. `RN-01` — Descrição é obrigatória e identifica o item para o operador. O catálogo de produtos não possui campo "nome" separado da descrição.
2. `RN-02` — Unidade-base é obrigatória e restrita às unidades aprovadas: `UNIDADE`, `LITRO`, `METRO`, `QUILOGRAMA`.
3. `RN-03` — Tipo funcional é obrigatório e restrito aos tipos iniciais aprovados pelo AG-04.
4. `RN-04` — Código interno é opcional. Quando informado, é normalizado para maiúsculas sem espaços nas extremidades e passa a ser único no catálogo. A unicidade é parcial: vários produtos podem permanecer sem código interno.
5. `RN-05` — Valores monetários (custo de referência e preço de venda) são opcionais, não negativos e limitados a duas casas decimais. Ausência de valor significa "não definido" e nunca zero.
6. `RN-06` — Estoque mínimo é opcional, não negativo e limitado a três casas decimais, acompanhando a unidade-base do item. É apenas um parâmetro do catálogo: nenhum alerta é emitido nesta Task.
7. `RN-07` — Todo produto novo nasce ativo. A criação não aceita o campo `active`; a inativação ocorre somente por atualização explícita.
8. `RN-08` — Não existe exclusão de produto. A tabela não possui operação de remoção exposta.
9. `RN-09` — O catálogo não armazena saldo. Nenhuma coluna de saldo, reserva ou movimentação existe em `productcatalog.product`.

## Critérios de aceite

1. `CA-01` — Criar produto com descrição, tipo e unidade retorna `201`, identificador próprio e `active = true`.
2. `CA-02` — Criar produto informando apenas os campos obrigatórios mantém os opcionais nulos e o produto ativo.
3. `CA-03` — Descrição em branco, unidade ausente, tipo ausente, unidade fora do domínio e valor monetário negativo são rejeitados com `400`.
4. `CA-04` — Código interno com caracteres inválidos é rejeitado com `400` e código `INVALID_PRODUCT`.
5. `CA-05` — Código interno repetido, ignorando caixa e espaços, é rejeitado com `409` e código `PRODUCT_INTERNAL_CODE_ALREADY_EXISTS`, sem gravar o segundo registro.
6. `CA-06` — Consulta por identificador inexistente retorna `404` e código `PRODUCT_NOT_FOUND`.
7. `CA-07` — Consultar, listar e atualizar produto preservam identificador, `createdAt` e histórico de criação, atualizando somente `updatedAt` e os campos informados.
8. `CA-08` — Atualização permite inativar o produto sem removê-lo.
9. `CA-09` — O contrato público `ProductCatalogQuery` devolve identificador, descrição, código interno, unidade, preço de venda e situação, e não expõe entidade JPA, repositório nem qualquer conceito de saldo.
10. `CA-10` — Todos os endpoints exigem autenticação e as mutações exigem CSRF.
11. `CA-11` — As constraints da migration `V6` rejeitam, em PostgreSQL real, descrição em branco, tipo inválido, unidade inválida, código interno fora do padrão, valores negativos e código interno duplicado; produtos sem código interno não colidem entre si.
12. `CA-12` — O frontend lista, cadastra e edita produtos usando a estrutura existente, sem novo framework de estilo e sem dados fictícios em runtime.
13. `CA-13` — `ApplicationModules.verify()`, `mvn test`, gates do frontend e `git diff --check` passam.

## Módulos envolvidos

- `productcatalog` (novo, proprietário), IAM somente pela infraestrutura de autenticação já instalada.
- Nenhuma alteração nos módulos `crm`, `servicecatalog` e `workorder`.
- Agentes: AG-00, AG-01, AG-02, AG-04, AG-09, AG-10, AG-11, AG-12, AG-13 e AG-15.

## Decision Requests

- `DR-0006` — `OPEN`, não bloqueadora. Precisão e arredondamento de quantidades fracionadas do catálogo físico. Ver `decision-requests/DR-0006-precisao-quantidade-item-fisico.md`.

## Histórico

- 2026-09-14 — Task criada a partir da auditoria do repositório após o encerramento da TASK-0004. Nenhuma Task posterior à TASK-0004 existia em andamento.
