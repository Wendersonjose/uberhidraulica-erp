# TASK-0007 — Orçamento com versionamento comercial

## Identificação

- Status: `DONE`
- Prioridade: `CRITICAL`
- Criada em: `2026-09-15`
- Proprietário principal: Oficina — `AG-03`
- Responsável atual: `AG-00`
- Encerrada em: `2026-09-15`

## Objetivo

Tornar executável a fundação comercial do orçamento já especificada e aprovada na `TASK-0001`: identidade do orçamento, apresentações versionadas, itens comerciais com histórico imutável, apresentação ao cliente com validade de sete dias e a regra de obsolescência da `DR-0001` calculada por item.

## Contexto

A `TASK-0001` está `SPECIFICATION_DONE` desde 2026-09-08, com requisito, domínio, arquitetura, modelo de dados, segurança e plano de testes aprovados, e `DR-0001` decidida. Nada disso havia sido implementado.

Depois da `TASK-0006` a OS já sabe o que foi feito e o que foi aplicado, mas não sabe **o que foi combinado com o cliente**. Esta Task implementa a parte comercial interna; o acesso público e a decisão do cliente vêm em seguida.

## Escopo

- `workshop.quote`, `quote_revision`, `quote_item`, `quote_item_revision` e `quote_revision_item`, conforme o modelo aprovado (REVISION 2), inclusive as FKs compostas contra associação cross-quote.
- Módulo Spring Modulith `quote`, com dependência declarada em `workorder` e `iam`.
- Contratos públicos novos: `WorkOrderQuery` e `CurrentUser`.
- Endpoints internos de criação do orçamento, criação de revisão, apresentação, consulta e histórico.
- Derivação de obsolescência e de expiração, sem coluna de estado.
- Telas de orçamentos da OS e de detalhe do orçamento, com criação de revisão e apresentação.
- Testes de integração em PostgreSQL real e testes de interface.

## Fora do escopo

Acesso público por token; `PublicQuoteAccess`; decisão do cliente; `QuoteDecisionSubmission`; `QuoteDecision`; aprovação parcial; rejeição; reabertura de item; retratação; idempotência de submissão; evidências de decisão; eventos de domínio e outbox; total do orçamento calculado pelo backend; desconto; acréscimo; imposto; comissão; execução operacional; estoque; exclusão ou edição de versão comercial já criada; descarte de rascunho; permissões granulares.

## Regras aprovadas implementadas

| Regra | Origem | Onde |
| --- | --- | --- |
| `RN-04` a `RN-06` — preço, descrição ou quantidade exigem nova versão | REQ-ORC-001 | `QuoteItemRevision.sameCommercialTerms` |
| `RN-08` — histórico não é sobrescrito | REQ-ORC-001 | nenhuma operação de update de versão comercial existe |
| `RN-09`, `RN-21`, `RN-22` — complemento preserva versões não alteradas | REQ-ORC-001 | reaproveitamento da versão vigente na nova revisão |
| `RN-12`, `RN-13` — expiração bloqueia pendentes e não afeta o passado | REQ-ORC-001 | `DecisionAvailability.EXPIRED`, derivado |
| `RN-19` — rascunho não invalida a versão apresentada | `DR-0001` opção B | `Quote.superseded` exige apresentação efetiva |
| `RN-20` — nova versão apresentada substitui a anterior | `DR-0001` opção B | `Quote.superseded` |
| Validade comercial de 7 dias | REQ-ORC-001 seção 29 | `QuoteRevision.DEFAULT_VALIDITY` |
| Obsolescência é por item, não por revisão | arquitetura seção 14 | `Quote.superseded` percorre versões do mesmo item |
| Proibição de `is_stale` / `current` | modelo de dados seção 31 | nenhuma coluna de estado derivado |
| `EXPIRED` não é estado persistido | modelo de dados seção 14 | `CHECK` aceita só `DRAFT` e `PRESENTED` |
| Integridade cross-quote no banco | modelo de dados, finding `HIGH-01` | FKs compostas de `quote_revision_item` |
| Relógio controlável | arquitetura seção 44 | `Clock` injetado |
| Optimistic locking na apresentação | arquitetura seções 29 e 34 | update condicional por `version` |
| Proibição de IDOR | arquitetura seção 39 | todo acesso valida `quote.workOrderId` |

## Regras definidas nesta Task

1. `RN-T7-01` — Uma nova revisão sempre nasce em `DRAFT` e recebe o próximo número. Não existe edição de revisão: compor de novo é criar outra revisão, e rascunhos antigos permanecem como registro de trabalho interno.
2. `RN-T7-02` — Uma revisão de número inferior a outra já apresentada não pode ser apresentada, para que a sequência do que o cliente recebeu permaneça legível.
3. `RN-T7-03` — Apresentar exige pelo menos um item: uma proposta vazia daria ao cliente algo inexistente para decidir e iniciaria uma validade sem conteúdo.
4. `RN-T7-04` — A validade comercial não é parametrizável por requisição enquanto não existir configuração aprovada.
5. `RN-T7-05` — O mesmo item comercial não pode aparecer duas vezes na mesma revisão.
6. `RN-T7-06` — O total do item é `quantidade × preço unitário`, calculado com a precisão integral do `BigDecimal` e arredondado para duas casas com `HALF_UP`; os operandos nunca são arredondados antes. O total da apresentação é a **soma das parcelas já arredondadas**. Quantidade e preço unitário aceitam até quatro casas. Conforme a `DR-0007`, aprovada em 2026-09-15.
   > Versão anterior desta regra: enquanto a `DR-0007` estava aberta, o total não exato era recusado com `422 QUOTE_TOTAL_REQUIRES_ROUNDING_DECISION`, para não arredondar sem política aprovada. A trava foi removida com a decisão.
8. `RN-T7-08` — Apresentar exige a permissão `QUOTE_PRESENT`, verificada no backend. `DONO` e `GERENTE_ADMINISTRATIVO` a recebem por perfil; `GERENTE_FINANCEIRO` não. Exceções individuais do IAM continuam valendo.
7. `RN-T7-07` — Itens do orçamento saem na ordem em que o cliente os vê, derivada do `displayOrder` da apresentação.

## Critérios de aceite

1. `CA-01` — Criar orçamento de uma OS existente retorna `201`, sem revisões, com autoria do usuário autenticado.
2. `CA-02` — Criar revisão gera `DRAFT` numerada, itens comerciais com versão 1, totais exatos e `availability = NOT_PRESENTED`.
3. `CA-03` — Apresentar move para `PRESENTED`, grava o instante e define validade de exatamente 7 dias, e o item passa a `AVAILABLE`.
4. `CA-04` — `DR-0001` opção B: criar A-v2 apenas em rascunho mantém A-v1 `AVAILABLE` e deixa A-v2 `NOT_PRESENTED`.
5. `CA-05` — Apresentar A-v2 torna A-v1 `SUPERSEDED` e A-v2 `AVAILABLE`, sem apagar nem alterar A-v1.
6. `CA-06` — Complemento que reaproveita os mesmos termos não cria nova versão do item e mantém A-v1 `AVAILABLE`.
7. `CA-07` — Apresentação com validade vencida deixa o item pendente como `EXPIRED`.
8. `CA-08` — Orçamento de outra OS, orçamento inexistente, revisão inexistente, item inexistente e serviço de outra OS respondem `404`.
9. `CA-09` — Lista vazia, descrição em branco, quantidade zero ou negativa e preço negativo respondem `400`.
10. `CA-10` — Total que exigiria arredondamento responde `422` e nada é gravado.
11. `CA-11` — Apresentar duas vezes responde `409`; apresentar revisão fora de ordem responde `409`; gravação com versão divergente não aplica nada.
12. `CA-12` — Item vinculado a serviço da OS preserva o vínculo, e o histórico sai em ordem cronológica.
13. `CA-13` — O PostgreSQL rejeita revisão de um orçamento associada a versão de item de outro orçamento, além de número, status, quantidade, preço e ordem inválidos.
14. `CA-14` — Todos os endpoints exigem sessão; mutações exigem CSRF.
15. `CA-15` — O frontend lista orçamentos, abre orçamento, compõe revisão semeada pela última apresentação, apresenta rascunho e mostra a situação derivada de cada versão.
16. `CA-16` — `ApplicationModules.verify()`, `mvn test`, gates do frontend e `git diff --check` passam.

## Módulos envolvidos

- `quote` (novo, proprietário), consumindo `workorder` e `iam` por contrato público.
- `workorder` e `iam` receberam apenas contratos públicos novos; nenhuma regra existente foi alterada.
- Agentes: AG-00, AG-01, AG-02, AG-03, AG-09, AG-10, AG-11, AG-12, AG-13 e AG-15.

## Decision Requests

- `DR-0001` — `DECIDED`, opção B. Implementada e coberta por teste.
- `DR-0006` — `OPEN`, não bloqueadora. Precisão da quantidade.
- `DR-0007` — `DECIDED` em 2026-09-15, opção C com `HALF_UP`. **Aberta e resolvida nesta Task.** Implementada e coberta por testes de fronteira.
- `DR-0008` — `OPEN`, **aberta nesta Task**. Vínculo entre item de orçamento e item físico da OS. Bloqueia rentabilidade por item e reserva de estoque a partir de aprovação.

## Divergências registradas em relação à especificação aprovada

1. **Módulo próprio `quote`** em vez de tudo dentro de "Oficina". Registrado em `docs/architecture/oficina/TASK-0007-revisao-fronteira-modulo-orcamento.md`.
2. **`POST .../items/{itemId}/reopen` não implementado.** Reabrir item rejeitado só tem significado quando existe decisão; pertence à Task seguinte.
3. **Escala monetária `NUMERIC(19,4)`** como o modelo aprovado sugere, divergindo das duas casas já usadas no restante do sistema. Resolvido pela `DR-0007`: quatro casas internas, duas no que é cobrado, sem migração especulativa das tabelas antigas.
4. **FK real de `quote.work_order_id` para `workorder.work_order`**, que o DDL aprovado não tinha porque a OS ainda não existia.

## Riscos

1. **Arredondamento acumulado** — somar parcelas já arredondadas é a política aprovada e evita divergência visual, mas em volumes altos acumula centavos em relação ao produto exato. Probabilidade `MÉDIA`, impacto `BAIXO`, explicitamente aceito na `DR-0007`.
2. **Rascunhos acumulados** — não há descarte de rascunho; um erro de composição deixa uma revisão `DRAFT` permanente. Probabilidade `MÉDIA`, impacto `BAIXO`: rascunho não produz efeito comercial nem aparece ao cliente.
3. **Orçamento sem vínculo com item físico** — `DR-0008`. Probabilidade `ALTA`, impacto `MÉDIO` para rentabilidade futura.
4. **Ausência de decisão** — o orçamento pode ser apresentado mas ainda não pode ser aprovado. É a ordem deliberada do roadmap, não uma omissão.

## Resultado dos gates

Medição final, após as decisões do proprietário de 2026-09-15:

- `mvn test`: `76` testes, `0` failures, `0` errors, `0` skipped, `BUILD SUCCESS`.
- `ModularityTest` / `ApplicationModules.verify()`: `PASS` com o módulo `quote` e a dependência `quote → workorder, iam`.
- `Task0007QuoteVersioningIntegrationTest`: `19` testes `PASS` em PostgreSQL 18 real via Testcontainers.
- `QuoteMonetaryArchitectureTest`: `2` testes `PASS` — nenhum `double`/`float` no módulo.
- Frontend: `65` testes `PASS`, `0` failures; TypeScript, build e lint verdes, `0` warnings.
- `git diff --check`: `PASS`.
- Regressão: IAM, Clientes, Veículos, Serviços, Produtos, OS e itens físicos `PASS`.

### Regressão produzida pelas decisões novas, e como foi tratada

`IamAuthenticationIntegrationTest.bootstrapCreatesFixedCatalogOwnerAndOnlyHashesTheSecret` **quebrou**
ao cadastrar `QUOTE_PRESENT`: ele afirmava que `DONO` tinha exatamente o catálogo do IAM e que
`GERENTE_ADMINISTRATIVO` não tinha nenhuma permissão. Ambas as afirmações deixaram de ser verdade.

A asserção foi **corrigida, não afrouxada**: continua exata, agora declarando por extenso o catálogo
do IAM mais `QUOTE_PRESENT`, e exigindo `QUOTE_PRESENT` exatamente para `GERENTE_ADMINISTRATIVO`.
Uma concessão acidental futura continua sendo detectada.

## Critérios de aceite

`16/16 IMPLEMENTADOS`.

## Revisão

- Revisão interna no papel do `AG-15`: `APPROVED_WITH_NOTES`, em `docs/review/TASK-0007-revisao-tecnica.md`.
- `F-07-02` — `RESOLVIDO`: permissão `QUOTE_PRESENT` criada, cadastrada pela `V9` e verificada no backend.
- `F-07-04` — `RESOLVIDO`: o total lido do banco passou a ser conferido contra a fórmula.
- `F-07-01` — permanece aberto **por desenho**: é o pré-requisito declarado da TASK-0008 e só pode ser resolvido junto com a operação de decisão.
- `F-07-03`, `F-07-05` e `F-07-06` — aceitos, sem impedimento.
- Nenhum finding `CRITICAL` ou `HIGH` aberto.

### Base do encerramento

O proprietário revisou os pontos levantados e decidiu `DR-0007`, `RN-T7-06` e `F-07-02`, o que
corresponde à validação externa exigida pelo AGENTS.md para esta Task. Registrado de forma explícita
que **não houve revisor técnico independente separado**: a revisão registrada é interna, e o
encerramento se apoia nas decisões do proprietário somadas a ela.

## Bugs de produção encontrados e corrigidos

1. `BUG-07-01` — a apresentação não aparecia na resposta porque o update condicional não sincronizava o contexto de persistência; o banco gravava e a releitura devolvia a revisão ainda em `DRAFT`.
2. `BUG-07-02` — a ordem dos itens do orçamento era aleatória, porque itens criados na mesma revisão empatavam em `createdAt` e desempatavam pelo `UUID`.
3. `BUG-07-03` — negar acesso a uma rota longa quebrava a gravação da auditoria (`audit_event.target_id` tem 120 caracteres) e a negação chegava ao cliente como erro de servidor em vez de `403`. Defeito **anterior** a esta Task, revelado pelo primeiro endpoint protegido longo o bastante. Corrigido truncando o alvo na origem e impedindo que uma falha de auditoria altere a resposta de segurança.

Todos foram encontrados pelos testes, e não por inspeção.

## Conferência do proprietário — respondida em 2026-09-15

1. `RN-T7-06` — **decidido**: `DR-0007`, opção C com `HALF_UP`. A trava do `422` foi removida e o arredondamento implementado.
2. `F-07-02` — **decidido**: permissão `QUOTE_PRESENT` para `DONO` e `GERENTE_ADMINISTRATIVO`, verificada no backend.
3. Divergências declaradas (módulo próprio `quote`, `reopen` adiado, escala `NUMERIC(19,4)`, FK real de `quote.work_order_id`) — mantidas, e a escala foi confirmada pela `DR-0007`.

## Pré-requisito para a Task de decisão pública

`F-07-01` — o ponto de serialização atual é a revisão. A arquitetura aprovada exige coordenação no contexto lógico do `QuoteItem` para a corrida `DECIDE A-v1 × PRESENT A-v2`. Enquanto não existe decisão, nada é violável; quando existir, a submissão terá de reconferir obsolescência dentro da própria transação.

## Histórico

- 2026-09-15 — Task criada a partir da especificação aprovada da TASK-0001, que estava `SPECIFICATION_DONE` e `NOT_IMPLEMENTED` desde 2026-09-08.
- 2026-09-15 — Backend, migration `V8`, contratos públicos, frontend e testes implementados; todos os gates aplicáveis verdes; dois bugs de produção corrigidos; revisão interna concluída. Status movido para `REVIEW`.
- 2026-09-15 — Proprietário decidiu `DR-0007`, `RN-T7-06` e `F-07-02`. Arredondamento `HALF_UP` implementado com testes de fronteira, permissão `QUOTE_PRESENT` criada na `V9`, `BUG-07-03` corrigido e a regressão de asserção do IAM ajustada sem afrouxamento. Gates reexecutados: `76` testes de backend e `65` de frontend, todos verdes. Status movido para `DONE`.
