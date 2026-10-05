# TASK-0019 — Fechamento do MVP/piloto para homologação

## Identificação

- Status: `DONE` — auditoria executada, defeitos corrigidos, gates verdes (backend e frontend no CI e localmente,
  stack Docker validado); pendências de produto registradas em Decision Requests
- Prioridade: `HIGH`
- Criada em: `2026-10-05`
- Origem: pedido do proprietário para finalizar o MVP/piloto com evidência objetiva, sem confiar em "parece pronto"
- Proprietário principal: Orquestrador (`AG-00`), com QA (`AG-13`), Segurança (`AG-09`), DevOps (`AG-14`) e
  Revisor (`AG-15`)
- Revisão: [`docs/review/TASK-0019-revisao-fechamento.md`](../../docs/review/TASK-0019-revisao-fechamento.md)

## Objetivo

Verificar de verdade o que `TASK-0001`–`0018` entregaram (backend, frontend, banco, segurança, deploy), corrigir os
defeitos técnicos evidentes com testes que os reproduzam, e transformar tudo que depende de decisão de produto em
Decision Request — sem inventar regra de negócio, financeira, fiscal ou de comissão e sem implementar módulos sem contrato.

## Entregue

| Área | O que mudou |
| --- | --- |
| IAM | `PasswordPolicy` (8 caracteres a 72 bytes) na troca de senha e no bootstrap; respostas 401/403 em UTF-8 |
| Estoque/Financeiro | estorno de baixa da OS passa a sair do custo histórico de peças |
| Frontend | tela do Caixa corrigida (204), formulários do Caixa, layout mobile, guards de rota por permissão, mensagens de erro em português |
| Deploy | `compose.yaml` é o stack do piloto; volume do PG18; `.env.example` sem segredo; perfil `prod` sem senha padrão; `SESSION_COOKIE_SECURE`; nginx (IP real, cabeçalhos, CSP, limite de login); healthchecks por IPv4 |
| CI | job `deploy-smoke` (stack Docker + E2E + reinício + backup + restore) e `migrations-immutable` (PR) |
| Testes | `PasswordPolicyTest`, `Task0019SecurityHardeningIntegrationTest`, fechamento concorrente do caixa, custo × estorno, 15 testes frontend novos, `scripts/e2e/workshop_flow.py` (103 verificações) |
| Documentação | README com o estado real, `DEPLOY-piloto.md` validado, `AGENTS.md` reconciliado, contrato IAM, relatório de revisão |
| Decisões | `DR-0019` (item rejeitado × estoque/custo), `DR-0020` (autenticação), `DR-0021` (escopo dos módulos pendentes) — todas `OPEN` |

## Migrations

Nenhuma. As 21 migrations existentes permanecem imutáveis; nenhuma correção exigiu mudança de schema.

## Fora do escopo (decisão `DR-0021`)

Compras e Fornecedores, Comissões, Conciliação bancária, Fiscal/NFS-e, rentabilidade avançada, garantia avançada,
reserva de estoque, multi-tenant. Não foram implementados: não há Task, requisito fechado e Decision Request para
eles, e implementá-los "para constar" contraria a regra de escopo do `AG-00` (seção 24).

## Checkpoint

| Gate | Resultado |
| --- | --- |
| `mvn compile` | verde |
| `mvn clean test` (PostgreSQL 18 real, Testcontainers) | **259 testes, 0 falhas, 0 erros, 0 skips** — local (9m56s) e no GitHub Actions ([run 64](https://github.com/Wendersonjose/uberhidraulica-erp/actions/runs/37346397823), 5m54s) |
| Suíte sem Docker (inclui `ModularityTest`) | 74 testes verdes |
| Frontend | 17 arquivos, **135 testes** verdes; `tsc`, `oxlint`, `vite build` limpos |
| Banco do zero | 21 migrations em PostgreSQL 16 e 18 vazios + `ddl-auto=validate`; reconstruído a cada classe de integração |
| E2E por HTTP | 103/103 (direto e atrás do nginx) |
| Navegador (Chromium) | 48/48 |
| `deploy-smoke` no CI | SMOKE OK: build das imagens, saúde, E2E pelo nginx, reinício, backup, restore em volume novo, 429 no login |
| `git diff --check` | limpo |

Falhas observadas e corrigidas no caminho (nenhuma escondida): run `7e14264` do `deploy-smoke` falhou por healthcheck
IPv6 do nginx — corrigido e reexecutado até passar.

## Histórico

- 2026-10-05 — Baseline do `main` (247 testes backend verdes, 120 frontend verdes). Reconstrução do banco do zero,
  E2E por HTTP, sondagem de segurança, navegação no Chromium e execução do deploy documentado. O stack Docker do piloto
  não subia (volume do PG18, `compose.yaml` × `docker-compose.yml`, `.env.example`); o proxy deixava forjar o IP da
  evidência do orçamento público; a tela do Caixa falhava no caso normal; senha acima de 72 bytes dava 500.
  Correções com testes que reproduzem cada defeito; decisões de produto registradas em `DR-0019`–`DR-0021`.
