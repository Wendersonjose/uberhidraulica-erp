# TASK-0018 — Resumo financeiro gerencial, administração de usuários e deploy do piloto

## Identificação

- Status: `REVIEW` — código e frontend completos e testados; checkpoint de integração do dashboard
  (PostgreSQL) pendente pelo mesmo bloqueio de ambiente da TASK-0017
- Prioridade: `HIGH`
- Criada em: `2026-09-29`
- Origem: requisitos diretos do piloto ("o piloto precisa mostrar custos e lucros", "administrador
  da oficina cadastrar os funcionários", preparar deploy da primeira oficina)
- Proprietário principal: Financeiro (`AG-06`) para o dashboard; IAM (`AG-09`) para administração de
  usuários (só frontend, backend já existia); DevOps (`AG-14`) para o deploy

## 1. Resumo financeiro gerencial

`GET /api/finance/dashboard?from=&to=` (`FINANCE_VIEW`): faturamento, recebido, a receber, vencido,
custo de peças, despesas registradas/pagas, lucro bruto, resultado operacional, margem bruta e
operacional. Não é DRE contábil — único custo direto confiável é peças; mão de obra não é custeada.

**Definições implementadas:**
- Faturamento = soma de `original_amount` dos recebíveis não cancelados **emitidos no período**
  (base comercial aprovada, nunca o total bruto da OS).
- A receber e vencido = saldo em aberto dos MESMOS recebíveis do período (painel inteiro fala do
  mesmo recorte de OS).
- Custo de peças = `InventoryCostQuery.partsCostForWorkOrders` (novo contrato público do módulo
  `inventory`): soma de `WORK_ORDER_OUT` menos `WORK_ORDER_RETURN`, pelo `unit_cost` histórico
  gravado na movimentação — a devolução usa o custo do movimento original via
  `reverses_movement_id`, porque não grava custo próprio. `finance` passa a declarar `inventory` em
  `allowedDependencies` (Spring Modulith).
- Recebido/despesas pagas = realizado do fluxo de caixa já existente (`realizedInflows`/
  `realizedOutflows`), somado no período.
- Despesas registradas = `finance.payable` não cancelada, por `created_at` convertido para o fuso da
  oficina (`America/Sao_Paulo`, achado corrigido em revisão própria — ver Histórico).
- Margens = `parte / faturamento * 100`, arredondado `HALF_UP` a 2 casas; **zero quando faturamento
  é zero**, nunca `NaN` nem divisão por zero.

Frontend: `/financeiro/dashboard` (primeiro item do menu Financeiro), filtro De/Até com atalhos
Hoje/Últimos 7 dias/Mês atual, 11 indicadores, vencido em destaque quando > 0. Sem gráfico.

## 2. Administração de usuários

Backend já existia (TASK-0003, `DR-0002`/`DR-0003`) e não foi alterado. Frontend novo:
`/configuracoes/usuarios` (gated por `IAM_USERS_READ`): lista paginada, criação com exibição única da
senha temporária (nunca persistida em `localStorage`/`sessionStorage`/log), ativar/inativar e
redefinir senha com confirmação em dois passos, exceções de permissão por usuário
(`INHERIT`/`ALLOW`/`DENY`) contra o catálogo completo.

## 3. Deploy do piloto (uma oficina, sem multi-tenant)

`Dockerfile` (backend), `frontend/Dockerfile` + `deploy/nginx/` (proxy reverso same-origin, sem CORS,
rate limiting em `/api/public/**` — `TASK-0008/F-08-01`), `server.forward-headers-strategy=framework`
em `application-prod.yml` (`TASK-0008/F-08-02`, IP real atrás de proxy), `docker-compose.yml`,
`.env.example` expandido, `docs/architecture/devops/DEPLOY-piloto.md` (passo a passo, backup/restore
do PostgreSQL, e o único ponto dependente do provedor de hospedagem — certificado HTTPS —
explicitamente isolado e documentado).

## Testes

- `FinanceDashboardServiceTest` (unitário, Mockito, sem banco): cálculo de margens, arredondamento,
  faturamento zero não gera `NaN`/erro. **Verde** (roda sem Docker).
- `Task0015FinanceIntegrationTest` — 3 métodos novos (PostgreSQL): dashboard bate matematicamente
  contra um fluxo real completo (OS → estoque → orçamento → recebível → recebimento → despesa),
  vencido como subconjunto de a-receber, faturamento zero sem erro. **Não executado** (mesmo
  bloqueio de Docker da TASK-0017).
- Frontend: `finance-dashboard.test.tsx` (4 testes) e `admin-users.test.tsx` (6 testes) — **verdes**;
  suíte frontend completa (14 arquivos, 120 testes) **verde**; `tsc`/`lint`/`build` limpos.

## Checkpoint (2026-09-29)

| Gate | Resultado |
| --- | --- |
| `mvn compile` | verde |
| Suíte backend sem Docker (69 testes, inclui `FinanceDashboardServiceTest` e `ModularityTest`) | **verde** |
| `Task0015FinanceIntegrationTest` (novos métodos, PostgreSQL) | **não executado** — Docker indisponível |
| `npx vitest run` (frontend completo) | **120/120 verde** |
| `tsc --noEmit`, `npm run lint`, `npm run build` | **limpos** |
| `git diff --check` | limpo |

Mesmo bloqueio de ambiente documentado na TASK-0017 (Docker Desktop travando com socket AF_UNIX
preso + pressão de memória na máquina). A lógica de cálculo do dashboard tem cobertura unitária
verde; a prova matemática contra um fluxo real de ponta a ponta (PostgreSQL) está pronta no código
mas não rodou ainda.

## Fora do escopo

DRE contábil completa, contabilidade fiscal, NF-e, custo de mão de obra, BI avançado, gráficos,
CI/CD automatizado, multi-tenant/SaaS.

## Histórico

- 2026-09-29 — Task criada. Backend do dashboard implementado; revisão própria encontrou e corrigiu
  dois problemas antes de qualquer teste rodar: (1) `dashboardExpensesRegistered` comparava
  `created_at` (TIMESTAMPTZ) no fuso da sessão do banco em vez do fuso da oficina — corrigido com
  `at time zone 'America/Sao_Paulo'`; (2) `coalesce(sum(numeric), 0)` podia voltar com escala menor
  que 2 quando vazio — normalizado. Frontend do dashboard e administração de usuários implementados
  em paralelo, 120 testes verdes. Infraestrutura de deploy implementada e commitada. Testes E2E do
  dashboard escritos mas não executados por falta de Docker. Task mantida em `REVIEW`.
