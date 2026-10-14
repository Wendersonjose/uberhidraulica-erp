#!/usr/bin/env python3
"""Fluxo E2E de Compras por HTTP real: fornecedor, importação da NF-e 21, recebimento, estoque e financeiro.

Roda contra qualquer ambiente já no ar (backend direto, ou o frontend/nginx do docker compose), DEPOIS do fluxo da
oficina (`workshop_flow.py`), que já deixa o Dono com a senha definitiva:

    BASE_URL=http://localhost:8080 \\
    E2E_OWNER_EMAIL=dono@example.test \\
    E2E_OWNER_PASSWORD=<senha definitiva do Dono> \\
    python3 scripts/e2e/purchasing_flow.py

Usa a NF-e 21 real REDIGIDA que já está no repositório (`src/test/resources/nfe/nfe-21-real-redigida.xml`). Isso grava
no banco do ambiente testado: use um ambiente de teste ou de homologação, nunca produção. Cada verificação é
independente e o código de saída é diferente de zero se qualquer uma falhar. Só usa a biblioteca padrão do Python 3.

Depende de `OWN_TAX_ID` (D9): o script grava o CNPJ do destinatário da nota (01.637.003/0001-01) na configuração de
Compras do ambiente testado, como o Dono faria pela tela.
"""
import os
import secrets
import sys
import uuid
from decimal import Decimal
from pathlib import Path

from workshop_flow import Client, OWNER_EMAIL, RUN

OWNER_PASSWORD = os.environ.get("E2E_OWNER_PASSWORD", "")
OWNER_BOOTSTRAP_PASSWORD = os.environ.get("E2E_OWNER_BOOTSTRAP_PASSWORD", "")
XML_PATH = Path(__file__).resolve().parents[2] / "src/test/resources/nfe/nfe-21-real-redigida.xml"
RECIPIENT = "01637003000101"
ACCESS_KEY = "31261067757350000178550010000000211010202158"
TOTAL = Decimal("1943.10")
# Unidade comercial das 12 linhas reais: KIT não equivale a UNIDADE e exige fator de conversão (D7).
UNITS = ["un", "un", "un", "un", "KIT", "KIT", "KIT", "UN", "UN", "KIT", "un", "un"]

results = []


def check(name, condition, detail=""):
    results.append((name, bool(condition), detail))
    print(("  [OK]   " if condition else "  [FAIL] ") + name + ("" if condition else f"  -> {detail}"))
    return bool(condition)


def section(title):
    print(f"\n== {title}")


def dec(value):
    return Decimal(str(value))


_cnpj_sequence = [0]


def cnpj():
    """CNPJ numérico válido e único a cada chamada (raiz derivada do RUN + contador, filial 0001, dígitos verificadores)."""
    _cnpj_sequence[0] += 1
    base = f"{(int(RUN, 16) + _cnpj_sequence[0]) % 10**8:08d}0001"

    def digit(digits, weights):
        remainder = sum(int(d) * w for d, w in zip(digits, weights)) % 11
        return "0" if remainder < 2 else str(11 - remainder)

    first = digit(base, [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2])
    second = digit(base + first, [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2])
    return base + first + second


def stock_line(client, product_id):
    page = client.get("/api/inventory/stock?size=100").json
    for line in page["items"]:
        if line["productId"] == product_id:
            return line
    return None


def stock_of(client, product_id):
    line = stock_line(client, product_id)
    return dec(line["quantity"]) if line else Decimal("0")


def make_user(owner, profile, key):
    email = f"{key}-compras-{RUN}@example.test"
    created = owner.post("/api/iam/users", {"name": f"{key} compras {RUN}", "email": email, "profileCode": profile})
    if not check(f"Dono cria usuário {profile}", created.status == 201 and created.json.get("temporaryPassword"),
                 f"{created.status} {created.raw}"):
        return None
    temporary = created.json["temporaryPassword"]
    client = Client(key)
    client.login(email, temporary)
    password = "Compras-" + secrets.token_urlsafe(16)
    client.change_password(temporary, password)
    client = Client(key)
    ok = client.login(email, password).status == 200
    check(f"{profile}: login definitivo", ok)
    return client if ok else None


def line_input(item_id, quantity, **extra):
    return {"purchaseItemId": item_id, "quantity": float(quantity), **extra}


def main():
    if not OWNER_EMAIL or not OWNER_PASSWORD:
        sys.exit("Defina E2E_OWNER_EMAIL e E2E_OWNER_PASSWORD (ver o cabeçalho deste arquivo).")
    if not XML_PATH.exists():
        sys.exit(f"XML da NF-e 21 não encontrado em {XML_PATH}")
    xml = XML_PATH.read_text(encoding="utf-8")

    owner = Client("dono")
    login = owner.login(OWNER_EMAIL, OWNER_PASSWORD)
    if login.status != 200 and OWNER_BOOTSTRAP_PASSWORD:
        owner.login(OWNER_EMAIL, OWNER_BOOTSTRAP_PASSWORD)
        owner.change_password(OWNER_BOOTSTRAP_PASSWORD, OWNER_PASSWORD)
        owner = Client("dono")
        login = owner.login(OWNER_EMAIL, OWNER_PASSWORD)
    section("Acesso")
    if not check("Dono entra com a senha definitiva", login.status == 200, f"{login.status} {login.raw}"):
        return 1
    adm = make_user(owner, "GERENTE_ADMINISTRATIVO", "adm")
    fin = make_user(owner, "GERENTE_FINANCEIRO", "fin")

    # ------------------------------------------------------------------ configuração (D9)
    section("Configuração: CNPJ do estabelecimento (D9)")
    before = owner.get("/api/purchasing/settings")
    check("configuração de Compras responde", before.status == 200, before.raw)
    check("raiz do CNPJ é recusada (D9 exige o CNPJ completo)",
          owner.put("/api/purchasing/settings/OWN_TAX_ID", {"value": RECIPIENT[:8]}).status == 400)
    saved = owner.put("/api/purchasing/settings/OWN_TAX_ID", {"value": RECIPIENT})
    check("Dono grava o CNPJ completo", saved.status == 200 and saved.json["ownTaxIdConfigured"] is True, saved.raw)
    if adm:
        check("Gerente Administrativo consulta a configuração", adm.get("/api/purchasing/settings").status == 200)

    # ------------------------------------------------------------------ fornecedor
    section("Fornecedor")
    own_supplier_cnpj = cnpj()
    supplier = owner.post("/api/purchasing/suppliers", {"personType": "PJ", "legalName": f"Fornecedor E2E {RUN} Ltda",
                                                        "document": own_supplier_cnpj})
    check("cria fornecedor PJ", supplier.status == 201, f"{supplier.status} {supplier.raw}")
    supplier_id = supplier.json["id"] if supplier.status == 201 else None
    check("CNPJ repetido é recusado (409)", owner.post("/api/purchasing/suppliers", {
        "personType": "PJ", "legalName": "Duplicado", "document": own_supplier_cnpj}).status == 409)
    if fin:
        check("Gerente Financeiro consulta fornecedores mas não cadastra",
              fin.get("/api/purchasing/suppliers").status == 200 and fin.post(
                  "/api/purchasing/suppliers", {"personType": "PJ", "legalName": "x", "document": cnpj()}).status == 403)

    # ------------------------------------------------------------------ prévia e importação da NF-e 21
    section("Importação da NF-e 21 (XML real redigido)")
    category = owner.post("/api/finance/expense-categories", {"name": f"Peças E2E {RUN}"})
    check("cria categoria de despesa", category.status == 201, category.raw)
    category_id = category.json["id"]
    products = []
    for index in range(12):
        created = owner.post("/api/products", {"description": f"Produto NF21 {RUN} #{index + 1}", "type": "PART", "unit": "UNIDADE"})
        products.append(created.json["id"] if created.status == 201 else None)
    check("cria os 12 produtos do catálogo", all(products), str(products))

    preview = owner.post("/api/purchasing/nfe/preview", {"xml": xml, "decisions": []})
    check("prévia lê a nota", preview.status == 200, f"{preview.status} {preview.raw[:200]}")
    p = preview.json or {}
    check("chave de acesso lida", p.get("accessKey") == ACCESS_KEY, str(p.get("accessKey")))
    check("destinatário autorizado (D9)", p.get("recipientCheck") == "AUTHORIZED", str(p.get("recipientCheck")))
    check("12 itens e total 1.943,10 que fecha", len(p.get("items", [])) == 12 and dec(p["totals"]["invoice"]) == TOTAL
          and p["totals"]["closes"] is True, str(p.get("totals")))
    check("3 duplicatas lidas da nota (D4)", len(p.get("installments", [])) == 3 and p.get("installmentsFromInvoice") is True)
    check("consulta oficial à SEFAZ NÃO foi feita (D12)", p.get("officialCheckPerformed") is False)
    check("prévia não grava nada: nenhuma compra existe ainda",
          owner.get(f"/api/purchasing/purchases?q={ACCESS_KEY}").json["totalItems"] == 0)

    decisions = []
    for index, product in enumerate(products):
        decision = {"lineNo": index + 1, "productId": product, "saveLink": True}
        if UNITS[index].upper() == "KIT":
            decision["unitConversionFactor"] = 1
        decisions.append(decision)
    body = {"xml": xml, "createSupplier": True, "expenseCategoryId": category_id, "decisions": decisions}

    if fin:
        check("Gerente Financeiro não importa NF-e (403)",
              fin.post("/api/purchasing/nfe/preview", {"xml": xml}).status == 403)
    no_key = owner.post("/api/purchasing/nfe/confirm", body)
    check("confirmar sem Idempotency-Key é recusado", no_key.status == 400 and no_key.code == "PURCHASE_IDEMPOTENCY_KEY_REQUIRED",
          f"{no_key.status} {no_key.raw}")

    key = str(uuid.uuid4())
    confirmed = owner.post("/api/purchasing/nfe/confirm", body, headers={"Idempotency-Key": key})
    check("confirma a importação", confirmed.status == 200, f"{confirmed.status} {confirmed.raw[:300]}")
    c = confirmed.json or {}
    purchase_id = c.get("purchaseId")
    check("compra nasce APROVADA com o total da nota", c.get("status") == "APPROVED" and dec(c["payableTotal"]) == TOTAL, str(c)[:200])
    check("o número sequencial da compra vem preenchido (não 0)", c.get("number", 0) >= 1, str(c.get("number")))
    installments = c.get("installments", [])
    check("1 compra, 3 obrigações de 647,70 que somam 1.943,10 (RN-04)",
          len(installments) == 3 and all(dec(i["amount"]) == Decimal("647.70") for i in installments)
          and sum(dec(i["amount"]) for i in installments) == TOTAL, str(installments))

    replay = owner.post("/api/purchasing/nfe/confirm", body, headers={"Idempotency-Key": key})
    check("retry com a mesma chave devolve a mesma compra, sem duplicar",
          replay.status == 200 and replay.json["purchaseId"] == purchase_id and replay.json["replayed"] is True, replay.raw[:200])
    duplicate = owner.post("/api/purchasing/nfe/confirm", body, headers={"Idempotency-Key": str(uuid.uuid4())})
    check("a mesma NF-e não vira uma segunda compra (409)",
          duplicate.status == 409 and duplicate.code == "PURCHASE_DUPLICATE_NFE", f"{duplicate.status} {duplicate.raw[:200]}")
    check("só existe 1 compra para a chave", owner.get(f"/api/purchasing/purchases?q={ACCESS_KEY}").json["totalItems"] == 1)

    # ------------------------------------------------------------------ importar não recebe nem paga
    section("Importar não recebe mercadoria nem paga")
    check("estoque dos 12 produtos segue zerado depois de importar", all(stock_of(owner, pid) == 0 for pid in products))
    payables = []
    for i in installments:
        payable = owner.get(f"/api/finance/payables/{i['payableId']}")
        payables.append(payable.json if payable.status == 200 else {})
    # Situação derivada do Financeiro: ABERTO, ou VENCIDO se a data do ambiente já passou do vencimento da nota.
    check("3 contas a pagar em aberto, nenhuma paga (RN-02)",
          len(payables) == 3 and all(dec(x.get("paidAmount", -1)) == 0 for x in payables)
          and all(x.get("status") in ("ABERTO", "VENCIDO") for x in payables), str([x.get("status") for x in payables]))
    check("as contas citam o fornecedor da nota", all((x.get("supplier") or "") != "" for x in payables),
          str([x.get("supplier") for x in payables]))
    if adm:
        check("Gerente Administrativo NÃO paga conta a pagar (403)", adm.post(
            f"/api/finance/payables/{installments[0]['payableId']}/payments",
            {"amount": 10, "method": "PIX", "paidOn": "2026-10-14"}, headers={"Idempotency-Key": str(uuid.uuid4())}).status == 403)

    # ------------------------------------------------------------------ recebimento, estoque e estorno
    section("Recebimento físico, estoque e estorno")
    detail = owner.get(f"/api/purchasing/purchases/{purchase_id}").json
    items = {i["lineNo"]: i for i in detail["items"]}
    check("itens da compra com o custo de aquisição gravado", len(items) == 12 and all(dec(i["unitCost"]) > 0 for i in items.values()))
    sheet = owner.get(f"/api/purchasing/purchases/{purchase_id}/receiving").json
    sheet["lines"].sort(key=lambda line: line["lineNo"])
    check("conferência inicial: nada recebido, tudo pendente", sheet["status"] == "NONE"
          and all(dec(l["received"]) == 0 and dec(l["pending"]) == dec(l["ordered"]) for l in sheet["lines"]))
    first = sheet["lines"][0]
    second = sheet["lines"][1]
    half = dec(first["ordered"]) / 2

    receive_key = str(uuid.uuid4())
    receipt_body = {"lines": [line_input(first["purchaseItemId"], half)]}
    partial = owner.post(f"/api/purchasing/purchases/{purchase_id}/receipts", receipt_body, headers={"Idempotency-Key": receive_key})
    check("recebimento parcial é registrado", partial.status == 201, f"{partial.status} {partial.raw[:300]}")
    check("entrou no estoque só o que chegou", stock_of(owner, products[0]) == half, str(stock_of(owner, products[0])))
    line = stock_line(owner, products[0])
    check("custo médio = custo de aquisição do item (D1)", line and dec(line["averageCost"]) == dec(items[1]["unitCost"]),
          str(line and line["averageCost"]))
    retry = owner.post(f"/api/purchasing/purchases/{purchase_id}/receipts", receipt_body, headers={"Idempotency-Key": receive_key})
    check("retry do mesmo recebimento não lança o estoque em dobro",
          retry.status == 200 and stock_of(owner, products[0]) == half, f"{retry.status} {stock_of(owner, products[0])}")
    status_now = owner.get(f"/api/purchasing/purchases/{purchase_id}/receiving").json["status"]
    check("situação derivada: recebida em parte", status_now == "PARTIAL", status_now)

    over = owner.post(f"/api/purchasing/purchases/{purchase_id}/receipts",
                      {"lines": [line_input(second["purchaseItemId"], dec(second["pending"]) + 1)]},
                      headers={"Idempotency-Key": str(uuid.uuid4())})
    check("receber a mais sem justificativa é recusado (D3) e não mexe no estoque",
          over.status == 400 and over.code == "PURCHASE_DIVERGENCE_JUSTIFICATION_REQUIRED" and stock_of(owner, products[1]) == 0,
          f"{over.status} {over.raw[:200]}")
    cancel_active = owner.post(f"/api/purchasing/purchases/{purchase_id}/cancel", {"reason": "teste de trava"})
    check("compra com recebimento ativo não cancela (D5)", cancel_active.status == 409
          and cancel_active.code == "PURCHASE_HAS_ACTIVE_RECEIPT", f"{cancel_active.status} {cancel_active.raw[:200]}")

    reversal = owner.post(f"/api/purchasing/receipts/{partial.json['id']}/reversal", {"reason": "Conferência errada"},
                          headers={"Idempotency-Key": str(uuid.uuid4())})
    check("estorno do recebimento", reversal.status == 201, f"{reversal.status} {reversal.raw[:200]}")
    check("estoque voltou a zero e o saldo voltou a pendente", stock_of(owner, products[0]) == 0
          and owner.get(f"/api/purchasing/purchases/{purchase_id}/receiving").json["status"] == "NONE")

    full = owner.post(f"/api/purchasing/purchases/{purchase_id}/receipts",
                      {"lines": [line_input(l["purchaseItemId"], dec(l["pending"])) for l in sheet["lines"]]},
                      headers={"Idempotency-Key": str(uuid.uuid4())})
    check("recebimento total das 12 linhas", full.status == 201, f"{full.status} {full.raw[:300]}")
    wrong = [i + 1 for i, l in enumerate(sheet["lines"]) if stock_of(owner, products[i]) != dec(l["ordered"])]
    check("estoque de cada produto = quantidade da nota", not wrong, f"linhas divergentes: {wrong}")
    final_sheet = owner.get(f"/api/purchasing/purchases/{purchase_id}/receiving").json
    check("situação derivada: recebida", final_sheet["status"] == "COMPLETE", final_sheet["status"])
    valued = sum(stock_of(owner, products[i]) * dec(stock_line(owner, products[i])["averageCost"]) for i in range(12))
    check("estoque valorizado fecha com a nota (1.943,10)", abs(valued - TOTAL) <= Decimal("0.05"), str(valued))

    after = [owner.get(f"/api/finance/payables/{i['payableId']}").json for i in installments]
    check("receber não pagou nem cancelou nada: as 3 contas seguem em aberto",
          all(dec(x["paidAmount"]) == 0 and x.get("cancelledAt") is None for x in after))
    movements = owner.get(f"/api/inventory/products/{products[0]}/movements?size=20")
    check("histórico do produto mostra a entrada de compra", movements.status == 200 and "PURCHASE" in movements.raw, movements.raw[:200])

    # ------------------------------------------------------------------ pedido: aprovação e DR-0023
    section("Pedido de compra: aprovação segregada e DR-0023")
    other_supplier = owner.post("/api/purchasing/suppliers", {"personType": "PJ", "legalName": f"Outro Fornecedor {RUN}", "document": cnpj()})
    order_product = products[0]
    order_body = {"origin": "ORDER", "supplierId": other_supplier.json["id"], "freight": 10,
                  "lines": [{"productId": order_product, "quantity": 10, "unitPrice": 10}]}
    if adm:
        created = adm.post("/api/purchasing/orders", order_body, headers={"Idempotency-Key": str(uuid.uuid4())})
        check("Gerente Administrativo cria o pedido em rascunho", created.status == 201 and created.json["status"] == "DRAFT"
              and dec(created.json["payableTotal"]) == Decimal("110.00"), f"{created.status} {created.raw[:200]}")
        order_id = created.json["id"]
        check("pedido não gera conta a pagar", created.json["installments"] == [])
        check("rascunho edita com a versão certa", adm.put(f"/api/purchasing/orders/{order_id}", {
            **{k: v for k, v in order_body.items() if k != "origin"}, "version": created.json["version"]}).status == 200)
        current = adm.get(f"/api/purchasing/purchases/{order_id}").json
        sent = adm.post(f"/api/purchasing/orders/{order_id}/submit", {"version": current["version"]})
        check("envia para aprovação", sent.status == 200 and sent.json["status"] == "AWAITING_APPROVAL", sent.raw[:200])
        selfie = adm.post(f"/api/purchasing/orders/{order_id}/approve", {"version": sent.json["version"]})
        check("D8: quem criou não aprova o próprio pedido (403)", selfie.status == 403
              and selfie.code == "PURCHASE_APPROVAL_SELF_FORBIDDEN", f"{selfie.status} {selfie.raw[:200]}")
        stale = owner.post(f"/api/purchasing/orders/{order_id}/approve", {"version": sent.json["version"] - 1})
        check("versão velha não aprova o que não viu (409)", stale.status == 409, f"{stale.status} {stale.raw[:200]}")
        approved = owner.post(f"/api/purchasing/orders/{order_id}/approve", {"version": sent.json["version"]})
        check("outra pessoa com permissão aprova", approved.status == 200 and approved.json["status"] == "APPROVED", approved.raw[:200])
        approved_sheet = owner.get(f"/api/purchasing/purchases/{order_id}/receiving").json
        blocked = owner.post(f"/api/purchasing/purchases/{order_id}/receipts", {"lines": [
            line_input(approved_sheet["lines"][0]["purchaseItemId"], 1)]}, headers={"Idempotency-Key": str(uuid.uuid4())})
        before_stock = stock_of(owner, order_product)
        check("DR-0023: pedido aprovado NÃO recebe (409) — só a compra de NF-e dá entrada em estoque",
              blocked.status == 409 and blocked.code == "PURCHASE_RECEIPT_REQUIRES_INVOICE", f"{blocked.status} {blocked.raw[:200]}")
        check("e o estoque não mudou", stock_of(owner, order_product) == before_stock)

    own = owner.post("/api/purchasing/orders", {**order_body, "origin": "DIRECT"}, headers={"Idempotency-Key": str(uuid.uuid4())})
    owner_sent = owner.post(f"/api/purchasing/orders/{own.json['id']}/submit", {"version": own.json["version"]})
    owner_approved = owner.post(f"/api/purchasing/orders/{own.json['id']}/approve", {"version": owner_sent.json["version"]})
    check("o Dono aprova o próprio pedido (exceção PURCHASE_APPROVE_OWN)",
          owner_approved.status == 200 and owner_approved.json["status"] == "APPROVED", owner_approved.raw[:200])
    if fin:
        check("Gerente Financeiro consulta compras mas não cria pedido (403)",
              fin.get("/api/purchasing/purchases").status == 200 and fin.post(
                  "/api/purchasing/orders", order_body, headers={"Idempotency-Key": str(uuid.uuid4())}).status == 403)

    # ------------------------------------------------------------------ painel financeiro
    section("Financeiro depois das compras")
    dashboard = owner.get("/api/finance/dashboard?from=2026-01-01&to=2026-12-31")
    check("painel financeiro responde", dashboard.status == 200, f"{dashboard.status} {dashboard.raw[:200]}")
    listing = owner.get("/api/finance/payables?size=100")
    check("as contas da NF-e 21 aparecem em Contas a pagar",
          listing.status == 200 and all(i["payableId"] in listing.raw for i in installments), listing.raw[:200])

    section("Sessão")
    check("logout", owner.post("/api/iam/auth/logout").status == 204)

    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)}/{len(results)} verificações OK; {len(failed)} falha(s).")
    for name, _, detail in failed:
        print(f"  FALHOU: {name} :: {detail}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
