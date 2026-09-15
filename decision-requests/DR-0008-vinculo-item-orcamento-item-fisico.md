# DR-0008 — Vínculo entre item de orçamento e item físico da OS

- Tipo: `ARCHITECTURE`
- Status: `OPEN`
- Task: `TASK-0007`
- Origem: `AG-02 — Arquitetura` e `AG-04 — Catálogo & Estoque`
- Responsável pela decisão: proprietário do produto, com AG-02
- Criada em: `2026-09-15`

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

- Data: `-`
- Opção escolhida: `-`
- Decisão: `PENDENTE`
