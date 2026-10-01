# Modelo de dados — Catálogo de Produtos

- Origem: `TASK-0005`
- Agente proprietário: `AG-10 — Banco de Dados`
- Migration: `V6__product_catalog.sql`
- Status: `APPROVED` para o escopo da TASK-0005
- Data: `2026-09-14`

## 1. Schema

`productcatalog`, próprio do módulo. Nenhuma tabela de outro schema foi alterada; nenhuma migration anterior foi modificada.

## 2. Tabela `productcatalog.product`

| Coluna | Tipo | Nulo | Observação |
| --- | --- | --- | --- |
| `id` | `UUID` | não | chave primária, gerada pela aplicação |
| `description` | `VARCHAR(200)` | não | identifica o item para o operador |
| `internal_code` | `VARCHAR(60)` | sim | rótulo interno normalizado em maiúsculas |
| `category` | `VARCHAR(100)` | sim | agrupamento gerencial livre |
| `item_type` | `VARCHAR(24)` | não | tipo funcional, domínio fechado |
| `unit` | `VARCHAR(16)` | não | unidade-base, domínio fechado |
| `reference_cost` | `NUMERIC(15,2)` | sim | valor indicativo de aquisição |
| `sale_price` | `NUMERIC(15,2)` | sim | valor indicativo de cobrança |
| `minimum_stock` | `NUMERIC(15,3)` | sim | parâmetro de catálogo, sem alerta |
| `active` | `BOOLEAN` | não | `DEFAULT TRUE` |
| `created_at` | `TIMESTAMPTZ` | não | |
| `updated_at` | `TIMESTAMPTZ` | não | |

`item_type` e não `type`: evita conflito com a palavra `TYPE` do PostgreSQL em DDL e em ferramentas de migração, sem custo algum de legibilidade.

## 3. Constraints

```text
ck_product_description_not_blank        btrim(description) <> ''
ck_product_internal_code                internal_code ~ '^[A-Z0-9][A-Z0-9._-]*$'
ck_product_item_type                    PART | SUPPLY | COMPONENT | KIT | INTERNAL_USE_MATERIAL
ck_product_unit                         UNIDADE | LITRO | METRO | QUILOGRAMA
ck_product_reference_cost_nonnegative   reference_cost >= 0
ck_product_sale_price_nonnegative       sale_price >= 0
ck_product_minimum_stock_nonnegative    minimum_stock >= 0
```

As três últimas aceitam `NULL`, porque ausência de valor é um estado válido e distinto de zero.

Os domínios fechados são `CHECK` e não tipos `ENUM` do PostgreSQL: acrescentar um valor a um `CHECK` é uma migration trivial, enquanto alterar um `ENUM` é operação com mais restrições. O conjunto de tipos e unidades ainda deve crescer conforme a operação real (AG-04, seções 10 e 21).

## 4. Unicidade do código interno

```sql
CREATE UNIQUE INDEX uq_product_internal_code
    ON productcatalog.product(internal_code)
    WHERE internal_code IS NOT NULL;
```

Índice único **parcial**, e não `UNIQUE (internal_code)`. Embora o PostgreSQL não considere `NULL`s iguais em uma constraint `UNIQUE` comum, o índice parcial declara a intenção de forma explícita e mantém o índice menor, já que o código interno é opcional e boa parte do catálogo tende a não usá-lo.

A normalização para maiúsculas ocorre no domínio, antes da persistência, e a `CHECK` do código interno rejeita qualquer linha inserida fora da aplicação que não respeite a forma canônica. Assim a unicidade não depende de `lower()`/`upper()` no índice e não há como gravar `atf-d3` e `ATF-D3` como itens diferentes.

## 5. Índice de consulta

```sql
CREATE INDEX ix_product_active_description ON productcatalog.product(active, description, id);
```

Cobre a listagem ordenada por descrição e a futura filtragem por situação. Nenhum outro índice foi criado: categoria ainda não tem consulta que o justifique, e índice sem consulta é custo de escrita sem retorno.

## 6. Ausências deliberadas

```text
nenhuma coluna de saldo, reservado, disponível ou custo médio
nenhuma coluna de tenant
nenhuma coluna de exclusão lógica além de active
nenhuma FK para fornecedor, compra ou movimentação
nenhum total derivado
```

Saldo em tabela de produto é o erro mais caro a evitar: ele transforma um cadastro em ponto de contenção transacional e duplica a fonte de verdade do estoque.

## 7. Validação

A migration é validada em PostgreSQL real por Testcontainers em `ProductCatalogIntegrationTest.postgresConstraintsRejectInvalidRows`, que verifica o registro `success` da versão `6` em `flyway_schema_history` e exercita cada constraint e o índice único parcial por SQL direto, fora da aplicação.

`spring.jpa.hibernate.ddl-auto=validate` permanece: o mapeamento de `ProductEntity` precisa corresponder exatamente à migration, inclusive precisão e escala das colunas numéricas.
