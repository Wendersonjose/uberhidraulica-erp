# TASK-0017 — Permissões granulares em Produtos, Estoque, OS, CRM, Serviços e Orçamento

## Identificação

- Status: `DONE` — checkpoint PostgreSQL executado com sucesso no GitHub Actions (runner remoto,
  Docker real), ver seção de checkpoint
- Prioridade: `HIGH`
- Criada em: `2026-09-29`
- Origem: auditoria de segurança da sprint de piloto (AGENTS.md, "Segurança") — nenhum dos módulos
  fora do Financeiro tinha `@PreAuthorize`
- Proprietário principal: Segurança & Auditoria — `AG-09`, com `AG-11` (backend)

## Objetivo

Impedir, por exemplo, que `GERENTE_ADMINISTRATIVO` altere custo/preço de produto ou faça ajuste
arbitrário de estoque, reaproveitando o padrão de autorização já existente do IAM/Financeiro — sem
criar um quarto perfil (`DR-0003`: perfis fixos `DONO`, `GERENTE_ADMINISTRATIVO`, `GERENTE_FINANCEIRO`).

## Permissões novas (`V20__module_permissions.sql`)

| Código | Concedida a | Cobre |
| --- | --- | --- |
| `PRODUCT_MANAGE` | DONO, GERENTE_ADMINISTRATIVO | Criar/editar produto (rotina) |
| `PRODUCT_COST_MANAGE` | DONO, GERENTE_FINANCEIRO | Alterar custo de referência e preço de venda |
| `INVENTORY_MOVE` | DONO, GERENTE_ADMINISTRATIVO | Entrada e saída de estoque |
| `INVENTORY_ADJUST` | DONO, GERENTE_FINANCEIRO | Ajuste, estorno de movimentação, modo de baixa |
| `WORKORDER_MANAGE` | DONO, GERENTE_ADMINISTRATIVO | Abrir/editar/diagnosticar/mover status/iniciar execução/entregar/cancelar/lançar item na OS |
| `WORKORDER_CONFIG` | DONO, GERENTE_ADMINISTRATIVO | Configurar status do fluxo da OS |
| `CRM_MANAGE` | DONO, GERENTE_ADMINISTRATIVO | Criar/editar/inativar/reativar/transferir cliente e veículo |
| `SERVICE_MANAGE` | DONO, GERENTE_ADMINISTRATIVO | Criar/editar/ativar/inativar serviço, categoria, grupo de veículo |
| `SERVICE_PRICE_MANAGE` | DONO, GERENTE_FINANCEIRO | Alterar preço de serviço por veículo/grupo |
| `QUOTE_MANAGE` | DONO, GERENTE_ADMINISTRATIVO | Criar orçamento e nova revisão |

`finish` da OS mantém `FINANCE_BILL` (TASK-0015/F3), intocado. `QUOTE_PRESENT`/`QUOTE_DISCOUNT`
(TASK-0008/0013) intocados.

## Defesa em profundidade

`ProductCatalogController.update` aceita todos os campos num único `PUT`; `PRODUCT_MANAGE` sozinho
permitiria alterar custo/preço junto com o resto. `ProductCatalogApplicationService.update` compara
`referenceCost`/`salePrice` contra o valor persistido (`compareTo`, não `equals`, por escala) e exige
`PRODUCT_COST_MANAGE` quando mudam, mesmo estilo do `FINANCE_BILL_REQUIRED` (TASK-0015/F3):
`403 PRODUCT_COST_MANAGE_REQUIRED`.

## Achado de arquitetura corrigido durante a implementação

A injeção de `CurrentUser`/`IamAuthorization` em `ProductCatalogApplicationService` violava o
`ModularityTest` (Spring Modulith): `productcatalog` não declarava `iam` em `allowedDependencies`.
Corrigido em `productcatalog/package-info.java` (mesmo padrão de `finance`/`inventory`, que já
declaravam `iam`). Pego pelo próprio `ModularityTest`, exatamente a função que ele existe para cumprir.

## Testes

- `ModulePermissionsIntegrationTest` (novo, PostgreSQL): permitido + negado para cada uma das 10
  permissões, endpoint representativo por módulo (não exaustivo por desenho).
- Testes de integração existentes ajustados para autenticar com sessão IAM real onde antes usavam
  usuário simulado sem perfil (`ProductCatalogIntegrationTest`, `Task0011ServiceCatalogIntegrationTest`,
  `ServiceCatalogIntegrationTest`, `Task0014InventoryIntegrationTest`, `Task0004VerticalIntegrationTest`,
  `Task0006ProductItemIntegrationTest`, `Task0012WorkOrderWorkflowIntegrationTest`,
  `Task0009Task0010CrmIntegrationTest`). Ajuste não trivial em
  `Task0013DiagnosisDiscountDecisionIntegrationTest`: como `QUOTE_MANAGE` agora protege o endpoint
  inteiro (só DONO/GERENTE_ADMINISTRATIVO), o cenário que provava `QUOTE_DISCOUNT` como gate
  independente foi recriado com um `GERENTE_ADMINISTRATIVO` com exceção individual `DENY` em
  `QUOTE_DISCOUNT`, preservando a intenção original do teste.

## Checkpoint (2026-09-30)

| Gate | Resultado |
| --- | --- |
| `mvn compile`, `mvn test-compile` | verdes |
| Suíte completa no GitHub Actions (runner remoto, PostgreSQL real via Testcontainers) | **243 testes, 0 falhas, 0 erros, 0 skipped — BUILD SUCCESS** ([run](https://github.com/Wendersonjose/uberhidraulica-erp/actions/runs/36735299234)) |
| `ModulePermissionsIntegrationTest` (PostgreSQL) | **verde, 7/7** — cada uma das 10 permissões provada com caso permitido e negado |
| Bateria de regressão dos testes ajustados (PostgreSQL) | **verde**, incluída na suíte completa acima |

**Bloqueio de ambiente resolvido pela troca de estratégia**: Docker Desktop local seguiu instável
(socket AF_UNIX preso + pressão de memória, ver `docker-desktop-socket-crash` na memória persistente),
mas o checkpoint real não depende mais do Docker local — o GitHub Actions (`ubuntu-latest`) roda o
Docker/Testcontainers da suíte no runner remoto, sem esse problema. A suíte local sem Docker (69
testes) e a suíte completa remota (243 testes) já cobriam a implementação; dois problemas reais foram
achados e corrigidos ao rodar a suíte completa contra PostgreSQL real (nenhum era falha de ambiente):

1. `IamAuthenticationIntegrationTest.bootstrapCreatesFixedCatalogOwnerAndOnlyHashesTheSecret` esperava
   o catálogo de permissões de antes da `V20` — DONO, GERENTE_ADMINISTRATIVO e GERENTE_FINANCEIRO
   passaram a receber as novas permissões de rotina/sensíveis e a asserção não foi atualizada. Teste
   corrigido para refletir o catálogo real pós-`V20` (nenhuma autorização foi removida do produto).
2. `Task0015FinanceIntegrationTest.dashboardMatchesTheUnderlyingLedgersForThePeriod` (TASK-0018):
   `partsCost` retornava `0.00` em vez de `40.00` — bug real em `partsCostForWorkOrders`, ver detalhe
   no Histórico da TASK-0018.

Revisão manual (linha a linha, todos os controllers e a migration) já havia confirmado que a
implementação segue exatamente o padrão especificado; o checkpoint real confirma isso contra
PostgreSQL de verdade.

## Fora do escopo

Quarto perfil/"operador"; permissões de leitura (GET) — mantido o padrão já existente fora do
Financeiro, onde consulta não é restrita; frontend refletindo as novas permissões (backend é o
limite de segurança real; UX de esconder botões pode vir depois, sem urgência de segurança).

## Histórico

- 2026-09-29 — Task criada a partir da auditoria de segurança da sprint. Implementação completa
  (migration `V20`, 8 controllers, defesa em profundidade, testes novos e ajustados). `ModularityTest`
  encontrou e motivou a correção de uma dependência de módulo não declarada. Suíte sem Docker verde.
  Checkpoint PostgreSQL pendente por indisponibilidade do Docker Desktop nesta máquina (falha de
  ambiente documentada, não de código). Task mantida em `REVIEW`.
- 2026-09-30 — Backend conectado ao Supabase (migrations V1-V20 aplicadas lá) e GitHub Actions
  configurado para rodar a suíte completa contra PostgreSQL real no runner remoto, contornando a
  instabilidade do Docker local. Suíte completa executada: 243 testes, 0 falhas, 0 erros. Corrigido
  teste de catálogo de permissões (`IamAuthenticationIntegrationTest`) desatualizado após a `V20`.
  Checkpoint PostgreSQL de `ModulePermissionsIntegrationTest` confirmado verde (7/7). Task movida
  para `DONE`.
