# DR-0016 — Produto inativo: quais operações de estoque permanecem permitidas

- Tipo: `DOMAIN`
- Status: `DECIDED`
- Task: `TASK-0014`
- Origem: `AG-15 — Revisor Técnico` (auditoria de decisões provisórias da sprint)
- Responsável pela decisão: proprietário do produto
- Criada em: `2026-09-18`
- Decidida em: `2026-09-22`, pelo Owner, após revisão externa

## Problema

O cartão Trello "Histórico de movimentações e inativação de produtos" define apenas:

```text
Histórico imutável de entradas, saídas, ajustes e estornos.
Produto inativo permanece em OS e movimentações históricas.
Reativação permitida.
Não excluir fisicamente itens com histórico.
```

O cartão trata do **histórico**. Ele não responde o que acontece com o **saldo vivo** de um produto
que acabou de ser inativado e ainda tem peças na prateleira.

São três perguntas distintas, e hoje elas estão sendo respondidas por um único campo `active`:

1. **Consultar histórico e saldo** de produto inativo.
2. **Movimentar o estoque existente** de produto inativo — entrada, saída, ajuste, estorno, devolução.
3. **Incluir o produto em novas OS**.

## Situação implementada hoje (TASK-0014), a confirmar

| Operação | Comportamento atual | Origem |
| --- | --- | --- |
| Consultar histórico e saldo | permitido; a listagem tem filtro `active` e mostra inativos | cartão (histórico preservado) |
| Incluir em nova OS | recusado (`409 PRODUCT_INACTIVE`) | `AG-04` seção 13 — regra aprovada |
| Entrada e ajuste positivo | recusado (`409 PRODUCT_INACTIVE`) | **decisão provisória da DR-0014** |
| Saída e ajuste negativo | permitido | **decisão provisória da DR-0014** |
| Estorno e devolução da OS | permitido | **decisão provisória da DR-0014** |

A intenção da regra provisória foi: não deixar entrar mais do que já se decidiu parar de comprar,
mas não travar a correção de histórico nem a saída do que ainda está fisicamente na prateleira.

Isso é plausível, mas **não é uma regra aprovada** — é uma escolha de implementação sobre matéria
de negócio, e por isso está registrada aqui em vez de permanecer implícita no código.

## Opções

- **A — manter o implementado:** inativo não recebe entrada nem ajuste positivo; saída, ajuste
  negativo, estorno e devolução continuam permitidos. Esvaziar o saldo remanescente é possível.
- **B — inativo é somente leitura:** nenhuma movimentação nova, de nenhum tipo. O saldo remanescente
  fica congelado até a reativação. Mais simples de explicar, porém obriga a reativar o produto só
  para registrar a saída da última peça, e impede corrigir um lançamento errado.
- **C — inativo bloqueia apenas a compra:** entrada recusada; todo o resto permitido, inclusive ajuste
  positivo (que é correção de contagem, não compra). Diferencia "comprar mais" de "corrigir o que já
  existe".

## Impacto

- Opção B invalida o comportamento atual de saída e estorno em produto inativo e exige mudança de
  código e de teste.
- Opção C amplia o atual, liberando ajuste positivo como instrumento de correção de inventário.
- Nenhuma das opções afeta migration: a regra vive na aplicação, porque depende do motivo da
  movimentação e não apenas do estado do produto.

## Recomendação

Opção C. Ajuste positivo com motivo obrigatório é o instrumento de correção de contagem, e recusá-lo
em produto inativo transforma um erro de inventário em um problema sem saída. A recomendação não é
decisão.

## Pergunta final

Produto inativo deve (A) recusar entrada e ajuste positivo, (B) recusar toda movimentação nova, ou
(C) recusar apenas entrada, mantendo ajuste, saída, estorno e devolução?

## Decisão final do Owner

- Data: `2026-09-22`
- Opção escolhida: **C — inativo bloqueia apenas a compra e a nova OS**
- Decisão: `DECIDED`

Inativar um produto impede novas entradas comerciais/compras e novos lançamentos em OS, mas não
impede correção de inventário nem o encerramento do histórico existente.

| Operação | Produto inativo |
| --- | --- |
| Consultar produto, histórico e saldo | permitido |
| Incluir em nova OS | **recusado** (`409 PRODUCT_INACTIVE`) |
| `ENTRY` | **recusado** (`409 PRODUCT_INACTIVE`) |
| `EXIT` | permitido |
| `ADJUSTMENT_IN` | permitido, com motivo obrigatório |
| `ADJUSTMENT_OUT` | permitido, com motivo obrigatório |
| `REVERSAL` | permitido |
| `WORK_ORDER_RETURN` | permitido |

A baixa de item já lançado em OS anterior à inativação (`WORK_ORDER_OUT` na finalização) também segue
permitida, porque encerra histórico existente e não é lançamento novo.

Mudança em relação ao comportamento anterior: `ADJUSTMENT_IN` em produto inativo, antes recusado,
passou a ser aceito. Teste:
`Task0014InventoryIntegrationTest.inactiveProductRefusesEntryAndNewWorkOrderButAllowsCorrections`.
