#!/usr/bin/env bash
# Prova que um backup RESTAURA: sobe um PostgreSQL descartável, restaura o arquivo nele e confere o conteúdo.
# Não toca no banco do stack nem em dados reais. Um backup que nunca foi restaurado não é backup validado.
#
#   scripts/ops/verify-backup.sh backups/erp-20261005T120000Z.dump
#
# Variáveis: POSTGRES_IMAGE (padrão postgres:18-alpine, a mesma do compose.yaml).
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
for _ in $(seq 1 60); do
    docker exec "$name" pg_isready -U postgres -d verify >/dev/null 2>&1 && break
    sleep 1
done
docker exec "$name" pg_isready -U postgres -d verify >/dev/null 2>&1 || die "o PostgreSQL descartável não subiu."
sleep 2 # o entrypoint reinicia o servidor uma vez depois do initdb

psql_v() { docker exec -i "$name" psql -U postgres -d verify -tA -v ON_ERROR_STOP=1 "$@"; }

echo "== restaurando $dump em um banco descartável"
docker exec -i "$name" pg_restore -U postgres -d verify --no-owner --exit-on-error < "$dump" \
    && ok "pg_restore concluiu sem erro" || { bad "pg_restore FALHOU"; exit 1; }

verdict=0
failed="$(psql_v -c "select count(*) from flyway_schema_history where not success" 2>/dev/null || echo erro)"
applied="$(psql_v -c "select count(*) from flyway_schema_history where success" 2>/dev/null || echo 0)"
latest="$(psql_v -c "select coalesce(max(version::int), 0) from flyway_schema_history where success and version is not null" 2>/dev/null || echo 0)"
if [ "$failed" = 0 ] && [ "$applied" -gt 0 ] 2>/dev/null; then
    ok "Flyway: $applied migrations aplicadas (última V$latest), nenhuma com falha"
else
    bad "histórico do Flyway inválido (falhas: $failed, aplicadas: $applied)"; verdict=1
fi

owners="$(psql_v -c "select count(*) from iam.app_user u join iam.profile p on p.id = u.profile_id where p.code = 'DONO' and u.state = 'ACTIVE'" 2>/dev/null || echo 0)"
if [ "$owners" -ge 1 ] 2>/dev/null; then ok "há $owners Dono(s) ativo(s) no backup"; else bad "o backup não tem Dono ativo: ninguém conseguiria entrar"; verdict=1; fi

echo "== linhas por tabela (tabelas com dados)"
psql_v -c "analyze" >/dev/null
psql_v -F ' ' -c "select schemaname || '.' || relname, n_live_tup from pg_stat_user_tables where n_live_tup > 0 order by 1" \
    | awk '{printf "     %-45s %s\n", $1, $2}'

echo
if [ "$verdict" -eq 0 ]; then echo "BACKUP VÁLIDO: restaura e abre."; else echo "BACKUP INVÁLIDO."; fi
exit "$verdict"
