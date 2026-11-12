# Deploy do piloto — uma oficina

Guia para colocar a primeira oficina no ar com Docker Compose, em homologação ou em produção. Não pressupõe um provedor
de hospedagem específico. O que depende do ambiente real (domínio, certificado emitido, destino do backup externo) está
marcado como **não validado neste repositório** na seção 12.

> **Estado: `READY_FOR_HOMOLOGATION`.** O sistema, o stack Docker, o HTTPS com proxy confiável, o backup, o restore, a
> atualização e o rollback foram exercitados com containers reais (ver seção 11). `READY_FOR_PRODUCTION` só vale depois
> que a seção 12 for cumprida em um servidor real e as decisões do proprietário (`DR-0019`, `DR-0020`) estiverem
> registradas. Veja `docs/review/RELEASE-producao.md`.

## 1. Visão geral

Quatro containers com HTTPS turnkey (`compose.yaml` + `compose.https.yaml`), três sem ele:

```text
Internet ──HTTPS──> caddy (80/443) ──HTTP──> frontend (nginx) ──> backend (Spring Boot :8080) ──> postgres 18
                    └ certificado Let's Encrypt automático
                    └ único proxy confiável (TRUSTED_PROXY_CIDRS)
```

Três maneiras de publicar; escolha **uma**:

| Modo | Quando | Arquivos | `APP_ENVIRONMENT` | `SESSION_COOKIE_SECURE` |
| --- | --- | --- | --- | --- |
| **A. HTTPS turnkey (Caddy)** | VPS simples, sem balanceador do provedor | `compose.yaml` + `compose.https.yaml` | `production` | `true` |
| **B. Balanceador HTTPS do provedor** | o provedor já termina o TLS | `compose.yaml` | `production` | `true` |
| **C. HTTP puro (homologação interna)** | testes sem domínio, rede interna | `compose.yaml` | `homologation` | `false` |

O frontend e o backend respondem na mesma origem (o nginx encaminha `/api/**`): não há CORS para configurar.
O PostgreSQL isolado para desenvolvimento local (backend e frontend fora do Docker) fica em `compose.dev.yaml`.

## 2. Pré-requisitos

- Docker e Docker Compose v2 (2.24 ou mais novo, por causa de `!reset` no `compose.https.yaml`) no servidor.
- Modo A: um domínio com o DNS apontando para o servidor e as portas **80 e 443** abertas (o Let's Encrypt valida pela 80).
- Modo B: o CIDR (ou IP) do balanceador, para `TRUSTED_PROXY_CIDRS`.
- Um destino **fora do servidor** para os backups (seção 6).

## 3. Configuração

```bash
cp .env.example .env
```

Preencha o `.env` (os segredos vêm **vazios** de propósito: o `docker compose` recusa subir enquanto faltarem, e nenhum
valor de exemplo funciona como senha). Gere cada segredo com `openssl rand -base64 24`. Nunca commite o `.env`.

| Variável | Para quê |
| --- | --- |
| `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | o mesmo trio cria o banco no container e é usado pelo backend |
| `IAM_BOOTSTRAP_OWNER_NAME` / `_EMAIL` / `_PASSWORD` | primeiro Dono; só têm efeito com o banco sem usuários (DR-0002); senha de 8 a 72 bytes, provisória (o primeiro login obriga a trocar); remova as três depois |
| `SPRING_PROFILES_ACTIVE=prod` | padrão |
| `APP_ENVIRONMENT` | `production` (padrão) ou `homologation` — ver seção 5 |
| `SESSION_COOKIE_SECURE` | `true` (padrão); `false` só no modo C |
| `TRUSTED_PROXY_CIDRS` | proxies confiáveis na frente do nginx (seção 4); vazio = ninguém |
| `SITE_ADDRESS`, `COMPOSE_FILE` | só no modo A: o domínio e `COMPOSE_FILE=compose.yaml:compose.https.yaml` |
| `BACKUP_EXTERNAL_CMD`, `BACKUP_KEEP`, `PUBLIC_URL` | operação (seção 6) |
| `HTTP_PORT` | porta publicada pelo nginx no modo B/C (padrão 80) |

## 4. HTTPS e IP real

O ERP grava o IP de quem decide um orçamento público (evidência, TASK-0008) e limita requisições por IP. Os dois
dependem de o nginx saber **quem é o cliente de verdade** — e de não acreditar em um cabeçalho que o próprio cliente
mande. A regra é:

- `X-Forwarded-For` e `X-Forwarded-Proto` só são aceitos de endereços listados em `TRUSTED_PROXY_CIDRS`.
- De qualquer outra origem eles são **ignorados**: vale o IP da conexão.
- Com proxy confiável, o IP do cliente é o primeiro endereço **não confiável** lido da direita para a esquerda
  (`real_ip_recursive`): um valor forjado à esquerda nunca é usado.
- O nginx sobrescreve `X-Forwarded-For` para o backend com esse IP; o backend usa o primeiro valor.
- **Falha fechada:** `TRUSTED_PROXY_CIDRS` inválido ou que aceitaria qualquer origem (`0.0.0.0/0`, `0.0.0.0`, `::`, `::/0`,
  `/1` a `/7` em IPv4, `/1` a `/15` em IPv6) impede o container de subir.

### Modo A — Caddy (HTTPS turnkey)

```bash
# .env
SITE_ADDRESS=erp.suaoficina.com.br
COMPOSE_FILE=compose.yaml:compose.https.yaml
APP_ENVIRONMENT=production
SESSION_COOKIE_SECURE=true

docker compose up -d --build
```

O `compose.https.yaml` põe um Caddy na frente, obtém e renova o certificado, redireciona HTTP→HTTPS (308) e envia
`Strict-Transport-Security`. O nginx do ERP **deixa de publicar porta**; só o Caddy (endereço fixo `172.31.250.250`, rede
`172.31.250.0/24`) é proxy confiável. Se essa faixa já existir na sua rede, troque `ERP_NETWORK_SUBNET`,
`ERP_NETWORK_IP_RANGE` e `ERP_CADDY_IP` juntos. Numa instalação anterior, rode `docker compose down` (os volumes ficam) antes
do primeiro `up` com o override, para a rede ser recriada.

> **Nunca use `docker compose down -v` em produção.** Além do banco, ele apaga o volume `caddy_data` (certificados e
> conta ACME): o Let's Encrypt limita as emissões por semana. Para restaurar dados use `scripts/ops/restore.sh`, que só
> substitui o banco.

Para ensaiar sem domínio, use `SITE_ADDRESS=localhost` (CA interna do Caddy; o navegador avisa que não é confiável).

### Modo B — balanceador HTTPS do provedor

Aponte o balanceador para a porta `HTTP_PORT` do serviço `frontend` e informe o endereço dele:

```bash
TRUSTED_PROXY_CIDRS=10.20.0.0/16      # o IP/CIDR a partir do qual o balanceador alcança o ERP
```

O balanceador precisa enviar `X-Forwarded-For` (com o IP do cliente) e `X-Forwarded-Proto: https`. **Não** liste um
CIDR mais largo do que o necessário: quem estiver na lista pode escolher o IP gravado como evidência.

### Modo C — HTTP puro (somente homologação)

`APP_ENVIRONMENT=homologation` e `SESSION_COOKIE_SECURE=false`. Sem `Secure` o cookie de sessão trafega em claro, então
é só para teste interno e temporário; o backend registra um aviso a cada subida.

### Como comprovar

```bash
docker build -f frontend/Dockerfile -t erp-frontend .
scripts/deploy/proxy-trust-check.sh erp-frontend        # 33 verificações com requisições reais (roda no CI)
PUBLIC_URL=https://erp.suaoficina.com.br scripts/ops/preflight.sh   # o site publicado de verdade
```

## 5. Produção falha fechada

Com o perfil `prod`, o backend **recusa subir** (antes de abrir conexão com o banco) quando:

- `DB_PASSWORD` está ausente, não resolvida, tem menos de 12 caracteres, é um valor conhecido (`postgres`, `admin`,
  `changeme`, `123456`...) ou igual ao usuário do banco;
- `IAM_BOOTSTRAP_OWNER_PASSWORD` é um valor conhecido;
- `SESSION_COOKIE_SECURE=false` sem `APP_ENVIRONMENT=homologation`;
- `APP_ENVIRONMENT` não é `production` nem `homologation`.
- `APP_ENVIRONMENT` está declarado mas o perfil `prod` não está ativo (erro de digitação em `SPRING_PROFILES_ACTIVE`
  faria valer os padrões de desenvolvimento).

A mensagem diz **qual** variável e **por quê**, e nunca repete o valor. O compose também recusa subir sem
`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD`.

## 6. Operação: preflight, backup, verificação e restore

Todos em `scripts/ops/`, a partir da raiz do repositório. Respeitam `COMPOSE_FILE`/`COMPOSE_PROJECT_NAME`.

```bash
scripts/ops/preflight.sh                       # antes de liberar e a cada deploy; exit 1 se houver FALHA
PUBLIC_URL=https://erp.suaoficina.com.br scripts/ops/preflight.sh
scripts/ops/backup.sh                          # dump + validação + cópia EXTERNA
scripts/ops/verify-backup.sh backups/erp-<data>.dump   # restaura em um banco descartável e confere
scripts/ops/restore.sh backups/erp-<data>.dump         # DESTRUTIVO: pede confirmação (ou --yes)
```

**Preflight** lê a configuração real do compose e falha para: perfil sem `prod`; `APP_ENVIRONMENT`/cookie fora do padrão
de produção; senha do banco fraca; porta do backend ou do PostgreSQL publicada fora do host; `SITE_ADDRESS` que não é
domínio público; `TRUSTED_PROXY_CIDRS` vazio; backup externo não configurado; e **qualquer permissão `IAM_*` fora do
Dono** (DR-0020). Com `PUBLIC_URL` confere também: certificado aceito e com mais de 15 dias, redirect HTTP→HTTPS, HSTS,
cabeçalhos de segurança, `/actuator/health` sem detalhes, nenhum outro endpoint do actuator, e cookie com
`Secure`/`HttpOnly`/`SameSite`. `--homologation` converte as exigências exclusivas de produção em avisos.

**Backup** (`pg_dump -F c`): grava em `./backups` (modo 700, fora do git), confere que o `pg_restore` lê o arquivo,
grava o `.sha256` e roda `BACKUP_EXTERNAL_CMD` para copiar **para fora do servidor**:

```bash
BACKUP_EXTERNAL_CMD='scp "$1" "$2" backup@servidor-externo:/srv/erp-backups/'
```

`$1` é o `.dump` e `$2` o `.sha256`. Sem `BACKUP_EXTERNAL_CMD` o script termina com **código 3** (falha fechada): um cron
nunca parece "ok" com o backup só no mesmo disco. `BACKUP_ALLOW_LOCAL_ONLY=1` existe só para homologação. Agende:

```cron
0 3 * * * cd /caminho/do/repositorio && scripts/ops/backup.sh >> /var/log/erp-backup.log 2>&1
```

**Verificação:** um backup que nunca foi restaurado não é backup. `verify-backup.sh` sobe um PostgreSQL descartável,
restaura, confere o checksum, o histórico do Flyway (nenhuma migration com falha) e a existência de um Dono ativo, e lista
as linhas por tabela. Rode contra a **cópia externa** (baixada de volta) ao menos a cada mudança de procedimento.

**Restore** (`restore.sh`): confere o arquivo; guarda o banco atual em `pre-restore-*.dump`; para backend/proxy; recria o
banco e restaura parando no primeiro erro; **apaga as sessões web** (todos entram de novo); sobe o stack (o Flyway valida o
esquema) e espera o health. Serve para banco vivo (voltar a um ponto anterior) e para banco novo (servidor perdido: suba
`docker compose up -d postgres` e rode o restore).

Dados do PostgreSQL 18 ficam em `/var/lib/postgresql/18/docker`; o mount é `/var/lib/postgresql` (montar em
`/var/lib/postgresql/data` faz o container abortar).

## 7. Atualizar e voltar atrás (rollback)

O Flyway só avança: uma migration aplicada não é desfeita ao voltar o código. Migrations publicadas são imutáveis (o CI
recusa PR que edite ou apague uma): a correção é sempre uma migration nova.

```bash
git pull                        # ou git checkout <tag/commit>
scripts/ops/update.sh           # preflight → backup (cópia externa) → build/subida → smoke → registro
```

`update.sh` não usa `down -v`. Registra em `backups/deploy-history.log` a data, o commit novo, o commit **anterior**, o backup
feito antes e as migrations antes/depois. Se o smoke falhar, imprime o rollback exato.

**Rollback sem migration nova** (`migrations` iguais antes/depois):

```bash
git checkout <commit anterior>
docker compose up -d --build
```

**Rollback com migration nova aplicada:** o código antigo não conhece o esquema novo (`ddl-auto: validate` recusa subir).
Restaure o backup feito antes da atualização **e só então** suba o código anterior:

```bash
scripts/ops/restore.sh backups/erp-<data-antes-da-atualização>.dump
git checkout <commit anterior> && docker compose up -d --build
```

Isso perde o que foi gravado depois do backup: por isso o `update.sh` exige um backup recém-feito e a atualização deve
ocorrer fora do horário de uso.

## 8. Logs e observabilidade

- `docker compose logs -f backend|frontend|postgres|caddy` — rotação de 10 MB × 3 arquivos por container.
- `/actuator/health` é o **único** endpoint exposto (`management.endpoints.web.exposure.include: health`,
  `show-details: never`); o nginx devolve 404 para qualquer outro `/actuator/**`.
- Nenhuma senha, senha temporária ou token de sessão é logado; a ausência de erros de stack trace nas respostas é o padrão
  do Spring Boot (`server.error.include-stacktrace=never`). Mantenha essa regra em qualquer log novo.
- Auditoria de negócio e de acesso em `iam.audit_event` (inclui `LOGIN_FAILED`).

## 9. Limites de requisição e cabeçalhos

`deploy/nginx/nginx.conf` limita por IP (resposta 429): `/api/public/**` a 10/min com rajada de 5 e `/api/iam/auth/login`
a 6/min com rajada de 20. Não há bloqueio por conta no backend: ver `DR-0020`. O limite usa o IP real do cliente (seção 4):
girar `X-Forwarded-For` não escapa dele. Toda resposta estática leva `X-Frame-Options`, `nosniff`, `Referrer-Policy`, CSP e
`Permissions-Policy`; o HSTS é enviado pelo Caddy (modo A) ou deve ser ligado no balanceador (modo B).

## 10. Segredos

Nunca versionar senhas, tokens, chaves, certificados privados, `.pfx`/`.p12` ou o `.env` real. A varredura do
histórico inteiro do git (arquivos sensíveis e padrões de credenciais) está registrada em
`docs/review/RELEASE-producao.md`. O `.env` deve ter modo 600 no servidor.

## 11. O que foi validado, e como

| Item | Evidência |
| --- | --- |
| Stack sobe do zero com Flyway V1–V21, Hibernate `validate`, bootstrap do Dono | `scripts/deploy/compose-smoke.sh` (CI `deploy-smoke`) e execução local com containers reais |
| Fluxo E2E da oficina (107 verificações, inclui caminhos negativos) pelo nginx **e** pelo HTTPS do Caddy | `scripts/e2e/workshop_flow.py` |
| Cookie `Secure; HttpOnly; SameSite=Lax`, redirect 308, HSTS, HTTP/2 | smoke seção 8 e laboratório com Caddy |
| IP forjado em `X-Forwarded-For` não é o gravado na evidência; limite por IP real | `scripts/deploy/proxy-trust-check.sh` (33 verificações; falha se o nginx confiar em todos) e E2E |
| Produção recusa credencial fraca/ausente | `ProductionStartupGuardTest` (14 testes) |
| Persistência após `docker compose restart` e `down` + `up` | smoke seção 5 |
| Backup, verificação em banco descartável, restore com volume destruído e com banco vivo, sessões invalidadas | `scripts/ops/*` no smoke e no ensaio de restore |
| Troca HTTP→HTTPS sobre os mesmos dados | smoke seção 8 |

## 12. O que NÃO foi validado aqui (precisa de um servidor real)

- Emissão real do certificado Let's Encrypt (domínio público, portas 80/443) — o laboratório usa a CA interna do Caddy.
- `TRUSTED_PROXY_CIDRS` com o balanceador real do provedor (modo B): o comportamento foi provado com um balanceador
  simulado em container.
- Cópia do backup para um destino externo **real** e a restauração a partir dela: o script e o gancho funcionam, o destino
  real é do operador.
- Desempenho e carga; observabilidade além de logs e health.
- O build das imagens dentro do container foi feito pelo CI (GitHub Actions); em ambientes com inspeção de TLS é preciso
  confiar na CA do proxy no build.

### Checklist antes de produção

- [ ] Modo A ou B escolhido; HTTPS ativo; `SESSION_COOKIE_SECURE=true`; `APP_ENVIRONMENT=production`.
- [ ] `PUBLIC_URL=https://... scripts/ops/preflight.sh` sem nenhuma FALHA.
- [ ] `BACKUP_EXTERNAL_CMD` configurado, backup agendado e **um restore real feito a partir da cópia externa**.
- [ ] Senha do Dono trocada; variáveis `IAM_BOOTSTRAP_*` removidas do `.env`; `.env` com modo 600.
- [ ] Nenhuma permissão `IAM_*` delegada a gerente (o preflight confere).
- [ ] `DR-0020` decidida, ou risco residual aceito por escrito pelo proprietário.
- [ ] `DR-0019` decidida, ou o processo de estorno manual de estoque aceito pelo proprietário.

## 13. Fora do escopo do piloto

Multi-tenant, múltiplas oficinas, observabilidade avançada (métricas/tracing), autoscaling, Compras, Comissões,
Conciliação bancária, NF-e/NFS-e e certificado digital A1. Ver `AGENTS.md` e `DR-0021`.
