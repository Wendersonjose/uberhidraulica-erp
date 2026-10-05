# Deploy do piloto — uma oficina

Guia mínimo e portável para colocar a primeira oficina no ar. Não pressupõe um provedor de hospedagem
específico; o único ponto que depende do provedor é o certificado HTTPS, marcado explicitamente abaixo.

> **Estado de validação (TASK-0019).** Os passos 2 a 7 foram executados de verdade com Docker: stack subindo
> com `docker compose up -d --build`, fluxo E2E da oficina pelo nginx, reinício, volume persistente, backup,
> destruição do volume e restore em volume novo (`scripts/deploy/compose-smoke.sh`, também rodado no CI).
> **HTTPS não está resolvido neste repositório** — depende do provedor (seção 5). Não declare produção pronta
> sem ele.

## Visão geral

Três containers (`compose.yaml` na raiz do repositório):

```text
frontend (nginx, porta 80) → reverse proxy /api e /actuator/health → backend (Spring Boot, porta 8080) → postgres 18
```

O frontend e o backend respondem na mesma origem (o nginx encaminha `/api/**`), então não existe CORS
para configurar — é uma decisão deliberada, não uma pendência.

`compose.yaml` é o arquivo que o `docker compose` encontra por padrão. O PostgreSQL isolado para desenvolvimento
local (backend e frontend fora do Docker) fica em `compose.dev.yaml`: `docker compose -f compose.dev.yaml up -d`.

## 1. Pré-requisitos

- Docker e Docker Compose v2 no servidor.
- Um domínio apontando para o servidor (necessário só para HTTPS — ver seção 5).

## 2. Configuração

```bash
cp .env.example .env
```

Preencha `.env` (os segredos vêm **vazios** de propósito: o `docker compose` se recusa a subir enquanto faltarem,
e nenhum valor de exemplo funciona como senha). Gere cada segredo com `openssl rand -base64 24`.

- `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD`: o mesmo trio cria o banco no container `postgres` e é usado pelo
  backend (não há mais um segundo grupo `POSTGRES_*` para manter igual).
- `IAM_BOOTSTRAP_OWNER_NAME` / `_EMAIL` / `_PASSWORD`: dados do primeiro Dono. Só têm efeito quando o banco ainda
  não tem nenhum usuário (DR-0002); a senha precisa ter de 8 a 72 caracteres, é provisória (o primeiro login exige
  trocá-la) e as três variáveis podem ser removidas do `.env` depois disso. Informar só parte delas faz o backend
  se recusar a iniciar.
- `SPRING_PROFILES_ACTIVE=prod` (padrão): cookie de sessão `Secure` e IP real atrás do proxy.
- `SESSION_COOKIE_SECURE=true` (padrão): ver seção 4 para o único caso em que `false` é aceitável.
- `HTTP_PORT` (padrão 80): porta publicada pelo frontend.
- Nunca commite `.env` (já está no `.gitignore`) nem coloque essas senhas em outro lugar do repositório.

## 3. Subir

```bash
docker compose up -d --build
docker compose ps          # postgres, backend e frontend devem estar "healthy"
```

O backend só fica saudável depois que o Postgres responde (`depends_on: condition: service_healthy`) e expõe
`/actuator/health`. As migrations do Flyway rodam automaticamente no start do backend, na ordem dos arquivos em
`src/main/resources/db/migration` — é o único processo que altera o schema (`spring.jpa.hibernate.ddl-auto:
validate`, nunca `update`). Um banco novo sobe **apenas com Flyway** (21 migrations em 2026-10).

Acesse `http://<servidor>/`, entre com o Dono do bootstrap e troque a senha no primeiro login.

Conferência rápida:

```bash
curl -fsS http://localhost/actuator/health        # {"status":"UP",...}
docker compose logs backend | tail                # sem ERROR; "Started UberhidraulicaErpApplication"
```

## 4. HTTP puro (homologação) × HTTPS (produção)

O perfil `prod` marca o cookie de sessão como `Secure`: o navegador só o devolve por HTTPS (a única exceção é
`http://localhost`). Em um servidor de **homologação sem HTTPS**, o login "funciona" (200) mas a sessão nunca
persiste. Nesse único caso defina no `.env`:

```text
SESSION_COOKIE_SECURE=false
```

Isso é só para homologação interna: sem `Secure`, o cookie de sessão pode trafegar em claro. **Nunca** use `false`
em produção, e remova a variável assim que o HTTPS estiver no ar.

## 5. HTTPS — **depende do provedor de hospedagem**

O `compose.yaml` publica o frontend em HTTP puro (porta 80) porque não há um jeito único e portável de obter
certificado TLS sem saber onde a aplicação vai rodar. Escolha uma das opções conforme o provedor:

- **Load balancer/proxy do provedor já faz TLS** (ex.: ALB, Cloud Run, um Caddy/Traefik que já existe na infra) —
  aponte esse proxy para a porta 80 do serviço `frontend`, com `X-Forwarded-Proto: https`. O nginx do piloto
  respeita esse cabeçalho e o repassa ao backend (`server.forward-headers-strategy: framework`). **Além disso**,
  descomente o bloco `set_real_ip_from` / `real_ip_header` em `deploy/nginx/nginx.conf` com o CIDR do balanceador:
  sem ele o nginx enxerga o IP do balanceador, todos os clientes dividem o mesmo limite de requisições e a
  evidência de IP do orçamento público grava o IP do balanceador.
- **Sem proxy gerenciado**: rode um Certbot (Let's Encrypt) apontando para o domínio, e sirva os certificados para
  o container `frontend` (monte os arquivos `fullchain.pem`/`privkey.pem` e adicione um
  `server { listen 443 ssl; ... }` em `deploy/nginx/nginx.conf` com `ssl_certificate`/`ssl_certificate_key`
  apontando para o volume montado). Depois de confirmar que todo tráfego chega por HTTPS, descomente a linha do
  `Strict-Transport-Security` em `deploy/nginx/security_headers.conf`.

Em qualquer dos casos, mantenha `SESSION_COOKIE_SECURE=true`.

## 6. Backup do PostgreSQL

Backup lógico diário (ajuste retenção conforme o disco disponível):

```bash
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -F c -d "$POSTGRES_DB"' > "backup-$(date +%F).dump"
```

Agende com cron no host (fora do container, para sobreviver a um `docker compose down`):

```cron
0 3 * * * cd /caminho/do/repositorio && docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -F c -d "$POSTGRES_DB"' > "/backups/uberhidraulica-$(date +\%F).dump" 2>> /backups/backup.log
```

Guarde os arquivos `.dump` **fora do host** (outro disco, outro servidor, ou storage do provedor) — um backup que só
existe na mesma máquina não protege contra perda do servidor. O volume `postgres_data` guarda os dados em
`/var/lib/postgresql/18/docker` (o mount fica em `/var/lib/postgresql`: montar em `/var/lib/postgresql/data` faz o
PostgreSQL 18 abortar).

## 7. Restore

Validado: backup → `docker compose down -v` (destrói o volume) → restore em volume novo → login e dados intactos.

```bash
# 1. Ambiente novo, ou depois de `docker compose down -v` (apaga o volume postgres_data):
docker compose up -d postgres
# 2. Restaura (--clean --if-exists derruba objetos existentes: rode só contra banco que pode ser sobrescrito):
docker compose exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists' < backup-2026-10-05.dump
# 3. Sobe o restante; o Flyway aplica qualquer migration mais nova que o dump
docker compose up -d --build
```

Confira: `docker compose exec postgres psql -U <usuario> -d <banco> -c "select count(*) from flyway_schema_history"`
e o login do Dono.

## 8. Atualizar e voltar atrás (rollback)

O Flyway só avança: uma migration aplicada não é desfeita ao voltar o código. Por isso:

1. **Antes de atualizar**, faça o backup da seção 6 e anote o commit atual (`git rev-parse --short HEAD`).
2. Atualizar: `git pull && docker compose up -d --build`. O backend aplica as migrations novas ao subir.
3. **Rollback sem migration nova**: `git checkout <commit anterior> && docker compose up -d --build`.
4. **Rollback com migration nova aplicada**: volte o código **e** restaure o backup anterior à atualização (seção 7),
   porque o código antigo não conhece o schema novo (`ddl-auto: validate` recusa subir).

Migrations publicadas são imutáveis (o CI recusa PR que edite ou apague uma): a correção é sempre uma migration nova.

## 9. Logs e observabilidade

- `docker compose logs -f backend` / `frontend` / `postgres` (os containers rotacionam o log: 10 MB × 3 arquivos).
- `/actuator/health` exposto (só esse endpoint — `management.endpoints.web.exposure.include: health`,
  `show-details: never`, para não vazar detalhe interno).
- Nenhuma credencial (senha, senha temporária, token de sessão) é logada — verificado pelos testes de IAM e
  Financeiro existentes; mantenha essa regra em qualquer log adicional que for criado depois.

## 10. Rate limiting, cabeçalhos e superfície pública

`deploy/nginx/nginx.conf` limita por IP (resposta 429): `/api/public/**` (decisão do cliente sobre orçamento,
TASK-0008) a 10 requisições/minuto com rajada de 5 (`F-08-01`) e `/api/iam/auth/login` a 6/minuto com rajada de 20.
Ajuste `rate=`/`burst=` se o volume real do piloto precisar de mais. Não existe bloqueio por conta no backend: ver
`DR-0020`.

O nginx **sobrescreve** `X-Forwarded-For` com o IP da conexão (o backend usa o primeiro valor como IP do cliente na
evidência do orçamento público; anexar ao valor do cliente deixaria qualquer um forjar a evidência) e inclui os
cabeçalhos de segurança (`X-Frame-Options`, `nosniff`, `Referrer-Policy`, CSP e `Permissions-Policy` do SPA) em toda
resposta estática.

## 11. Validação automatizada

```bash
scripts/deploy/compose-smoke.sh
```

Sobe o stack com segredos aleatórios em um projeto isolado (`erp-smoke`), roda `scripts/e2e/workshop_flow.py` pelo
nginx, confere cabeçalhos, a evidência de IP, reinício, backup, restore e o 429 do login, e derruba tudo. O mesmo
script roda no CI (job `deploy-smoke`).

## 12. Checklist antes de produção

- [ ] HTTPS ativo (seção 5) e `SESSION_COOKIE_SECURE=true`; HSTS ligado depois de confirmar o HTTPS.
- [ ] `set_real_ip_from` configurado se houver balanceador na frente.
- [ ] Backup agendado **e copiado para fora do servidor**; um restore já testado no ambiente real.
- [ ] Senha do Dono trocada; variáveis `IAM_BOOTSTRAP_*` removidas do `.env`.
- [ ] Porta 5432 não publicada.
- [ ] `DR-0019` e `DR-0020` decididas ou riscos aceitos por escrito pelo proprietário.

## 13. O que este guia não cobre (fora do escopo do piloto)

Multi-tenant, múltiplas oficinas, observabilidade avançada (métricas/tracing), autoscaling, NF-e/fiscal, certificado
digital A1. Ver `AGENTS.md` e `DR-0021` para a lista completa do que não entra nesta fase.
