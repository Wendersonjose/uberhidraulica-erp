#!/usr/bin/env bash
# Atualização do piloto sem perder dados: preflight → backup → build/subida → health → smoke → registro do deploy.
# Nunca usa `down -v` e nunca apaga o volume do banco. O Flyway aplica as migrations novas ao subir o backend.
#
#   git pull                       # ou: git checkout <tag/commit>
#   scripts/ops/update.sh          # produção: preflight de produção + backup com cópia externa obrigatória
#   scripts/ops/update.sh --homologation
#
# O histórico fica em BACKUP_DIR/deploy-history.log (data, commit, backup anterior, migrations antes/depois).
# Se algo falhar, o script imprime o rollback exato (veja DEPLOY-piloto.md, seção "Atualizar e voltar atrás").
# Códigos: 0 ok · 1 falhou antes ou depois de subir (veja a mensagem).
set -euo pipefail
cd "$(dirname "$0")/../.."
# shellcheck source=lib.sh
. scripts/ops/lib.sh
load_ops_config

require_cmd docker
mode_flag=""
case "${1:-}" in
    "") ;;
    --homologation) mode_flag="--homologation" ;;
    *) die "uso: $0 [--homologation]" ;;
esac
BACKUP_DIR="${BACKUP_DIR:-./backups}"
export BACKUP_DIR
history="$BACKUP_DIR/deploy-history.log"
commit="$(git rev-parse --short HEAD 2>/dev/null || echo desconhecido)"
previous=""
[ -f "$history" ] && previous="$(tail -1 "$history" | sed -n 's/.*commit=\([^ ]*\).*/\1/p')"

migrations() {
    in_postgres 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "select count(*) from flyway_schema_history where success"' 2>/dev/null || echo "?"
}

echo "== atualização: ${previous:-primeira instalação} -> $commit"
echo "== 1. preflight"
# shellcheck disable=SC2086
scripts/ops/preflight.sh $mode_flag || die "o preflight falhou: nada foi alterado. Corrija e rode de novo."

echo "== 2. backup antes de atualizar"
before_migrations="?"
backup_file="(banco novo: sem backup)"
if postgres_running; then
    before_migrations="$(migrations)"
    scripts/ops/backup.sh || die "o backup falhou (ou não há cópia externa): nada foi alterado. Sem backup não se atualiza."
    backup_file="$(ls -1t "$BACKUP_DIR"/erp-*.dump | head -1)"
else
    echo "     o postgres não está rodando: instalação nova, nada a copiar."
fi

rollback_hint() {
    cat >&2 <<EOF

ROLLBACK (a atualização para $commit não ficou saudável):
  1. git checkout ${previous:-<commit anterior>}
  2. docker compose up -d --build --wait
  Se as migrations mudaram ($before_migrations -> $(migrations)), o código antigo não aceita o esquema novo:
  restaure o backup anterior à atualização:
     scripts/ops/restore.sh $backup_file
  e só então suba o código anterior.
EOF
}

echo "== 3. build e subida (docker compose up -d --build --wait)"
if ! BUILDKIT_PROGRESS=quiet compose up -d --build --wait --wait-timeout 420 >/dev/null; then
    bad "o stack não ficou saudável"; compose ps >&2 || true; rollback_hint; exit 1
fi

echo "== 4. smoke pós-deploy"
smoke_failed=0
health="$(compose exec -T backend wget -q -O - http://127.0.0.1:8080/actuator/health 2>/dev/null || true)"
printf '%s' "$health" | grep -q '"status":"UP"' && ok "backend UP" || { bad "backend sem health UP"; smoke_failed=1; }
via_proxy="$(compose exec -T frontend wget -q -O - http://127.0.0.1/actuator/health 2>/dev/null || true)"
printf '%s' "$via_proxy" | grep -q '"status":"UP"' && ok "nginx encaminha o health ao backend" || { bad "nginx não alcança o backend"; smoke_failed=1; }
csrf="$(compose exec -T frontend wget -q -O - http://127.0.0.1/api/iam/csrf 2>/dev/null || true)"
printf '%s' "$csrf" | grep -q headerName && ok "API responde pelo proxy (/api/iam/csrf)" || { bad "API não responde pelo proxy"; smoke_failed=1; }
failed_mig="$(in_postgres 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "select count(*) from flyway_schema_history where not success"' 2>/dev/null || echo erro)"
[ "$failed_mig" = 0 ] && ok "Flyway sem migration com falha" || { bad "histórico do Flyway com falha ($failed_mig)"; smoke_failed=1; }
after_migrations="$(migrations)"
ok "migrations aplicadas: $before_migrations -> $after_migrations"
for service in postgres backend frontend; do
    state="$(compose ps --format '{{.Service}} {{.Health}}' | awk -v s="$service" '$1 == s {print $2}')"
    [ "$state" = healthy ] && ok "$service saudável" || { bad "$service não está saudável ($state)"; smoke_failed=1; }
done
if [ "$smoke_failed" -ne 0 ]; then rollback_hint; exit 1; fi

mkdir -p "$BACKUP_DIR"
printf '%s commit=%s previous=%s backup=%s migrations=%s->%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$commit" "${previous:-none}" \
    "$backup_file" "$before_migrations" "$after_migrations" >> "$history"
echo
echo "ATUALIZAÇÃO CONCLUÍDA ($commit). Registro em $history."
[ "$before_migrations" = "$after_migrations" ] \
    && echo "Nenhuma migration nova: o rollback é só voltar o código (git checkout ${previous:-<anterior>} && docker compose up -d --build)." \
    || echo "Migrations novas aplicadas: para voltar, restaure $backup_file ANTES de subir o código anterior."
