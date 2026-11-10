#!/usr/bin/env bash
# ENSAIO DA PROMOÇÃO sobre uma CÓPIA do banco: restaura um backup (ex.: o dump do Supabase do pré-voo) em um PostgreSQL
# descartável, sobe o backend DESTE checkout sobre ele (o Flyway aplica as migrations pendentes, como o Render fará) e prova
# que nenhuma linha existente mudou. Não toca na produção: o único insumo é o arquivo de backup.
#
#   scripts/ops/rehearse-promotion.sh prevoo/erp-antes-v22-<data>.dump            # ensaia e apaga tudo no fim
#   scripts/ops/rehearse-promotion.sh prevoo/erp-antes-v22-<data>.dump --keep     # deixa no ar para homologar pela tela
#
# Com --keep o backend fica em http://127.0.0.1:${REHEARSAL_PORT:-8080} (perfil padrão: cookie sem Secure, só para
# localhost) e o frontend de desenvolvimento (`cd frontend && npm run dev`) já repassa /api para a porta 8080. Entre com
# o e-mail e a senha REAIS do Dono: a cópia tem os mesmos usuários da origem. Para encerrar e apagar a cópia:
#   docker rm -f -v erp-rehearsal-backend erp-rehearsal-pg && docker network rm erp-rehearsal
#
# A cópia contém dados reais de clientes: ela vive só em containers locais, sem volume nomeado, e é apagada no fim
# (ou pelo comando acima). Variáveis: POSTGRES_IMAGE (postgres:18-alpine), REHEARSAL_IMAGE (imagem do backend já
# construída; padrão: constrói o Dockerfile deste checkout), REHEARSAL_PORT (8080), FLYWAY_SCHEMA (detectado).
# Código de saída: 0 ensaio aprovado, 1 reprovado.
set -euo pipefail
cd "$(dirname "$0")/../.."
# shellcheck source=lib.sh
. scripts/ops/lib.sh

require_cmd docker
dump="${1:-}"; keep=0; [ "${2:-}" = "--keep" ] && keep=1
[ -n "$dump" ] || die "uso: $0 <arquivo.dump> [--keep]"
[ -s "$dump" ] || die "arquivo inexistente ou vazio: $dump"
PG_IMAGE="${POSTGRES_IMAGE:-postgres:18-alpine}"
PORT="${REHEARSAL_PORT:-8080}"
NET=erp-rehearsal PG=erp-rehearsal-pg APP=erp-rehearsal-backend
WORK="$(mktemp -d)"
cleanup() {
    if [ "$keep" -eq 0 ]; then
        docker rm -f -v "$APP" "$PG" >/dev/null 2>&1 || true
        docker network rm "$NET" >/dev/null 2>&1 || true
    fi
    rm -rf "$WORK"
}
trap cleanup EXIT
verdict=0

if docker ps -a --format '{{.Names}}' | grep -Eq "^($PG|$APP)\$"; then
    die "já existe um ensaio ($PG/$APP). Apague com: docker rm -f -v $APP $PG && docker network rm $NET"
fi

echo "== 1. backend deste checkout ($(git rev-parse --short HEAD 2>/dev/null || echo '?'))"
IMAGE="${REHEARSAL_IMAGE:-}"
if [ -z "$IMAGE" ]; then
    IMAGE=erp-rehearsal-backend:local
    docker build -q -t "$IMAGE" . >/dev/null && ok "imagem construída a partir do Dockerfile" || die "docker build falhou"
fi

echo "== 2. cópia do banco em um PostgreSQL descartável"
password="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-20)"
docker network create "$NET" >/dev/null
docker run -d --name "$PG" --network "$NET" -e POSTGRES_PASSWORD="$password" -e POSTGRES_DB=rehearsal "$PG_IMAGE" >/dev/null
for _ in $(seq 1 90); do
    docker exec "$PG" pg_isready -h 127.0.0.1 -U postgres -d rehearsal >/dev/null 2>&1 && break
    sleep 1
done
docker exec "$PG" pg_isready -h 127.0.0.1 -U postgres -d rehearsal >/dev/null 2>&1 || die "o PostgreSQL descartável não subiu."
psql_r() { docker exec -i "$PG" psql -U postgres -d rehearsal -tA -v ON_ERROR_STOP=1 "$@"; }
docker exec -i "$PG" pg_restore -U postgres -d rehearsal --no-owner --no-privileges --exit-on-error < "$dump" \
    && ok "backup restaurado" || die "pg_restore falhou"

fs="${FLYWAY_SCHEMA:-$(psql_r -c "select case when to_regclass('erp_meta.flyway_schema_history') is not null then 'erp_meta' else 'public' end")}"
printf '%s' "$fs" | grep -Eq '^[a-z_][a-z0-9_]*$' || die "FLYWAY_SCHEMA inválido: $fs"
version_sql="select coalesce(max(version::int), 0) from ${fs}.flyway_schema_history where success and version ~ '^[0-9]+\$'"
before="$(psql_r -c "$version_sql")"
[ "$(psql_r -c "select count(*) from ${fs}.flyway_schema_history where not success")" = 0 ] \
    && ok "histórico do Flyway ($fs) na V$before, sem falhas" || { bad "a cópia já tem migration com falha"; exit 1; }
psql_r -q -F ' ' < scripts/ops/row-counts.sql > "$WORK/antes.txt"
ok "contagem exata antes: $(grep -c . "$WORK/antes.txt") tabelas"
perm_sql="select count(*) from iam.permission where code like 'PURCHASE\_%'"
grant_sql="select count(*) from iam.profile_permission pp join iam.permission p on p.id = pp.permission_id where p.code like 'PURCHASE\_%'"
perm_before="$(psql_r -c "$perm_sql")"; grant_before="$(psql_r -c "$grant_sql")"
snapped="$(psql_r -q < scripts/ops/rehearsal-snapshot.sql | tail -1)" \
    && ok "foto linha a linha de $snapped tabelas do ERP (só no banco descartável)" || die "foto das tabelas falhou"

echo "== 3. backend novo sobre a cópia (o Flyway aplica o que falta, como no deploy)"
docker run -d --name "$APP" --network "$NET" -p "127.0.0.1:${PORT}:8080" \
    -e SPRING_DATASOURCE_URL="jdbc:postgresql://$PG:5432/rehearsal" -e SPRING_DATASOURCE_USERNAME=postgres \
    -e SPRING_DATASOURCE_PASSWORD="$password" -e SPRING_FLYWAY_DEFAULT_SCHEMA="$fs" -e SPRING_FLYWAY_CREATE_SCHEMAS=true \
    -e JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC" "$IMAGE" >/dev/null
up=0
for _ in $(seq 1 180); do
    docker exec "$APP" wget -q -O - http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"' && { up=1; break; }
    [ "$(docker inspect -f '{{.State.Running}}' "$APP" 2>/dev/null)" = true ] || break
    sleep 2
done
if [ "$up" -eq 1 ]; then ok "backend no ar com health UP"; else
    bad "backend não subiu sobre a cópia"; docker logs --tail 60 "$APP" 2>&1 | sed 's/^/     /'; exit 1; fi
docker logs "$APP" 2>&1 | grep -E 'Migrating schema|Successfully applied|is up to date' | sed 's/^.*o\.f\.c\.i\.//; s/^/     /' || true

after="$(psql_r -c "$version_sql")"
[ "$(psql_r -c "select count(*) from ${fs}.flyway_schema_history where not success")" = 0 ] \
    && ok "Flyway: V$before → V$after, nenhuma migration com falha" || { bad "migration com falha na cópia"; verdict=1; }

echo "== 4. nenhuma linha existente foi alterada ou apagada"
psql_r -q -F ' ' -c "select * from rehearsal_snapshot.diff()" > "$WORK/diff.txt" || die "comparação linha a linha falhou"
changed="$(awk '$3 != 0 {print "     " $1 ": " $3 " de " $2 " linhas alteradas ou apagadas"}' "$WORK/diff.txt")"
if [ -z "$changed" ]; then ok "nenhuma das $(awk '{s += $2} END {print s}' "$WORK/diff.txt") linhas pré-existentes ($(grep -c . "$WORK/diff.txt") tabelas) foi alterada ou apagada"
else bad "a promoção alterou ou apagou linhas existentes:"; echo "$changed"; verdict=1; fi
# Linhas novas em tabela pré-existente: só o catálogo de permissões de Compras que a V23 semeia (PURCHASE_* e as concessões
# delas aos perfis). Qualquer outra é uma escrita inesperada da promoção.
awk '$4 != 0 {print "     " $1 ": +" $4 " linha(s)"}' "$WORK/diff.txt"
unexpected="$(awk '$4 != 0 && $1 != "iam.permission" && $1 != "iam.profile_permission" {print $1}' "$WORK/diff.txt")"
new_perm="$(awk '$1 == "iam.permission" {print $4}' "$WORK/diff.txt")"
new_grant="$(awk '$1 == "iam.profile_permission" {print $4}' "$WORK/diff.txt")"
purchase_perm=$(( $(psql_r -c "$perm_sql") - perm_before ))
purchase_grant=$(( $(psql_r -c "$grant_sql") - grant_before ))
if [ -z "$unexpected" ] && [ "${new_perm:-0}" = "$purchase_perm" ] && [ "${new_grant:-0}" = "$purchase_grant" ]; then
    ok "linhas novas fora de purchasing: só $purchase_perm permissão(ões) PURCHASE_* nova(s) e $purchase_grant concessão(ões) delas (V23)"
else
    bad "linhas novas inesperadas (tabelas: ${unexpected:-nenhuma}; permissões novas $new_perm x PURCHASE_* $purchase_perm; concessões $new_grant x $purchase_grant)"; verdict=1
fi
psql_r -q -F ' ' < scripts/ops/row-counts.sql > "$WORK/depois.txt"
added="$(awk 'NR==FNR {a[$1]=1; next} !($1 in a) {print $1}' "$WORK/antes.txt" "$WORK/depois.txt")"
outside="$(printf '%s\n' "$added" | grep -v '^$' | grep -v '^purchasing\.' || true)"
[ -z "$outside" ] && ok "tabelas novas só no schema purchasing ($(printf '%s\n' "$added" | grep -c . || true))" \
    || { bad "tabela nova fora de purchasing: $outside"; verdict=1; }
[ "$(psql_r -c "select count(*) from iam.app_user u join iam.profile p on p.id = u.profile_id where p.code = 'DONO' and u.state = 'ACTIVE'")" -ge 1 ] \
    && ok "o Dono continua ativo (entre com o e-mail e a senha reais)" || { bad "nenhum Dono ativo na cópia"; verdict=1; }

echo
if [ "$verdict" -eq 0 ]; then echo "ENSAIO APROVADO: a promoção aplica V$before → V$after sem alterar dados existentes."
else echo "ENSAIO REPROVADO: não promova. Guarde esta saída."; fi
if [ "$keep" -eq 1 ]; then
    echo
    echo "Cópia no ar para homologar: backend http://127.0.0.1:${PORT} · frontend: cd frontend && npm run dev (http://localhost:5173)"
    echo "Encerrar e apagar a cópia: docker rm -f -v $APP $PG && docker network rm $NET"
fi
exit "$verdict"
