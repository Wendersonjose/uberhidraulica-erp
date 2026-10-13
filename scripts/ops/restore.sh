#!/usr/bin/env bash
# Restaura um backup (pg_dump -F c) no PostgreSQL do stack. DESTRUTIVO: substitui o banco inteiro.
#
#   scripts/ops/restore.sh backups/erp-20261005T120000Z.dump          (pede confirmação)
#   scripts/ops/restore.sh backups/erp-20261005T120000Z.dump --yes    (sem perguntar; para automação)
#
# O que faz, nesta ordem:
#   1. confere o arquivo (checksum e leitura pelo pg_restore);
#   2. guarda uma cópia do banco atual em BACKUP_DIR/pre-restore-<data>.dump (rede de segurança);
#   3. para backend/frontend/proxy HTTPS (ninguém escreve durante a restauração);
#   4. recria o banco vazio e restaura o backup, parando no primeiro erro;
#   5. apaga as sessões web (todos precisam entrar de novo: sessões de antes do backup não valem);
#   6. sobe o stack, deixa o Flyway validar o esquema e espera o health ficar UP.
# Ensaie antes com scripts/ops/verify-backup.sh (banco descartável) e depois em um ambiente de teste.
set -euo pipefail
cd "$(dirname "$0")/../.."
# shellcheck source=lib.sh
. scripts/ops/lib.sh

require_cmd docker
dump="${1:-}"
assume_yes="${2:-}"
[ -n "$dump" ] || die "uso: $0 <arquivo.dump> [--yes]"
[ -s "$dump" ] || die "arquivo inexistente ou vazio: $dump"
[ -z "$assume_yes" ] || [ "$assume_yes" = "--yes" ] || die "argumento desconhecido: $assume_yes"
BACKUP_DIR="${BACKUP_DIR:-./backups}"

echo "== 1. conferindo o arquivo"
if [ -f "$dump.sha256" ]; then
    ( cd "$(dirname "$dump")" && sha256sum -c "$(basename "$dump").sha256" >/dev/null 2>&1 ) \
        && ok "checksum confere" || die "checksum NÃO confere: o arquivo foi alterado ou corrompido. Nada foi feito."
else
    warn "sem .sha256 ao lado do arquivo"
fi
compose up -d postgres --wait --wait-timeout 120 >/dev/null
compose exec -T postgres pg_restore --list < "$dump" >/dev/null || die "o pg_restore não consegue ler $dump. Nada foi feito."
ok "o pg_restore lê o arquivo"

if [ "$assume_yes" != "--yes" ]; then
    [ -t 0 ] || die "sem terminal para confirmar; use --yes."
    echo
    echo "ATENÇÃO: isto SUBSTITUI todo o banco do ERP pelo conteúdo de $dump."
    printf 'Digite RESTAURAR para continuar: '
    read -r answer
    [ "$answer" = "RESTAURAR" ] || die "cancelado; nada foi alterado."
fi

echo "== 2. cópia de segurança do banco atual"
mkdir -p "$BACKUP_DIR"; chmod 700 "$BACKUP_DIR"
safety="$BACKUP_DIR/pre-restore-$(date -u +%Y%m%dT%H%M%SZ).dump"
umask 077
if in_postgres 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -F c' > "$safety.partial" 2>/dev/null && [ -s "$safety.partial" ]; then
    mv "$safety.partial" "$safety"; ok "banco atual guardado em $safety"
else
    rm -f "$safety.partial"; warn "não foi possível copiar o banco atual (vazio ou inexistente); seguindo"
fi

echo "== 3. parando a aplicação"
compose stop caddy frontend backend >/dev/null 2>&1 || true
ok "backend/frontend parados"

echo "== 4. recriando o banco e restaurando"
in_postgres 'psql -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 \
    -c "DROP DATABASE IF EXISTS \"$POSTGRES_DB\" WITH (FORCE)" \
    -c "CREATE DATABASE \"$POSTGRES_DB\" OWNER \"$POSTGRES_USER\""' >/dev/null
in_postgres 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --exit-on-error' < "$dump" \
    || die "a restauração FALHOU. O banco atual está guardado em $safety. A aplicação continua parada."
ok "backup restaurado"

echo "== 5. encerrando as sessões web"
in_postgres 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -c "DELETE FROM iam.spring_session"' >/dev/null
ok "sessões apagadas: todos precisam entrar de novo"

echo "== 6. subindo o stack (o Flyway valida o esquema)"
compose up -d --wait --wait-timeout 300 >/dev/null || die "o stack não ficou saudável; veja: docker compose logs backend"
health="$(compose exec -T backend wget -q -O - http://127.0.0.1:8080/actuator/health 2>/dev/null || true)"
printf '%s' "$health" | grep -q '"status":"UP"' && ok "backend UP" || die "backend sem health UP: $health"
migrations="$(in_postgres 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "select count(*) from flyway_schema_history where success"')"
ok "Flyway: $migrations migrations aplicadas"

echo
echo "RESTAURAÇÃO CONCLUÍDA. Entre no sistema e confira os dados antes de liberar o uso."
