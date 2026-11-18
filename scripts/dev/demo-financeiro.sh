#!/usr/bin/env bash
# DEMONSTRAÇÃO LOCAL do Financeiro (TASK-0024) — banco descartável em Docker, NUNCA a produção.
#
#   bash scripts/dev/demo-financeiro.sh up      # PostgreSQL local (porta 55432) + backend (8080), espera o health
#   bash scripts/dev/demo-financeiro.sh seed    # cria pela API: OS faturadas, recebimentos, ajuste, despesas, caixa
#   bash scripts/dev/demo-financeiro.sh seed-b  # etapa B: parcelas, avulso, juros e multa, cartão, aprovação, conciliação
#   bash scripts/dev/demo-financeiro.sh check-b # confere pela API os valores da etapa B (sai com erro se algo divergir)
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


get() { curl -s -b $J "$API$1"; }
py() { python -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

# Etapa B (DR-0024), tudo pela API, no banco local descartável.
seed_b() {
  [ "$(login "$PASS")" = 200 ] || { echo "rode antes: seed"; exit 1; }
  local pix card rc mc os r rec p1 g acc admin
  pix=$(get /api/finance/payment-methods | py "[m['id'] for m in d if m['code']=='PIX'][0]")
  card=$(get /api/finance/payment-methods | py "[m['id'] for m in d if m['code']=='CARTAO_CREDITO'][0]")
  call PUT /api/finance/settings '{"payableApprovalThreshold":"1000.00"}' >/dev/null
  # B4: cartão de crédito com 1x a 3,50% e 3x a 5,99%, repasse em 30 dias.
  call PUT /api/finance/payment-methods/$card/fees '{"fees":[{"installments":1,"feePercent":"3.50","settlementDays":30},{"installments":3,"feePercent":"5.99","settlementDays":30}]}' >/dev/null
  # B1: OS de R$ 1.200,00 em 3 parcelas; a primeira recebida no cartão em 3x.
  r=$(billed "Oficina Parceira" DEM4D44 "Retífica de bomba" 1200.00)
  call PUT /api/finance/receivables/$r/installments "{\"reason\":\"Negociado em 3x\",\"installments\":[{\"amount\":\"400.00\",\"dueDate\":\"$(date +%F)\"},{\"amount\":\"400.00\",\"dueDate\":\"$(date -d '+30 day' +%F)\"},{\"amount\":\"400.00\",\"dueDate\":\"$(date -d '+60 day' +%F)\"}]}" >/dev/null
  rec=$(call POST /api/finance/receivables/$r/receipts "{\"amount\":\"400.00\",\"paymentMethodId\":\"$card\",\"installments\":3}" | id)
  # B3: recebível vencido há 15 dias para a sugestão de juros e multa.
  os=$(billed "Cliente Atrasado" DEM5E55 "Troca de válvula" 600.00)
  docker exec $PG psql -U postgres -d erp -qc "update finance.receivable set due_date = current_date - 15 where id = '$os'"
  # B2: venda de balcão avulsa em 2x.
  rc=$(call POST /api/finance/revenue-categories '{"name":"Venda de balcão"}' | id)
  mc=$(call POST /api/customers '{"personType":"PF","name":"Cliente Balcão","phone":"34999990000"}' | id)
  call POST /api/finance/receivables "{\"customerId\":\"$mc\",\"description\":\"Venda de mangueiras\",\"revenueCategoryId\":\"$rc\",\"amount\":\"250.01\",\"dueDate\":\"$(date +%F)\",\"installments\":2}" >/dev/null
  # B5 e B6: fornecedor do cadastro, despesa parcelada (aprovação pelo total) e conta acima do limite.
  local sup cat
  sup=$(call POST /api/purchasing/suppliers '{"personType":"PJ","legalName":"Máquinas Sul Ltda","tradeName":"Máquinas Sul","document":"11222333000181"}' | id)
  cat=$(call POST /api/finance/expense-categories '{"name":"Equipamentos"}' | id)
  g=$(call POST /api/finance/payables/installments "{\"description\":\"Elevador hidráulico\",\"supplierId\":\"$sup\",\"categoryId\":\"$cat\",\"totalAmount\":\"3000.00\",\"installments\":3,\"firstDueDate\":\"$(date +%F)\"}" | id)
  echo "$g" > target/demo-group.id
  # B7: conta bancária e conciliação do PIX do seed (R$ 1.000,00).
  acc=$(call POST /api/finance/accounts '{"name":"Banco PJ","type":"BANK","bankName":"Banco da oficina"}' | id)
  local pixReceipt
  pixReceipt=$(get "/api/finance/reconciliation/entries?from=$(date +%F)&to=$(date +%F)&type=RECEIPT" | py "[e['id'] for e in d if e['paymentMethodName']=='PIX'][0]")
  call POST /api/finance/reconciliations "{\"accountId\":\"$acc\",\"entryType\":\"RECEIPT\",\"entryId\":\"$pixReceipt\",\"statementDate\":\"$(date +%F)\",\"statementAmount\":\"1000.00\",\"statementReference\":\"EXT-0001\"}" >/dev/null
  echo "$os" > target/demo-late.id
  echo "etapa B criada (somente no banco local)"
}

# Confere pela API os números da etapa B; cada linha é "OK" ou "DIVERGE".
check_b() {
  [ "$(login "$PASS")" = 200 ] || { echo "login falhou"; exit 1; }
  local fail=0 late g today; late=$(cat target/demo-late.id); g=$(cat target/demo-group.id); today=$(date +%F)
  expect() { if [ "$2" = "$3" ]; then echo "OK      $1 = $2"; else echo "DIVERGE $1: esperado $3, veio $2"; fail=1; fi; }
  expect "juros+multa sugeridos (600 x 2% + 600 x 1%/30 x 15)" "$(get /api/finance/receivables/$late/late-charge | py "d['total']")" "15.0"
  expect "grupo de 3 parcelas pendente de aprovação" "$(get /api/finance/payable-groups/$g | py "sum(1 for p in d['payables'] if p['approvalStatus']=='PENDING')")" "3"
  expect "taxas de cartão no demonstrativo (400 x 5,99%)" "$(get "/api/finance/reports/income-statement?from=$today&to=$today" | py "d['financialExpenses']")" "23.96"
  expect "outras receitas (avulsa)" "$(get "/api/finance/reports/income-statement?from=$today&to=$today" | py "d['otherRevenue']")" "250.01"
  local dash ist
  dash=$(get "/api/finance/dashboard?from=$today&to=$today" | py "d['operatingResult']")
  ist=$(get "/api/finance/reports/income-statement?from=$today&to=$today" | py "d['operatingResult']")
  expect "painel e demonstrativo com o mesmo resultado operacional" "$dash" "$ist"
  expect "conciliação do PIX" "$(get "/api/finance/reconciliation/entries?from=$today&to=$today&reconciled=true" | py "len(d)")" "1"
  return $fail
}

down() { [ -f target/demo-backend.pid ] && kill "$(cat target/demo-backend.pid)" 2>/dev/null || true; docker rm -f -v $PG >/dev/null 2>&1 || true; echo "demo removida"; }

case "${1:-up}" in seed-b) seed_b ;; check-b) check_b ;; *) "${1:-up}" ;; esac
