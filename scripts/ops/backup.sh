#!/usr/bin/env bash
# Backup lógico do PostgreSQL do piloto (pg_dump -F c) e cópia para FORA do servidor.
#
#   scripts/ops/backup.sh
#
# Variáveis (todas opcionais):
#   BACKUP_DIR               onde ficam os arquivos locais (padrão ./backups, fora do git, modo 700)
#   BACKUP_KEEP              quantos backups locais manter (padrão 14)
#   BACKUP_EXTERNAL_CMD      comando que copia o backup para FORA deste servidor. Roda com `bash -c`, recebendo
#                            $1 = o arquivo .dump e $2 = o arquivo .sha256. Exemplos:
#                              BACKUP_EXTERNAL_CMD='scp "$1" "$2" backup@servidor-externo:/srv/erp-backups/'
#                              BACKUP_EXTERNAL_CMD='rclone copyto "$1" remoto:erp/$(basename "$1") && rclone copyto "$2" remoto:erp/$(basename "$2")'
#                              BACKUP_EXTERNAL_CMD='aws s3 cp "$1" s3://meu-bucket/erp/ && aws s3 cp "$2" s3://meu-bucket/erp/'
#   BACKUP_ALLOW_LOCAL_ONLY  =1 aceita ficar só no servidor (homologação/desenvolvimento). Em produção NÃO use: um
#                            backup no mesmo disco some junto com o servidor.
#
# Falha fechada: sem BACKUP_EXTERNAL_CMD (e sem BACKUP_ALLOW_LOCAL_ONLY=1) o arquivo local é criado e validado, mas o
# script termina com código 3, para um agendamento (cron) nunca parecer "ok" sem cópia externa.
# Códigos: 0 ok · 1 erro (dump inválido, cópia externa falhou) · 3 backup local ok, sem cópia externa configurada.
set -euo pipefail
cd "$(dirname "$0")/../.."
# shellcheck source=lib.sh
. scripts/ops/lib.sh
load_ops_config

require_cmd docker
BACKUP_DIR="${BACKUP_DIR:-./backups}"
KEEP="${BACKUP_KEEP:-14}"
case "$KEEP" in ''|*[!0-9]*) die "BACKUP_KEEP deve ser um número inteiro (recebido '$KEEP')." ;; esac
[ "$KEEP" -ge 1 ] || die "BACKUP_KEEP deve ser pelo menos 1."

postgres_running || die "o container postgres não está rodando (docker compose up -d postgres)."

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
file="$BACKUP_DIR/erp-$stamp.dump"
partial="$file.partial"
trap 'rm -f "$partial"' EXIT

echo "== backup do PostgreSQL ($stamp)"
umask 077
# pg_dump roda dentro do container: o redirecionamento só recebe o arquivo se o comando terminar com sucesso.
in_postgres 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -F c' > "$partial" \
    || die "pg_dump falhou; nenhum backup foi gravado."
[ -s "$partial" ] || die "o backup saiu vazio; nada foi gravado."
# O arquivo precisa ser lido de volta pelo pg_restore: pega arquivo truncado/corrompido já aqui.
compose exec -T postgres pg_restore --list < "$partial" > /dev/null || die "o pg_restore não consegue ler o backup gerado."
mv "$partial" "$file"
( cd "$BACKUP_DIR" && sha256sum "$(basename "$file")" > "$(basename "$file").sha256" )
ok "arquivo local: $file ($(wc -c < "$file") bytes, sha256 $(cut -c1-16 "$file.sha256")...)"

status=0
if [ -n "${BACKUP_EXTERNAL_CMD:-}" ]; then
    echo "== cópia para fora do servidor"
    if bash -c "$BACKUP_EXTERNAL_CMD" _ "$file" "$file.sha256"; then
        ok "cópia externa concluída"
    else
        bad "a cópia externa FALHOU: o backup existe só neste servidor."
        status=1
    fi
elif [ "${BACKUP_ALLOW_LOCAL_ONLY:-0}" = 1 ]; then
    warn "backup SOMENTE local (BACKUP_ALLOW_LOCAL_ONLY=1). Em produção isto não é backup: configure BACKUP_EXTERNAL_CMD."
else
    bad "BACKUP_EXTERNAL_CMD não está configurado: o backup está só neste servidor e se perde com ele."
    echo "         Configure a cópia externa (veja o cabeçalho deste script) ou, só em homologação, BACKUP_ALLOW_LOCAL_ONLY=1." >&2
    status=3
fi

# Retenção local: só depois de um backup novo e válido existir.
if [ "$status" -ne 1 ]; then
    # shellcheck disable=SC2012
    ls -1t "$BACKUP_DIR"/erp-*.dump 2>/dev/null | tail -n +"$((KEEP + 1))" | while read -r old; do
        rm -f "$old" "$old.sha256"
    done
fi
exit "$status"
