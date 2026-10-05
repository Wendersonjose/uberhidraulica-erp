#!/usr/bin/env bash
# Prova, com requisições de verdade, que o nginx do ERP só confia em X-Forwarded-For / X-Forwarded-Proto vindos
# dos proxies listados em TRUSTED_PROXY_CIDRS — e que um cliente comum NÃO consegue forjar o IP gravado como
# evidência do orçamento público nem escapar do limite de requisições girando o cabeçalho.
#
# Uso: scripts/deploy/proxy-trust-check.sh <imagem-do-frontend>
#   (a imagem construída por `docker compose build frontend`; veja o job deploy-smoke do CI)
#
# Monta uma rede Docker isolada com endereços fixos:
#   .10  nginx do ERP (a imagem sob teste)      .50  "balanceador" (proxy confiável nos cenários B e D1)
#   .51  cliente comum (nunca confiável)        backend: um nginx que devolve em JSON os cabeçalhos recebidos
# Requer docker. Não toca no compose do piloto nem em dados.
set -uo pipefail

IMAGE="${1:-${FRONTEND_IMAGE:-}}"
[ -n "$IMAGE" ] || { echo "uso: $0 <imagem-do-frontend>" >&2; exit 2; }
PREFIX="${PROXYTRUST_PREFIX:-172.29.88}"
NET="erp-proxytrust-$$"
ERP="$NET-erp"
STUB="$NET-backend"
LB_IP="$PREFIX.50"
OUTSIDER_IP="$PREFIX.51"
WORK="$(mktemp -d)"
pass=0; fail=0

cleanup() {
    docker rm -f "$ERP" "$STUB" >/dev/null 2>&1
    docker network rm "$NET" >/dev/null 2>&1
    rm -rf "$WORK"
}
trap cleanup EXIT

check() { # descrição, obtido, esperado
    if [ "$2" = "$3" ]; then pass=$((pass + 1)); echo "  ok   $1"
    else fail=$((fail + 1)); echo "  FAIL $1 (obtido '$2', esperado '$3')"; fi
}
check_contains() { # descrição, texto, trecho
    if printf '%s' "$2" | grep -qF -- "$3"; then pass=$((pass + 1)); echo "  ok   $1"
    else fail=$((fail + 1)); echo "  FAIL $1 (não contém '$3'): $2"; fi
}

docker network create --subnet "$PREFIX.0/24" "$NET" >/dev/null || { echo "não criou a rede $PREFIX.0/24 (PROXYTRUST_PREFIX)"; exit 2; }

cat > "$WORK/stub.conf" <<'EOF'
server {
    listen 8080;
    default_type application/json;
    location / {
        return 200 '{"xff":"$http_x_forwarded_for","xri":"$http_x_real_ip","xfp":"$http_x_forwarded_proto"}';
    }
}
EOF
docker run -d --name "$STUB" --network "$NET" --network-alias backend \
    -v "$WORK/stub.conf:/etc/nginx/conf.d/default.conf:ro" --entrypoint nginx "$IMAGE" -g 'daemon off;' >/dev/null

from() { # ip_de_origem, args do curl...  (um cliente com endereço fixo na rede de teste)
    local ip="$1"; shift
    docker run --rm --network "$NET" --ip "$ip" --entrypoint curl "$IMAGE" -s -m 5 "$@"
}
field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

start_erp() { # TRUSTED_PROXY_CIDRS
    docker rm -f "$ERP" >/dev/null 2>&1
    docker run -d --name "$ERP" --network "$NET" --ip "$PREFIX.10" -e TRUSTED_PROXY_CIDRS="$1" "$IMAGE" >/dev/null
    for _ in $(seq 1 30); do
        [ "$(from "$OUTSIDER_IP" -o /dev/null -w '%{http_code}' "http://$ERP/")" = 200 ] && return 0
        sleep 1
    done
    echo "  FAIL nginx do ERP não subiu com TRUSTED_PROXY_CIDRS='$1'"; docker logs "$ERP" 2>&1 | tail -5
    fail=$((fail + 1)); return 1
}
probe() { # ip_de_origem, args extras do curl... → "xff|xri|xfp" que o backend recebeu
    local ip="$1"; shift
    local body; body="$(from "$ip" "$@" "http://$ERP/api/probe")"
    echo "$(printf '%s' "$body" | field xff)|$(printf '%s' "$body" | field xri)|$(printf '%s' "$body" | field xfp)"
}

echo "== A. sem proxy confiável (TRUSTED_PROXY_CIDRS vazio): o cliente não consegue forjar nada"
start_erp ""
check "A1 XFF e Proto forjados são ignorados" \
    "$(probe "$OUTSIDER_IP" -H 'X-Forwarded-For: 6.6.6.6' -H 'X-Forwarded-Proto: https')" "$OUTSIDER_IP|$OUTSIDER_IP|http"
check "A2 sem cabeçalhos vale o IP da conexão" "$(probe "$OUTSIDER_IP")" "$OUTSIDER_IP|$OUTSIDER_IP|http"
check "A3 X-Real-IP forjado não passa" \
    "$(probe "$OUTSIDER_IP" -H 'X-Real-IP: 6.6.6.6')" "$OUTSIDER_IP|$OUTSIDER_IP|http"
check "A4 mesmo um 'balanceador' sem estar listado é tratado como cliente comum" \
    "$(probe "$LB_IP" -H 'X-Forwarded-For: 203.0.113.7' -H 'X-Forwarded-Proto: https')" "$LB_IP|$LB_IP|http"

echo "== B. com proxy confiável ($LB_IP): só ele informa o IP real e o esquema"
start_erp "$LB_IP/32"
check "B1 o balanceador anexa o IP real; o valor forjado à esquerda é descartado" \
    "$(probe "$LB_IP" -H 'X-Forwarded-For: 6.6.6.6, 203.0.113.7' -H 'X-Forwarded-Proto: https')" "203.0.113.7|203.0.113.7|https"
check "B2 o balanceador informa só o IP real" \
    "$(probe "$LB_IP" -H 'X-Forwarded-For: 203.0.113.7' -H 'X-Forwarded-Proto: https')" "203.0.113.7|203.0.113.7|https"
check "B3 sem X-Forwarded-For vale o IP do balanceador" "$(probe "$LB_IP")" "$LB_IP|$LB_IP|http"
check "B4 recursivo: endereços confiáveis à direita são pulados" \
    "$(probe "$LB_IP" -H "X-Forwarded-For: 203.0.113.7, $LB_IP")" "203.0.113.7|203.0.113.7|http"
check "B5 cliente comum com XFF e Proto forjados continua sem poder nada" \
    "$(probe "$OUTSIDER_IP" -H 'X-Forwarded-For: 6.6.6.6' -H 'X-Forwarded-Proto: https')" "$OUTSIDER_IP|$OUTSIDER_IP|http"
check "B6 cliente comum alegando o IP do balanceador no XFF também não passa" \
    "$(probe "$OUTSIDER_IP" -H "X-Forwarded-For: $LB_IP")" "$OUTSIDER_IP|$OUTSIDER_IP|http"
check "B7 Proto 'http' do balanceador é respeitado" \
    "$(probe "$LB_IP" -H 'X-Forwarded-For: 203.0.113.7' -H 'X-Forwarded-Proto: http')" "203.0.113.7|203.0.113.7|http"
check "B8 Proto inválido do balanceador cai no esquema da conexão" \
    "$(probe "$LB_IP" -H 'X-Forwarded-For: 203.0.113.7' -H 'X-Forwarded-Proto: gopher')" "203.0.113.7|203.0.113.7|http"

echo "== C. falha fechada: TRUSTED_PROXY_CIDRS que aceitaria qualquer origem, ou inválido, impede a subida"
for bad in "0.0.0.0/0" "0.0.0.0" "::/0" "::" "10.0.0.0/7" "128.0.0.0/1" "abc" "10.0.0.0/" "10.0.0.0/33" "300.1.1.1" \
           "2001:db8::/8" "10.0.0.0/8,0.0.0.0/0" "10.0.0.0/x"; do
    docker rm -f "$ERP" >/dev/null 2>&1
    docker run -d --name "$ERP" --network "$NET" -e TRUSTED_PROXY_CIDRS="$bad" "$IMAGE" >/dev/null
    code="$(timeout 20 docker wait "$ERP" 2>/dev/null || echo running)"
    logs="$(docker logs "$ERP" 2>&1)"
    if [ "$code" != "running" ] && [ "$code" != 0 ]; then
        check_contains "C recusado: '$bad'" "$logs" "TRUSTED_PROXY_CIDRS inválido"
    else
        fail=$((fail + 1)); echo "  FAIL C '$bad' foi ACEITO (container ${code:-?})"
    fi
done
for good in "10.0.0.0/8" "172.16.0.0/12,192.168.0.0/16" "$LB_IP" "fd00::/16" "10.1.2.3/32, 10.1.2.4/32"; do
    docker rm -f "$ERP" >/dev/null 2>&1
    docker run -d --name "$ERP" --network "$NET" -e TRUSTED_PROXY_CIDRS="$good" "$IMAGE" >/dev/null
    ok=0
    for _ in $(seq 1 20); do
        docker logs "$ERP" 2>&1 | grep -q "start worker processes" && { ok=1; break; }
        [ "$(docker inspect -f '{{.State.Running}}' "$ERP")" = true ] || break
        sleep 1
    done
    check "C aceito: '$good'" "$ok" 1
done

echo "== D. o limite de requisições segue o IP real, e girar o cabeçalho não escapa dele"
start_erp "$LB_IP/32"
limited=0
for _ in $(seq 1 30); do
    code="$(from "$LB_IP" -o /dev/null -w '%{http_code}' -X POST -H 'X-Forwarded-For: 203.0.113.7' "http://$ERP/api/iam/auth/login")"
    [ "$code" = 429 ] && limited=$((limited + 1))
done
check "D1 o cliente real 203.0.113.7 (via balanceador) estoura o limite de login" "$([ "$limited" -gt 0 ] && echo sim || echo não)" sim
check "D1 outro cliente real (203.0.113.8) tem o próprio limite" \
    "$(from "$LB_IP" -o /dev/null -w '%{http_code}' -X POST -H 'X-Forwarded-For: 203.0.113.8' "http://$ERP/api/iam/auth/login")" 200
start_erp "$LB_IP/32"
limited=0
for i in $(seq 1 30); do
    code="$(from "$OUTSIDER_IP" -o /dev/null -w '%{http_code}' -X POST -H "X-Forwarded-For: 198.51.100.$i" "http://$ERP/api/iam/auth/login")"
    [ "$code" = 429 ] && limited=$((limited + 1))
done
check "D2 cliente comum girando X-Forwarded-For continua limitado como um só" "$([ "$limited" -gt 0 ] && echo sim || echo não)" sim

echo "== E. o log de acesso não grava o token do link público"
start_erp ""
SECRET_PAGE="tokPAGINA$(head -c 12 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-16)"
SECRET_API="tokAPI$(head -c 12 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-16)"
from "$OUTSIDER_IP" -o /dev/null "http://$ERP/orcamento/$SECRET_PAGE"
from "$OUTSIDER_IP" -o /dev/null -X POST -H "Referer: http://$ERP/orcamento/$SECRET_PAGE" "http://$ERP/api/public/quotes/$SECRET_API/decision?x=1"
sleep 1
access_log="$(docker logs "$ERP" 2>&1)"
check "E1 o token da página não aparece no log" "$(printf '%s' "$access_log" | grep -c "$SECRET_PAGE")" 0
check "E2 o token da API não aparece no log" "$(printf '%s' "$access_log" | grep -c "$SECRET_API")" 0
check_contains "E3 a rota continua registrada, com o token mascarado" "$access_log" 'POST /api/public/quotes/[token]/decision?x=1'
check_contains "E4 a página também, e o Referer mascarado" "$access_log" 'GET /orcamento/[token]'

echo
echo "proxy-trust-check: $pass ok, $fail falhas"
[ "$fail" -eq 0 ]
