#!/usr/bin/env bash
# Verifica o ERP PUBLICADO (frontend hospedado + backend hospedado + Supabase) pelo endereço público do frontend:
# health do backend, SPA e refresh, /api passando pelo frontend, CSRF, login, cookie de sessão, sessão que persiste,
# logout e novo login e, opcionalmente, cliente → veículo → OS com dados de teste identificáveis.
#
#   ERP_URL=https://uberhidraulica-erp-web.onrender.com \
#   ERP_BACKEND_URL=https://uberhidraulica-erp-api.onrender.com \
#   ERP_EMAIL=dono@suaoficina.com.br ERP_PASSWORD='...' \
#   scripts/deploy/cloud-check.sh [--create-test-data]
#
# Os valores vêm do AMBIENTE e nunca são impressos. Requer curl e python3.
#   ERP_BACKEND_URL    opcional; confere /actuator/health direto no backend
#   ERP_CURL_OPTS      opcional; opções extras do curl (ex.: --cacert ca.pem num ambiente de teste)
#   --create-test-data cria "ZZ TESTE CLOUD <data>" (cliente, veículo e OS). Não há exclusão pela API: depois, inative o
#                      cliente e o veículo de teste pela tela se não quiser vê-los.
#
# Com a senha provisória do bootstrap o script confirma o login e PARA no passo "troca obrigatória da senha": troque a
# senha pela tela, depois rode de novo com a senha nova. Código de saída: 0 sem falhas, 1 com falhas, 2 uso incorreto.
set -uo pipefail

[ -n "${ERP_URL:-}" ] && [ -n "${ERP_EMAIL:-}" ] && [ -n "${ERP_PASSWORD:-}" ] || {
    echo "uso: ERP_URL=... ERP_EMAIL=... ERP_PASSWORD=... [ERP_BACKEND_URL=...] $0 [--create-test-data]" >&2; exit 2; }
CREATE=0; [ "${1:-}" = "--create-test-data" ] && CREATE=1
BASE="${ERP_URL%/}"
JAR="$(mktemp)"; trap 'rm -f "$JAR" "$JAR.h" "$JAR.b" "$JAR.code"' EXIT
FAILS=0
ok()   { echo "  [OK]     $*"; }
fail() { echo "  [FALHA]  $*"; FAILS=$((FAILS + 1)); }
note() { echo "  [INFO]   $*"; }
# shellcheck disable=SC2086
cx() { curl -sS -m 120 ${ERP_CURL_OPTS:-} "$@"; }
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)" 2>/dev/null; }

echo "== ERP publicado: $BASE"
if [ -n "${ERP_BACKEND_URL:-}" ]; then
    echo "-- backend direto (a primeira chamada pode levar ~1 min se o serviço estava dormindo)"
    health="$(cx "${ERP_BACKEND_URL%/}/actuator/health" 2>&1)"
    printf '%s' "$health" | grep -q '"status":"UP"' && ok "/actuator/health responde UP" || fail "/actuator/health não responde UP: $(printf '%s' "$health" | head -c 160)"
    printf '%s' "$health" | grep -Eq '"(components|details|db|diskSpace)"' && fail "/actuator/health expõe detalhes internos" || ok "health sem detalhes internos"
fi

echo "-- frontend"
code="$(cx -o /dev/null -w '%{http_code} %{content_type}' "$BASE/" 2>&1)"
case "$code" in 200\ text/html*) ok "GET / → 200 HTML (HTTPS aceito pelo cliente)" ;; *) fail "GET / → $code" ;; esac
code="$(cx -o /dev/null -w '%{http_code} %{content_type}' "$BASE/clientes" 2>&1)"
case "$code" in 200\ text/html*) ok "refresh em /clientes devolve o SPA (fallback /* → /index.html)" ;; *) fail "refresh em /clientes → $code" ;; esac
headers="$(cx -I "$BASE/" 2>/dev/null)"
for h in x-frame-options content-security-policy strict-transport-security; do
    printf '%s' "$headers" | grep -qi "^$h:" && ok "cabeçalho $h presente" || fail "sem o cabeçalho $h"
done

echo "-- /api passando pelo frontend (mesma origem, sem CORS)"
cx -D "$JAR.h" -o "$JAR.b" -c "$JAR" "$BASE/api/iam/csrf"
body="$(cat "$JAR.b")"
HEADER="$(printf '%s' "$body" | json 'd["headerName"]')"; TOKEN="$(printf '%s' "$body" | json 'd["token"]')"
if [ -n "$HEADER" ] && [ -n "$TOKEN" ]; then ok "GET /api/iam/csrf → JSON com $HEADER (o frontend alcança o backend)"
else fail "GET /api/iam/csrf não devolveu o token CSRF (o rewrite /api/* → backend está certo?): $(printf '%s' "$body" | head -c 120)"; fi
[ "$(cx -o /dev/null -w '%{http_code}' "$BASE/api/customers")" = 401 ] && ok "rota interna sem sessão → 401" || fail "rota interna sem sessão não devolveu 401"

login() { # grava o cookie em $JAR, o corpo em $JAR.b, o código HTTP em $LOGIN_CODE e o token CSRF novo em HEADER/TOKEN
    # (chame SEM $(...): em subshell as variáveis não chegam ao script)
    rm -f "$JAR"; cx -o /dev/null -c "$JAR" "$BASE/api/iam/csrf"
    local csrf; csrf="$(cx -b "$JAR" -c "$JAR" "$BASE/api/iam/csrf")"
    HEADER="$(printf '%s' "$csrf" | json 'd["headerName"]')"; TOKEN="$(printf '%s' "$csrf" | json 'd["token"]')"
    python3 -c 'import json,os,sys; print(json.dumps({"email": os.environ["ERP_EMAIL"], "password": os.environ["ERP_PASSWORD"]}))' \
        | cx -D "$JAR.h" -o "$JAR.b" -w '%{http_code}' -b "$JAR" -c "$JAR" -H "$HEADER: $TOKEN" -H 'Content-Type: application/json' -d @- "$BASE/api/iam/auth/login" > "$JAR.code"
    LOGIN_CODE="$(cat "$JAR.code")"
    # O login troca a sessão e o token CSRF antigo deixa de valer: o frontend também busca um novo token depois de entrar.
    local fresh; fresh="$(cx -b "$JAR" -c "$JAR" "$BASE/api/iam/csrf")"
    HEADER="$(printf '%s' "$fresh" | json 'd["headerName"]')"; TOKEN="$(printf '%s' "$fresh" | json 'd["token"]')"
}
post() { # caminho, corpo json → código HTTP (corpo em $JAR.b)
    cx -o "$JAR.b" -w '%{http_code}' -b "$JAR" -c "$JAR" -H "$HEADER: $TOKEN" -H 'Content-Type: application/json' -d "$2" "$BASE$1"
}

echo "-- login e cookie de sessão"
login; code="$LOGIN_CODE"
if [ "$code" != 200 ]; then fail "POST /api/iam/auth/login → $code (credenciais do ambiente, ou o cookie/CSRF não atravessa o rewrite)"; echo; echo "Falhas: $FAILS"; exit 1; fi
ok "POST /api/iam/auth/login → 200"
cookie_line="$(grep -i '^set-cookie:' "$JAR.h" | grep -i 'SESSION=' | head -1)"
for flag in "Secure" "HttpOnly" "SameSite=Lax"; do
    printf '%s' "$cookie_line" | grep -qi "$flag" && ok "cookie SESSION com $flag" || fail "cookie SESSION sem $flag"
done
if [ "$(json 'str(d.get("mustChangePassword"))' < "$JAR.b")" = True ]; then
    note "senha provisória: o login funciona e a troca é OBRIGATÓRIA."
    [ "$(cx -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/customers")" = 403 ] && ok "antes da troca, as rotas de negócio respondem 403 (PASSWORD_CHANGE_REQUIRED)" || fail "rota de negócio não foi barrada antes da troca de senha"
    echo
    echo "PENDENTE: troque a senha pela tela do ERP ($BASE/login) e rode este script de novo com a senha nova."
    echo "Falhas até aqui: $FAILS"; [ "$FAILS" -eq 0 ]; exit $?
fi

echo "-- sessão, CSRF e persistência"
[ "$(cx -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/customers")" = 200 ] && ok "GET autenticado /api/customers → 200" || fail "GET autenticado não devolveu 200"
# "refresh": um processo novo do curl, só com o cookie gravado, é o que o navegador faz ao recarregar a página
[ "$(cx -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/iam/session")" = 200 ] && ok "a sessão persiste após o refresh (/api/iam/session → 200)" || fail "a sessão não persistiu"
nocsrf="$(cx -o /dev/null -w '%{http_code}' -b "$JAR" -H 'Content-Type: application/json' -d '{}' "$BASE/api/customers")"
[ "$nocsrf" = 403 ] && ok "POST sem token CSRF → 403" || fail "POST sem token CSRF → $nocsrf (esperado 403)"
bad="$(cx -o /dev/null -w '%{http_code}' -b "$JAR" -H "$HEADER: token-invalido" -H 'Content-Type: application/json' -d '{}' "$BASE/api/customers")"
[ "$bad" = 403 ] && ok "POST com token CSRF inválido → 403" || fail "POST com token CSRF inválido → $bad (esperado 403)"

if [ "$CREATE" -eq 1 ]; then
    echo "-- dados de teste identificáveis (ZZ TESTE CLOUD)"
    stamp="$(date +%Y%m%d-%H%M%S)"; suffix="$(printf '%02d' $((RANDOM % 100)))"
    plate="ZTC1Z${suffix}"
    c="$(post /api/customers "{\"personType\":\"PF\",\"name\":\"ZZ TESTE CLOUD $stamp\",\"phone\":\"34999990000\",\"document\":null}")"
    [ "$c" = 201 ] && ok "POST /api/customers (com CSRF válido) → 201" || fail "POST /api/customers → $c: $(head -c 160 "$JAR.b")"
    cid="$(json 'd["id"]' < "$JAR.b")"
    v="$(post /api/vehicles "{\"customerId\":\"$cid\",\"plate\":\"$plate\",\"manufacturer\":\"Teste\",\"model\":\"Cloud\",\"modelYear\":2020,\"mileage\":1000}")"
    [ "$v" = 201 ] && ok "POST /api/vehicles → 201 (placa $plate)" || fail "POST /api/vehicles → $v: $(head -c 160 "$JAR.b")"
    vid="$(json 'd["id"]' < "$JAR.b")"
    o="$(post /api/work-orders "{\"customerId\":\"$cid\",\"vehicleId\":\"$vid\",\"entryMileage\":1010,\"complaint\":\"ZZ TESTE CLOUD: pode ser ignorada\"}")"
    [ "$o" = 201 ] && ok "POST /api/work-orders → 201 (OS de teste criada)" || fail "POST /api/work-orders → $o: $(head -c 160 "$JAR.b")"
    oid="$(json 'd["id"]' < "$JAR.b")"
    [ "$(cx -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/work-orders/$oid")" = 200 ] && ok "GET da OS criada → 200" || fail "GET da OS criada falhou"
fi

echo "-- logout e novo login"
lo="$(post /api/iam/auth/logout '')"
[ "$lo" = 204 ] && ok "logout → 204" || fail "logout → $lo"
[ "$(cx -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/customers")" = 401 ] && ok "depois do logout a sessão não vale mais (401)" || fail "a sessão continuou válida depois do logout"
login; [ "$LOGIN_CODE" = 200 ] && ok "novo login → 200" || fail "novo login falhou ($LOGIN_CODE)"

echo
if [ "$FAILS" -eq 0 ]; then echo "ERP ONLINE E FUNCIONANDO (0 falhas)."; else echo "COM $FAILS FALHA(S)."; fi
[ "$FAILS" -eq 0 ]
