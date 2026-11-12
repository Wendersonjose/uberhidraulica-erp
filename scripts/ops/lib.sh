# Funções comuns dos scripts de operação (scripts/ops/*.sh). Use com `. scripts/ops/lib.sh`, a partir da raiz do repo.
#
# Os scripts falam com o stack pelo `docker compose` e respeitam COMPOSE_FILE / COMPOSE_PROJECT_NAME / COMPOSE_ENV_FILES
# (inclusive os definidos no .env). Se você sobe com o HTTPS do Caddy, ponha no .env:
#     COMPOSE_FILE=compose.yaml:compose.https.yaml
# e `docker compose up -d`, backup, restore e preflight passam a enxergar o mesmo conjunto de serviços.

ok()   { echo "  [OK]     $*"; }
warn() { echo "  [AVISO]  $*"; }
bad()  { echo "  [FALHA]  $*"; }
die()  { echo "ERRO: $*" >&2; exit 1; }

compose() { docker compose "$@"; }

# Roda um comando dentro do container postgres, com o usuário/banco que o próprio container conhece
# (POSTGRES_USER / POSTGRES_DB), para os scripts não precisarem ler segredos do .env.
in_postgres() { compose exec -T postgres sh -c "$1"; }

require_cmd() { command -v "$1" >/dev/null 2>&1 || die "'$1' não encontrado no PATH."; }

postgres_running() {
    [ "$(compose ps --status running --format '{{.Service}}' postgres 2>/dev/null)" = "postgres" ]
}

# Lê do .env só as chaves de operação abaixo (sem `source`: o .env do compose aceita valores com espaço sem aspas e
# `source` quebraria). O que já está no ambiente do shell/cron tem prioridade. Aspas simples ou duplas nas pontas são
# removidas. Use aspas SIMPLES em comandos com $1/$2 (BACKUP_EXTERNAL_CMD), para o compose não interpolá-los.
load_ops_config() {
    [ -f .env ] || return 0
    local key line value
    for key in BACKUP_DIR BACKUP_KEEP BACKUP_EXTERNAL_CMD BACKUP_ALLOW_LOCAL_ONLY PUBLIC_URL; do
        [ -n "${!key:-}" ] && continue
        line="$(grep -E "^${key}=" .env | tail -1 || true)"
        [ -n "$line" ] || continue
        value="${line#*=}"
        case "$value" in
            \"*\") value="${value%\"}"; value="${value#\"}" ;;
            \'*\') value="${value%\'}"; value="${value#\'}" ;;
        esac
        export "$key=$value"
    done
}
