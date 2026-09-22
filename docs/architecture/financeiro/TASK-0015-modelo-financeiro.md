# TASK-0015 — Modelo de domínio e de dados do Financeiro

Fonte das regras: `DR-0015` (decidida), `DR-0008` (decidida), `DR-0007`, `DR-0009`, `DR-0012`,
`AG-06`. Pendência: `DR-0017` (correção de ajuste) — nada dela está modelado.

---

## 1. Módulo e fronteiras

Módulo novo `finance`:

```text
allowedDependencies = { workorder, quote, crm, iam }
```

| Dependência | Uso | Contrato público |
| --- | --- | --- |
| `workorder` | reagir à finalização e ao cancelamento; número e cliente da OS | `WorkOrderEvents`, `WorkOrderQuery` |
| `quote` | base comercial de faturamento | `QuoteBillingQuery` (novo) |
| `crm` | nome do cliente na listagem | `CustomerVehicleQuery` |
| `iam` | autor e permissão | `CurrentUser`, `IamAuthorization` |

A OS continua sem conhecer Orçamento e Financeiro. O identificador do orçamento de faturamento atravessa
a OS como dado opaco: `POST /finish {billingQuoteId?}` → `WorkOrderEvents.Finished.billingQuoteId`. O
ouvinte do Financeiro roda **na transação da OS** (`Propagation.MANDATORY`, mesmo desenho do Estoque):
recusa do Financeiro desfaz a finalização, e recusa do Estoque desfaz o recebível.

Nenhuma dependência circular: `quote → workorder`, `finance → quote, workorder`, `inventory → workorder`.

---

## 2. Base comercial de faturamento (`QuoteBillingQuery`)

```text
billingCandidates(workOrderId) → [ BillingCandidate(quoteId, approvedTotal, lines[]) ]
BillingLine(quoteItemId, quoteItemRevisionId, description, quantity, unitPrice,
            discountAmount, totalPrice, workOrderServiceId, workOrderProductId)
```

Para cada orçamento da OS e cada item comercial:

1. versão **corrente** = a de maior sequência entre as versões **apresentadas** (a não substituída —
   domínio aprovado §§56–58); item nunca apresentado não compõe oferta;
2. a linha entra se, e somente se, a versão corrente tem decisão `APPROVE`;
3. versão corrente `REJECT` ou sem decisão não entra; versão substituída não entra, mesmo aprovada.

Candidato = orçamento com ao menos uma linha. `approvedTotal` = soma dos totais já arredondados das
linhas (`DR-0007`).

Seleção na finalização:

| Candidatos | `billingQuoteId` informado | Resultado |
| --- | --- | --- |
| 0 | — | `409 WORK_ORDER_WITHOUT_BILLING_BASIS` |
| 1 | ausente | selecionado automaticamente |
| ≥ 2 | ausente | `409 BILLING_QUOTE_SELECTION_REQUIRED` (resposta lista os candidatos) |
| ≥ 1 | informado e candidato | usado |
| ≥ 1 | informado e não candidato (outra OS, sem aprovação, inexistente) | `409 BILLING_QUOTE_NOT_ELIGIBLE` |

`GET /api/finance/work-orders/{id}/billing-candidates` expõe os candidatos para a tela de finalização.

---

## 3. Agregados

### 3.1 Recebível (`Receivable`)

| Campo | Natureza |
| --- | --- |
| `workOrderId`, `workOrderNumber`, `customerId` | origem; um recebível por OS |
| `billingQuoteId` | orçamento que serviu de fonte comercial |
| `lines[]` | snapshot das linhas aprovadas no instante da geração |
| `originalAmount` | **gravado e congelado**; igual à soma das linhas, conferido ao carregar |
| `issuedOn`, `dueDate` | data da finalização (fuso da oficina) e vencimento corrente |
| `adjustments[]` | desconto/acréscimo imutáveis |
| `dueDateChanges[]` | histórico das mudanças de vencimento |
| `receipts[]` | recebimentos, cada um com estorno opcional |
| `cancelledAt/By/Reason` | fato de cancelamento, não situação derivada |

Derivados — nunca gravados:

```text
discountAmount    = Σ ajustes DISCOUNT
surchargeAmount   = Σ ajustes SURCHARGE
adjustedAmount    = originalAmount − discountAmount + surchargeAmount      (≥ 0)
receivedAmount    = Σ recebimentos sem estorno
outstandingBalance= adjustedAmount − receivedAmount                          (≥ 0)
```

Situação derivada, em ordem de precedência:

```text
CANCELADO  cancelledAt ≠ null
QUITADO    outstandingBalance = 0
VENCIDO    dueDate < hoje  e  outstandingBalance > 0
PARCIAL    receivedAmount > 0
ABERTO     demais casos
```

Operações:

| Operação | Pré-condição | Recusa |
| --- | --- | --- |
| gerar (ouvinte da finalização) | nenhum recebível da OS | idempotente por `UNIQUE(work_order_id)` |
| receber | não cancelado; forma ativa; forma sem sessão de caixa; `0 < valor ≤ saldo`; data ≤ hoje | `409 RECEIVABLE_CANCELLED`, `409 PAYMENT_METHOD_INACTIVE`, `409 CASH_SESSION_REQUIRED`, `409 AMOUNT_EXCEEDS_BALANCE`, `400 INVALID_FINANCE_ENTRY` |
| estornar recebimento | não estornado; motivo | `409 RECEIPT_ALREADY_REVERSED` |
| desconto | não cancelado; `0 < valor ≤ saldo`; motivo | `409 AMOUNT_EXCEEDS_BALANCE` |
| acréscimo | não cancelado; `valor > 0`; motivo | — |
| mudar vencimento | não cancelado; saldo > 0; motivo; data diferente | `409 RECEIVABLE_NOT_OPEN` |
| cancelar (ouvinte do cancelamento da OS) | nenhum recebimento sem estorno | `409 RECEIVABLE_HAS_RECEIPTS` |

### 3.2 Conta a pagar (`Payable`)

`description`, `supplier` (texto livre, opcional), `categoryId`, `amount` (> 0), `dueDate`
(obrigatório), `notes`, pagamentos com estorno, cancelamento. Derivados: `paidAmount`,
`outstandingBalance`, situação (`CANCELADO`, `PAGO`, `VENCIDO`, `PARCIAL`, `ABERTO`).

| Operação | Pré-condição | Recusa |
| --- | --- | --- |
| pagar | não cancelada; forma ativa e sem sessão de caixa; `0 < valor ≤ saldo`; data ≤ hoje | como no recebimento |
| estornar pagamento | não estornado; motivo | `409 PAYMENT_ALREADY_REVERSED` |
| cancelar | nenhum pagamento sem estorno; motivo | `409 PAYABLE_HAS_PAYMENTS` |

### 3.3 Configuração

- `PaymentMethod(code, name, active, cashSessionRequired)`. `code` imutável; `name` renomeável;
  inativar/reativar; nunca excluir. `DINHEIRO` nasce com `cashSessionRequired = true`, flag não
  editável pela API.
- `ExpenseCategory(name, active)`, lista plana; nome único sem distinção de caixa; nunca excluir.
- `default_receivable_due_days`, inteiro entre 0 e 365, inicial 0.

---

## 4. Idempotência

| Operação | Mecanismo |
| --- | --- |
| geração do recebível | bloqueio da linha da OS pela própria finalização + `UNIQUE(receivable.work_order_id)` |
| recebimento, estorno de recebimento, pagamento, estorno de pagamento, criação de conta a pagar | cabeçalho `Idempotency-Key` obrigatório (1–100 caracteres), gravado com `UNIQUE`; mesma chave + mesmo conteúdo devolve o lançamento original (`200`, cabeçalho `Idempotent-Replay: true`); mesma chave + conteúdo diferente → `409 IDEMPOTENCY_KEY_REUSED` |
| estorno duplo | `UNIQUE(receipt_id)` / `UNIQUE(payment_id)` na tabela de estorno |

Concorrência: toda mutação de um recebível ou conta a pagar começa por `SELECT … FOR UPDATE` na linha
do agregado. A verificação de chave, de saldo e de estorno acontece **depois** do bloqueio, então duas
requisições simultâneas se serializam e a segunda enxerga o que a primeira gravou. As constraints são a
autoridade final se a aplicação falhar.

Invariantes que atravessam tabelas (soma de recebimentos ≤ valor ajustado; desconto ≤ saldo) não são
expressáveis em `CHECK`. São protegidas pelo bloqueio da linha do agregado e cobertas por teste
concorrente real contra PostgreSQL. Não foi criado trigger: manter a regra em dois lugares seria uma
segunda fonte de verdade.

---

## 5. Fluxo de caixa

`GET /api/finance/cash-flow?from=&to=&categoryId=`, período obrigatório (máx. 366 dias).

| Bloco | Entradas | Saídas |
| --- | --- | --- |
| **Realizado** | recebimentos sem estorno, por `received_on` | pagamentos sem estorno, por `paid_on` (filtro de categoria) |
| **Previsto** | saldo em aberto de recebíveis não cancelados, por `due_date` | saldo em aberto de contas não canceladas, por `due_date` (filtro de categoria) |

Cada bloco devolve entradas, saídas, variação líquida e a série diária. Sem saldo inicial, sem
competência. A tela diz "entradas", "saídas" e "variação líquida" — nunca "saldo".

---

## 6. Banco (`V18__finance.sql`)

Schema `finance`. Todos os valores em `NUMERIC(19,2)` (valor cobrado, `DR-0007`), quantidades e preços
de linha em `NUMERIC(19,4)` como no orçamento.

| Tabela | Proteções principais |
| --- | --- |
| `settings` | `CHECK` de chave conhecida |
| `payment_method` | `UNIQUE(code)`; `CHECK` de código em maiúsculas |
| `expense_category` | índice único em `lower(name)` |
| `receivable` | `UNIQUE(work_order_id)`; FK `work_order`; FK composta `(billing_quote_id, work_order_id) → workshop.quote(id, work_order_id)` (orçamento da mesma OS); `original_amount ≥ 0`; coerência do cancelamento |
| `receivable_line` | FK `(receivable_id, quote_id) → receivable(id, billing_quote_id)`; FK `(quote_item_revision_id, quote_id) → workshop.quote_item_revision(id, quote_id)`; FK `(quote_item_revision_id, decision_type) → workshop.quote_decision(quote_item_revision_id, decision_type)` com `decision_type = 'APPROVE'` — **linha cobrada sem aprovação é impossível no banco**; `UNIQUE(receivable_id, quote_item_revision_id)` |
| `receivable_adjustment` | tipo `DISCOUNT`/`SURCHARGE`; `amount > 0`; motivo não vazio |
| `receivable_due_date_change` | datas diferentes; motivo não vazio |
| `receipt` | `amount > 0`; FK forma; `UNIQUE(idempotency_key)` |
| `receipt_reversal` | `UNIQUE(receipt_id)`; `UNIQUE(idempotency_key)`; motivo não vazio |
| `payable` | `amount > 0`; `due_date NOT NULL`; FK categoria; `UNIQUE(idempotency_key)` |
| `payable_payment` / `payable_payment_reversal` | análogos a recebimento/estorno |

Para a FK de decisão, a `V18` acrescenta `UNIQUE (quote_item_revision_id, decision_type)` em
`workshop.quote_decision` — redundante com o `UNIQUE (quote_item_revision_id)` existente e necessário
como alvo da FK. A `V17` (DR-0008) já cria `UNIQUE (id, work_order_id)` em `workshop.quote`.

Nenhum `UPDATE` em lançamento; nenhum `DELETE` em tabela financeira pela aplicação. Os únicos `UPDATE`
são: vencimento corrente e cancelamento do recebível/conta (fatos com histórico próprio) e nome/ativo
de forma e categoria.

Permissões `FINANCE_*` e distribuição por perfil: `INSERT` em `iam.permission` e `iam.profile_permission`
na `V18`, como a `V9` e a `V15` fizeram.

---

## 7. Contrato REST

| Método e caminho | Permissão |
| --- | --- |
| `GET /api/finance/receivables?status=&q=&dueFrom=&dueTo=&page=&size=` | `FINANCE_VIEW` |
| `GET /api/finance/receivables/{id}` | `FINANCE_VIEW` |
| `GET /api/finance/work-orders/{id}/receivable` | `FINANCE_VIEW` |
| `GET /api/finance/work-orders/{id}/billing-candidates` | `FINANCE_VIEW` |
| `POST /api/finance/receivables/{id}/receipts` `{amount, paymentMethodId, receivedOn?, notes?}` | `FINANCE_RECEIVE` |
| `POST /api/finance/receipts/{id}/reversal` `{reason}` | `FINANCE_REVERSE` |
| `POST /api/finance/receivables/{id}/adjustments` `{type, amount, reason}` | `FINANCE_ADJUST` |
| `PUT /api/finance/receivables/{id}/due-date` `{dueDate, reason}` | `FINANCE_ADJUST` |
| `GET /api/finance/payables?status=&categoryId=&dueFrom=&dueTo=&page=&size=` | `FINANCE_VIEW` |
| `GET /api/finance/payables/{id}` | `FINANCE_VIEW` |
| `POST /api/finance/payables` `{description, supplier?, categoryId, amount, dueDate, notes?}` | `FINANCE_PAYABLE` |
| `POST /api/finance/payables/{id}/payments` `{amount, paymentMethodId, paidOn?, notes?}` | `FINANCE_PAYABLE` |
| `POST /api/finance/payments/{id}/reversal` `{reason}` | `FINANCE_REVERSE` |
| `POST /api/finance/payables/{id}/cancel` `{reason}` | `FINANCE_PAYABLE` |
| `GET /api/finance/cash-flow?from=&to=&categoryId=` | `FINANCE_VIEW` |
| `GET /api/finance/payment-methods`, `GET /api/finance/expense-categories`, `GET /api/finance/settings` | `FINANCE_VIEW` |
| `POST`/`PUT` em formas, categorias e `PUT /api/finance/settings` | `FINANCE_CONFIG` |

`POST /api/work-orders/{id}/finish` passa a aceitar corpo opcional `{billingQuoteId}`; continua sem
exigir permissão financeira — gerar o recebível é consequência da finalização, não ato financeiro.
