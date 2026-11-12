# Contrato REST — Compras: NF-e, recebimento e pedido

- Origem: `TASK-0021`, incrementos `I3`, `I4` e `I5`
- Base: `/api/purchasing`
- Autenticação: sessão Spring Security (cookie) obrigatória em todos os métodos
- CSRF: obrigatório em `POST`, `PUT` e `DELETE` (cabeçalho devolvido por `GET /api/iam/csrf`)
- Data: `2026-10-14`
- Fornecedores têm contrato próprio: [TASK-0021-contrato-rest-fornecedores.md](TASK-0021-contrato-rest-fornecedores.md)

A autorização é do **backend**. Esconder ou desabilitar botão no frontend é conforto de interface e não substitui a
proteção do endpoint (`AG-09`).

> **Validação neste contrato.** Os endpoints de I4 e I5 têm testes de integração contra PostgreSQL 18 real (Testcontainers) no CI do
> PR #4; ver `TASK-0021`, "Checkpoint de banco no CI". Valide no Insomnia contra o banco **de teste/local**, nunca contra o
> Supabase de produção.

## 1. Permissões

| Código | Para quê | DONO | GERENTE_ADMINISTRATIVO | GERENTE_FINANCEIRO |
| --- | --- | --- | --- | --- |
| `PURCHASE_VIEW` | consultar | sim | sim | sim |
| `PURCHASE_IMPORT_XML` | prévia e confirmação de NF-e | sim | sim | — |
| `PURCHASE_ORDER_MANAGE` | criar, editar e enviar pedido; configurar | sim | sim | — |
| `PURCHASE_APPROVE` | aprovar e devolver pedido | sim | sim | — |
| `PURCHASE_APPROVE_OWN` | aprovar o **próprio** pedido (exceção da D8) | **sim** | — | — |
| `PURCHASE_RECEIVE` | registrar recebimento | sim | sim | — |
| `PURCHASE_DIVERGENCE_ACCEPT` | aceitar divergência de recebimento | sim | sim | — |
| `PURCHASE_CANCEL` | cancelar compra, anular documento, estornar recebimento | sim | sim | — |

Pagar conta continua exigindo `FINANCE_PAYABLE`; nenhuma permissão de Compras concede isso.

## 2. Endpoints

| Método e caminho | Permissão | Observação |
| --- | --- | --- |
| `POST /nfe/preview` | `PURCHASE_IMPORT_XML` | XML (`application/xml`, multipart ou JSON `{xml, decisions}`); somente leitura |
| `POST /nfe/confirm` | `PURCHASE_IMPORT_XML` | `Idempotency-Key` obrigatória; aceita `workOrderId`, e `orderId` + `divergenceJustification` para vincular a um pedido (seção 4b) |
| `GET /nfe/linkable-orders?supplierId=` | `PURCHASE_IMPORT_XML` | pedidos e compras diretas aprovados do fornecedor, ainda sem nota |
| `POST /nfe/order-comparison` | `PURCHASE_IMPORT_XML` | `{xml, decisions, orderId}`; somente leitura; devolve as diferenças entre o pedido e a nota |
| `GET /purchases` | `PURCHASE_VIEW` | filtros `q`, `supplierId`, `status`, `origin`, `issuedFrom`, `issuedTo`, `page`, `size` |
| `GET /purchases/{id}` | `PURCHASE_VIEW` | inclui `version`, `createdBy`, `approvedAt`, `approvedBy`, `receiptStatus` |
| `GET /purchases/{id}/events` | `PURCHASE_VIEW` | trilha append-only |
| `POST /purchases/{id}/cancel` | `PURCHASE_CANCEL` | `{reason}`; recusa com recebimento ativo (409) |
| `POST /documents/{id}/void` | `PURCHASE_CANCEL` | `{reason}`; exige a compra cancelada |
| `GET /purchases/{id}/receiving` | `PURCHASE_VIEW` | conferência: pedido, recebido, encerrado, pendente |
| `GET /purchases/{id}/receipts` | `PURCHASE_VIEW` | |
| `POST /purchases/{id}/receipts` | `PURCHASE_RECEIVE` | `Idempotency-Key` obrigatória; divergência exige também `PURCHASE_DIVERGENCE_ACCEPT` |
| `POST /receipts/{id}/reversal` | `PURCHASE_CANCEL` | `Idempotency-Key` obrigatória; `{reason}` |
| `POST /orders` | `PURCHASE_ORDER_MANAGE` | `Idempotency-Key` obrigatória; 201, ou 200 com `Idempotent-Replay: true` |
| `PUT /orders/{id}` | `PURCHASE_ORDER_MANAGE` | só rascunho; carrega `version` |
| `POST /orders/{id}/submit` | `PURCHASE_ORDER_MANAGE` | `{version}` |
| `POST /orders/{id}/approve` | `PURCHASE_APPROVE` | `{version}`; autor só com `PURCHASE_APPROVE_OWN` |
| `POST /orders/{id}/return` | `PURCHASE_APPROVE` | `{version, reason}` |

## 3. Pedido e compra direta (I5)

### Criar — `POST /api/purchasing/orders`

Cabeçalho: `Idempotency-Key: <uuid>` (obrigatório). Mesma chave, mesmo autor, mesmo fornecedor e mesma origem devolvem o pedido
já criado (`200`, `Idempotent-Replay: true`); a chave usada por outro autor ou fornecedor é `409`.

```json
{
  "origin": "ORDER",
  "supplierId": "0f6a1f6e-4a1e-4a35-9d7a-2f0a3f2b9c11",
  "workOrderId": null,
  "notes": "Entregar até sexta",
  "freight": 10.00,
  "insurance": 0,
  "other": 0,
  "lines": [
    { "productId": "…", "quantity": 10, "unitPrice": 10.00, "discount": 0, "supplierCode": "RET-1", "workOrderItemId": null },
    { "productId": "…", "quantity": 5,  "unitPrice": 10.00 }
  ]
}
```

- `origin`: `ORDER` (pedido prévio) ou `DIRECT` (compra direta). `NFE_IMPORT` é recusado (`PURCHASE_ORDER_ORIGIN_INVALID`).
- `quantity` até 3 casas, `0 < q ≤ 1.000.000`; `unitPrice` até 4 casas, `0 ≤ p ≤ 1.000.000`; `discount` ≤ valor do item;
  frete, seguro e outras despesas `≥ 0`. Até 200 itens. O total do pedido deve ser maior que zero.
- A unidade é a do catálogo. Frete, seguro e outras despesas são rateados por valor, com fechamento exato dos centavos.
- Resposta: o mesmo `DetailResponse` de `GET /purchases/{id}`, com `status: "DRAFT"` e `version: 0`. Cada item traz `unitCost` e
  `cost` (composição). **Não há `installments`:** pedido não gera conta a pagar.

### Editar, enviar, aprovar, devolver

```http
PUT  /api/purchasing/orders/{id}          { "version": 0, "supplierId": "…", "freight": 10.00, "lines": [ … ] }
POST /api/purchasing/orders/{id}/submit   { "version": 1 }
POST /api/purchasing/orders/{id}/approve  { "version": 2 }
POST /api/purchasing/orders/{id}/return   { "version": 2, "reason": "Preço acima do combinado" }
```

Cada chamada que muda o pedido avança `version` em um. **`version` velha → `409 PURCHASE_ORDER_VERSION_CONFLICT`:** quem aprova só
aprova a versão que viu.

### Erros

| HTTP | `code` | Quando |
| --- | --- | --- |
| 400 | `PURCHASE_IDEMPOTENCY_KEY_REQUIRED` | criação sem `Idempotency-Key` |
| 400 | `PURCHASE_ORDER_ORIGIN_INVALID` | origem diferente de `ORDER`/`DIRECT` |
| 400 | `PURCHASE_SUPPLIER_UNRESOLVED` / `PURCHASE_SUPPLIER_INACTIVE` | fornecedor inexistente / inativo |
| 400 | `PURCHASE_ORDER_LINE_INVALID` | produto inexistente, quantidade/preço/desconto inválidos, item de OS que não é do produto |
| 400 | `PURCHASE_ITEM_PRODUCT_INACTIVE` | produto inativo (também revalidado na aprovação) |
| 400 | `PURCHASE_ORDER_LINES_REQUIRED` / `PURCHASE_ORDER_TOO_MANY_LINES` | sem item / mais de 200 |
| 400 | `PURCHASE_ORDER_EXPENSE_INVALID` / `PURCHASE_ORDER_TOTAL_INVALID` | despesa fora do limite / total zero |
| 400 | `PURCHASE_ORDER_WORK_ORDER_INVALID` | OS inexistente |
| 400 | `PURCHASE_REASON_REQUIRED` | devolução sem motivo (3 a 500 caracteres) |
| 403 | `PURCHASE_APPROVAL_SELF_FORBIDDEN` | autor tentando aprovar sem `PURCHASE_APPROVE_OWN` |
| 404 | `PURCHASE_NOT_FOUND` | compra inexistente |
| 409 | `PURCHASE_ORDER_NOT_EDITABLE` | não é rascunho de pedido/compra direta |
| 409 | `PURCHASE_ORDER_NOT_AWAITING_APPROVAL` | aprovar/devolver fora de `AWAITING_APPROVAL` (inclui a segunda de duas aprovações simultâneas) |
| 409 | `PURCHASE_ORDER_VERSION_CONFLICT` | `version` velha |
| 409 | `PURCHASE_ORDER_STATE_CHANGED` | a situação mudou durante a operação |
| 409 | `PURCHASE_IDEMPOTENCY_KEY_REUSED` | chave de idempotência já usada por outra compra |
| 409 | `PURCHASE_RECEIPT_REQUIRES_INVOICE` | receber pedido/compra direta (`POST /purchases/{id}/receipts`), DR-0023 |
| 400 | `PURCHASE_ORDER_LINK_INVALID` | vincular pedido de outro fornecedor, não aprovado, ou compra que não é pedido |
| 400 | `PURCHASE_ORDER_LINK_JUSTIFICATION_REQUIRED` | a nota difere do pedido e não veio justificativa (3 a 500 caracteres) |
| 403 | `PURCHASE_DIVERGENCE_NOT_AUTHORIZED` | a nota difere do pedido e o usuário não tem `PURCHASE_DIVERGENCE_ACCEPT` |
| 409 | `PURCHASE_ORDER_ALREADY_LINKED` | o pedido já tem nota ativa (inclui a segunda de duas importações simultâneas) |
| 409 | `PURCHASE_ORDER_CLOSED_BY_INVOICE` | cancelar pedido encerrado pela nota; cancele a compra da nota primeiro |

## 4. Recebimento (I4)

`POST /purchases/{id}/receipts` com `Idempotency-Key`:

```json
{
  "receivedOn": "2026-10-14",
  "documentId": null,
  "notes": null,
  "lines": [
    { "purchaseItemId": "…", "quantity": 6 },
    { "purchaseItemId": "…", "quantity": 3, "closeRemaining": true, "justification": "Fornecedor não enviará o restante" }
  ]
}
```

- Só compra `APPROVED` recebe (`409 PURCHASE_NOT_RECEIVABLE`) e **só a compra nascida de NF-e** (`origin = NFE_IMPORT`): pedido e
  compra direta são recusados com `409 PURCHASE_RECEIPT_REQUIRES_INVOICE` (`DR-0023` C1-A: a mercadoria entra sempre pela compra
  da nota à qual o pedido foi vinculado). Entra no estoque **apenas** o que foi conferido, ao custo de aquisição do item.
- Receber menos que o pendente **sem** encerrar é recebimento parcial: não é divergência.
- Receber a mais, ou encerrar o saldo (`closeRemaining`), é divergência: exige `justification` (`400
  PURCHASE_DIVERGENCE_JUSTIFICATION_REQUIRED`) e `PURCHASE_DIVERGENCE_ACCEPT` (`403 PURCHASE_DIVERGENCE_NOT_AUTHORIZED`).
- Estorno (`POST /receipts/{id}/reversal`) desfaz a entrada em estoque e devolve o saldo ao pendente; recusa quando a peça já foi
  consumida (`409 PURCHASE_RECEIPT_STOCK_CONSUMED`).
- Mesma chave com conteúdo diferente: `409 PURCHASE_IDEMPOTENCY_KEY_REUSED`.

## 4b. Pedido × NF-e (I5b, `DR-0023` decidida em 2026-11-12)

`POST /nfe/confirm` aceita, além do que já aceitava:

```json
{ "orderId": "…", "divergenceJustification": "Fornecedor enviou uma unidade a mais", "workOrderId": null }
```

- `orderId` é opcional. Informado, o pedido precisa ser `ORDER` ou `DIRECT`, estar `APPROVED`, ser do fornecedor emitente, não ter
  vínculo ativo e não ter recebimento ativo.
- `POST /nfe/order-comparison` devolve `{orderId, orderNumber, orderTotal, invoiceTotal, differences[], mayAccept}`. Cada diferença
  tem `kind` (`TOTAL`, `QUANTITY`, `UNIT_PRICE`, `ITEM_NOT_IN_INVOICE`, `ITEM_NOT_IN_ORDER`), `productId`, `description`,
  `orderValue` e `invoiceValue`. A confirmação refaz a mesma comparação; nada é confiado ao cliente.
- Havendo diferença, a confirmação exige `divergenceJustification` e `PURCHASE_DIVERGENCE_ACCEPT`. Recusar o vínculo desfaz a
  importação inteira (compra, documento e contas a pagar).
- A resposta da confirmação traz `linkedOrderId`. `GET /purchases/{id}` traz `orderLink` (nulo quando não há vínculo ativo) com
  `orderPurchaseId`, `orderNumber`, `invoicePurchaseId`, `invoiceNumber`, `linkedAt`, `divergent`, `divergenceJustification` e
  `differences[]` — o mesmo objeto no pedido e na compra da nota.
- Sem `workOrderId`, a compra da nota herda a OS do pedido vinculado.
- Cancelar a compra da nota libera o vínculo: o pedido volta a aparecer em `GET /nfe/linkable-orders`.

## 5. O que este contrato **não** faz

- Pedido não cria conta a pagar e não movimenta estoque. Pedido e compra direta **não recebem**; o recebimento é só na compra da
  nota importada, à qual o pedido é vinculado na importação (`DR-0023` C1-A).
- Compra sem NF-e (balcão, recibo) não tem fluxo próprio em Compras: use a entrada manual do Estoque e o lançamento manual em
  Contas a pagar.
- Solicitação, cotação e comparação de fornecedores não existem (`DR-0023` C4-A: depois do piloto).
- Não há consulta oficial à SEFAZ: a autorização é a declarada no XML (`officialCheckPerformed: false`).
