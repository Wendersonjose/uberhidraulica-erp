# Deploy do piloto — uma oficina

Guia mínimo e portável para colocar a primeira oficina no ar. Não pressupõe um provedor de hospedagem
específico; o único ponto que depende do provedor é o certificado HTTPS, marcado explicitamente abaixo.

## Visão geral

Três containers (`docker-compose.yml` na raiz do repositório):

```text
frontend (nginx, porta 80) → reverse proxy /api e /actuator/health → backend (Spring Boot, porta 8080) → postgres
```

O frontend e o backend respondem na mesma origem (o nginx encaminha `/api/**`), então não existe CORS
para configurar — é uma decisão deliberada, não uma pendência.

## 1. Pré-requisitos

- Docker e Docker Compose no servidor.
- Um domínio apontando para o servidor (necessário só para HTTPS — ver seção 4).

## 2. Configuração

```bash
cp .env.example .env
```

Preencha `.env`:

- `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD`: escolha valores próprios; `POSTGRES_DB` / `POSTGRES_USER` /
  `POSTGRES_PASSWORD` devem ser **iguais** aos três anteriores (o Postgres do compose usa esse segundo
  grupo para criar o banco na primeira subida).
- `IAM_BOOTSTRAP_OWNER_NAME` / `_EMAIL` / `_PASSWORD`: dados do primeiro Dono. Só têm efeito quando o
  banco ainda não tem nenhum usuário (DR-0002); depois do primeiro login (que exige troca de senha), pode
  remover essas três variáveis do `.env` sem qualquer efeito.
- Nunca commite `.env` (já está no `.gitignore`) nem coloque essas senhas em outro lugar do repositório.

## 3. Subir

```bash
docker compose up -d --build
```

O backend só fica saudável depois que o Postgres responde (`depends_on: condition: service_healthy`) e
expõe `/actuator/health`. As migrations do Flyway rodam automaticamente no start do backend, na ordem dos
arquivos em `src/main/resources/db/migration` — é o único processo que altera o schema
(`spring.jpa.hibernate.ddl-auto: validate`, nunca `update`).

Acesse `http://<servidor>/`, entre com o Dono do bootstrap e troque a senha no primeiro login.

## 4. HTTPS — **depende do provedor de hospedagem**

O `docker-compose.yml` publica o frontend em HTTP puro (porta 80) porque não há um jeito único e portável
de obter certificado TLS sem saber onde a aplicação vai rodar. Escolha uma das opções conforme o
provedor:

- **Load balancer/proxy do provedor já faz TLS** (ex.: ALB, Cloud Run, um Caddy/Traefik que já existe na
  infra) — não mexa neste repositório; aponte esse proxy para a porta 80 do serviço `frontend`, com
  `X-Forwarded-Proto: https`. O backend já lê esse cabeçalho
  (`server.forward-headers-strategy: framework` em `application-prod.yml`), então o restante do sistema
  (cookie `Secure`, evidência de IP do orçamento público) funciona sem mudança.
- **Sem proxy gerenciado**: rode um Certbot (Let's Encrypt) apontando para o domínio, e sirva os
  certificados para o container `frontend` (monte os arquivos `fullchain.pem`/`privkey.pem` e adicione um
  `server { listen 443 ssl; ... }` em `deploy/nginx/nginx.conf` com `ssl_certificate`/`ssl_certificate_key`
  apontando para o volume montado). Depois de confirmar que todo tráfego chega por HTTPS, descomente a
  linha do `Strict-Transport-Security` já deixada pronta (comentada) em `deploy/nginx/nginx.conf`.

Em qualquer um dos dois casos, ative `secure: true` do cookie de sessão definindo
`SPRING_PROFILES_ACTIVE=prod` (já é o padrão do `.env.example`) — sem HTTPS na frente, navegadores
descartam esse cookie e ninguém consegue logar, então não ligue o profile `prod` antes do HTTPS estar de
pé.

## 5. Backup do PostgreSQL

Backup lógico diário (ajuste retenção conforme o disco disponível):

```bash
docker compose exec -T postgres pg_dump -U "$DB_USERNAME" -F c -d "$DB_NAME" > "backup-$(date +%F).dump"
```

Agende com cron no host (fora do container, para sobreviver a um `docker compose down`):

```cron
0 3 * * * cd /caminho/do/repositorio && docker compose exec -T postgres pg_dump -U "$DB_USERNAME" -F c -d "$DB_NAME" > "/backups/uberhidraulica-$(date +\%F).dump" 2>> /backups/backup.log
```

Guarde os arquivos `.dump` fora do host (outro disco, outro servidor, ou storage do provedor) — um backup
que só existe na mesma máquina não protege contra perda do servidor.

## 6. Restore

```bash
# Banco vazio (ambiente novo) ou depois de um docker compose down -v (apaga o volume postgres_data):
docker compose up -d postgres
docker compose exec -T postgres pg_restore -U "$DB_USERNAME" -d "$DB_NAME" --clean --if-exists < backup-2026-09-29.dump
docker compose up -d --build
```

O `--clean --if-exists` derruba os objetos existentes antes de restaurar, então rode isso só contra um
banco que pode ser sobrescrito. Depois do restore, o backend aplica automaticamente qualquer migration do
Flyway mais nova que o `.dump`.

## 7. Logs e observabilidade

- `docker compose logs -f backend` / `frontend` / `postgres`.
- `/actuator/health` exposto (só esse endpoint — `management.endpoints.web.exposure.include: health`,
  `show-details: never`, para não vazar detalhe interno).
- Nenhuma credencial (senha, senha temporária, token de sessão) é logada — verificado pelos testes de
  IAM e Financeiro existentes; mantenha essa regra em qualquer log adicional que for criado depois.

## 8. Rate limiting e superfície pública

`deploy/nginx/nginx.conf` limita `/api/public/**` (decisão do cliente sobre orçamento, TASK-0008) a
10 requisições/minuto por IP (`F-08-01`). Ajuste `rate=` se o volume real do piloto precisar de mais.

## 9. O que este guia não cobre (fora do escopo do piloto)

Multi-tenant, múltiplas oficinas, CI/CD automatizado, observabilidade avançada (métricas/tracing),
autoscaling, NF-e/fiscal. Ver `AGENTS.md` para a lista completa do que não entra nesta fase.
