# TASK-0014 — Estoque: saldo, movimentações, custo médio e baixa pela OS

## Identificação

- Status: `REVIEW`
- Prioridade: `HIGH`
- Criada em: `2026-09-17`
- Origem: quadro Trello "Projetos wenderson", lista "A fazer"
- Proprietário principal: Catálogo & Estoque — `AG-04`
- Responsável atual: `AG-15` (revisão independente)

## Cartões Trello cobertos

| Cartão | Resultado |
| --- | --- |
| Cadastro de produtos, peças e fluidos | unidades de embalagem `GALAO_5L` e `BALDE_20L` somadas às existentes |
| Listagem e busca de estoque | saldo, unidade, mínimo, custo médio, situação, busca por descrição/código, filtros de status e "abaixo do mínimo", paginação |
| Entradas e ajustes de estoque | entrada com custo, saída, ajuste com motivo obrigatório, autor e instante; correção por estorno |
| Baixa de estoque pela ordem de serviço | baixa configurável no lançamento do item ou na finalização; devolução no cancelamento; saldo nunca negativo |
| Histórico de movimentações e inativação de produtos | histórico imutável e paginado por produto; produto inativo permanece com histórico e não recebe entrada |

## Decisões

- `DR-0014` (provisória): unidades de embalagem, saldo somente físico (sem reserva), custo médio ponderado recalculado apenas nas entradas com custo, três modos de baixa pela OS e devolução no cancelamento.
- `DR-0006` (`OPEN`) — **bloqueadora parcial**: a granularidade da quantidade por unidade não está decidida. O estoque adota provisoriamente três casas decimais para qualquer unidade e registra isso como provisório, conforme a própria DR exige.
- `DR-0016` (`OPEN`) — criada nesta revisão: quais operações de estoque um produto inativo ainda admite.

### Regras desta Task ancoradas em requisito aprovado

| Regra | Origem |
| --- | --- |
| Saldo nunca negativo | `README` — "Estoque nunca negativo" |
| Custo médio ponderado | `README` — "custo médio" |
| Unidades `GALAO_5L` / `BALDE_20L` | cartão "Cadastro de produtos, peças e fluidos" |
| Movimentação imutável, correção por compensação | cartão "Entradas e ajustes de estoque" |
| Ajuste exige motivo; movimento registra item, quantidade, tipo, data/hora e usuário | cartão "Entradas e ajustes de estoque" |
| Baixa pela OS automática e configurável | cartão "Baixa de estoque pela OS" |
| Cancelamento gera movimentação inversa | cartão "Baixa de estoque pela OS" |
| Produto inativo permanece no histórico; não entra em nova OS | cartão "Histórico de movimentações…" + `AG-04` seção 13 |

## Regras implementadas

1. Saldo por produto em tabela própria, com `CHECK` de não negativo e bloqueio da linha (`SELECT … FOR UPDATE`) em toda movimentação — duas baixas do mesmo item se serializam.
2. Custo médio: `(saldo × médio + qtd × custo) ÷ novo saldo`, quatro casas, `HALF_UP` (DR-0007). Entrada sem custo preserva o médio; saída não o altera.
3. Movimentações imutáveis; ajuste exige motivo (aplicação e `CHECK`); estorno é movimento novo ligado ao original, e cada movimento só pode ser estornado uma vez (índice único).
4. Produto inativo não recebe entrada; devolução e estorno continuam permitidos para não travar correção de histórico.
5. Baixa pela OS conforme o modo configurado; sem saldo, a operação da OS é recusada e desfeita (`409 INSUFFICIENT_STOCK`), porque o ouvinte roda na transação da OS.
6. Cancelamento da OS devolve ao estoque todas as baixas ainda não estornadas daquela OS.
7. Um item físico da OS gera no máximo uma baixa. A chave de idempotência é o **item da OS**: a
   aplicação verifica a baixa existente antes de movimentar e o índice único parcial
   `uq_stock_movement_work_order_item` é a autoridade final para o caso concorrente. Reenvio HTTP,
   duplo clique, reprocesso do evento e troca do modo de baixa entre o lançamento e a finalização
   chegam todos ao mesmo ponto, e só o primeiro desconta.
8. Finalização com vários itens é tudo-ou-nada: o ouvinte roda na transação da OS, então a falta de
   saldo em um item desfaz as baixas dos demais.

## Contrato REST

- `GET /api/inventory/stock?q=&category=&active=&belowMinimum=&page=&size=`
- `GET /api/inventory/products/{productId}/movements?page=&size=`
- `POST /api/inventory/products/{productId}/entries` `{quantity, unitCost?, reason?}`
- `POST /api/inventory/products/{productId}/exits` `{quantity, reason}`
- `POST /api/inventory/products/{productId}/adjustments` `{quantity, direction, reason}`
- `POST /api/inventory/movements/{movementId}/reverse` `{reason?}`
- `GET /api/inventory/settings`; `PUT /api/inventory/settings/write-off` `{mode}`

## Persistência

- `V16__inventory.sql`: unidades novas no catálogo, `inventory.stock_balance`, `inventory.stock_movement` com todas as constraints e índices, `inventory.settings` e saldo zero para os produtos existentes.

## Arquitetura

- Novo módulo `inventory`, dependente de `productcatalog`, `workorder` (apenas os eventos) e `iam`.
- A OS publica `WorkOrderEvents.ProductLaunched`, `Finished` e `Cancelled`; o Estoque reage na mesma transação. A OS não conhece saldo, custo nem movimentação.
- O catálogo ganhou `ProductCatalogQuery.products()` para que o Estoque cruze catálogo e saldo sem ler a tabela de outro módulo.

## Frontend

- Tela de Estoque com busca, filtros, paginação, situação em relação ao mínimo e seletor do modo de baixa pela OS.
- Tela do item com saldo, custo médio, histórico paginado, estorno de movimentação manual e formulários de entrada, saída e ajuste.
- Menu lateral passou a ter Estoque.

## Testes

- `Task0014InventoryIntegrationTest` (PostgreSQL/Testcontainers), 12 testes: custo médio em entradas
  sucessivas, saída sem saldo, ajuste sem motivo, estorno único, baixa no lançamento e devolução no
  cancelamento, mudança de modo e recusa na finalização, listagem com filtros e unidades de
  embalagem, constraints do banco.
- Testes de concorrência e idempotência acrescentados na revisão `AG-15`, todos contra PostgreSQL
  real — mock não exerce o `SELECT … FOR UPDATE` nem o índice único:
  - `concurrentExitsOnTheSameProductCannotConsumeMoreThanTheBalance`: duas saídas simultâneas de 6
    sobre saldo 10 — exatamente uma passa, saldo final 4, um único movimento `EXIT`;
  - `concurrentWriteOffsOfTheSameWorkOrderItemDiscountOnlyOnce`: duas baixas simultâneas do mesmo
    item da OS — um único `WORK_ORDER_OUT`;
  - `changingTheWriteOffModeAfterLaunchDoesNotDiscountTheSameItemTwice`: item baixado no lançamento
    não baixa de novo quando o modo muda para `WORK_ORDER_FINISH`;
  - `repeatingTheFinishDoesNotDiscountTwice`: repetir a finalização não gera segunda baixa;
  - `finishingWithOneItemWithoutBalanceLeavesNoPartialMovement`: atomicidade — falta de saldo em um
    item não deixa outro baixado pela metade.
- Frontend: `inventory.test.tsx`.

## Fora do escopo

Reserva e disponível; inventário rotativo; transferência entre locais; custo específico por compra vinculada à OS; permissão separada para ver custo (cartão de perfis e permissões).

## Checkpoint PostgreSQL (2026-09-21)

A última execução registrada antes deste checkpoint (2026-09-18) terminou com todos os testes de
integração em erro por `Could not find a valid Docker environment` — Docker fechado, não falha
funcional. Por isso os testes de concorrência ainda não tinham resultado verde registrado.

Execução com Docker (Testcontainers, `postgres:18-alpine`), Docker fechado em seguida:

| Gate | Resultado |
| --- | --- |
| `Task0014InventoryIntegrationTest` | 12 testes, 0 falhas, 0 erros — inclui as duas saídas simultâneas, as duas baixas simultâneas do mesmo item, a idempotência da finalização e a atomicidade |
| `Task0006ProductItemIntegrationTest` (regressão F-05) | 5 testes, 0 falhas |
| `ModularityTest` | 1 teste, 0 falhas |
| Suíte backend completa (`mvn test`) | 137 testes, 0 falhas, 0 erros, 0 ignorados |
| Frontend (`vitest`, `tsc --noEmit`, `oxlint`) | 94 testes verdes; tipos e lint sem apontamentos |

Os gates técnicos para `DONE` estão cumpridos. A Task permanece em `REVIEW` apenas pelas pendências
de decisão do Owner listadas abaixo e pela ausência de CI remoto.

## Pendências abertas ao fechar a Task

Nenhuma delas impede a entrega; todas estão registradas como decisão pendente, não como regra:

1. `DR-0014` aguarda ratificação do Owner (modos de baixa, padrão `ITEM_LAUNCH`, fórmula do custo médio).
2. `DR-0006` (`OPEN`) governa a fração por unidade. Enquanto isso, três casas para qualquer unidade.
3. `DR-0016` (`OPEN`) governa o que produto inativo ainda pode movimentar.
4. Autorização por permissão nos endpoints de estoque depende do cartão "Perfis e permissões de
   acesso", ainda não implementado. Hoje os endpoints exigem sessão autenticada e CSRF, como todo o
   resto do sistema fora do IAM e do Orçamento. Ver `docs/review/TASK-0014-revisao-tecnica.md`.

## Histórico

- 2026-09-17 — Task criada a partir do Trello; backend, frontend e testes implementados; movida para `REVIEW`.
- 2026-09-18 — Revisão `AG-15`: corrigida a baixa não idempotente que travava a finalização após troca
  de modo; acrescentados testes de concorrência, idempotência e atomicidade; `DR-0014` deixou de
  pré-decidir a `DR-0006`; criada a `DR-0016`; removido o predicado `ProductUnit.countable()`, que
  não tinha chamador.
- 2026-09-21 — Checkpoint PostgreSQL: testes de integração e suíte backend completa executados contra
  PostgreSQL real (137 testes verdes); resultados registrados acima.
