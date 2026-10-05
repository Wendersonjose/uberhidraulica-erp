#!/usr/bin/env python3
"""Fluxo E2E da oficina por HTTP real, do login do Dono ao painel financeiro.

Roda contra qualquer ambiente já no ar (backend direto, ou o frontend/nginx do docker compose):

    BASE_URL=http://localhost:8080 \\
    E2E_OWNER_EMAIL=dono@example.test \\
    E2E_OWNER_BOOTSTRAP_PASSWORD=<senha do bootstrap> \\
    python3 scripts/e2e/workshop_flow.py

Não grava segredo nenhum: a senha do bootstrap vem do ambiente e as senhas novas são geradas a cada
execução. Cada verificação é independente — uma falha não esconde as seguintes — e o código de saída é
diferente de zero se qualquer uma falhar. Só usa a biblioteca padrão do Python 3.

Premissas: banco recém-criado (ou ao menos um Dono cuja senha de bootstrap ainda seja a informada, ou
cuja senha atual seja E2E_OWNER_PASSWORD) e perfil sem cookie `Secure` quando o BASE_URL for HTTP puro.
"""
import http.cookiejar
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import date, timedelta
from decimal import Decimal

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
OWNER_EMAIL = os.environ.get("E2E_OWNER_EMAIL", "")
OWNER_BOOTSTRAP_PASSWORD = os.environ.get("E2E_OWNER_BOOTSTRAP_PASSWORD", "")
OWNER_PASSWORD = os.environ.get("E2E_OWNER_PASSWORD") or ("Dono-" + secrets.token_urlsafe(18))
RUN = uuid.uuid4().hex[:8]
# Opcional: simula o cabeçalho que um proxy/cliente envia ao link público (evidência de IP, TASK-0008/F-08-02).
PUBLIC_XFF = os.environ.get("E2E_PUBLIC_XFF", "")

results = []


def check(name, condition, detail=""):
    results.append((name, bool(condition), detail))
    print(("  [OK]   " if condition else "  [FAIL] ") + name + ("" if condition else f"  -> {detail}"))
    return bool(condition)


def note(text):
    """Observação informativa (lacuna conhecida, aguardando decisão de produto); não conta como falha."""
    print("  [NOTA] " + text)


def section(title):
    print(f"\n== {title}")


class Resp:
    def __init__(self, status, headers, raw):
        self.status, self.headers, self.raw = status, headers, raw
        try:
            self.json = json.loads(raw) if raw else None
        except ValueError:
            self.json = None

    @property
    def code(self):
        return self.json.get("code") if isinstance(self.json, dict) else None


class Client:
    """Sessão de navegador: cookie jar real e CSRF obtido do próprio backend."""

    def __init__(self, label):
        self.label = label
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf_header = None
        self.csrf_token = None
        self.extra_headers = {}

    def request(self, method, path, body=None, headers=None, csrf=True):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(BASE_URL + path, data=data, method=method)
        if body is not None:
            request.add_header("Content-Type", "application/json")
        if csrf and method not in ("GET", "HEAD") and self.csrf_token:
            request.add_header(self.csrf_header, self.csrf_token)
        for key, value in {**self.extra_headers, **(headers or {})}.items():
            request.add_header(key, value)
        # O nginx do piloto limita /api/public/ e o login por IP e responde 429 ANTES de o pedido chegar ao
        # backend; repetir é seguro e é o que um cliente educado faria. Contra o backend direto nunca ocorre.
        for attempt in range(12):
            try:
                with self.opener.open(request, timeout=30) as response:
                    return Resp(response.status, response.headers, response.read().decode("utf-8", "replace"))
            except urllib.error.HTTPError as error:
                result = Resp(error.code, error.headers, error.read().decode("utf-8", "replace"))
                if error.code != 429 or attempt == 11:
                    return result
                time.sleep(float(error.headers.get("Retry-After") or 7))

    def get(self, path, **kw):
        return self.request("GET", path, **kw)

    def post(self, path, body=None, **kw):
        return self.request("POST", path, {} if body is None else body, **kw)

    def put(self, path, body=None, **kw):
        return self.request("PUT", path, {} if body is None else body, **kw)

    def refresh_csrf(self):
        response = self.get("/api/iam/csrf")
        self.csrf_header, self.csrf_token = response.json["headerName"], response.json["token"]

    def login(self, email, password):
        self.refresh_csrf()
        response = self.post("/api/iam/auth/login", {"email": email, "password": password})
        if response.status == 200:
            self.refresh_csrf()
        return response

    def change_password(self, current, new):
        return self.post("/api/iam/password/change", {"currentPassword": current, "newPassword": new})


def money(value):
    return Decimal(str(value)).quantize(Decimal("0.01"))


def main():
    if not OWNER_EMAIL or not OWNER_BOOTSTRAP_PASSWORD:
        sys.exit("Defina E2E_OWNER_EMAIL e E2E_OWNER_BOOTSTRAP_PASSWORD (ver o cabeçalho deste arquivo).")

    # ------------------------------------------------------------------ infraestrutura
    section("Saúde e superfície pública")
    anonymous = Client("anonimo")
    health = anonymous.get("/actuator/health")
    check("actuator/health responde UP", health.status == 200 and health.json and health.json.get("status") == "UP", health.raw)
    check("health não vaza detalhes", health.json is not None and "components" not in health.json, health.raw)
    check("rota interna sem sessão responde 401", anonymous.get("/api/customers").status == 401)
    env = anonymous.get("/actuator/env")
    check("actuator/env não expõe propriedades", "propertySources" not in env.raw and env.status in (200, 401, 403, 404),
          f"{env.status} {env.raw[:120]}")

    # ------------------------------------------------------------------ Dono: primeiro login e senha
    section("Dono: bootstrap, primeiro login e troca obrigatória de senha")
    owner = Client("dono")
    first = owner.login(OWNER_EMAIL, OWNER_PASSWORD)
    if first.status != 200:
        first = owner.login(OWNER_EMAIL, OWNER_BOOTSTRAP_PASSWORD)
        check("login com a senha de bootstrap", first.status == 200, f"{first.status} {first.raw}")
        check("primeiro login exige troca de senha", first.json and first.json.get("mustChangePassword") is True)
        blocked = owner.get("/api/customers")
        check("antes da troca, rota de negócio é barrada (403 PASSWORD_CHANGE_REQUIRED)",
              blocked.status == 403 and blocked.code == "PASSWORD_CHANGE_REQUIRED", f"{blocked.status} {blocked.raw}")
        changed = owner.change_password(OWNER_BOOTSTRAP_PASSWORD, OWNER_PASSWORD)
        check("troca de senha do Dono", changed.status == 204, f"{changed.status} {changed.raw}")
        owner = Client("dono")
        first = owner.login(OWNER_EMAIL, OWNER_PASSWORD)
    check("login do Dono com a nova senha", first.status == 200 and first.json["mustChangePassword"] is False,
          f"{first.status} {first.raw}")
    check("Dono tem perfil DONO", first.json and first.json.get("profileCode") == "DONO")
    wrong = Client("errado")
    check("senha errada responde 401 genérico",
          wrong.login(OWNER_EMAIL, "senha-errada-" + RUN).status == 401)
    unknown = Client("desconhecido").login("ninguem-" + RUN + "@example.test", "x" * 12)
    check("e-mail inexistente responde igual à senha errada (sem enumeração)",
          unknown.status == 401 and unknown.code == "AUTHENTICATION_FAILED", f"{unknown.status} {unknown.raw}")

    section("CSRF")
    no_csrf = owner.request("POST", "/api/customers", {"personType": "PF", "name": "Sem CSRF", "phone": "34999990000"}, csrf=False)
    check("POST autenticado sem token CSRF é recusado (403)", no_csrf.status == 403, f"{no_csrf.status} {no_csrf.raw}")
    bad_csrf = owner.request("POST", "/api/customers", {"personType": "PF", "name": "CSRF ruim", "phone": "34999990000"},
                             headers={owner.csrf_header: "token-invalido"})
    check("POST autenticado com CSRF inválido é recusado (403)", bad_csrf.status == 403, f"{bad_csrf.status} {bad_csrf.raw}")

    # ------------------------------------------------------------------ usuários e permissões
    section("Administração de usuários e permissões")
    users = {}
    for profile, key in (("GERENTE_ADMINISTRATIVO", "adm"), ("GERENTE_FINANCEIRO", "fin")):
        email = f"{key}-{RUN}@example.test"
        created = owner.post("/api/iam/users", {"name": f"Gerente {key} {RUN}", "email": email, "profileCode": profile})
        ok = check(f"Dono cria usuário {profile}", created.status == 201 and created.json.get("temporaryPassword"),
                   f"{created.status} {created.raw}")
        if not ok:
            continue
        temporary = created.json["temporaryPassword"]
        check(f"{profile}: senha temporária não volta na listagem",
              temporary not in owner.get("/api/iam/users?size=100").raw)
        client = Client(key)
        login = client.login(email, temporary)
        check(f"{profile}: primeiro login com a senha temporária exige troca",
              login.status == 200 and login.json["mustChangePassword"] is True, f"{login.status} {login.raw}")
        new_password = "Gerente-" + secrets.token_urlsafe(16)
        check(f"{profile}: troca de senha", client.change_password(temporary, new_password).status == 204)
        client = Client(key)
        check(f"{profile}: login definitivo", client.login(email, new_password).status == 200)
        users[key] = client

    adm, fin = users.get("adm"), users.get("fin")
    if adm:
        check("Gerente Administrativo não administra usuários (403)", adm.get("/api/iam/users").status == 403)
        denied_create = adm.post("/api/iam/users", {"name": "Intruso", "email": f"intruso-{RUN}@example.test",
                                                    "profileCode": "DONO"})
        check("Gerente Administrativo não cria Dono (403)", denied_create.status == 403, f"{denied_create.status} {denied_create.raw}")
    if fin:
        check("Gerente Financeiro não abre OS (403, WORKORDER_MANAGE)",
              fin.post("/api/work-orders", {"customerId": str(uuid.uuid4()), "vehicleId": str(uuid.uuid4()),
                                            "complaint": "x"}).status == 403)
        check("Gerente Financeiro não cadastra cliente (403, CRM_MANAGE)",
              fin.post("/api/customers", {"personType": "PF", "name": "x", "phone": "1"}).status == 403)

    # ------------------------------------------------------------------ cadastros
    section("Cadastros: cliente, veículo, serviço, produto")
    plate = "E2E" + RUN[:4].upper()
    customer = owner.post("/api/customers", {"personType": "PF", "name": f"Cliente E2E {RUN}", "phone": "34999990000",
                                             "document": None, "email": f"cliente-{RUN}@example.test"})
    check("cadastra cliente", customer.status == 201, f"{customer.status} {customer.raw}")
    customer_id = customer.json["id"]
    vehicle = owner.post("/api/vehicles", {"customerId": customer_id, "plate": plate, "manufacturer": "Ford",
                                           "model": "Ranger", "modelYear": 2020, "mileage": 85000})
    check("cadastra veículo", vehicle.status == 201, f"{vehicle.status} {vehicle.raw}")
    vehicle_id = vehicle.json["id"]
    check("placa duplicada é recusada", owner.post("/api/vehicles", {"customerId": customer_id, "plate": plate,
          "manufacturer": "Ford", "model": "Ranger"}).status in (400, 409))

    service = owner.post("/api/services", {"name": f"Revisão da caixa de direção {RUN}", "basePrice": 350.00,
                                           "defaultWarrantyDays": 90})
    check("cadastra serviço", service.status == 201, f"{service.status} {service.raw}")
    service_id = service.json["id"]

    pump = owner.post("/api/products", {"description": f"Bomba hidráulica {RUN}", "internalCode": "BH-" + RUN,
                                        "type": "PART", "unit": "UNIDADE", "referenceCost": 150.00, "salePrice": 300.00,
                                        "minimumStock": 1})
    seal = owner.post("/api/products", {"description": f"Kit de reparo {RUN}", "internalCode": "KR-" + RUN,
                                        "type": "PART", "unit": "UNIDADE", "referenceCost": 40.00, "salePrice": 90.00})
    check("cadastra produtos", pump.status == 201 and seal.status == 201, f"{pump.raw} {seal.raw}")
    pump_id, seal_id = pump.json["id"], seal.json["id"]

    check("Gerente Administrativo cadastra cliente (CRM_MANAGE)", adm is not None and adm.post(
        "/api/customers", {"personType": "PF", "name": f"Cliente ADM {RUN}", "phone": "34988880000"}).status == 201)

    # ------------------------------------------------------------------ estoque
    section("Estoque: entrada, saldo insuficiente, custo médio")
    entry = owner.post(f"/api/inventory/products/{pump_id}/entries", {"quantity": 5, "unitCost": 150.00, "reason": "Compra inicial"})
    check("entrada de estoque", entry.status == 201, f"{entry.status} {entry.raw}")
    check("entrada do kit", owner.post(f"/api/inventory/products/{seal_id}/entries",
                                       {"quantity": 10, "unitCost": 40.00, "reason": "Compra inicial"}).status == 201)
    check("saída acima do saldo é recusada (409)", owner.post(f"/api/inventory/products/{seal_id}/exits",
          {"quantity": 999, "reason": "teste"}).status == 409)
    check("entrada com quantidade fracionada em UNIDADE é recusada",
          owner.post(f"/api/inventory/products/{seal_id}/entries", {"quantity": 1.5, "unitCost": 1}).status in (400, 409))

    # ------------------------------------------------------------------ OS
    section("OS: abertura, serviço, peças, diagnóstico")
    other_customer = owner.post("/api/customers", {"personType": "PF", "name": f"Outro {RUN}", "phone": "34977770000"}).json["id"]
    mismatch = owner.post("/api/work-orders", {"customerId": other_customer, "vehicleId": vehicle_id,
                                               "complaint": "Veículo de outro cliente"})
    check("OS com veículo de outro cliente é recusada (409)", mismatch.status == 409, f"{mismatch.status} {mismatch.raw}")
    order = owner.post("/api/work-orders", {"customerId": customer_id, "vehicleId": vehicle_id, "entryMileage": 85010,
                                            "complaint": "Vazamento e ruído na caixa de direção"})
    check("abre OS", order.status == 201, f"{order.status} {order.raw}")
    order_id = order.json["id"]
    check("OS nasce na etapa ABERTA", order.json["status"] == "ABERTA")
    add_service = owner.post(f"/api/work-orders/{order_id}/services", {"serviceId": service_id})
    check("lança serviço (preço do catálogo)", add_service.status == 201 and
          money(add_service.json["services"][0]["basePrice"]) == Decimal("350.00"), f"{add_service.status} {add_service.raw}")
    service_item_id = add_service.json["services"][0]["id"]
    add_pump = owner.post(f"/api/work-orders/{order_id}/products", {"productId": pump_id, "quantity": 2})
    check("lança bomba x2", add_pump.status == 201, f"{add_pump.status} {add_pump.raw}")
    add_seal = owner.post(f"/api/work-orders/{order_id}/products", {"productId": seal_id, "quantity": 1})
    check("lança kit x1", add_seal.status == 201, f"{add_seal.status} {add_seal.raw}")
    items = {p["productId"]: p["id"] for p in add_seal.json["products"]}
    check("estoque da bomba baixou no lançamento (ITEM_LAUNCH): 5 -> 3",
          stock_of(owner, pump_id) == Decimal("3"), str(stock_of(owner, pump_id)))
    too_many = owner.post(f"/api/work-orders/{order_id}/products", {"productId": pump_id, "quantity": 4})
    check("lançar peça acima do saldo é recusado (409)", too_many.status == 409, f"{too_many.status} {too_many.raw}")
    check("saldo não mudou após a recusa", stock_of(owner, pump_id) == Decimal("3"))
    check("diagnóstico", owner.put(f"/api/work-orders/{order_id}/diagnosis",
                                   {"diagnosis": "Retentor da bomba rompido; recomenda-se troca."}).status == 200)

    # ------------------------------------------------------------------ orçamento
    section("Orçamento: revisão, apresentação, link público")
    quote = owner.post(f"/api/work-orders/{order_id}/quotes")
    check("abre orçamento", quote.status == 201, f"{quote.status} {quote.raw}")
    quote_id = quote.json["id"]
    revision_body = {"items": [
        {"workOrderServiceId": service_item_id, "description": "Revisão da caixa de direção", "quantity": 1, "unitPrice": 350.00},
        {"workOrderProductId": items[pump_id], "description": "Bomba hidráulica", "quantity": 2, "unitPrice": 300.00},
        {"workOrderProductId": items[seal_id], "description": "Kit de reparo", "quantity": 1, "unitPrice": 90.00},
    ]}
    revision = owner.post(f"/api/work-orders/{order_id}/quotes/{quote_id}/revisions", revision_body)
    check("cria revisão com 3 itens", revision.status == 201, f"{revision.status} {revision.raw}")
    revision_id = revision.json["revisions"][-1]["id"]
    check("total da revisão = 350 + 600 + 90 = 1040,00", money(revision.json["revisions"][-1]["total"]) == Decimal("1040.00"),
          revision.raw)
    check("sem apresentar, não há link público", owner.post(
        f"/api/work-orders/{order_id}/quotes/{quote_id}/revisions/{revision_id}/public-access").status in (400, 409))
    check("Gerente Financeiro não apresenta orçamento (403)", fin is not None and fin.post(
        f"/api/work-orders/{order_id}/quotes/{quote_id}/revisions/{revision_id}/present").status == 403)
    present = owner.post(f"/api/work-orders/{order_id}/quotes/{quote_id}/revisions/{revision_id}/present")
    check("apresenta a revisão", present.status == 200, f"{present.status} {present.raw}")
    check("OS foi para AGUARDANDO_APROVACAO (automação)", owner.get(f"/api/work-orders/{order_id}").json["status"] == "AGUARDANDO_APROVACAO")
    issued = owner.post(f"/api/work-orders/{order_id}/quotes/{quote_id}/revisions/{revision_id}/public-access")
    check("emite link público", issued.status == 201 and issued.json.get("token"), f"{issued.status} {issued.raw}")
    token = issued.json["token"]
    check("token tem alta entropia (>= 43 caracteres url-safe)", len(token) >= 43 and token.replace("-", "").replace("_", "").isalnum(), token[:6])
    check("listagem de acessos não devolve o token nem o digest",
          token not in owner.get(f"/api/work-orders/{order_id}/quotes/{quote_id}/public-access").raw
          and "digest" not in owner.get(f"/api/work-orders/{order_id}/quotes/{quote_id}/public-access").raw.lower())

    # ------------------------------------------------------------------ cliente (sem sessão)
    section("Cliente: abre o link público e decide parcialmente")
    customer_browser = Client("cliente-sem-conta")
    if PUBLIC_XFF:
        customer_browser.extra_headers = {"X-Forwarded-For": PUBLIC_XFF}
    check("token inexistente responde 404", customer_browser.get("/api/public/quotes/" + secrets.token_urlsafe(32)).status == 404)
    check("token malformado responde 404", customer_browser.get("/api/public/quotes/abc").status == 404)
    view = customer_browser.get(f"/api/public/quotes/{token}")
    check("cliente vê a proposta sem login", view.status == 200, f"{view.status} {view.raw}")
    check("resposta pública sem cache", "no-store" in (view.headers.get("Cache-Control") or ""))
    check("resposta pública não expõe custo nem dados internos", all(
        word not in view.raw.lower() for word in ("cost", "custo", "margin", "createdby", "supplier", "customerid", "workorderid")), view.raw)
    public_items = {i["description"]: i["itemReference"] for i in view.json["items"]}
    revision_reference = view.json["revisionReference"]

    decision_body = lambda request_id: {  # noqa: E731
        "revisionReference": revision_reference, "requestId": request_id, "explicitAcceptance": True,
        "customer": {"name": "Cliente E2E", "documentType": "CPF", "documentNumber": "52998224725"},
        "decisions": [
            {"itemReference": public_items["Revisão da caixa de direção"], "decision": "APPROVE"},
            {"itemReference": public_items["Bomba hidráulica"], "decision": "APPROVE"},
            {"itemReference": public_items["Kit de reparo"], "decision": "REJECT"},
        ]}
    no_accept = decision_body("req-sem-aceite-" + RUN)
    no_accept["explicitAcceptance"] = False
    check("decisão sem aceite explícito é recusada", customer_browser.post(
        f"/api/public/quotes/{token}/decisions", no_accept).status in (400, 409, 422))
    request_id = "req-" + RUN
    decided = customer_browser.post(f"/api/public/quotes/{token}/decisions", decision_body(request_id))
    check("cliente aprova 2 itens e rejeita 1 (201)", decided.status == 201, f"{decided.status} {decided.raw}")
    replay = customer_browser.post(f"/api/public/quotes/{token}/decisions", decision_body(request_id))
    check("duplo clique/retry com o mesmo requestId é idempotente (200 replayed)",
          replay.status == 200 and replay.json["replayed"] is True, f"{replay.status} {replay.raw}")
    second = customer_browser.post(f"/api/public/quotes/{token}/decisions", decision_body("req-outra-" + RUN))
    check("nova tentativa com outro requestId sobre itens já decididos é recusada (409)", second.status == 409,
          f"{second.status} {second.raw}")
    check("OS foi para APROVADA (automação)", owner.get(f"/api/work-orders/{order_id}").json["status"] == "APROVADA")

    # ------------------------------------------------------------------ obsolescência
    section("Versão obsoleta")
    order2 = owner.post("/api/work-orders", {"customerId": customer_id, "vehicleId": vehicle_id, "complaint": "Segunda OS (obsolescência)"})
    order2_id = order2.json["id"]
    svc2 = owner.post(f"/api/work-orders/{order2_id}/services", {"serviceId": service_id}).json["services"][0]["id"]
    quote2 = owner.post(f"/api/work-orders/{order2_id}/quotes").json["id"]
    rev_a = owner.post(f"/api/work-orders/{order2_id}/quotes/{quote2}/revisions", {"items": [
        {"workOrderServiceId": svc2, "description": "Serviço v1", "quantity": 1, "unitPrice": 350.00}]})
    item_id2 = rev_a.json["items"][0]["id"]
    rev_a_id = rev_a.json["revisions"][-1]["id"]
    owner.post(f"/api/work-orders/{order2_id}/quotes/{quote2}/revisions/{rev_a_id}/present")
    token_a = owner.post(f"/api/work-orders/{order2_id}/quotes/{quote2}/revisions/{rev_a_id}/public-access").json["token"]
    view_a = customer_browser.get(f"/api/public/quotes/{token_a}")
    rev_b = owner.post(f"/api/work-orders/{order2_id}/quotes/{quote2}/revisions", {"items": [
        {"quoteItemId": item_id2, "workOrderServiceId": svc2, "description": "Serviço v2", "quantity": 1, "unitPrice": 400.00,
         "revisionReason": "Novo preço"}]})
    check("nova versão do item criada", rev_b.status == 201, f"{rev_b.status} {rev_b.raw}")
    rev_b_id = rev_b.json["revisions"][-1]["id"]
    owner.post(f"/api/work-orders/{order2_id}/quotes/{quote2}/revisions/{rev_b_id}/present")
    stale = customer_browser.post(f"/api/public/quotes/{token_a}/decisions", {
        "revisionReference": view_a.json["revisionReference"], "requestId": "stale-" + RUN, "explicitAcceptance": True,
        "customer": {"name": "Cliente E2E", "documentType": "CPF", "documentNumber": "52998224725"},
        "decisions": [{"itemReference": view_a.json["items"][0]["itemReference"], "decision": "APPROVE"}]})
    check("aprovar a versão obsoleta é recusado (409)", stale.status == 409, f"{stale.status} {stale.raw}")

    # ------------------------------------------------------------------ execução e faturamento
    section("Execução, finalização e recebível")
    window = f"from={date.today() - timedelta(days=1)}&to={date.today() + timedelta(days=1)}"
    baseline = owner.get(f"/api/finance/dashboard?{window}").json
    check("OS não pode ser finalizada antes de iniciar execução (409)",
          owner.post(f"/api/work-orders/{order_id}/finish").status == 409)
    check("Gerente Financeiro não inicia execução (403, WORKORDER_MANAGE)", fin is not None and
          fin.post(f"/api/work-orders/{order_id}/start-execution").status == 403)
    started = owner.post(f"/api/work-orders/{order_id}/start-execution")
    check("inicia execução", started.status == 200 and started.json["status"] == "EM_EXECUCAO", f"{started.status} {started.raw}")
    finished = owner.post(f"/api/work-orders/{order_id}/finish")
    check("finaliza OS e gera recebível", finished.status == 200 and finished.json["status"] == "FINALIZADA",
          f"{finished.status} {finished.raw}")
    receivable = owner.get(f"/api/finance/work-orders/{order_id}/receivable")
    check("recebível existe", receivable.status == 200, f"{receivable.status} {receivable.raw}")
    check("recebível = apenas o que o cliente aprovou: 350 + 600 = 950,00 (kit rejeitado fora)",
          money(receivable.json["originalAmount"]) == Decimal("950.00"), receivable.raw)
    receivable_id = receivable.json["id"]
    check("OS finalizada não aceita novo item (409)", owner.post(
        f"/api/work-orders/{order_id}/products", {"productId": seal_id, "quantity": 1}).status == 409)
    check("OS finalizada não aceita edição de dados (409)", owner.put(
        f"/api/work-orders/{order_id}", {"complaint": "alterada depois de encerrada"}).status == 409)

    # ------------------------------------------------------------------ caixa e recebimento
    section("Caixa físico e recebimento")
    methods = {m["code"]: m["id"] for m in owner.get("/api/finance/payment-methods").json}
    no_session = owner.post(f"/api/finance/receivables/{receivable_id}/receipts",
                            {"amount": 100.00, "paymentMethodId": methods["DINHEIRO"], "cashTendered": 100.00},
                            headers={"Idempotency-Key": "cash-" + RUN})
    check("dinheiro sem caixa aberto é recusado (409)", no_session.status == 409, f"{no_session.status} {no_session.raw}")
    check("Gerente Administrativo não abre caixa (403)", adm is not None and adm.post(
        "/api/finance/cash/sessions", {"countedBalance": 0}).status == 403)
    opening = owner.get("/api/finance/cash/suggested-opening-balance")
    opening_balance = money(opening.json["amount"]) if opening.status == 200 and opening.json else Decimal("0.00")
    session_open = owner.post("/api/finance/cash/sessions", {"countedBalance": float(opening_balance)})
    check("abre caixa contando o saldo sugerido", session_open.status == 201, f"{session_open.status} {session_open.raw}")
    session_id = session_open.json["id"]
    check("segunda abertura simultânea é recusada (409)",
          owner.post("/api/finance/cash/sessions", {"countedBalance": float(opening_balance)}).status == 409)
    cash = owner.post(f"/api/finance/receivables/{receivable_id}/receipts",
                      {"amount": 450.00, "paymentMethodId": methods["DINHEIRO"], "cashTendered": 500.00},
                      headers={"Idempotency-Key": "cash-ok-" + RUN})
    check("recebe R$ 450,00 em dinheiro", cash.status == 201, f"{cash.status} {cash.raw}")
    cash_retry = owner.post(f"/api/finance/receivables/{receivable_id}/receipts",
                            {"amount": 450.00, "paymentMethodId": methods["DINHEIRO"], "cashTendered": 500.00},
                            headers={"Idempotency-Key": "cash-ok-" + RUN})
    check("retry com a mesma Idempotency-Key não duplica (200 replay)", cash_retry.status == 200 and
          cash_retry.headers.get("Idempotent-Replay") == "true", f"{cash_retry.status} {cash_retry.raw}")
    over = owner.post(f"/api/finance/receivables/{receivable_id}/receipts",
                      {"amount": 600.00, "paymentMethodId": methods["PIX"]}, headers={"Idempotency-Key": "over-" + RUN})
    check("recebimento acima do saldo é recusado", over.status in (400, 409, 422), f"{over.status} {over.raw}")
    pix = owner.post(f"/api/finance/receivables/{receivable_id}/receipts",
                     {"amount": 500.00, "paymentMethodId": methods["PIX"]}, headers={"Idempotency-Key": "pix-" + RUN})
    check("quita o restante via PIX (R$ 500,00)", pix.status == 201, f"{pix.status} {pix.raw}")
    settled = owner.get(f"/api/finance/receivables/{receivable_id}").json
    check("recebível quitado", settled["status"] in ("PAID", "SETTLED", "QUITADO") or money(settled["balance"]) == Decimal("0.00"),
          json.dumps(settled)[:300])
    delivered = owner.post(f"/api/work-orders/{order_id}/deliver")
    check("entrega a OS", delivered.status == 200 and delivered.json["status"] == "ENTREGUE", f"{delivered.status} {delivered.raw}")
    check("OS entregue não pode ser cancelada (409)", owner.post(f"/api/work-orders/{order_id}/cancel", {"reason": "x"}).status == 409)

    open_session = owner.get("/api/finance/cash/sessions/open")
    expected = money(open_session.json["currentExpectedBalance"]) if open_session.status == 200 else None
    check("saldo esperado do caixa = abertura + R$ 450,00 recebidos em dinheiro",
          expected == opening_balance + Decimal("450.00"), open_session.raw)
    closed = owner.post(f"/api/finance/cash/sessions/{session_id}/close", {"countedBalance": float(expected)})
    check("fecha caixa conferido", closed.status == 200, f"{closed.status} {closed.raw}")
    check("fechamento repetido é recusado (409)", owner.post(
        f"/api/finance/cash/sessions/{session_id}/close", {"countedBalance": float(expected)}).status == 409)

    # ------------------------------------------------------------------ despesa e painel
    section("Conta a pagar e painel financeiro")
    categories = owner.get("/api/finance/expense-categories").json
    category = categories[0]["id"] if categories else owner.post("/api/finance/expense-categories", {"name": "Operacional " + RUN}).json["id"]
    payable = owner.post("/api/finance/payables", {"description": "Aluguel E2E", "supplier": "Locador", "categoryId": category,
                                                    "amount": 200.00, "dueDate": (date.today() + timedelta(days=5)).isoformat()},
                         headers={"Idempotency-Key": "payable-" + RUN})
    check("lança conta a pagar", payable.status == 201, f"{payable.status} {payable.raw}")
    paid = owner.post(f"/api/finance/payables/{payable.json['id']}/payments",
                      {"amount": 200.00, "paymentMethodId": methods["PIX"]}, headers={"Idempotency-Key": "pay-" + RUN})
    check("paga a conta via PIX", paid.status == 201, f"{paid.status} {paid.raw}")

    today = date.today()
    dash = owner.get(f"/api/finance/dashboard?{window}")
    check("painel financeiro responde", dash.status == 200, f"{dash.status} {dash.raw}")
    if dash.status == 200:
        d = dash.json
        delta = lambda key: money(d[key]) - money(baseline[key])  # noqa: E731
        print("     painel:", json.dumps(d, ensure_ascii=False))
        check("faturamento desta OS = R$ 950,00 (base comercial aprovada, kit rejeitado fora)", delta("revenue") == Decimal("950.00"),
              str(delta("revenue")))
        check("recebido nesta execução = R$ 950,00", delta("received") == Decimal("950.00"), str(delta("received")))
        check("despesa paga nesta execução = R$ 200,00", delta("expensesPaid") == Decimal("200.00"), str(delta("expensesPaid")))
        check("custo de peças inclui ao menos as 2 bombas aprovadas (2 x R$ 150,00)", delta("partsCost") >= Decimal("300.00"),
              str(delta("partsCost")))
        if delta("partsCost") != Decimal("300.00"):
            note(f"custo de peças desta OS = {delta('partsCost')}: inclui o kit de reparo REJEITADO pelo cliente "
                 "(baixado no lançamento e nunca devolvido) — lacuna F5, ver DR-0019.")
        check("lucro bruto = faturamento - custo de peças", delta("grossProfit") == delta("revenue") - delta("partsCost"),
              f"{delta('grossProfit')} vs {delta('revenue') - delta('partsCost')}")
    check("Gerente Administrativo enxerga o painel (FINANCE_VIEW) mas não lança despesa (403)",
          adm is not None and adm.get(f"/api/finance/dashboard?from={today}&to={today}").status == 200 and adm.post(
              "/api/finance/payables", {"description": "x", "categoryId": category, "amount": 1, "dueDate": today.isoformat()}).status == 403)

    # ------------------------------------------------------------------ cancelamento e devolução
    section("Cancelamento e devolução de estoque")
    pump_before = stock_of(owner, pump_id)
    order3 = owner.post("/api/work-orders", {"customerId": customer_id, "vehicleId": vehicle_id, "complaint": "OS a cancelar"}).json["id"]
    owner.post(f"/api/work-orders/{order3}/products", {"productId": pump_id, "quantity": 1})
    check("peça baixou ao lançar na terceira OS", stock_of(owner, pump_id) == pump_before - 1)
    cancelled = owner.post(f"/api/work-orders/{order3}/cancel", {"reason": "Cliente desistiu"})
    check("cancela OS", cancelled.status == 200 and cancelled.json["status"] == "CANCELADA", f"{cancelled.status} {cancelled.raw}")
    check("estoque devolvido no cancelamento", stock_of(owner, pump_id) == pump_before, str(stock_of(owner, pump_id)))
    check("OS cancelada não aceita novo item (409)", owner.post(
        f"/api/work-orders/{order3}/products", {"productId": pump_id, "quantity": 1}).status == 409)

    # ------------------------------------------------------------------ sessão
    section("Sessão")
    check("logout", owner.post("/api/iam/auth/logout").status == 204)
    check("após logout a sessão não vale mais (401)", owner.get("/api/customers").status == 401)

    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)}/{len(results)} verificações OK; {len(failed)} falha(s).")
    for name, _, detail in failed:
        print(f"  FALHOU: {name} :: {detail}")
    return 1 if failed else 0


def stock_of(client, product_id):
    page = client.get("/api/inventory/stock?size=100").json
    for line in page["items"]:
        if line["productId"] == product_id:
            return Decimal(str(line["quantity"]))
    return Decimal("-1")


if __name__ == "__main__":
    sys.exit(main())
