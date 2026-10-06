# ERP na nuvem: Supabase + Render

```text
Navegador ──HTTPS──> Frontend (Render Static Site) ──rewrite /api/*──> Backend Spring Boot (Render, Docker) ──TLS──> PostgreSQL (Supabase "Saas Oficina")
```

O banco oficial é o projeto Supabase **Saas Oficina**. Não há PostgreSQL em Docker nesta arquitetura (o `compose.yaml` do repositório
é o piloto com banco local e **não** é o ambiente de produção). O navegador só fala com o domínio do frontend: `/api/*` é repassado ao
backend, sem CORS e sem URL do backend dentro do código do frontend.

Configuração: [`render.yaml`](../../../render.yaml) (Blueprint) e `src/main/resources/application-supabase.yml`. Nenhum segredo está no Git.

## 1. O que já foi validado (e o que só dá para validar no Render)

Ensaiado localmente com um PostgreSQL equivalente (TLS `sslmode=require`, role **não** superusuário, tabelas já existentes em `public`):

| Item | Resultado |
| --- | --- |
| Perfil `prod,supabase`, `PORT=10000` | o Spring escuta `PORT`; o healthcheck do Dockerfile acompanha a porta |
| Flyway | V1–V21 aplicadas em `erp_meta.flyway_schema_history` (22 linhas: 21 migrations + a criação do schema), 0 falhas |
| Schemas do ERP | `erp_meta, iam, crm, servicecatalog, productcatalog, inventory, workorder, workshop, finance` |
| Hibernate `ddl-auto=validate` | passou (a aplicação subiu) |
| Dados existentes | as tabelas de `public` (inclusive um `flyway_schema_history` de outra ferramenta) ficaram **idênticas**; o ERP não criou nada em `public`; nenhum SQL manual |
| Memória | 512 MB / 0,5 CPU: ~330 MB em uso, sem OOM |
| Conexões | pool de 5 (o padrão do Hikari usava 10): cabe no limite do Session Pooler mesmo com duas instâncias durante o deploy |
| Cookie `SESSION` | `Secure; HttpOnly; SameSite=Lax`, atravessa o proxy, persiste após refresh e após reiniciar o backend |
| Fluxo | E2E 105/107 (as 2 que faltam testam `/actuator/health` pelo frontend, que não passa pelo rewrite por desenho); Chromium 42/42 |

**Só dá para validar no Render:** o rewrite do Static Site (cookie, cabeçalho CSRF e POST atravessando-o), a conexão real com o Supabase e o
certificado. É para isso que existe o `scripts/deploy/cloud-check.sh` (seção 5).

## 2. Supabase — o que você precisa fazer (uma vez)

1. Abra o projeto **Saas Oficina** em https://supabase.com/dashboard. Confirme que está **Active** (projetos inativos ficam pausados:
   se aparecer "Restore project", restaure e espere).
2. Anote a **região** do projeto (Project Settings → General). Ela decide a região do Render (seção 3, passo 5).
3. **Backup antes do primeiro deploy** (o banco tem o módulo de salários). Em um terminal seu, com o `psql`/`pg_dump` instalados:
   ```bash
   export PGPASSWORD='<senha do banco>'      # não use a senha direto no comando
   pg_dump "host=<HOST-DO-SESSION-POOLER> port=5432 dbname=postgres user=postgres.<PROJECT_REF> sslmode=require" -F c -f saas-oficina-antes-do-erp.dump
   ```
   Guarde o arquivo fora do Git. (Alternativa: Database → Backups, se o seu plano tiver.)
4. Pegue a conexão: botão **Connect** (barra superior do projeto) → aba **Session pooler** (porta **5432**). **Não** use o *Transaction pooler* (6543).
   Anote o **Host** (`aws-0-<região>.pooler.supabase.com`) e o **User** (`postgres.<PROJECT_REF>`).
   A conexão direta (`db.<ref>.supabase.co`) só tem IPv6 e não funciona no Render: use o Session pooler.
5. A **senha do banco** é a do projeto (Project Settings → Database → *Database password*). Se não a tiver, *Reset database password* gera outra
   — **cuidado:** o módulo de salários, se usar essa mesma senha, deixa de conectar até você atualizá-la lá.

Os três valores viram:

```text
SUPABASE_DB_URL      = jdbc:postgresql://<HOST>:5432/postgres?sslmode=require
SUPABASE_DB_USERNAME = postgres.<PROJECT_REF>
SUPABASE_DB_PASSWORD = <senha do banco>
```

## 3. Render — o que você precisa fazer

Pré-requisito: a conta do Render conectada ao GitHub com acesso ao repositório `Wendersonjose/uberhidraulica-erp`
(Account Settings → GitHub). A branch `release/supabase-cloud` precisa estar no GitHub (o Render lê o `render.yaml` dela).

1. https://dashboard.render.com → **New +** → **Blueprint**.
2. Escolha o repositório `uberhidraulica-erp` → em **Branch** selecione `release/supabase-cloud` → **Connect**.
3. O Render mostra dois serviços: `uberhidraulica-erp-api` (Web Service, Docker) e `uberhidraulica-erp-web` (Static Site) e pede os valores
   das variáveis sem valor no arquivo. Preencha **só aqui**, nunca no Git:

   | Variável | Valor |
   | --- | --- |
   | `SUPABASE_DB_URL` | a URL JDBC da seção 2 |
   | `SUPABASE_DB_USERNAME` | `postgres.<PROJECT_REF>` |
   | `SUPABASE_DB_PASSWORD` | a senha do banco |
   | `IAM_BOOTSTRAP_OWNER_NAME` | nome do Dono |
   | `IAM_BOOTSTRAP_OWNER_EMAIL` | e-mail do Dono (o login) |
   | `IAM_BOOTSTRAP_OWNER_PASSWORD` | senha **provisória** de 8 a 72 caracteres (será trocada no primeiro login) |

4. **Plano do backend:** o Blueprint usa **Starter** (o plano Free dorme após 15 min sem uso e a primeira requisição leva ~1 min; para testar
   sem custo, troque `plan: starter` por `plan: free` no `render.yaml` antes de aplicar).
5. **Região:** o Blueprint usa `virginia`. Se o projeto Supabase não estiver em us-east-1 ou sa-east-1 (seção 2, passo 2), troque `region:` no
   `render.yaml` para a mais próxima **antes** de aplicar (ela não muda depois).
6. Clique **Apply**. O backend leva alguns minutos (build do Docker, depois a subida e o Flyway: ~1 min).
7. Quando o backend ficar *Live*, abra `https://uberhidraulica-erp-api.onrender.com/actuator/health`: precisa responder **HTTP 200** com
   `{"status":"UP"...}`. **Só siga se responder.** Se a URL do backend for outra (o Render acrescenta um sufixo quando o nome já existe),
   edite o frontend: serviço `uberhidraulica-erp-web` → **Redirects/Rewrites** → o destino da regra `/api/*` deve ser
   `https://<URL-REAL-DO-BACKEND>/api/*`. A regra `/*` → `/index.html` (Rewrite) fica **depois** dela.
8. Abra `https://uberhidraulica-erp-web.onrender.com` (URL do frontend).

## 4. Primeiro acesso

1. Entre com o e-mail e a senha provisória do bootstrap. O ERP obriga trocar a senha: escolha a definitiva (8 a 72 caracteres).
2. Saia e entre de novo com a senha nova.
3. Depois disso as variáveis `IAM_BOOTSTRAP_OWNER_*` podem ser removidas do Render (Environment).

## 5. Verificação pelo endereço público

Em um terminal seu (a senha fica só no ambiente e não é impressa):

```bash
export ERP_URL=https://uberhidraulica-erp-web.onrender.com
export ERP_BACKEND_URL=https://uberhidraulica-erp-api.onrender.com
export ERP_EMAIL='<e-mail do Dono>'
export ERP_PASSWORD='<senha atual do Dono>'
scripts/deploy/cloud-check.sh --create-test-data
```

Confere: health do backend, SPA e refresh, `/api` pelo frontend, CSRF, login, cookie `Secure/HttpOnly/SameSite=Lax`, sessão após refresh, logout e novo
login, e cria o cliente, o veículo e a OS de teste `ZZ TESTE CLOUD` (a API não exclui; inative-os pela tela depois, se quiser).
Com a senha provisória ele para no passo da troca de senha e avisa. Termina com `ERP ONLINE E FUNCIONANDO (0 falhas)` ou aponta o que falhou.

## 6. Se algo falhar

| Sintoma | Causa provável | O que fazer |
| --- | --- | --- |
| Build do backend falha no Render | memória/rede do build | veja o log do deploy; rode **Manual Deploy → Clear build cache & deploy** |
| `Tenant or user not found` / `password authentication failed` nos logs | usuário sem o `.<PROJECT_REF>` no Session pooler, ou senha errada | confira `SUPABASE_DB_USERNAME` (`postgres.<PROJECT_REF>`) e a senha |
| `Network is unreachable` / timeout ao conectar | usou a conexão direta (`db.<ref>.supabase.co`, só IPv6) | use o host do **Session pooler** |
| `MaxClientsInSessionMode` | conexões demais no pooler | já está em 5 por instância; feche outros clientes ou reduza `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` |
| `permission denied for database` ao criar schema | o usuário não pode criar schemas | use o usuário `postgres.<PROJECT_REF>` do Supabase |
| `/actuator/health` demora ~1 min na primeira vez | plano Free dormindo | normal no Free; use Starter |
| O login devolve 200 mas a tela volta ao login | o cookie `SESSION` não atravessa o rewrite | rode o `cloud-check.sh` e me envie a saída; o plano B é servir o frontend por um Web Service com proxy (nginx) em vez do Static Site |
| `/api/...` devolve a página do frontend | a regra `/api/*` está depois de `/*`, ou o destino está errado | ordem: `/api/*` primeiro, `/*` por último |
| Quer voltar atrás | — | desligue os serviços no Render. O Flyway só criou os schemas do ERP (`erp_meta, iam, crm, servicecatalog, productcatalog, inventory, workorder, workshop, finance`); `public` não foi tocado. **Não apague nada sem backup** |

## 7. Limitações conhecidas desta implantação

- O IP gravado como evidência do orçamento público é o que o backend recebe em `X-Forwarded-For`. No Render, o backend também tem URL pública
  própria: quem a chamar direto pode escolher esse valor. O frontend usa o caminho pelo rewrite; endurecer isso (proxy confiável) é a
  `TRUSTED_PROXY_CIDRS` do pacote de produção, fora desta branch.
- Não há bloqueio de conta por tentativas de login nem limite de requisições (`DR-0020`): o nginx do piloto fazia isso e aqui não existe. Use senhas fortes
  e não delegue permissões `IAM_*` a gerentes.
- O backup do banco é o do Supabase (o plano define a retenção). Faça o `pg_dump` da seção 2 antes de mudanças grandes.
- Não foram implementados Compras, NFS-e, Comissões, Conciliação nem outros módulos (`DR-0021`).
