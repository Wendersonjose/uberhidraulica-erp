# TASK-0017 — Permissões granulares em Produtos, Estoque, OS, CRM, Serviços e Orçamento

## Identificação

- Status: `DONE` — suíte completa validada contra PostgreSQL real no GitHub Actions em 2026-09-30
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

## Checkpoint (2026-09-29)

| Gate | Resultado |
| --- | --- |
| `mvn compile`, `mvn test-compile` | verdes |
| Suíte sem Docker (`!*IntegrationTest`, `!UberhidraulicaErpApplicationTest`) — 69 testes, inclui `ModularityTest` corrigido | **verde, 0 falhas** |
| `ModulePermissionsIntegrationTest` (PostgreSQL) | **não executado** |
| Bateria de regressão dos testes ajustados (PostgreSQL) | **não executada** |

**Bloqueio de ambiente, não de código**: Docker Desktop 4.48.0 travou repetidamente nesta sessão com
sockets AF_UNIX presos (`dockerInference`, depois `docker-secrets-engine\engine.sock`) que as APIs do
Windows não conseguem remover — só `wsl -e rm` remove, mas o processo recria o problema a cada
tentativa. Em paralelo, a máquina entrou em pressão severa de memória (PowerShell chegou a lançar
`OutOfMemoryException` ao carregar um módulo próprio; `git status` falhou uma vez com
"fatal: inflate: out of memory"). Depois de ~6 ciclos de tentativa em duas sessões diferentes, o WSL
`docker-desktop` permaneceu `Stopped` mesmo com o backend relatando `"state":"starting"` — sintoma de
falha na própria virtualização, que normalmente só se resolve com reinício completo da máquina.

Revisão manual (linha a linha, todos os controllers e a migration) confirma que a implementação segue
exatamente o padrão especificado; a suíte sem Docker cobre compilação, `ArchUnit` monetário e
`ModularityTest`. **Checkpoint PostgreSQL concluído em 2026-09-30:** a suíte backend completa executou no GitHub Actions e terminou verde após alinhar `IamAuthenticationIntegrationTest` ao catálogo V20. O bloqueio era exclusivamente do Docker local; o gate remoto removeu a pendência para `DONE`.

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

- 2026-09-30 — Suíte backend completa verde no GitHub Actions (run 36735299234). Checkpoint PostgreSQL concluído; Task encerrada como `DONE`.
