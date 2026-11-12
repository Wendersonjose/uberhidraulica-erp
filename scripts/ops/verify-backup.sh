#!/usr/bin/env bash
# Prova que um backup RESTAURA: sobe um PostgreSQL descartável, restaura o arquivo nele e confere o conteúdo.
# Não toca no banco do stack nem em dados reais. Um backup que nunca foi restaurado não é backup validado.
#
#   scripts/ops/verify-backup.sh backups/erp-20261005T120000Z.dump
#
# Variáveis: POSTGRES_IMAGE (padrão postgres:18-alpine, a mesma do compose.yaml); FLYWAY_SCHEMA (schema do histórico
# do Flyway; detectado sozinho: `erp_meta` no Supabase, `public` no compose); COUNTS_OUT (arquivo onde gravar a contagem
# exata de linhas, no formato de scripts/ops/row-counts.sql, para comparar com a mesma contagem feita na origem).
#
# Backup do Supabase: os GRANTs para papéis que só existem lá (anon, authenticated, service_role...) não importam para a
# prova e não existem no banco descartável; por isso a restauração ignora dono e privilégios (--no-owner --no-privileges).
# Confere: checksum (.sha256 ao lado, se existir), restauração sem erro, histórico do Flyway íntegro (nenhuma
# migration com falha), pelo menos um Dono ativo e as contagens de linhas por tabela.
set -euo pipefail
cd "$(dirname "$0")/../.."
# shellcheck source=lib.sh
. scripts/ops/lib.sh

require_cmd docker
dump="${1:-}"
[ -n "$dump" ] || die "uso: $0 <arquivo.dump>"
[ -s "$dump" ] || die "arquivo inexistente ou vazio: $dump"
IMAGE="${POSTGRES_IMAGE:-postgres:18-alpine}"

if [ -f "$dump.sha256" ]; then
    ( cd "$(dirname "$dump")" && sha256sum -c "$(basename "$dump").sha256" >/dev/null 2>&1 ) \
        && ok "checksum confere" || { bad "checksum NÃO confere: o arquivo foi alterado ou corrompido"; exit 1; }
else
    warn "sem arquivo .sha256 ao lado; integridade só verificada pela restauração"
fi

name="erp-verify-$$"
trap 'docker rm -f "$name" >/dev/null 2>&1' EXIT
password="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-20)"
docker run -d --name "$name" -e POSTGRES_PASSWORD="$password" -e POSTGRES_DB=verify "$IMAGE" >/dev/null
# Durante o initdb o entrypoint sobe um servidor TEMPORÁRIO que só atende no socket unix e depois o reinicia: um
# pg_isready pelo socket passa cedo demais. Só o servidor definitivo escuta em TCP, então a espera é por 127.0.0.1.
for _ in $(seq 1 90); do
    docker exec "$name" pg_isready -h 127.0.0.1 -U postgres -d verify >/dev/null 2>&1 && break
    sleep 1
done
docker exec "$name" pg_isready -h 127.0.0.1 -U postgres -d verify >/dev/null 2>&1 || die "o PostgreSQL descartável não subiu."

psql_v() { docker exec -i "$name" psql -U postgres -d verify -tA -v ON_ERROR_STOP=1 "$@"; }

echo "== restaurando $dump em um banco descartável"
docker exec -i "$name" pg_restore -U postgres -d verify --no-owner --no-privileges --exit-on-error < "$dump" \
    && ok "pg_restore concluiu sem erro" || { bad "pg_restore FALHOU"; exit 1; }

verdict=0
# O histórico do Flyway fica em `public` no compose e em `erp_meta` no Supabase (FLYWAY_DEFAULT_SCHEMA). Sem isto um
# backup do Supabase seria declarado inválido por não achar a tabela.
fs="${FLYWAY_SCHEMA:-$(psql_v -c "select case when to_regclass('erp_meta.flyway_schema_history') is not null then 'erp_meta' else 'public' end" 2>/dev/null || echo public)}"
printf '%s' "$fs" | grep -Eq '^[a-z_][a-z0-9_]*$' || die "FLYWAY_SCHEMA inválido: $fs"
failed="$(psql_v -c "select count(*) from ${fs}.flyway_schema_history where not success" 2>/dev/null || echo erro)"
applied="$(psql_v -c "select count(*) from ${fs}.flyway_schema_history where success" 2>/dev/null || echo 0)"
latest="$(psql_v -c "select coalesce(max(version::int), 0) from ${fs}.flyway_schema_history where success and version is not null" 2>/dev/null || echo 0)"
if [ "$failed" = 0 ] && [ "$applied" -gt 0 ] 2>/dev/null; then
    ok "Flyway ($fs): $applied migrations aplicadas (última V$latest), nenhuma com falha"
else
    bad "histórico do Flyway inválido (falhas: $failed, aplicadas: $applied)"; verdict=1
fi

owners="$(psql_v -c "select count(*) from iam.app_user u join iam.profile p on p.id = u.profile_id where p.code = 'DONO' and u.state = 'ACTIVE'" 2>/dev/null || echo 0)"
if [ "$owners" -ge 1 ] 2>/dev/null; then ok "há $owners Dono(s) ativo(s) no backup"; else bad "o backup não tem Dono ativo: ninguém conseguiria entrar"; verdict=1; fi

echo "== linhas por tabela (contagem exata; compare com scripts/ops/row-counts.sql rodado na origem)"
if counts="$(psql_v -q -F ' ' < scripts/ops/row-counts.sql)"; then
    printf '%s\n' "$counts" | awk 'NF {printf "     %-45s %s\n", $1, $2}'
    if [ -n "${COUNTS_OUT:-}" ]; then printf '%s\n' "$counts" > "$COUNTS_OUT" && ok "contagem gravada em $COUNTS_OUT"; fi
else
    bad "contagem de linhas falhou"; verdict=1
fi

echo
if [ "$verdict" -eq 0 ]; then echo "BACKUP VÁLIDO: restaura e abre."; else echo "BACKUP INVÁLIDO."; fi
exit "$verdict"
