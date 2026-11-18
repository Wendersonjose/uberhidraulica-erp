#!/usr/bin/env bash
# DEMONSTRAÇÃO LOCAL do Financeiro (TASK-0024) — banco descartável em Docker, NUNCA a produção.
#
#   bash scripts/dev/demo-financeiro.sh up      # PostgreSQL local (porta 55432) + backend (8080), espera o health
#   bash scripts/dev/demo-financeiro.sh seed    # cria pela API: OS faturadas, recebimentos, ajuste, despesas, caixa
#   bash scripts/dev/demo-financeiro.sh down    # apaga o container e o banco local
# Depois: cd frontend && npm run dev  (http://localhost:5173) e entre com as credenciais LOCAIS abaixo.
#
# Credenciais de DEMONSTRAÇÃO, válidas só neste banco local descartável:
#   e-mail: dono.demo@oficina.test   senha: Demo-Financeiro-2026
set -euo pipefail
cd "$(dirname "$0")/../.."
PG=erp-demo-pg; PORT=55432; API=http://localhost:8080
EMAIL=dono.demo@oficina.test; BOOT='Bootstrap-Demo-2026x'; PASS='Demo-Financeiro-2026'
JAR=$(ls target/uberhidraulica-erp-*.jar 2>/dev/null | grep -v plain | head -1 || true)

up() {
  docker rm -f -v $PG >/dev/null 2>&1 || true
  docker run -d --name $PG -p 127.0.0.1:$PORT:5432 -e POSTGRES_PASSWORD=demo-local -e POSTGRES_DB=erp postgres:17-alpine >/dev/null
  for _ in $(seq 1 60); do docker exec $PG pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done; sleep 2
  [ -n "$JAR" ] || { mvn -q -o package -DskipTests; JAR=$(ls target/uberhidraulica-erp-*.jar | grep -v plain | head -1); }
  SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$PORT/erp" SPRING_DATASOURCE_USERNAME=postgres SPRING_DATASOURCE_PASSWORD=demo-local \
  IAM_BOOTSTRAP_OWNER_NAME="Dono Demonstração" IAM_BOOTSTRAP_OWNER_EMAIL=$EMAIL IAM_BOOTSTRAP_OWNER_PASSWORD="$BOOT" \
    nohup java -jar "$JAR" > target/demo-backend.log 2>&1 &
  echo $! > target/demo-backend.pid
  for _ in $(seq 1 120); do curl -fs $API/actuator/health 2>/dev/null | grep -q UP && { echo "backend no ar"; return; }; sleep 2; done
  echo "backend não subiu (veja target/demo-backend.log)"; exit 1
}

J=target/demo-cookies.txt
HDR=X-CSRF-TOKEN   # padrão do Spring (HttpSessionCsrfTokenRepository); confirmado por GET /api/iam/csrf
csrf() { curl -s -c $J -b $J $API/api/iam/csrf | sed -E 's/.*"token":"([^"]+)".*/\1/'; }
call() {  # corpo via stdin: argumento com acento chega corrompido no curl do Windows
  local m=$1 p=$2 b=${3:-} t; t=$(csrf)
  local args=(-s -c $J -b $J -X "$m" "$API$p" -H "$HDR: $t" -H "Idempotency-Key: demo-$RANDOM-$RANDOM")
  if [ -n "$b" ]; then curl "${args[@]}" -H 'Content-Type: application/json' --data-binary @- <<< "$b"; else curl "${args[@]}"; fi; }
id() { sed -E 's/^\{"id":"([^"]+)".*/\1/'; }
login() { rm -f $J; local t; t=$(csrf); curl -s -o /dev/null -w '%{http_code}' -c $J -b $J -X POST $API/api/iam/auth/login -H "$HDR: $t" \
  -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$1\"}"; }

billed() {  # $1 cliente  $2 placa  $3 descrição  $4 preço
  local c v o q r rev item
  c=$(call POST /api/customers "{\"personType\":\"PF\",\"name\":\"$1\",\"phone\":\"34999990000\"}" | id)
  v=$(call POST /api/vehicles "{\"customerId\":\"$c\",\"plate\":\"${2:0:5}$(printf %02d $((RANDOM % 100)))\",\"manufacturer\":\"Volvo\",\"model\":\"FH 540\"}" | id)
  o=$(call POST /api/work-orders "{\"customerId\":\"$c\",\"vehicleId\":\"$v\",\"complaint\":\"Revisão do sistema hidráulico\"}" | id)
  q=$(call POST /api/work-orders/$o/quotes | id)
  rev=$(call POST /api/work-orders/$o/quotes/$q/revisions "{\"items\":[{\"description\":\"$3\",\"quantity\":\"1\",\"unitPrice\":\"$4\"}]}"         | python -c "import json,sys; print(json.load(sys.stdin)['revisions'][-1]['id'])")
  call POST /api/work-orders/$o/quotes/$q/revisions/$rev/present >/dev/null
  item=$(curl -s -b $J $API/api/work-orders/$o/quotes/$q | python -c "import json,sys; print(json.load(sys.stdin)['items'][0]['revisions'][-1]['id'])")
  call POST /api/work-orders/$o/quotes/$q/revisions/$rev/decisions \
    "{\"contactChannel\":\"PRESENCIAL\",\"authorizedBy\":\"Cliente\",\"decisions\":[{\"itemReference\":\"$item\",\"decision\":\"APPROVE\"}]}" >/dev/null
  call POST /api/work-orders/$o/start-execution >/dev/null
  call POST /api/work-orders/$o/finish >/dev/null
  curl -s -b $J $API/api/finance/work-orders/$o/receivable | id
}

seed() {
  if [ "$(login "$PASS")" != 200 ]; then
    [ "$(login "$BOOT")" = 200 ] || { echo "login falhou"; exit 1; }
    call POST /api/iam/password/change "{\"currentPassword\":\"$BOOT\",\"newPassword\":\"$PASS\"}" >/dev/null
    [ "$(login "$PASS")" = 200 ] || { echo "login com a senha nova falhou"; exit 1; }
  fi
  pix=$(curl -s -b $J $API/api/finance/payment-methods | grep -oE '\{"id":"[^"]+","code":"PIX"' | cut -d'"' -f4)
  cash=$(curl -s -b $J $API/api/finance/payment-methods | grep -oE '\{"id":"[^"]+","code":"DINHEIRO"' | cut -d'"' -f4)
  call POST /api/finance/cash/sessions '{"countedBalance":"200.00","differenceReason":"Fundo de troco inicial"}' >/dev/null
  r1=$(billed "Transportes Silva" DEM1A11 "Troca de mangueiras hidráulicas" 1850.00)
  r2=$(billed "Agro Minas Ltda" DEM2B22 "Revisão de cilindro" 920.00)
  r3=$(billed "Carlos Pereira" DEM3C33 "Regulagem de válvula" 380.00)
  call POST /api/finance/receivables/$r1/adjustments '{"type":"DISCOUNT","amount":"50.00","reason":"Cliente frequente"}' >/dev/null
  call POST /api/finance/receivables/$r1/receipts "{\"amount\":\"1000.00\",\"paymentMethodId\":\"$pix\"}" >/dev/null
  call POST /api/finance/receivables/$r3/receipts "{\"amount\":\"380.00\",\"paymentMethodId\":\"$cash\",\"cashTendered\":\"400.00\"}" >/dev/null
  docker exec $PG psql -U postgres -d erp -qc "update finance.receivable set due_date = current_date - 45 where id = '$r2'"
  rent=$(call POST /api/finance/expense-categories '{"name":"Aluguel"}' | id)
  power=$(call POST /api/finance/expense-categories '{"name":"Energia elétrica"}' | id)
  parts=$(call POST /api/finance/expense-categories '{"name":"Ferramentas e insumos"}' | id)
  p1=$(call POST /api/finance/payables "{\"description\":\"Aluguel do galpão\",\"categoryId\":\"$rent\",\"amount\":\"2500.00\",\"dueDate\":\"$(date +%F)\"}" | id)
  call POST /api/finance/payables/$p1/payments "{\"amount\":\"2500.00\",\"paymentMethodId\":\"$pix\"}" >/dev/null
  call POST /api/finance/payables "{\"description\":\"Conta de luz\",\"supplier\":\"Cemig\",\"categoryId\":\"$power\",\"amount\":\"640.35\",\"dueDate\":\"$(date -d '+5 day' +%F)\"}" >/dev/null
  call POST /api/finance/payables "{\"description\":\"Kit de vedação\",\"supplier\":\"Hidro Peças\",\"categoryId\":\"$parts\",\"amount\":\"310.00\",\"dueDate\":\"$(date -d '+20 day' +%F)\"}" >/dev/null
  call POST /api/finance/cash/movements/withdrawal '{"amount":"100.00","reason":"Sangria para depósito"}' >/dev/null
  echo "dados de demonstração criados (somente no banco local)"
}

down() { [ -f target/demo-backend.pid ] && kill "$(cat target/demo-backend.pid)" 2>/dev/null || true; docker rm -f -v $PG >/dev/null 2>&1 || true; echo "demo removida"; }

"${1:-up}"
