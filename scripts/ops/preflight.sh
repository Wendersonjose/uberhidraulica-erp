#!/usr/bin/env bash
# Checklist automático de produção do piloto: lê a configuração REAL que o docker compose vai usar (`docker compose
# config`, já com .env, COMPOSE_FILE e overrides) e confere o que, se estiver errado, expõe o sistema ou os dados.
#
#   scripts/ops/preflight.sh                                  confere só a configuração e o banco
#   PUBLIC_URL=https://erp.suaoficina.com.br scripts/ops/preflight.sh   confere também o site publicado (HTTPS de verdade)
#   scripts/ops/preflight.sh --homologation                   tolera HTTP, cookie sem Secure e backup só local (AVISO)
#
# Código de saída: 0 sem falhas · 1 com pelo menos uma FALHA. Nenhum segredo é impresso.
# Variáveis: PUBLIC_URL, PREFLIGHT_CURL_OPTS (ex.: --cacert ca.pem num ambiente de teste), BACKUP_DIR,
# BACKUP_EXTERNAL_CMD (só é verificado se está definido; ver scripts/ops/backup.sh).
#
# Isto NÃO substitui o ensaio de restore (scripts/ops/verify-backup.sh) nem a homologação operacional.
set -uo pipefail
cd "$(dirname "$0")/../.."
# shellcheck source=lib.sh
. scripts/ops/lib.sh
load_ops_config

mode=production
case "${1:-}" in
    "") ;;
    --homologation) mode=homologation ;;
    *) die "uso: $0 [--homologation]" ;;
esac
failures=0; warnings=0
# Em produção é falha; em homologação, aviso.
strict() { if [ "$mode" = production ]; then bad "$*"; failures=$((failures + 1)); else warn "$* (tolerado em homologação)"; warnings=$((warnings + 1)); fi; }
fail()   { bad "$*"; failures=$((failures + 1)); }
note()   { warn "$*"; warnings=$((warnings + 1)); }

require_cmd docker
require_cmd python3
echo "== preflight ($mode)"

echo "-- configuração do compose"
if ! cfg="$(compose config --format json 2>&1)"; then
    fail "docker compose config falhou (variável obrigatória ausente?):"
    printf '%s\n' "$cfg" | sed 's/^/         /'
    echo; echo "Preflight: abortado, $failures falha(s)."; exit 1
fi
ok "docker compose config válido"

# Fatos derivados da configuração (nunca os valores secretos).
facts="$(printf '%s' "$cfg" | python3 -c '
import json, sys
cfg = json.load(sys.stdin); svcs = cfg.get("services", {})
env = lambda s: svcs.get(s, {}).get("environment") or {}
be, fe, ca = env("backend"), env("frontend"), env("caddy")
WEAK = {"postgres","postgresql","password","passwd","pass","admin","administrator","root","123456","12345678",
        "123456789","1234567890","qwerty","secret","changeme","change-me","change_me","troque-esta-senha",
        "uberhidraulica","uberhidraulica_dev","test","teste","example","default","letmein","welcome"}
def weakness(v, minlen):
    p = []
    if not v: return ["ausente"]
    n = v.strip().lower()
    if "${" in v: p.append("não resolvido")
    if n in WEAK or "changeme" in n or "troque-esta" in n: p.append("valor conhecido")
    if len(v) < minlen: p.append("menos de %d caracteres" % minlen)
    if len(set(v)) < 5: p.append("pouca variedade")
    return p
pw = be.get("DB_PASSWORD", "")
w = weakness(pw, 12)
if pw and pw.lower() == (be.get("DB_USERNAME") or "").strip().lower(): w.append("igual ao usuário")
boot = be.get("IAM_BOOTSTRAP_OWNER_PASSWORD", "")
bw = [] if not boot else [x for x in weakness(boot, 8) if x in ("valor conhecido", "não resolvido")]
def exposed(s):
    out = []
    for p in svcs.get(s, {}).get("ports") or []:
        out.append("%s:%s" % (p.get("host_ip") or "0.0.0.0", p.get("published")))
    return ",".join(out)
print("profiles=" + (be.get("SPRING_PROFILES_ACTIVE") or ""))
print("app_environment=" + (be.get("APP_ENVIRONMENT") or "production"))
print("cookie_secure=" + str(be.get("SESSION_COOKIE_SECURE") or ""))
print("db_password_problems=" + "; ".join(w))
print("bootstrap_problems=" + "; ".join(bw))
print("trusted=" + (fe.get("TRUSTED_PROXY_CIDRS") or ""))
print("has_caddy=" + ("1" if "caddy" in svcs else "0"))
print("site_address=" + (ca.get("SITE_ADDRESS") or ""))
print("postgres_ports=" + exposed("postgres"))
print("backend_ports=" + exposed("backend"))
print("frontend_ports=" + exposed("frontend"))
')" || { fail "não consegui interpretar a configuração do compose"; echo "Preflight: $failures falha(s)."; exit 1; }
fact() { printf '%s\n' "$facts" | sed -n "s/^$1=//p"; }

profiles="$(fact profiles)"; app_env="$(fact app_environment)"; cookie="$(fact cookie_secure)"
case ",$profiles," in *,prod,*) ok "perfil Spring contém 'prod' ($profiles)" ;; *) fail "SPRING_PROFILES_ACTIVE='$profiles' não contém 'prod'" ;; esac
if [ "$app_env" = production ]; then ok "APP_ENVIRONMENT=production"
elif [ "$app_env" = homologation ]; then strict "APP_ENVIRONMENT=homologation (permite cookie sem Secure em HTTP)"
else fail "APP_ENVIRONMENT='$app_env' inválido (use production ou homologation)"; fi
[ "$cookie" = true ] && ok "SESSION_COOKIE_SECURE=true" || strict "SESSION_COOKIE_SECURE='$cookie' (produção exige true e HTTPS)"
problems="$(fact db_password_problems)"
[ -z "$problems" ] && ok "senha do banco forte (12+ caracteres, não é valor conhecido)" || fail "DB_PASSWORD recusada: $problems"
problems="$(fact bootstrap_problems)"
[ -z "$problems" ] && ok "senha de bootstrap do Dono não é valor conhecido" || fail "IAM_BOOTSTRAP_OWNER_PASSWORD recusada: $problems"

echo "-- exposição de portas"
[ -z "$(fact backend_ports)" ] && ok "backend não publica porta no host" || fail "backend publica porta: $(fact backend_ports)"
pg_exposed="$(fact postgres_ports | tr ',' '\n' | grep -v '^127\.0\.0\.1:' | grep -v '^$' || true)"
[ -z "$pg_exposed" ] && ok "PostgreSQL não está exposto fora do host" || fail "PostgreSQL publicado para fora do host: $pg_exposed"

echo "-- HTTPS e proxy confiável"
trusted="$(fact trusted)"
if [ "$(fact has_caddy)" = 1 ]; then
    ok "Caddy (compose.https.yaml) termina o HTTPS"
    site="$(fact site_address)"
    case "$site" in
        ""|localhost|*.localhost|127.*|*.local) strict "SITE_ADDRESS='$site' não é um domínio público (certificado de CA interna)" ;;
        *) ok "SITE_ADDRESS é um domínio público ($site)" ;;
    esac
    [ -z "$(fact frontend_ports)" ] && ok "o nginx do ERP não publica porta (só o Caddy fala com o mundo)" || fail "o nginx do ERP publica porta junto com o Caddy: $(fact frontend_ports)"
else
    [ -n "$(fact frontend_ports)" ] && note "sem Caddy: o frontend publica $(fact frontend_ports); o HTTPS precisa vir do balanceador do provedor"
fi
if [ -n "$trusted" ]; then ok "TRUSTED_PROXY_CIDRS definido ($trusted)"
else strict "TRUSTED_PROXY_CIDRS vazio: com um proxy HTTPS na frente, o IP gravado no orçamento público seria o do proxy"; fi

if [ -n "${PUBLIC_URL:-}" ]; then
    echo "-- site publicado ($PUBLIC_URL)"
    require_cmd curl
    # shellcheck disable=SC2086
    curlx() { curl -sS -m 15 ${PREFLIGHT_CURL_OPTS:-} "$@"; }
    case "$PUBLIC_URL" in https://*) host="${PUBLIC_URL#https://}"; host="${host%%/*}" ;; *) host=""; fail "PUBLIC_URL deve começar com https://" ;; esac
    if [ -n "$host" ]; then
        base="https://$host"
        if curlx -o /dev/null "$base/" 2>/tmp/preflight-curl.$$; then ok "HTTPS responde com certificado aceito pelo cliente"
        else fail "falha no HTTPS: $(head -c 200 /tmp/preflight-curl.$$)"; fi
        rm -f /tmp/preflight-curl.$$
        loc="$(curlx -o /dev/null -w '%{http_code} %{redirect_url}' "http://$host/" 2>/dev/null || true)"
        case "$loc" in 301\ https://*|302\ https://*|307\ https://*|308\ https://*) ok "HTTP redireciona para HTTPS ($loc)" ;; *) fail "HTTP não redireciona para HTTPS (obtido: '$loc')" ;; esac
        headers="$(curlx -I "$base/" 2>/dev/null || true)"
        printf '%s' "$headers" | grep -qi '^strict-transport-security:' && ok "HSTS presente" || fail "sem Strict-Transport-Security"
        for header in "x-frame-options" "x-content-type-options" "content-security-policy"; do
            printf '%s' "$headers" | grep -qi "^$header:" && ok "cabeçalho $header presente" || fail "sem o cabeçalho $header"
        done
        printf '%s' "$headers" | grep -qi '^server: .*[0-9]\.[0-9]' && note "o cabeçalho Server revela a versão do software"
        health="$(curlx "$base/actuator/health" 2>/dev/null || true)"
        if printf '%s' "$health" | grep -q '"status":"UP"'; then
            printf '%s' "$health" | grep -Eq '"(components|details|db|diskSpace)"' && fail "/actuator/health expõe detalhes internos" || ok "/actuator/health responde UP sem detalhes internos"
        else fail "/actuator/health não responde UP: $(printf '%s' "$health" | head -c 120)"; fi
        for endpoint in env beans metrics heapdump loggers mappings configprops; do
            ct="$(curlx -o /dev/null -w '%{http_code} %{content_type}' "$base/actuator/$endpoint" 2>/dev/null || true)"
            case "$ct" in 200\ *json*|200\ *octet-stream*) fail "/actuator/$endpoint está acessível ($ct)" ;; esac
        done
        ok "nenhum endpoint sensível do actuator está acessível"
        cookie_header="$(curlx -D - -o /dev/null "$base/api/iam/csrf" 2>/dev/null | grep -i '^set-cookie:' | head -1 || true)"
        if [ -z "$cookie_header" ]; then note "não consegui observar o cookie de sessão (sem Set-Cookie em /api/iam/csrf)"
        else
            for flag in "Secure" "HttpOnly" "SameSite="; do
                printf '%s' "$cookie_header" | grep -qi "$flag" && ok "cookie de sessão com $flag" || fail "cookie de sessão sem $flag"
            done
        fi
        if command -v openssl >/dev/null 2>&1; then
            end="$(echo | openssl s_client -servername "$host" -connect "$host:443" 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)"
            if [ -n "$end" ]; then
                days=$(( ( $(date -d "$end" +%s) - $(date +%s) ) / 86400 ))
                [ "$days" -ge 15 ] && ok "certificado válido por mais $days dias" || strict "certificado vence em $days dia(s)"
            fi
        fi
    fi
else
    note "PUBLIC_URL não definida: o site publicado (HTTPS, HSTS, cookie) NÃO foi conferido"
fi

echo "-- banco e acessos"
if postgres_running; then
    pg() { in_postgres "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -tA -v ON_ERROR_STOP=1 -c \"$1\""; }
    failed_migrations="$(pg "select count(*) from flyway_schema_history where not success" 2>/dev/null || echo erro)"
    [ "$failed_migrations" = 0 ] && ok "Flyway sem migration com falha" || fail "histórico do Flyway com falha ($failed_migrations)"
    owners="$(pg "select count(*) from iam.app_user u join iam.profile p on p.id = u.profile_id where p.code = 'DONO' and u.state = 'ACTIVE'" 2>/dev/null || echo 0)"
    [ "$owners" -ge 1 ] 2>/dev/null && ok "há $owners Dono(s) ativo(s)" || fail "nenhum Dono ativo (o bootstrap rodou?)"
    # DR-0020: enquanto não houver decisão do proprietário, só o Dono tem permissões IAM_*.
    delegated="$(pg "select p.code || ' tem ' || perm.code from iam.profile_permission pp join iam.profile p on p.id = pp.profile_id join iam.permission perm on perm.id = pp.permission_id where perm.code like 'IAM\_%' and p.code <> 'DONO' union all select 'usuário ' || u.id || ' tem exceção ALLOW em ' || perm.code from iam.user_permission_exception e join iam.app_user u on u.id = e.user_id join iam.profile p on p.id = u.profile_id join iam.permission perm on perm.id = e.permission_id where e.resolution = 'ALLOW' and perm.code like 'IAM\_%' and p.code <> 'DONO' and u.state = 'ACTIVE'" 2>/dev/null || echo erro)"
    if [ "$delegated" = erro ]; then fail "não consegui consultar as permissões IAM"
    elif [ -z "$delegated" ]; then ok "nenhum perfil/usuário além do Dono tem permissão IAM_* (DR-0020)"
    else fail "permissão IAM_* delegada fora do Dono (DR-0020 ainda sem decisão): $(printf '%s' "$delegated" | tr '\n' ';')"; fi
else
    note "o container postgres não está rodando: as checagens do banco foram puladas"
fi

echo "-- backup"
if [ -n "${BACKUP_EXTERNAL_CMD:-}" ]; then ok "BACKUP_EXTERNAL_CMD definido (cópia para fora do servidor)"
else strict "BACKUP_EXTERNAL_CMD não definido neste ambiente: o backup ficaria só no servidor"; fi
latest="$(ls -1t "${BACKUP_DIR:-./backups}"/erp-*.dump 2>/dev/null | head -1 || true)"
if [ -z "$latest" ]; then note "nenhum backup local encontrado em ${BACKUP_DIR:-./backups}"
else
    age_h=$(( ( $(date +%s) - $(stat -c %Y "$latest") ) / 3600 ))
    [ "$age_h" -le 26 ] && ok "último backup local tem ${age_h}h" || note "último backup local tem ${age_h}h (mais de um dia)"
fi

echo
echo "Preflight ($mode): $failures falha(s), $warnings aviso(s)."
[ "$failures" -eq 0 ] && echo "As checagens automáticas passaram. Elas NÃO substituem o ensaio de restore nem a homologação operacional." || echo "Corrija as FALHAS antes de liberar o sistema."
[ "$failures" -eq 0 ]
