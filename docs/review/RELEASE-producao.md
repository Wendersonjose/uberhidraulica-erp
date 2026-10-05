# Preparação para produção — classificação, evidências e o que falta (TASK-0020)

Data: 2026-10-05 · Base: `main` após o merge da PR #2 (`a712c11`) · Branch: `release/homologacao-producao`

## Veredito

```text
Estado: READY_FOR_HOMOLOGATION  (mantido)
READY_FOR_PRODUCTION: NÃO declarado
```

O sistema, o stack Docker, o HTTPS com proxy confiável, o backup, o restore, a atualização e o rollback foram exercitados
com containers reais e uma jornada completa no navegador. O que impede declarar produção **não é código pendente**: é o que
só um servidor real e o proprietário podem provar ou decidir (seção 2). Declarar `READY_FOR_PRODUCTION` agora seria afirmar
HTTPS com certificado real, backup externo restaurado e decisões de segurança que não existem.

## 1. Matriz de prontidão

| Área | Estado | Evidência |
| --- | --- | --- |
| BACKEND | `[OK]` | `mvn clean test` em PostgreSQL 18 real: **276 testes, 0 falhas, 0 erros, 0 skips**; guarda de produção com 15 testes |
| FRONTEND | `[OK]` | 137 testes, `tsc`, `oxlint`, `vite build`; jornada de 26 passos e 18 caminhos negativos no Chromium |
| BANCO | `[OK]` | Flyway V1–V21 em banco vazio com `ddl-auto=validate`; sem migration nova nesta fase; restore validado |
| SEGURANÇA | `[OK]` na implementação · `[BLOQUEADO]` por decisão | produção falha fechada, proxy confiável, token fora dos logs; **DR-0020 sem decisão** |
| E2E | `[OK]` | `scripts/e2e/workshop_flow.py` 107/107 pelo nginx e pelo HTTPS; navegador 26/26 + 18/18 |
| INFRAESTRUTURA | `[OK]` em laboratório · `[BLOQUEADO]` em servidor real | compose de 4 containers, saúde, restart, `down`/`up`, update, rollback; sem servidor real |
| HTTPS | `[BLOQUEADO]` | redirect 308, HSTS, cookie `Secure` provados com a CA interna do Caddy; **certificado real não emitido** |
| BACKUP | `[BLOQUEADO]` | `backup.sh` valida o arquivo e copia por gancho; **destino externo real não existe** |
| RESTORE | `[OK]` em laboratório · `[BLOQUEADO]` a partir da cópia externa | volume destruído e banco vivo; `verify-backup.sh` em banco descartável |
| ROLLBACK | `[OK]` | versão anterior sobre os mesmos dados; `update.sh` registra o commit anterior e o backup |
| CI | `[A CONFIRMAR NO PR]` | ver seção 6 |

## 2. BLOQUEADORES DE PRODUÇÃO

Cada item só some quando a evidência pedida existir. Nenhum se resolve com mais código neste repositório.

| # | Bloqueador | Por que bloqueia | Evidência que fecha |
| --- | --- | --- | --- |
| B1 | HTTPS com certificado **real** em domínio real | o laboratório usa a CA interna do Caddy; Let's Encrypt exige domínio público e portas 80/443 | `PUBLIC_URL=https://<domínio> scripts/ops/preflight.sh` sem nenhuma FALHA (certificado aceito, HSTS, cookie `Secure`) |
| B2 | Backup **externo** real e restore a partir dele | o script e o gancho funcionam; o destino externo é do operador e nunca foi exercitado de verdade | `BACKUP_EXTERNAL_CMD` configurado, agendado e `verify-backup.sh` rodado sobre a cópia **baixada de volta** |
| B3 | Homologação operacional em servidor real | tudo foi validado em um host de laboratório | deploy real com `scripts/ops/update.sh`, E2E contra o servidor, um ciclo de restart e um de rollback |
| B4 | CI verde no PR final e varredura de dependências Maven | o banco OSV não é alcançável do ambiente de validação; só o CI consulta | job `dependency-scan` verde (sem HIGH/CRITICAL) e demais jobs verdes |
| B5 | **DR-0020** — decisão do proprietário | força bruta lenta e senha fraca não têm controle no backend; só o proprietário aceita o risco ou manda resolver | decisão registrada, ou aceite do risco residual por escrito |

Itens que dependem do proprietário e **não bloqueiam se aceitos**: `DR-0019` (estorno manual de estoque, seção 3).

Controle em vigor para o que ficou em aberto: `scripts/ops/preflight.sh` falha se existir permissão `IAM_*` fora do Dono
(delegação de `IAM_USERS_MANAGE` permitiria criar um Dono — `DR-0020` item 3). **Não delegue `IAM_*` a gerentes em produção.**

## 3. PENDÊNCIAS DE HOMOLOGAÇÃO

O que a oficina e o operador exercitam no ambiente real; não impedem uma homologação, e podem virar bloqueador se falharem.

| Item | Observação |
| --- | --- |
| Primeiro dia: categorias de despesa e formas de pagamento | um banco novo vem sem categoria de despesa (as formas de pagamento são semeadas). A tela agora orienta, mas alguém precisa cadastrar (Financeiro › Configurações) |
| `DR-0019` — item físico rejeitado fica baixado do estoque e entra no custo de peças | **limitação conhecida do piloto**, reproduzida na jornada do navegador. Contorno: Estoque › produto › movimentações › **Estornar** a baixa da OS. O recebível já fatura só o aprovado. Só deixa de ser limitação com a decisão do proprietário; aceitar o processo de estorno não bloqueia a produção |
| Balanceador do provedor (modo B) | `TRUSTED_PROXY_CIDRS` com o balanceador real: provado só com um balanceador simulado em container |
| Limite de login | `burst=20` permite 21 tentativas erradas por minuto por IP antes do 429, depois 6/min. Ajustar com o volume real (`DR-0020`, item 1) |
| Duplo envio no mesmo instante | dois `click()` no mesmo tick criam dois clientes (`POST /api/customers` não tem chave de idempotência); duplo clique humano não duplica. Baixa gravidade; vale conferir nos demais cadastros |
| Erros fora do contrato `{code,message}` em caminhos malformados | `/orcamento/..%2F..` mostra a página 400 do nginx; `GET /api/customers/<não-uuid>` volta 400 sem corpo; sem stack trace. Cosmético |
| Log de erro do nginx | em falha do backend, o nginx grava a linha da requisição no log de **erro**, inclusive a URL do link público. O log de acesso mascara o token; o de erro não tem como. Restrinja o acesso aos logs e mantenha a retenção curta |
| Aparelhos reais do cliente | a página pública foi validada no Chromium a 420 px; falta passar por celulares reais |

## 4. PENDÊNCIAS PÓS-MVP

Não implementados e **não** deve ser assumido que existem (`DR-0021`, nenhuma Task aberta, nada foi implementado nesta fase):

Compras e Fornecedores · Comissões · Conciliação bancária (Itaú, Rede, OFX, CSV) · Fiscal/NFS-e · rentabilidade avançada e
precificação · garantia avançada · reserva de estoque, inventário e perdas · segundo fator para o Dono · OpenAPI gerada ·
Transactional Outbox · segunda autorização do Dono em ações críticas · DRE e custo de mão de obra · multi-tenant · integrações
externas (WhatsApp etc.).

## 5. Achados desta fase

| # | Achado | Gravidade | Situação |
| --- | --- | --- | --- |
| 1 | Produção aceitava senha de banco ausente/fraca e cookie sem `Secure` por configuração | alta | **corrigido** — `ProductionStartupGuard` recusa subir (antes de abrir conexão) e nunca repete o valor; prova com a imagem real do backend |
| 2 | nginx confiava em `X-Forwarded-*` conforme um bloco comentado; atrás de balanceador era preciso editar o arquivo | alta | **corrigido** — `TRUSTED_PROXY_CIDRS`; de origem não confiável os cabeçalhos são ignorados; valor que aceitaria qualquer origem derruba o container; 37 verificações reais, com mutação que confia em todos |
| 3 | O token do link público aparecia no log de acesso do nginx (URI e Referer) | média | **corrigido** — URI e Referer mascarados; teste falha contra o log padrão |
| 4 | O Caddy com IP fixo colidia com a distribuição automática de IPs do Docker | média | **corrigido** — `ip_range` na metade baixa da sub-rede, Caddy na alta; achado em laboratório |
| 5 | D1: OS cancelada aceitava decisão por link já emitido; OS finalizada/entregue/cancelada aceitava orçamento, revisão, apresentação e link novos | média | **corrigido** — 409 `WORK_ORDER_CLOSED`; testes de integração; navegador N09/N14 passam |
| 6 | D2: tabela da página pública escondia "Sua decisão" no celular | média | **corrigido** — cartões abaixo de 640 px |
| 7 | D3: violação de CSP a cada carga (zod testava `new Function`) | baixa | **corrigido** — `z.config({ jitless: true })` |
| 8 | D4: select de categoria vazio em banco novo | baixa | **corrigido** — aviso com link |
| 9 | `APP_ENVIRONMENT` declarado sem o perfil `prod` (erro de digitação) faria valer os padrões de desenvolvimento | média | **corrigido** — a subida é recusada |
| 10 | Sem varredura automática de dependências | média | **corrigido** — OSV (Maven) + `npm audit` no CI, Dependabot semanal; `npm audit` local: 0 vulnerabilidades |
| 11 | D5, D6, D7 | baixa/média | **abertos**, listados na seção 3 e na `DR-0020` |

## 6. Evidências

| Gate | Resultado |
| --- | --- |
| `mvn clean test` (PostgreSQL 18, Testcontainers) | **276 testes, 0 falhas, 0 erros, 0 skips** (259 da TASK-0019 + 15 da guarda de produção + 2 de orçamento em OS encerrada), 6m52s |
| Frontend `npm ci` · `npm test -- --run` · `oxlint` · `tsc` · `vite build` | 17 arquivos, **137 testes** verdes; lint e build limpos |
| `git diff --check` | limpo |
| `proxy-trust-check.sh` | 37/37 (a mutação "confiar em todos" derruba 4; a mutação "log sem máscara" derruba 4) |
| E2E `workshop_flow.py` | 107/107 contra o nginx e contra o HTTPS do Caddy |
| Navegador (Chromium, `https://localhost`) | jornada 26/26 · negativos 18/18 depois das correções (antes: 26/26 e 16/18) |
| Compose smoke (`compose-smoke.sh`) | build, E2E, evidência de IP, restart, backup/verify/restore, 429, troca HTTP→HTTPS sobre os mesmos dados |
| Persistência | `docker compose restart` e `down` + `up -d`: contagens idênticas, login e consultas de cliente, OS, estoque e financeiro |
| Sessão expirada | com a sessão vencida: GET 401, POST recusado; novo login funciona |
| Logs | 10 MB × 3 por container; sem senha, token de link, cookie nem hash de senha |
| Restore | volume destruído e banco vivo: dados, login e histórico do Flyway íntegros; sessões antigas invalidadas |
| Atualização e rollback | `update.sh` com backup e cópia externa simulada; versão anterior sobre os mesmos dados; migrations 21→21 |
| `npm audit` | 0 vulnerabilidades |

### Auditoria de segredos e dados pessoais

- Nenhum arquivo `.env`, `.pfx`, `.p12`, `.pem`, `.key`, `.jks` ou `.dump` em qualquer commit do histórico.
- Nenhum padrão de credencial conhecida (chave privada, AWS, GitHub, Slack, JWT) em todo o histórico.
- Valores de senha/segredo no código só como nomes de variável e tipos; o único padrão de desenvolvimento
  (`DB_PASSWORD:uberhidraulica_dev`, `application.yml`) é recusado pelo perfil `prod`.
- Logs do backend: nenhuma chamada de log com senha, token, e-mail, documento ou telefone; sem stack trace nas respostas.

### Caminhos negativos (Fase 5) e onde estão provados

| Caminho | Prova |
| --- | --- |
| Sem permissão | E2E (vários 403) e navegador N01/N02 |
| Sessão expirada | `restart-check` (401 com sessão vencida) e navegador N03 |
| CSRF inválido | E2E e navegador N04 |
| Rota não autorizada por URL | navegador N01/N02 ("Acesso negado", API 403) |
| Orçamento expirado | `expiredAccessAnswersGone` (410) |
| Versão obsoleta | E2E, `aPresentedNewerVersionBlocksTheDecisionOnTheOldOne`, navegador N10 |
| Duplo clique / retry | E2E (idempotência), navegador N06 |
| Estoque insuficiente | E2E e navegador N07 |
| Dinheiro sem caixa aberto | E2E e navegador N08 |
| Editar OS encerrada ou orçamento em OS encerrada | E2E, navegador N09/N14 e `aCancelledWorkOrderRefusesDecisionsAndEveryNewCommercialChange` |
| Tentativa concorrente | `Task0014/0015/0016` e `IamAuthenticationIntegrationTest` (PostgreSQL real) |
| Link público inválido | E2E (404) e navegador N11 |
| IDOR | navegador N12 (15 casos: 404/403/400, nunca 500 nem dado alheio); a decisão fora da revisão é barrada no banco (`postgresRejectsDecisionsOutsideTheSubmissionRevision`) |
| Senha curta / acima de 72 bytes | `PasswordPolicyTest`, `Task0019SecurityHardeningIntegrationTest`, navegador N05 |
| Login repetido até o limite | smoke (429) e navegador N13 (429 na 22ª tentativa; liberado em 75 s) |

## 7. Limites do ambiente onde isto foi validado

- Sem domínio público e sem acesso aos bancos de vulnerabilidades (OSV/NVD): a varredura Maven só roda no CI.
- O build das imagens dentro do container não valida o TLS do proxy deste ambiente; o laboratório usa o **mesmo estágio de
  runtime** dos Dockerfiles com o jar e o `dist` construídos fora. O build real das imagens roda no job `deploy-smoke` do CI.
- Docker Hub com limite anônimo de pulls: nenhum espelho foi usado.

## 8. Do `READY_FOR_HOMOLOGATION` ao `READY_FOR_PRODUCTION`

1. Escolher o modo de HTTPS (A, B ou C da `DEPLOY-piloto.md`) e subir em um servidor real.
2. `PUBLIC_URL=https://<domínio> scripts/ops/preflight.sh` sem nenhuma FALHA (B1).
3. Configurar `BACKUP_EXTERNAL_CMD`, agendar, e rodar `verify-backup.sh` sobre a cópia baixada de volta (B2).
4. Um ciclo real de `update.sh` e um de rollback no servidor, com a oficina (B3).
5. CI verde no PR final, inclusive `dependency-scan` (B4).
6. O proprietário decide a `DR-0020` (ou aceita o risco por escrito) e confirma o processo de estorno da `DR-0019` (B5).
7. Só então trocar o estado do `README.md` para `READY_FOR_PRODUCTION`.
