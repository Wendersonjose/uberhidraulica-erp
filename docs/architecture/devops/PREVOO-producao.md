# Pré-voo da produção (Render + Supabase) — antes de promover V22–V25

Roteiro executável do §3 de [`PROMOCAO-compras-v22-v25.md`](PROMOCAO-compras-v22-v25.md). **Tudo aqui é leitura do banco de
produção ou trabalho na sua máquina.** Nenhum passo faz deploy, aplica migration, rollback ou restore no Supabase. Quem roda é
o proprietário: a senha do banco não sai da sua máquina e não é colada em nenhum comando, log ou chat.

Ambiente de referência: Windows + Git Bash, na raiz do repositório, com Docker Desktop **funcionando** (`docker ps` sem erro).
Não há `psql`/`pg_dump` instalados: os comandos usam a imagem `postgres:18-alpine` (pg_dump 18 lê servidores 15–18).

## 0. Variáveis da sessão (uma vez por terminal)

No painel do Supabase: *Connect → Session pooler* (porta **5432**, nunca 6543). Copie host e usuário (`postgres.<PROJECT_REF>`).

```bash
export PGHOST='<host do Session pooler>'          # ex.: aws-0-us-east-2.pooler.supabase.com
export PGUSER='postgres.<PROJECT_REF>'
read -rs -p 'Senha do banco: ' PGPASSWORD; echo; export PGPASSWORD   # não aparece na tela nem no histórico
export PGPORT=5432 PGDATABASE=postgres PGSSLMODE=require
mkdir -p prevoo && cd prevoo && cp ../scripts/ops/supabase-readonly-check.sql ../scripts/ops/row-counts.sql .
pgx() { MSYS_NO_PATHCONV=1 docker run --rm -i -v "$(pwd -W 2>/dev/null || pwd)":/w -w /w \
          -e PGHOST -e PGUSER -e PGPASSWORD -e PGPORT -e PGDATABASE -e PGSSLMODE postgres:18-alpine "$@"; }
```

`-e PGPASSWORD` sem `=` repassa o valor do ambiente sem escrevê-lo na linha de comando. A pasta `prevoo/` fica fora do Git
(`git status` não deve listá-la; se listar, não a adicione).

## 1. Estado do Flyway e do banco (somente leitura)

```bash
pgx psql -v ON_ERROR_STOP=1 -f supabase-readonly-check.sql | tee 1-readonly-check.txt
```

O arquivo abre `BEGIN READ ONLY` e termina em `ROLLBACK`: o banco recusaria qualquer escrita. **GO somente se:**

| Bloco | Esperado |
| --- | --- |
| 1 | última versão **21**, `success = t`; `migrations_com_falha = 0` e `migrations_ja_aplicadas_22_ou_mais = 0` |
| 2 | `purchasing` **não** aparece |
| 3 | 0 linhas |
| 4 | `0` |
| 5 | `0` |
| 6 | `pode_criar_schema = t` |
| 8 | `conexoes_ativas` + 10 abaixo de `limite` |

Qualquer divergência: **pare**. Se a última versão já for 22–25, alguém já promoveu: não repita a promoção; me traga o
`1-readonly-check.txt`.

## 2. Contagem exata na origem (somente leitura)

Faça fora do horário de uso, logo antes do backup, para que as duas fotos sejam do mesmo momento.

```bash
pgx psql -qtA -F ' ' -v ON_ERROR_STOP=1 -f row-counts.sql > 2-contagem-producao.txt && wc -l 2-contagem-producao.txt
```

## 3. Backup

```bash
STAMP=$(date +%Y%m%d-%H%M)
pgx pg_dump -F c --schema=erp_meta --schema=iam --schema=crm --schema=servicecatalog --schema=productcatalog \
  --schema=inventory --schema=workorder --schema=workshop --schema=finance -f "erp-antes-v22-$STAMP.dump"
pgx pg_restore --list "erp-antes-v22-$STAMP.dump" > /dev/null && echo "backup legível"
sha256sum "erp-antes-v22-$STAMP.dump" > "erp-antes-v22-$STAMP.dump.sha256" && cat "erp-antes-v22-$STAMP.dump.sha256"
```

`public` fica de fora de propósito (é do módulo de salários; o ERP não o toca). Se o seu plano do Supabase tiver backups
diários, confira também em *Database → Backups* que existe um de hoje.

## 4. Prova de restauração (banco descartável local, nunca o Supabase)

```bash
cd .. && COUNTS_OUT=prevoo/4-contagem-restaurada.txt scripts/ops/verify-backup.sh prevoo/erp-antes-v22-$STAMP.dump \
  | tee prevoo/4-verify-backup.txt; cd prevoo
diff <(grep -v '^iam\.spring_session' 2-contagem-producao.txt) <(grep -v '^iam\.spring_session' 4-contagem-restaurada.txt) \
  && echo "CÓPIA RESTAURADA = PRODUÇÃO"
```

**GO somente se:** `checksum confere`, `pg_restore concluiu sem erro`, `Flyway (erp_meta): N migrations aplicadas (última V21),
nenhuma com falha` (N é 21 ou **22**: quando o próprio Flyway criou o schema `erp_meta`, ele grava uma linha a mais, sem versão),
`há N Dono(s) ativo(s)`, `BACKUP VÁLIDO` e `CÓPIA RESTAURADA = PRODUÇÃO`. Uma diferença só em tabelas que mudam com o uso (sessão,
auditoria de login) quer dizer que alguém usou o sistema entre os passos 2 e 3: repita os dois.

Depois: copie `erp-antes-v22-*.dump` **e** o `.sha256` para fora do notebook (nuvem pessoal/HD externo). Nunca para o Git.

## 4b. Ensaio da promoção sobre a cópia (local) e homologação com os dados reais

Aplica V22–V25 com o código que será promovido sobre a cópia restaurada, exatamente como o Render fará no Supabase, e prova linha
a linha que nada existente mudou. Faça no checkout do SHA a promover (`git checkout <SHA>`).

```bash
cd .. && scripts/ops/rehearse-promotion.sh prevoo/erp-antes-v22-$STAMP.dump --keep | tee prevoo/4b-ensaio.txt
```

**GO somente se** terminar em `ENSAIO APROVADO`, com: `Flyway: V21 → V25, nenhuma migration com falha`; `nenhuma das N linhas
pré-existentes ... foi alterada ou apagada`; linhas novas fora de `purchasing` **só** `9 permissão(ões) PURCHASE_*` e `18
concessão(ões)`; `tabelas novas só no schema purchasing (14)`.

Com `--keep` a cópia migrada fica no ar em `http://127.0.0.1:8080`. Em outro terminal: `cd frontend && npm run dev` e abra
`http://localhost:5173` com o **seu e-mail e a sua senha reais de Dono**. Execute o roteiro
[`HOMOLOGACAO-roteiro.md`](../../review/HOMOLOGACAO-roteiro.md) nessa cópia: é homologação com os dados reais, sem risco para
a produção. No fim, apague a cópia (ela tem dados reais de clientes):

```bash
docker rm -f -v erp-rehearsal-backend erp-rehearsal-pg && docker network rm erp-rehearsal
```

## 5. Render — variáveis do backend (`uberhidraulica-erp-api` → *Environment*)

Confira pelo painel; não copie valores para cá.

| Variável | Deve ser |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod,supabase` |
| `SESSION_COOKIE_SECURE` | `true` |
| `FLYWAY_DEFAULT_SCHEMA` | `erp_meta` |
| `SUPABASE_DB_URL` | `jdbc:postgresql://<host do Session pooler>:5432/postgres?sslmode=require` |
| `SUPABASE_DB_USERNAME` | `postgres.<PROJECT_REF>` |
| `SUPABASE_DB_PASSWORD` | ver o teste abaixo |
| `IAM_BOOTSTRAP_OWNER_PASSWORD` | ausente (recomendado: o Dono já existe) ou forte |
| `APP_ENVIRONMENT` | **ausente** |

A versão nova traz a `ProductionStartupGuard`, que **não existe** na versão no ar: com senha fraca o deploy novo não sobe (a versão
antiga continua atendendo). Teste a mesma senha que está no Render, sem exibi-la (no mesmo terminal do passo 0):

```bash
python -c "import os;p=os.environ['PGPASSWORD'];u=os.environ['PGUSER'];n=p.strip().lower();w=set('postgres postgresql password passwd pass admin administrator root 123456 12345678 123456789 1234567890 qwerty secret changeme change-me change_me troque-esta-senha uberhidraulica uberhidraulica_dev test teste example default letmein welcome'.split());print('OK' if len(p)>=12 and len(set(p))>=5 and n not in w and 'changeme' not in n and 'troque-esta' not in n and p.lower()!=u.strip().lower() else 'FRACA: troque no Supabase e no Render antes da promoção')"
```

Frontend (`uberhidraulica-erp-web`): `NODE_VERSION=22`; o rewrite `/api/*` aponta para a URL real do backend.

## 6. Encerrar a sessão

```bash
unset PGPASSWORD; cd ..
```

## Resultado do pré-voo

Guarde `1-readonly-check.txt`, `2-contagem-producao.txt`, `4-verify-backup.txt` e o `.sha256`. Com todos os critérios acima
atendidos, o pré-voo é **GO** e a promoção segue o §4 de `PROMOCAO-compras-v22-v25.md` com o SHA homologado.
