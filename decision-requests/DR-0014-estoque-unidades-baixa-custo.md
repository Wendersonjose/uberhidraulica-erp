# DR-0014 — Estoque: unidades de embalagem, baixa pela OS e custo médio

- Tipo: `DOMAIN` / `FINANCIAL`
- Status: `DECIDED_PROVISIONALLY` — aguardando ratificação do Owner
- Task: `TASK-0014`
- Origem: `AG-04 — Catálogo & Estoque`
- Responsável pela decisão: proprietário do produto
- Criada em: `2026-09-17`
- Decidida provisoriamente em: `2026-09-17`, sob delegação explícita do Owner

## Problema

Os cartões de estoque pedem:

- unidades como "peça/unidade e volumes como litro, 5 litros e 20 litros";
- entradas, ajustes com motivo, movimentações imutáveis e correção por compensação;
- baixa "automática configurável" pela OS, com estorno em cancelamento;
- histórico completo e saldo com situação do estoque.

O README já define: estoque nunca negativo, custo médio ponderado, físico/reservado/disponível.
Reserva não é pedida pelos cartões.

## Decisão provisória

1. **Unidades**: além de `UNIDADE`, `LITRO`, `METRO` e `QUILOGRAMA`, o catálogo aceita
   `GALAO_5L` e `BALDE_20L` para quem controla fluido pela embalagem fechada — o cartão "Cadastro de
   produtos, peças e fluidos" pede exatamente "peça/unidade e volumes como litro, 5 litros e 20 litros".
   Toda quantidade é expressa na unidade cadastrada do produto, de modo que não existe soma entre
   unidades diferentes.

   **A granularidade por unidade — se `UNIDADE`, `GALAO_5L` e `BALDE_20L` admitem fração — não é
   decidida aqui.** Essa é a pergunta em aberto da `DR-0006`, e esta DR não tem autoridade para
   respondê-la. Enquanto a `DR-0006` estiver `OPEN`, o estoque persiste e aceita três casas decimais
   para qualquer unidade (`NUMERIC(15,3)`, mesma escala do item físico da OS), como precisão
   **provisória** registrada explicitamente, conforme a própria `DR-0006` exige de toda Task que
   introduza movimentação.
2. **Saldo** por produto em tabela própria do módulo Estoque, nunca negativo (`CHECK` e bloqueio da
   linha durante a movimentação). Nesta Task o saldo é somente físico; reserva fica fora.
3. **Movimentações imutáveis**: entrada, saída manual, ajuste positivo e negativo (motivo
   obrigatório), baixa pela OS, devolução pela OS e estorno. Correção de lançamento manual é feita
   por estorno que referencia a movimentação original; cada movimentação só pode ser estornada uma vez.
4. **Custo médio ponderado**: a entrada com custo unitário recalcula
   `médio = (saldo × médio + qtd × custo) / (saldo + qtd)`, guardado com quatro casas (`DR-0007`).
   Saídas não alteram o médio. Entrada sem custo mantém o médio.
5. **Baixa pela OS configurável** com três modos, padrão `ITEM_LAUNCH`:
   - `ITEM_LAUNCH`: o lançamento do item físico na OS baixa o estoque; sem saldo, o lançamento é recusado;
   - `WORK_ORDER_FINISH`: todos os itens físicos baixam ao finalizar a OS; sem saldo, a finalização é recusada;
   - `DISABLED`: a OS não movimenta estoque.
6. **Cancelamento da OS** devolve ao estoque tudo o que ela baixou, com movimentação de devolução
   ligada à OS.
7. A OS não conhece o estoque: publica eventos (item lançado, OS finalizada, OS cancelada) e o
   módulo Estoque reage na mesma transação, de modo que uma recusa desfaz a operação da OS.

## Fronteiras desta DR (revisão AG-15 de 2026-09-18)

A auditoria de decisões provisórias da sprint separou o que aqui é regra aprovada, o que é detalhe
técnico e o que continua sendo matéria de negócio em aberto:

| Regra | Classificação | Origem |
| --- | --- | --- |
| Saldo nunca negativo; operação sem saldo é recusada | **requisito aprovado** | `README` — "Estoque nunca negativo" / "Não será permitido: estoque negativo" |
| Unidades de embalagem `GALAO_5L` e `BALDE_20L` | **requisito aprovado** | cartão "Cadastro de produtos, peças e fluidos" |
| Movimentação imutável; correção por movimento de compensação | **requisito aprovado** | cartão "Entradas e ajustes de estoque" |
| Ajuste exige motivo | **requisito aprovado** | cartão "Entradas e ajustes de estoque" |
| Movimentação registra item, quantidade, tipo, data/hora e usuário | **requisito aprovado** | cartão "Entradas e ajustes de estoque" |
| Cancelamento da OS gera movimentação inversa | **requisito aprovado** | cartão "Baixa de estoque pela OS" |
| Baixa pela OS automática e configurável | **requisito aprovado** | cartão "Baixa de estoque pela OS" |
| Custo médio ponderado | **requisito aprovado** | `README` — "custo médio" |
| Chave de idempotência da baixa = item da OS; bloqueio da linha do saldo; `409` em vez de `500` | **detalhe técnico reversível** | decisão de implementação |
| Escala `NUMERIC(15,3)` da quantidade | **detalhe técnico provisório** | subordinado à `DR-0006` |
| Quais dos três modos de baixa existem e qual é o padrão (`ITEM_LAUNCH`) | **negócio — em aberto nesta DR** | pergunta final abaixo |
| Fórmula do médio e recálculo só nas entradas com custo | **negócio — em aberto nesta DR** | pergunta final abaixo |
| Fração por unidade | **negócio — em aberto na `DR-0006`** | remetido, não decidido aqui |
| O que produto inativo ainda pode movimentar | **negócio — em aberto na `DR-0016`** | remetido, não decidido aqui |

O item 4 desta DR ("produto inativo não recebe entrada; devolução e estorno continuam permitidos")
permanece implementado, mas **deixou de ser tratado como decidido**: foi promovido à `DR-0016`,
porque separa três operações distintas que o cartão não separa.

## Pergunta final

Confirma as unidades de embalagem, a baixa no lançamento do item como padrão e o custo médio
ponderado recalculado somente nas entradas com custo?
