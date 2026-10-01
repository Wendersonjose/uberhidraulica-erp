# DR-0008 — Vínculo entre item de orçamento e item físico da OS

- Tipo: `ARCHITECTURE`
- Status: `DECIDED`
- Task: `TASK-0007`
- Origem: `AG-02 — Arquitetura` e `AG-04 — Catálogo & Estoque`
- Responsável pela decisão: proprietário do produto, com AG-02
- Criada em: `2026-09-15`
- Decidida em: `2026-09-22`, pelo Owner — pré-requisito da origem comercial do recebível (`DR-0015`)

## Problema

O modelo de dados aprovado da TASK-0001 prevê em `workshop.quote_item` apenas:

```text
work_order_service_id
```

Não existe coluna equivalente para o item físico da OS, porque `workorder.work_order_product` só passou a existir na `TASK-0006`, depois daquela aprovação.

Na prática, o orçamento hoje consegue cobrar uma peça apenas como item de texto livre, sem nenhuma ligação com o produto do catálogo nem com o item físico lançado na OS.

## Contexto

A ausência do vínculo tem consequências concretas nas Tasks seguintes:

1. **Rentabilidade** — comparar o que foi cobrado com o que foi consumido exige saber qual item físico corresponde a qual item cobrado. Sem vínculo, a comparação passa a depender de semelhança de descrição.
2. **Estoque** — reserva a partir de item aprovado precisa saber qual produto reservar.
3. **Compras** — necessidade de compra gerada por item aprovado precisa identificar o item interno.
4. **Duplicidade** — nada impede hoje cobrar duas vezes o mesmo item físico em orçamentos diferentes da mesma OS.

## Opções

- **A — acrescentar `work_order_product_id` a `quote_item`:** simétrico ao que já existe para serviço, com FK real e verificação de que o item pertence à mesma OS. Simples, e mantém o vínculo no lugar onde a identidade comercial vive.
- **B — vincular ao produto do catálogo (`product_id`) em vez do item da OS:** identifica o que está sendo cobrado, mas não qual lançamento físico específico, e portanto não fecha rentabilidade por OS.
- **C — tabela de correspondência própria:** permite muitos-para-muitos, útil se um item cobrado agrupar vários itens físicos. Mais flexível e mais complexo, e sem requisito que hoje exija essa flexibilidade.
- **D — manter sem vínculo:** o orçamento continua textual. Nenhum custo agora, e a rentabilidade por item passa a depender de trabalho manual depois.

## Recomendação

Opção A, com a ressalva de que ela assume um item cobrado por item físico. Se a oficina precisar agrupar vários itens físicos em uma linha do orçamento, a resposta correta é a Opção C. A recomendação não é decisão, e a pergunta operacional abaixo é o que a resolve.

## Impacto e bloqueio

Não bloqueia a TASK-0007: o orçamento funciona com item de texto livre, e itens sem vínculo continuarão válidos qualquer que seja a decisão.

Bloqueia rentabilidade por item, reserva de estoque a partir de aprovação e geração de necessidade de compra a partir de item aprovado.

Se a decisão for A ou C, será necessária migration adicional. Nenhuma linha existente precisará ser reescrita, porque o vínculo é opcional.

## Pergunta final

Uma linha do orçamento sempre corresponde a, no máximo, um item físico lançado na OS, ou a oficina precisa agrupar vários itens físicos em uma única linha cobrada do cliente?

## Decisão final do Owner

- Data: `2026-09-22`
- Opção escolhida: **A — `quote_item.work_order_product_id`**
- Decisão: `DECIDED`

Vínculo **opcional** do item comercial com o item físico específico da OS, simétrico ao vínculo que já
existe com o serviço (`work_order_service_id`). Resposta à pergunta final: uma linha do orçamento
corresponde a **no máximo um** item físico lançado na OS.

### Regras

1. Um `quote_item` representa no máximo um item físico da OS.
2. O item físico pertence à **mesma OS** do orçamento.
3. No mesmo orçamento, o mesmo item físico não é cobrado por dois `quote_item`.
4. Orçamentos alternativos **diferentes** da mesma OS podem referenciar o mesmo item físico.
5. Itens de texto livre continuam permitidos.
6. Vínculos históricos continuam opcionais; dados antigos não são reescritos.

### Proteção no banco (migration nova, `V17`; V1–V16 intactas)

- `workshop.quote_item.work_order_product_id` — `NULL` permitido.
- `workshop.quote_item.work_order_id` — cópia redundante e intencional da OS do orçamento, preenchida
  **somente** quando há vínculo físico. Existe para habilitar as FKs compostas abaixo, no mesmo padrão
  que a `V8` usa contra associação cross-quote. Linhas antigas ficam com `NULL` e não são tocadas.
- `UNIQUE (id, work_order_id)` em `workshop.quote` e em `workorder.work_order_product`, alvos das FKs.
- `FOREIGN KEY (quote_id, work_order_id) → workshop.quote(id, work_order_id)`: a OS declarada no item é
  a OS do orçamento.
- `FOREIGN KEY (work_order_product_id, work_order_id) → workorder.work_order_product(id, work_order_id)`:
  o item físico pertence àquela mesma OS. As duas juntas tornam a associação cross-OS impossível no
  PostgreSQL, mesmo com defeito no Java.
- `CHECK ((work_order_product_id IS NULL) = (work_order_id IS NULL))`.
- Índice único parcial `(quote_id, work_order_product_id) WHERE work_order_product_id IS NOT NULL`:
  o mesmo item físico uma vez por orçamento; orçamentos diferentes podem repeti-lo.

### Aplicação

- O item comercial aceita `workOrderProductId` opcional ao ser criado; o vínculo é da identidade do
  item (como o de serviço) e não muda entre versões comerciais.
- Recusas, simétricas às do serviço: item físico inexistente ou de outra OS →
  `404 WORK_ORDER_PRODUCT_NOT_FOUND`;
  mesmo item físico duas vezes no orçamento → `409 QUOTE_ITEM_PRODUCT_ALREADY_LINKED`.
