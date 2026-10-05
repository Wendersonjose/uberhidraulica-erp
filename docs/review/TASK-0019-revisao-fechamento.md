# TASK-0019 — Revisão de fechamento do MVP/piloto

- Data: `2026-10-05`
- Base auditada: `main` em `3ba18e1` (merge do PR #1, "concluir sprint catálogo e caixa físico")
- Branch de trabalho: `feature/finalizacao-mvp-oficina`
- Veredito: **`READY_FOR_HOMOLOGATION`** — **não** `READY_FOR_PRODUCTION` (ver "Bloqueadores de produção")

Esta revisão **não** confia na documentação existente: cada afirmação abaixo foi verificada executando o código,
o banco, o navegador ou o Docker. Nada aqui é "parece implementado".

## 1. Método

1. Leitura de `AGENTS.md`, dos agentes, das Tasks `0001`–`0018`, das `DR-0001`–`DR-0018`, de `docs/` e do deploy.
2. Baseline dos gates **antes de qualquer alteração**, no `main`.
3. Reconstrução do banco do zero (PostgreSQL 16 local vazio, depois PostgreSQL 18 em container), subindo a aplicação.
4. Roteiro E2E por HTTP real (`scripts/e2e/workshop_flow.py`) com caminhos negativos.
5. Sondagem de segurança direta (cookies, cabeçalhos, formato de erro, força bruta, senhas, IP, segredos, logs).
6. Navegação do SPA no Chromium contra o stack real (CSP, console, rotas, celular, fluxos de Caixa e link público).
7. Execução do deploy documentado com Docker, incluindo reinício, backup, destruição do volume e restore.
8. Correção do que for defeito técnico evidente; Decision Request para o que for decisão de produto.

## 2. Baseline (`main` sem alterações)

| Gate | Resultado |
| --- | --- |
| `mvn compile` | OK |
| `mvn clean test` (PostgreSQL 18 real via Testcontainers) | **247 testes, 0 falhas, 0 erros, 0 skips** — BUILD SUCCESS (9m59s) |
| Subconjunto sem Docker (unitários + arquitetura + Modulith) | 69 testes, 0 falhas |
| Frontend `vitest` | 14 arquivos, 120 testes, 0 falhas |
| `tsc --noEmit`, `oxlint`, `vite build` | limpos (aviso de chunk > 500 kB, não bloqueante) |
| 21 migrations Flyway em PostgreSQL vazio + `ddl-auto=validate` | OK |
| `docker compose up -d --build` na raiz | **falhava** (achados F10 e F11) |

A `TASK-0018` registra 243 testes no CI; a contagem local no mesmo commit é 247 (`Task0016` e testes posteriores).
Nenhum teste foi removido, desativado (`@Disabled`) ou pulado.

## 3. Achados e disposição

Severidade: **A** alta, **M** média, **B** baixa. "Defeito" foi corrigido nesta Task; "Decisão" virou Decision Request.

| ID | Sev. | Área | Achado (evidência) | Disposição |
| --- | --- | --- | --- | --- |
| F11 | A | Deploy | `postgres:18-alpine` aborta (`Exited (1)`) com volume em `/var/lib/postgresql/data`; o stack do piloto **nunca subiu** | Defeito — mount em `/var/lib/postgresql` |
| F10 | A | Deploy | `docker compose up -d --build` escolhe `compose.yaml` (Postgres de desenvolvimento, porta 5432 pública, senha padrão) e ignora `docker-compose.yml` | Defeito — `compose.yaml` é o stack; dev em `compose.dev.yaml` |
| F1 | A | Deploy | `.env.example` com `prod,supabase`; o compose não repassa `SUPABASE_*`; a doc afirmava outro padrão | Defeito |
| F9 | A | Segurança/Auditoria | nginx anexava ao `X-Forwarded-For`; o backend usa o 1º valor: o cliente forjava o IP gravado na evidência da decisão pública (testado: `198.51.100.1` gravado) | Defeito — nginx sobrescreve com o IP da conexão; verificado no smoke |
| FE-1 | A | Frontend | tela do Caixa físico mostrava "Não foi possível carregar o caixa" no caso normal (204 → `undefined` rejeitado pelo TanStack Query v5); a página não tinha testes | Defeito — 7 testes novos; regressão reproduzida sem a correção |
| F8 | M | IAM | trocar senha com > 72 bytes → HTTP 500; senha de 1 caractere aceita (a tela exigia 8) | Defeito — `PasswordPolicy` |
| F4 | M | IAM | corpos JSON de 401/403/`PASSWORD_CHANGE_REQUIRED` em ISO-8859-1 (byte `0xE7` inválido em UTF-8) | Defeito |
| F2 | M | Deploy | perfil `prod` marca o cookie `Secure`; em homologação HTTP a sessão nunca persiste | Defeito — `SESSION_COOKIE_SECURE`, documentado |
| F12 | M | Deploy | `add_header` em `location /` e `/assets/` anulava os cabeçalhos de segurança (herança do nginx); `X-Forwarded-Proto` do balanceador era sobrescrito | Defeito — includes, CSP validada no Chromium |
| F13 | M | Segurança | `DB_PASSWORD` com padrão `uberhidraulica_dev` também em `prod`; `${VAR}` vazio passava no compose | Defeito — sem padrão em `prod`, `:?` no compose |
| F5a | M | Estoque/Financeiro | estorno manual de baixa da OS devolvia o saldo mas não saía do custo de peças do painel | Defeito — SQL + teste em PostgreSQL (falha sem a correção) |
| FE-2 | M | Frontend | celular (390 px): rolagem horizontal da página (sidebar e painel) | Defeito |
| F15 | A | Deploy | healthcheck do nginx (`wget localhost` → `::1`; nginx só escuta IPv4) deixava o container `unhealthy` em runner com IPv6 e quebrava `docker compose up --wait` (visto só no CI) | Defeito — `127.0.0.1` nos healthchecks |
| FE-3 | B | Frontend | contagem do fechamento/abertura do Caixa nascia com valor antigo e exigia justificativa inexistente | Defeito |
| F5b | M | Domínio | item físico **rejeitado** pelo cliente continua baixado e entra no custo de peças; não há remoção de item da OS | **Decisão — DR-0019** (contorno: estornar a baixa) |
| F7a | M | IAM | sem bloqueio de conta por tentativas (30 falhas seguidas → 401 e depois login OK) | Mitigação no nginx (6/min por IP); **Decisão — DR-0020** |
| F7b | M | IAM | `IAM_USERS_MANAGE` permite criar usuário com perfil `DONO` (escalada se o Dono delegar) | **Decisão — DR-0020**; contorno: não delegar `IAM_*` |
| F14 | B | Frontend/API | erros do framework (404/405/415/400 de UUID) fora do formato `{code,message,details}`; sufixo de unidade de galão/balde | Mensagens em português no frontend; sufixos corrigidos; formato do backend → pós-MVP |
| F16 | B | Backend | aviso do Hibernate Validator `HV000271` (`@Valid` em `List`) a cada requisição | Defeito — `List<@Valid X>`, comportamento idêntico |
| — | — | Escopo | Compras, Comissões, Conciliação, Fiscal sem Task/DR/código | **Decisão — DR-0021** |

## 4. O que foi verificado e está correto

- **Arquitetura**: `ModularityTest` (Spring Modulith) verde; fronteiras declaradas em `package-info.java`; fluxo
  Controller → serviço de aplicação → domínio → porta → adaptador; eventos de domínio na mesma transação (a OS
  publica, Estoque e Financeiro reagem; recusa por saldo desfaz a OS). Sem Kafka/RabbitMQ/microserviços.
- **Concorrência e idempotência**: testes de PostgreSQL já existentes cobrem recebimento concorrente (não excede o
  saldo), retry com a mesma `Idempotency-Key`, estorno concorrente, baixa de estoque concorrente, finalização × apresentação ×
  decisão, login concorrente, bootstrap concorrente, último Dono. **Adicionado**: fechamento concorrente do caixa.
- **Banco**: 21 migrations aplicam em PostgreSQL 16 e 18 vazios; `NUMERIC` para dinheiro/quantidade; `TIMESTAMPTZ`;
  `CHECK`s de saldo não negativo, direção/tipo de movimento, sessão única de caixa aberta, índices únicos de
  idempotência/estorno; sem SQL manual escondido (o schema do zero reconstrói o sistema).
- **Autenticação/sessão**: cookie `HttpOnly` e `SameSite=Lax`; fixação de sessão trocada no login; uma sessão por
  usuário; CSRF obrigatório em mutações (ausente e inválido → 403); troca obrigatória de senha bloqueia o resto da API;
  e-mail inexistente e senha errada respondem igual; senha temporária nunca volta em listagens nem em log.
- **Autorização**: `@PreAuthorize` por permissão em toda escrita e no financeiro; matriz Dono/Gerente
  Administrativo/Gerente Financeiro conferida pelo E2E (403 onde deve) e por `ModulePermissionsIntegrationTest`.
- **Link público**: token de 256 bits, só o SHA-256 no banco, resposta `no-store`/`no-referrer`, sem dado interno,
  token inválido/malformado → 404, versão obsoleta → 409, retry idempotente, aceite explícito obrigatório, sem cookie.
- **Segredos**: nenhum segredo, `.env`, chave ou certificado versionado (inclusive no histórico git); logs do backend,
  do nginx e do Postgres sem senha, senha temporária ou token; stack traces não vazam; `/actuator` expõe só `health`.
- **Frontend (Chromium)**: login, 14 rotas sem erro de console/CSP, sessão removida volta ao `/login`, Caixa
  (abrir/suprimento/fechar), link público (cliente aprova um item), celular 390 px sem rolagem horizontal.

## 5. Verificação das Tasks `DONE` contra os critérios

O comportamento de cada Task foi exercitado pelos testes de integração existentes (247, todos verdes) e pelo E2E novo.
Duas Tasks marcadas `DONE` **não cumpriam** seus critérios na prática e foram reabertas tecnicamente:

- `TASK-0016` (Caixa físico): o backend estava correto, mas a tela principal falhava no caso normal (FE-1) e nenhum
  teste frontend cobria a página.
- `TASK-0018` (Deploy do piloto): "Docker/deploy implementados" nunca tinha sido executado; o stack não subia (F10/F11/F1).

Também corrigidos, sem reabrir: `TASK-0003` (política de senha, charset), `TASK-0014`/`0015` (custo de peças × estorno).

## 6. Resultado dos gates finais

Branch `feature/finalizacao-mvp-oficina`, commit `064f477` (CI) e a mesma árvore localmente.

| Gate | Resultado |
| --- | --- |
| `mvn compile` | OK |
| `mvn clean test` local (PostgreSQL 18 real, Testcontainers) | **259 testes, 0 falhas, 0 erros, 0 skips** — BUILD SUCCESS (9m56s) |
| Testes de integração em PostgreSQL | todos executados (nenhum pulado); 259 = 247 do baseline + 12 novos |
| Subconjunto sem Docker (inclui `ModularityTest`) | 74 testes, 0 falhas |
| `ModularityTest` (Spring Modulith) | OK |
| CI GitHub Actions — job `test` | **259 testes, 0 falhas, 0 erros, 0 skips** — BUILD SUCCESS (5m54s), [run 64](https://github.com/Wendersonjose/uberhidraulica-erp/actions/runs/37346397823) |
| Frontend (local e CI) | 17 arquivos, **135 testes**, 0 falhas; `tsc --noEmit`, `oxlint` e `vite build` limpos |
| CI — job `deploy-smoke` (runner com Docker) | **SMOKE OK**: builds do Maven e do Node, PG 18, 21 migrations do zero, healthchecks, E2E pelo nginx, IP forjado não gravado, reinício com dados, backup, `down -v`, restore, login após restore, 429 no login |
| E2E por HTTP (`workshop_flow.py`) | 103/103 verificações (PG 16 direto e PG 18 atrás do nginx) |
| Navegador (Chromium) | 48/48 verificações (CSP, 14 rotas, Caixa, link público, celular) |
| `git diff --check` | limpo |

Descoberto só no CI: o healthcheck do nginx falhava em runner com IPv6 (`wget localhost` → `::1`, nginx só IPv4) e
travava o `docker compose up --wait` (**F15**, corrigido: `127.0.0.1`). O primeiro run do job (`7e14264`) falhou por
isso; o run seguinte, com a correção, passou inteiro.

O job `migrations-immutable` só roda em Pull Request.

## 7. Bloqueadores de produção

1. **HTTPS**: depende do provedor; sem ele o cookie `Secure` não persiste e a senha trafega em claro. Não resolvido.
2. Ambiente real de hospedagem, domínio e **backup copiado para fora do servidor** (o restore foi validado em Docker,
   não no ambiente final).
3. Se houver balanceador do provedor: configurar `set_real_ip_from` (senão o limite por IP e a evidência de IP usam o IP do balanceador).
4. `DR-0020` decidida ou risco aceito por escrito (bloqueio de conta; delegação do perfil Dono) antes de expor na internet.

## 8. Pendências pós-MVP (não bloqueiam homologação)

- `DR-0019`: item rejeitado × estoque/custo (contorno: estornar a baixa).
- `DR-0021`: Compras, Comissões, Conciliação, Fiscal/NFS-e, rentabilidade avançada, garantia avançada.
- Reserva de estoque/inventário; composição de kits e aplicações por veículo.
- Formato de erro `{code,message,details}` para erros do framework (404/405/415/400 de UUID).
- Chunk do frontend de ~600 kB (code splitting).
- Observabilidade avançada (métricas/tracing); OpenAPI gerado; Transactional Outbox quando houver integração externa.

## 9. Como reproduzir

```bash
mvn clean test                                  # backend completo (Docker)
cd frontend && npm ci && npm test -- --run && npx tsc --noEmit && npm run lint && npm run build
python3 scripts/e2e/workshop_flow.py            # E2E (variáveis no cabeçalho do arquivo)
scripts/deploy/compose-smoke.sh                 # stack Docker: E2E pelo nginx, reinício, backup, restore
```
