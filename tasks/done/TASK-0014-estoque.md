# TASK-0014 — Estoque: saldo, movimentações, custo médio e baixa pela OS

## Identificação

- Status: `DONE`
- Prioridade: `HIGH`
- Criada em: `2026-09-17`
- Origem: quadro Trello "Projetos wenderson", lista "A fazer"
- Proprietário principal: Catálogo & Estoque — `AG-04`
- Responsável atual: `-` (encerrada em `2026-09-22`)

## Cartões Trello cobertos

| Cartão | Resultado |
| --- | --- |
| Cadastro de produtos, peças e fluidos | unidades de embalagem `GALAO_5L` e `BALDE_20L` somadas às existentes |
| Listagem e busca de estoque | saldo, unidade, mínimo, custo médio, situação, busca por descrição/código, filtros de status e "abaixo do mínimo", paginação |
| Entradas e ajustes de estoque | entrada com custo, saída, ajuste com motivo obrigatório, autor e instante; correção por estorno |
| Baixa de estoque pela ordem de serviço | baixa configurável no lançamento do item ou na finalização; devolução no cancelamento; saldo nunca negativo |
| Histórico de movimentações e inativação de produtos | histórico imutável e paginado por produto; produto inativo permanece com histórico, não recebe entrada nem entra em nova OS e continua corrigível (`DR-0016`) |

## Decisões

Todas decididas pelo Owner em `2026-09-22`, após revisão externa sobre a branch real:

- `DR-0014` — `DECIDED`, ratificada **com correção obrigatória do custo médio**: unidades, estoque
  nunca negativo sem exceção administrativa, histórico imutável, três modos de baixa com padrão
  `ITEM_LAUNCH`, devolução por movimento inverso no cancelamento, `work_order_item_id` como chave de
  idempotência com índice único como autoridade, `SELECT … FOR UPDATE` na linha do saldo.
- `DR-0006` — `DECIDED`, opção C: `UNIDADE`, `GALAO_5L` e `BALDE_20L` aceitam somente inteiros;
  `LITRO`, `METRO` e `QUILOGRAMA` aceitam até três casas. `NUMERIC(15,3)` mantido, sem migration.
- `DR-0016` — `DECIDED`, opção C: produto inativo recusa `ENTRY` e nova OS; permite `EXIT`,
  `ADJUSTMENT_IN` e `ADJUSTMENT_OUT` com motivo, `REVERSAL` e `WORK_ORDER_RETURN`.

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
2. Custo médio (`DR-0014`, política corrigida em `2026-09-22`). Custo nulo é **desconhecido**, nunca zero:
   - A — saldo zero e entrada com custo: médio = custo da entrada;
   - B — saldo positivo, médio conhecido, entrada com custo: `(saldo × médio + qtd × custo) ÷ novo saldo`,
     quatro casas, `HALF_UP` (`DR-0007`);
   - C — saldo positivo, médio desconhecido, entrada com custo: médio = custo da entrada. Política de
     inicialização de custo para estoque legado ou sem custo conhecido no MVP;
   - D — entrada sem custo: mantém o médio, inclusive nulo.

   Saída não altera o médio. Antes da correção, o caso C tratava o saldo antigo como custo zero e
   diluía o custo da compra (10 sem custo + 10 a R$ 30,00 davam R$ 15,00).
3. Movimentações imutáveis; ajuste exige motivo (aplicação e `CHECK`); estorno é movimento novo ligado ao original, e cada movimento só pode ser estornado uma vez (índice único).
4. Produto inativo (`DR-0016`): recusa `ENTRY` e lançamento em nova OS; saída, ajustes positivos e
   negativos com motivo, estorno e devolução continuam permitidos.
9. Precisão por unidade (`DR-0006`), validada no backend com `400 INVALID_QUANTITY_FOR_UNIT` no
   estoque mínimo do catálogo, no item físico da OS e nas movimentações manuais. Baixa pela OS,
   estorno e devolução repetem quantidades já validadas ou já persistidas e não são revalidados.
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
- A regra de precisão por unidade vive no domínio do catálogo (`ProductUnit`) e é exposta aos outros
  módulos pelo contrato público `ProductQuantityRule`, sem vazar o enum interno.

## Frontend

- Tela de Estoque com busca, filtros, paginação, situação em relação ao mínimo e seletor do modo de baixa pela OS.
- Tela do item com saldo, custo médio, histórico paginado, estorno de movimentação manual e formulários de entrada, saída e ajuste.
- Menu lateral passou a ter Estoque.
- Campos de quantidade (movimentação, item da OS e estoque mínimo) usam passo inteiro para unidades
  contáveis e avisam antes do envio. É apenas UX: a autoridade é o backend.

## Testes

- `StockAverageCostTest` (unitário, sem banco): casos A, B, C e D do custo médio, arredondamento de
  quatro casas `HALF_UP` e saída sem saldo.
- `ProductQuantityRuleTest` (unitário, sem banco): inteiros nas contáveis, três casas nas contínuas.
- `Task0014InventoryIntegrationTest` (PostgreSQL/Testcontainers), 15 testes:
  - custo médio em entradas sucessivas e entrada com custo sobre saldo de custo desconhecido (caso C);
  - saída sem saldo, ajuste sem motivo, estorno único, constraints do banco;
  - baixa no lançamento, devolução no cancelamento, mudança de modo e recusa na finalização;
  - listagem com filtros e unidades de embalagem;
  - precisão por unidade no estoque mínimo, no item da OS e nas movimentações (`DR-0006`);
  - produto inativo: entrada e nova OS recusadas; saída, ajustes, estorno e devolução aceitos (`DR-0016`);
  - concorrência e idempotência contra PostgreSQL real — mock não exerce o `SELECT … FOR UPDATE` nem o
    índice único: duas saídas simultâneas sobre saldo para uma, duas baixas simultâneas do mesmo item
    da OS, troca de modo após o lançamento, finalização repetida e atomicidade da finalização.
- Frontend: `inventory.test.tsx`, incluindo o passo inteiro e o aviso para unidade contável.

## Fora do escopo

Reserva e disponível; inventário rotativo; transferência entre locais; custo específico por compra vinculada à OS; permissão separada para ver custo (cartão de perfis e permissões).

## Checkpoints PostgreSQL

### 2026-09-21

A execução registrada anterior (2026-09-18) tinha terminado com todos os testes de integração em erro
por `Could not find a valid Docker environment` — Docker fechado, não falha funcional. Com Docker:
`Task0014InventoryIntegrationTest` 12/12, `Task0006ProductItemIntegrationTest` 5/5, `ModularityTest`
1/1 e suíte completa com 137 testes, todos verdes.

### 2026-09-22 — fechamento, após as decisões e a correção do custo médio

Desenvolvimento com Docker fechado; Docker aberto só para os testes de integração e a suíte
completa, e fechado em seguida.

| Gate | Resultado |
| --- | --- |
| `mvn compile` e testes unitários sem Docker | verdes (`StockAverageCostTest` 5, `ProductQuantityRuleTest` 23) |
| `Task0014InventoryIntegrationTest` (PostgreSQL, inclui concorrência) | 15 testes, 0 falhas, 0 erros |
| `mvn test` — suíte backend completa | 168 testes, 0 falhas, 0 erros, 0 ignorados, `BUILD SUCCESS` |
| `npm test -- --run` | 11 arquivos, 95 testes verdes |
| `npx tsc --noEmit` | sem erros |
| `npm run build` | concluído; o aviso de chunk acima de 500 kB já existia antes desta Task |
| `npm run lint` | sem apontamentos |
| `git diff --check` | sem problemas |

**CI remoto:** não há workflow em `.github/workflows` nesta branch; não existe CI para aguardar.
CI será tratado em Task própria, fora desta.

## Pendências após o fechamento

Nenhuma pendência de decisão. Continua fora de escopo e registrado:

- autorização por permissão nos endpoints de estoque — depende do cartão "Perfis e permissões de
  acesso"; hoje os endpoints exigem sessão autenticada e CSRF, como o resto do sistema fora do IAM e do
  Orçamento (ver `docs/review/TASK-0014-revisao-tecnica.md`);
- paginação da listagem de estoque em memória, adequada ao porte atual.

## Histórico

- 2026-09-17 — Task criada a partir do Trello; backend, frontend e testes implementados; movida para `REVIEW`.
- 2026-09-18 — Revisão `AG-15`: corrigida a baixa não idempotente que travava a finalização após troca
  de modo; acrescentados testes de concorrência, idempotência e atomicidade; `DR-0014` deixou de
  pré-decidir a `DR-0006`; criada a `DR-0016`; removido o predicado `ProductUnit.countable()`, que
  não tinha chamador.
- 2026-09-21 — Checkpoint PostgreSQL: testes de integração e suíte backend completa executados contra
  PostgreSQL real (137 testes verdes); resultados registrados acima.
- 2026-09-22 — Owner decidiu `DR-0006` (opção C) e `DR-0016` (opção C) e ratificou a `DR-0014` com
  correção obrigatória do custo médio sobre saldo de custo desconhecido. Implementadas a precisão por
  unidade, a nova regra de produto inativo e a política de custo A/B/C/D, com testes. Gates verdes;
  Task movida para `DONE`.
