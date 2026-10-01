# Modelo de dados — Itens físicos da Ordem de Serviço

- Origem: `TASK-0006`
- Agente proprietário: `AG-10 — Banco de Dados`
- Migration: `V7__work_order_product.sql`
- Status: `APPROVED` para o escopo da TASK-0006
- Data: `2026-09-14`

## 1. Tabela `workorder.work_order_product`

| Coluna | Tipo | Nulo | Observação |
| --- | --- | --- | --- |
| `id` | `UUID` | não | chave primária |
| `work_order_id` | `UUID` | não | FK para `workorder.work_order` |
| `product_id` | `UUID` | não | FK para `productcatalog.product` |
| `product_description` | `VARCHAR(200)` | não | snapshot |
| `product_internal_code` | `VARCHAR(60)` | sim | snapshot |
| `unit` | `VARCHAR(16)` | não | snapshot da unidade-base |
| `quantity` | `NUMERIC(15,3)` | não | na unidade-base do snapshot |
| `unit_price` | `NUMERIC(15,2)` | não | snapshot do preço de venda |
| `added_at` | `TIMESTAMPTZ` | não | instante do lançamento |

A tabela segue o mesmo desenho de `workorder.work_order_service`, já aprovado na TASK-0004: referência ao catálogo **mais** cópia dos valores.

## 2. Por que manter a FK para o produto junto com o snapshot

A FK e o snapshot respondem perguntas diferentes:

```text
product_id           qual item do catálogo foi escolhido
snapshot             o que foi acordado no momento do lançamento
```

Guardar apenas a FK faria o histórico mudar junto com o catálogo. Guardar apenas o snapshot impediria rastrear consumo por item, que é exatamente a consulta que Estoque e rentabilidade vão precisar.

A FK também garante que ninguém lance um produto que não existe, o que uma cópia de texto jamais garantiria.

## 3. Constraints

```text
work_order_product_work_order_id_fkey   OS precisa existir
work_order_product_product_id_fkey      produto precisa existir
ck_work_order_product_description       btrim(product_description) <> ''
ck_work_order_product_unit              btrim(unit) <> ''
ck_work_order_product_quantity          quantity > 0
ck_work_order_product_unit_price        unit_price >= 0
```

`unit` recebe apenas `NOT BLANK` e não a lista fechada do catálogo. A unidade aqui é histórica: se o catálogo passar a aceitar `PACOTE` amanhã, uma `CHECK` com a lista antiga rejeitaria linhas legítimas; e se uma unidade for retirada do catálogo, as linhas antigas devem continuar válidas. Domínio fechado é do cadastro, não do histórico.

`quantity > 0` e não `>= 0`: uma linha com quantidade zero não registra nada e só polui a OS.

`unit_price >= 0` permite zero, porque um item de cortesia é uma decisão comercial possível — mas o preço zero precisa vir do catálogo, e não da ausência de preço.

## 4. Índices

```sql
CREATE INDEX ix_work_order_product_order ON workorder.work_order_product(work_order_id, added_at, id);
CREATE INDEX ix_work_order_product_catalog ON workorder.work_order_product(product_id);
```

O primeiro cobre a consulta da OS já na ordem de exibição. O segundo serve ao consumo por item, que Estoque e rentabilidade vão exercer, e evita varredura completa na validação da FK em operações sobre o catálogo.

## 5. Nenhum total persistido

Não existe coluna `total`. O total é `quantity × unit_price`, e persistir um valor derivado antes de existir regra de arredondamento decidida criaria duas fontes de verdade que podem divergir por um centavo — divergência que, em dinheiro, ninguém perdoa.

Quando `DR-0006` for decidida e o Orçamento existir, o total poderá ser persistido de forma deliberada, junto com a regra que o produziu.

## 6. Efeito da nova FK sobre a OS

Uma OS com item físico vinculado não pode mais ser apagada diretamente. Isso é desejável e está coberto por teste: excluir OS com histórico material seria perda de dado.

## 7. Validação

`Task0006ProductItemIntegrationTest` verifica o registro `success` da versão `7` em `flyway_schema_history` e exercita cada constraint e cada FK por SQL direto em PostgreSQL real, além do fluxo completo pela API.
