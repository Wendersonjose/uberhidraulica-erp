#!/usr/bin/env bash
# Smoke test do stack do piloto com Docker de verdade (compose.yaml):
#   1. sobe postgres + backend + frontend e espera todos ficarem saudáveis;
#   2. confere /actuator/health, o proxy /api, os cabeçalhos de segurança e o cookie de sessão;
#   3. roda o fluxo E2E da oficina (scripts/e2e/workshop_flow.py) pelo nginx;
#   4. confere que o nginx não deixa o cliente forjar o IP gravado na evidência do orçamento público;
#   5. reinicia os containers e confere que os dados persistem;
#   6. faz backup (pg_dump), destrói o volume, restaura em um volume novo e confere os dados;
#   7. confere o limite de requisições do login (429).
#
# Uso:  scripts/deploy/compose-smoke.sh        (requer docker compose, curl, python3)
# Não toca no seu .env: usa um arquivo temporário com segredos aleatórios e o projeto `erp-smoke`.
set -euo pipefail
cd "$(dirname "$0")/../.."

PROJECT="${COMPOSE_PROJECT_NAME:-erp-smoke}"
HTTP_PORT="${SMOKE_HTTP_PORT:-18081}"
BASE="http://localhost:${HTTP_PORT}"
WORK="$(mktemp -d)"
ENV_FILE="${WORK}/smoke.env"

rand() { head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-24; }
DB_PASSWORD="$(rand)"
BOOTSTRAP_PASSWORD="Boot-$(rand)"
OWNER_PASSWORD="Dono-$(rand)"
OWNER_EMAIL="dono-smoke@example.test"

cat > "${ENV_FILE}" <<EOF
SPRING_PROFILES_ACTIVE=prod
# O smoke fala HTTP puro: o cookie sem Secure só é aceito com a homologação declarada de propósito (falha fechada).
APP_ENVIRONMENT=homologation
SESSION_COOKIE_SECURE=false
HTTP_PORT=${HTTP_PORT}
DB_NAME=uberhidraulica
DB_USERNAME=uberhidraulica
DB_PASSWORD=${DB_PASSWORD}
IAM_BOOTSTRAP_OWNER_NAME=Dono Smoke
IAM_BOOTSTRAP_OWNER_EMAIL=${OWNER_EMAIL}
IAM_BOOTSTRAP_OWNER_PASSWORD=${BOOTSTRAP_PASSWORD}
EOF

compose() { docker compose --env-file "${ENV_FILE}" -p "${PROJECT}" "$@"; }
psql_q() { compose exec -T postgres psql -U uberhidraulica -d uberhidraulica -tA -c "$1"; }
FAILURES=0
ok()   { echo "  [OK]   $1"; }
fail() { echo "  [FAIL] $1"; FAILURES=$((FAILURES + 1)); }
check() { local name="$1"; shift; if "$@" >/dev/null 2>&1; then ok "${name}"; else fail "${name}"; fi; }

cleanup() {
  local code=$?
  if [ "${code}" -ne 0 ] || [ "${FAILURES}" -ne 0 ]; then
    echo; echo "== logs (falha)"; compose logs --no-color --tail 60 backend frontend postgres 2>&1 || true
  fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "${WORK}"
}
trap cleanup EXIT

login_status() {  # login_status <email> <senha>  -> código HTTP
  local jar="${WORK}/jar.$$"; rm -f "${jar}"
  local csrf token header
  csrf="$(curl -fsS -c "${jar}" "${BASE}/api/iam/csrf")"
  token="$(printf '%s' "${csrf}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')"
  header="$(printf '%s' "${csrf}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["headerName"])')"
  curl -s -o /dev/null -w '%{http_code}' -b "${jar}" -c "${jar}" -H "${header}: ${token}" \
    -H 'Content-Type: application/json' -d "{\"email\":\"$1\",\"password\":\"$2\"}" "${BASE}/api/iam/auth/login"
}

echo "== 1. subir o stack (docker compose up -d --build --wait)"
compose up -d --build --wait --wait-timeout 420
compose ps
for service in postgres backend frontend; do
  state="$(compose ps --format '{{.Service}} {{.Health}}' | awk -v s="${service}" '$1 == s {print $2}')"
  [ "${state}" = "healthy" ] && ok "${service} saudável" || fail "${service} não saudável (${state})"
done

echo; echo "== 2. health, proxy e cabeçalhos"
check "/actuator/health via nginx responde UP" bash -c "curl -fsS ${BASE}/actuator/health | grep -q '\"status\":\"UP\"'"
check "/api/iam/csrf via proxy responde JSON" bash -c "curl -fsS ${BASE}/api/iam/csrf | grep -q headerName"
check "rota interna sem sessão responde 401 via proxy" bash -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' ${BASE}/api/customers)\" = 401 ]"
check "SPA servida em /" bash -c "curl -fsS ${BASE}/ | grep -q 'id=\"root\"'"
check "rota do SPA cai no index.html" bash -c "curl -fsS ${BASE}/ordens-servico | grep -q 'id=\"root\"'"
HEADERS="$(curl -sI "${BASE}/")"
for header in "X-Frame-Options: DENY" "X-Content-Type-Options: nosniff" "Content-Security-Policy:" "Referrer-Policy: no-referrer"; do
  printf '%s' "${HEADERS}" | grep -qi "${header}" && ok "HTML com ${header}" || fail "HTML sem ${header}"
done
ASSET="$(curl -fsS "${BASE}/" | grep -o '/assets/[^"]*\.js' | head -1)"
curl -sI "${BASE}${ASSET}" | grep -qi "X-Frame-Options: DENY" && ok "/assets com X-Frame-Options" || fail "/assets sem X-Frame-Options"
curl -sI "${BASE}/api/iam/csrf" | grep -qi "X-Content-Type-Options: nosniff" && ok "/api com nosniff (backend)" || fail "/api sem nosniff"
[ "$(login_status "${OWNER_EMAIL}" "${BOOTSTRAP_PASSWORD}")" = 200 ] && ok "bootstrap criou o Dono (login com a senha provisória)" || fail "bootstrap não criou o Dono"

echo; echo "== 3. fluxo E2E da oficina pelo nginx"
FORGED="203.0.113.250"
if BASE_URL="${BASE}" E2E_OWNER_EMAIL="${OWNER_EMAIL}" E2E_OWNER_BOOTSTRAP_PASSWORD="${BOOTSTRAP_PASSWORD}" \
   E2E_OWNER_PASSWORD="${OWNER_PASSWORD}" E2E_PUBLIC_XFF="${FORGED}" python3 scripts/e2e/workshop_flow.py; then
  ok "E2E completo"
else
  fail "E2E completo"
fi

echo; echo "== 4. evidência de IP do orçamento público"
RECORDED="$(psql_q "select host(ip_address) from workshop.quote_decision_submission where channel='PUBLIC_LINK' order by occurred_at desc limit 1")"
echo "     IP gravado: ${RECORDED}"
[ -n "${RECORDED}" ] && [ "${RECORDED}" != "${FORGED}" ] && ok "IP forjado no X-Forwarded-For NÃO foi gravado" || fail "IP forjado foi gravado (${RECORDED})"

echo; echo "== 5. reinício e persistência"
ORDERS_BEFORE="$(psql_q 'select count(*) from workorder.work_order')"
compose restart
compose up -d --wait --wait-timeout 300
ORDERS_AFTER="$(psql_q 'select count(*) from workorder.work_order')"
[ "${ORDERS_BEFORE}" = "${ORDERS_AFTER}" ] && [ "${ORDERS_AFTER}" -gt 0 ] && ok "dados persistem após reinício (${ORDERS_AFTER} OS)" || fail "dados mudaram no reinício (${ORDERS_BEFORE} -> ${ORDERS_AFTER})"
[ "$(login_status "${OWNER_EMAIL}" "${OWNER_PASSWORD}")" = 200 ] && ok "login do Dono após reinício" || fail "login do Dono após reinício"
[ "$(login_status "${OWNER_EMAIL}" "${BOOTSTRAP_PASSWORD}")" = 401 ] && ok "senha de bootstrap não vale mais" || fail "senha de bootstrap ainda vale"
MIGRATIONS="$(psql_q 'select count(*) from flyway_schema_history where success')"
echo "     migrations Flyway aplicadas: ${MIGRATIONS}"

echo; echo "== 6. backup e restore em volume novo"
compose exec -T postgres pg_dump -U uberhidraulica -F c -d uberhidraulica > "${WORK}/backup.dump"
[ -s "${WORK}/backup.dump" ] && ok "backup gerado ($(wc -c < "${WORK}/backup.dump") bytes)" || fail "backup vazio"
compose down -v --remove-orphans
compose up -d postgres --wait --wait-timeout 120
compose exec -T postgres pg_restore -U uberhidraulica -d uberhidraulica --clean --if-exists < "${WORK}/backup.dump"
[ "$(psql_q 'select count(*) from workorder.work_order')" = "${ORDERS_AFTER}" ] && ok "restore trouxe as ${ORDERS_AFTER} OS de volta" || fail "restore não trouxe as OS"
compose up -d --build --wait --wait-timeout 300
[ "$(login_status "${OWNER_EMAIL}" "${OWNER_PASSWORD}")" = 200 ] && ok "login do Dono depois do restore" || fail "login do Dono depois do restore"
[ "$(psql_q 'select count(*) from flyway_schema_history where success')" = "${MIGRATIONS}" ] && ok "histórico Flyway íntegro após o restore" || fail "histórico Flyway divergente"

echo; echo "== 7. limite de requisições do login"
CODES=""
for _ in $(seq 1 45); do CODES="${CODES} $(login_status "${OWNER_EMAIL}" "senha-errada-$RANDOM")"; done
printf '%s' "${CODES}" | grep -q 429 && ok "login excedendo o limite responde 429" || fail "login nunca respondeu 429 (${CODES})"

echo
if [ "${FAILURES}" -eq 0 ]; then echo "SMOKE OK"; else echo "SMOKE COM ${FAILURES} FALHA(S)"; exit 1; fi
