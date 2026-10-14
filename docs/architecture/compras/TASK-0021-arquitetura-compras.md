# TASK-0021 — Arquitetura, dados e segurança do módulo Compras e Fornecedores

Status: `PROPOSTA` (AG-02/AG-09/AG-10 em um único documento enxuto; revisão por AG-15 pendente)
Base: `release/supabase-cloud` + `release/homologacao-producao` (merge local), migrations existentes até `V21`.
Decisões que ainda travam partes deste desenho: `DR-0022`.

## 1. Princípios

1. Necessidade ≠ cotação ≠ pedido ≠ recebimento ≠ documento fiscal ≠ pagamento (`AG-05` §130). Cada um tem estado
   próprio; **nenhum campo "pago" ou "recebido" é booleano de Compras** — recebimento é derivado de
   `goods_receipt_item` e situação financeira é lida do Financeiro.
2. Valor econômico da compra aparece **uma vez**. As obrigações (duplicatas) são partes dele: `Σ parcelas = total a
   pagar` é invariante verificada em aplicação e coberta por teste; a compra em si não cria nenhum lançamento além das
   contas a pagar.
3. Lançamentos são append-only; correção é estorno/anulação em tabela própria, com `UNIQUE` no item corrigido (padrão
   de `V18`/`V21`). Nada de `UPDATE`/`DELETE` em fatos (recebimento, parcela, preço histórico, evento).
4. Dinheiro `NUMERIC(19,2)`, preço unitário e custo `NUMERIC(19,4)`, quantidade `NUMERIC(15,3)` (igual ao Estoque);
   `BigDecimal` no Java, sem `double/float` (teste ArchUnit `PurchasingMonetaryArchitectureTest`).
5. Instalação única (sem `tenant_id`), como `V21`/`DR-0021`. O isolamento entre as duas empresas se dá por **banco e
   schema próprios** do ERP da oficina: todas as tabelas novas vivem em schemas `purchasing` (e colunas aditivas em
   `inventory`/`productcatalog`), nunca em `public` do Supabase, que já tem tabelas do módulo de salários.

## 2. Fronteiras (Spring Modulith)

```text
purchasing  -->  iam, productcatalog, inventory, finance, workorder   (allowedDependencies, ver package-info)
finance     -x-> purchasing          (o vínculo compra↔conta a pagar vive em purchasing; finance não conhece Compras)
inventory   -x-> purchasing          (estoque expõe um contrato; não sabe quem o chama)
```

Contratos públicos novos (interfaces na raiz do módulo dono; implementação em `application`):

| Módulo dono | Contrato | Finalidade | Idempotência |
| --- | --- | --- | --- |
| `inventory` | `PurchaseStockEntry.registerEntry(receiptItemId, productId, quantity, unitCost, ref)` / `reverseEntry(receiptItemId, reason)` | entrada por compra com custo de aquisição | `receiptItemId` único em `stock_movement` |
| `finance` | `PayableRegistry.register(PayableRequest)` / `cancel(payableId, reason)` / `snapshot(ids)` | criar/cancelar/ler contas a pagar derivadas de uma compra | `idempotency_key` já existente em `finance.payable` |
| `productcatalog` | `ProductCatalogQuery.findByGtin(...)` e `gtin/ncm` em `ProductReference` | casar item do XML | — |
| `workorder` | consulta de existência/identificação da OS (a definir em I5) | vínculo compra↔OS | — |

Regra: `purchasing` **não** lê `finance.payable` nem `inventory.stock_*` por SQL. A tela "contas a pagar da compra" usa
`purchasing.purchase_payable` (ids) + `PayableRegistry.snapshot`.

## 3. Modelo de dados por incremento

Convenção: UUID, `created_at/created_by`, `CHECK (btrim(x) <> '')` em textos, `idempotency_key VARCHAR(100) UNIQUE`
nas operações que um retry HTTP poderia duplicar, estornos em tabela própria.

### I2 — `V22__purchasing_suppliers.sql` (implementada; somente aditiva, schema novo)

- `purchasing.supplier`: `person_type PF|PJ`, `document VARCHAR(14)` (CPF de 11 dígitos ou CNPJ numérico/alfanumérico de 14,
  opcional, **único quando informado**, `CHECK` de formato e de coerência com o tipo de pessoa), nome/razão social, fantasia,
  IE, telefone, e-mail, endereço, condições comerciais padrão (`payment_terms`, `preferred_payment_method`, `usual_due_day`,
  `credit_limit NUMERIC(19,2)`, `commercial_notes`), `related_party` + nota (DR-0022 D10, padrão recomendado),
  `status ACTIVE|INACTIVE` com motivo/autor/data de inativação obrigatórios quando inativo, `version BIGINT` (controle
  otimista) e `created_*/updated_*`. O dígito verificador é validado na aplicação (`BrazilianTaxId`).
- `purchasing.supplier_contact`: nome, função, telefone, e-mail, `primary_contact`, `active`; índice único parcial "no máximo
  um principal **ativo** por fornecedor" e `CHECK` de que contato inativo não é principal. Remover = inativar.
- `purchasing.supplier_event`: trilha append-only (`CREATED, UPDATED, INACTIVATED, REACTIVATED, CONTACT_*`) com autor, instante,
  motivo e `changes JSONB` (CPF sempre mascarado).
- Triggers `forbid_mutation`: o banco recusa `DELETE` em fornecedor e contato e `UPDATE/DELETE` em evento.
- Permissões `PURCHASE_VIEW` (`…031`) e `PURCHASE_SUPPLIER_MANAGE` (`…032`), com os **mesmos códigos e ids** da branch antiga
  `feature/task-0019-compras-fornecedores` (ver §3.1). `DONO` e `GERENTE_ADMINISTRATIVO` recebem as duas; `GERENTE_FINANCEIRO`,
  só `PURCHASE_VIEW`.
- `purchasing.settings` (`OWN_TAX_ID`, parâmetros de custo) **não** entrou na `V22`: depende de DR-0022 D9/D1/D2 e vai com o I3.

#### 3.1 Reconciliação com `feature/task-0019-compras-fornecedores`

Existe uma branch anterior (2026-10-01, 14 commits, ainda não integrada) com `V22__purchasing_foundation.sql`, módulo `purchasing`
e tela de fornecedores. Ela diverge de `main` (12 commits atrás, usa o número `TASK-0019` já ocupado) e a sua `V22` cria também
`purchase_order`, `goods_receipt` e `supplier_price_history` sem código nem testes por trás e sem os invariantes de NF-e,
idempotência, ligação com contas a pagar e com o movimento de estoque. Decisão desta branch: **reaproveitar** o formato da API de
fornecedores e os códigos/ids de permissão dela; **não** reaproveitar as tabelas de pedido/recebimento, que serão desenhadas
nos incrementos I3–I4. Consequência: as duas branches **não podem ser integradas juntas** (Flyway acusa duas `V22`, de propósito);
a antiga deve ser arquivada depois que esta for integrada. Nada na branch antiga foi alterado.

### I3 — `V23__purchasing_purchase_nfe.sql`

- `purchasing.purchase(id, number BIGINT identity, supplier_id, origin CHECK IN ('NFE_IMPORT','DIRECT','ORDER'),
  status CHECK IN ('DRAFT','AWAITING_APPROVAL','APPROVED','PARTIALLY_RECEIVED','RECEIVED','CANCELLED'), goods_total,
  discount_total, freight_total, other_total, tax_added_total, payable_total NUMERIC(19,2), expense_category_id UUID,
  commercial_snapshot JSONB, work_order_id UUID NULL, notes, idempotency_key UNIQUE, created_*, cancelled_*)`.
  `status` de **recebimento** é derivado; este `status` é só o ciclo comercial. Não há coluna "paga".
- `purchasing.purchase_item(id, purchase_id, line_no, product_id, supplier_code, description, unit_original, quantity_original,
  unit_conversion_factor, quantity NUMERIC(15,3), unit_price NUMERIC(19,4), discount, line_total NUMERIC(19,2), ncm, cfop,
  gtin, work_order_id NULL, work_order_item_id NULL, UNIQUE(purchase_id, line_no))`.
- `purchasing.purchase_document(id, purchase_id, kind CHECK IN ('NFE','PROVISIONAL','OTHER'), access_key CHAR(44) NULL,
  series, number, issued_at, issuer_document, recipient_document, total_amount, protocol_number, protocol_status,
  xml_sha256 CHAR(64), xml_content TEXT (≤2 MB), voided_at/by/reason, created_*)` com
  **`UNIQUE (access_key) WHERE voided_at IS NULL`** (D5) e `CHECK ((kind='NFE') = (access_key IS NOT NULL))`.
- `purchasing.purchase_payable(purchase_id, payable_id UNIQUE, installment_no, due_date, amount, UNIQUE(purchase_id, installment_no))`
  — ligação sem FK para `finance` (o contrato é por id); a soma é conferida na aplicação e em teste de integração.
- `purchasing.supplier_product_link(id, supplier_id, supplier_code, gtin, product_id, unit_conversion_factor, confirmed_by/at,
  UNIQUE(supplier_id, supplier_code))` — vínculo "aprendido" (D6/D7).
- `purchasing.supplier_price_history` append-only (`supplier_id, product_id, unit_price, quantity, source_type, source_id, occurred_on`).
- `purchasing.purchase_event` append-only (`purchase_id, event_type, actor, occurred_at, payload JSONB`) — trilha de auditoria.
- Colunas aditivas em `productcatalog.product`: `gtin VARCHAR(14) NULL`, `ncm CHAR(8) NULL` + índice único parcial de GTIN (D6).
- Permissões `PURCHASE_ORDER_MANAGE` (`…033`), `PURCHASE_CANCEL` (`…036`) e `PURCHASE_IMPORT_XML` (`…037`).

### I4 — `V24__purchasing_receipts_inventory.sql`

- `purchasing.goods_receipt(id, purchase_id, document_id NULL, received_on, notes, received_by, idempotency_key UNIQUE)`,
  `goods_receipt_item(id, receipt_id, purchase_item_id, quantity_received NUMERIC(15,3) > 0, unit_cost NUMERIC(19,4),
  divergence_type CHECK IN ('NONE','QUANTITY','PRICE','ITEM'), expected_value, actual_value, divergence_accepted_by,
  divergence_justification, stock_movement_id UUID UNIQUE)`, `goods_receipt_reversal(receipt_id UNIQUE, reason, …)`.
  `CHECK (divergence_type = 'NONE' OR divergence_justification IS NOT NULL)` (`AG-05` §65).
- Em `inventory.stock_movement`: `source_type` passa a aceitar `'PURCHASE'` e nova coluna `purchase_receipt_item_id UUID`
  com `UNIQUE WHERE movement_type='ENTRY'` (uma entrada por item recebido) — `ck_stock_movement_work_order` é preservada.
- Permissões `PURCHASE_RECEIVE` (`…034`) e `PURCHASE_DIVERGENCE_ACCEPT` (`…035`).

#### Como o I4 foi realmente entregue

`V24__purchasing_receipts.sql` (o nome planejado acima era `…_receipts_inventory`). A divergência é `NONE | OVER | SHORT_CLOSED` com
quantidade esperada e cancelada gravadas; a situação de recebimento é a view `purchasing.purchase_item_receiving` (derivada). O Estoque
expõe `inventory.PurchaseStockEntry` e continua dono do próprio saldo e custo médio.

### I5 — `V25__purchasing_orders.sql` (implementada; somente aditiva, sem tabela nova)

- `purchasing.purchase`: `version BIGINT` (controle otimista), `updated_at/by`, `approved_at/by` com `CHECK` de par.
- Trigger `tg_purchase_item_delete_draft_only` substitui o `no_delete` do item: apagar item só com a compra em `DRAFT` (editar rascunho
  é trocar itens). Compra, documento e parcela continuam sem `DELETE`.
- `purchase_event.event_type` ganha `ORDER_UPDATED | ORDER_SUBMITTED | ORDER_RETURNED | ORDER_APPROVED`.
- Permissões `PURCHASE_APPROVE` (`…038`; DONO e GERENTE_ADMINISTRATIVO) e `PURCHASE_APPROVE_OWN` (`…039`; só DONO).
- Pedido **não** cria `purchase_payable` nem toca `inventory`; a ligação com a NF-e depende da `DR-0023`. Enquanto ela não
  existir, **só a compra de NF-e recebe** (`Purchase.requireReceivable()`): é a única com identidade (chave de acesso única) e
  idempotência, e dois caminhos de recebimento para a mesma mercadoria não são habilitados sem isso.

### I5b/I6 (desenho posterior, uma migration por incremento)

`purchase_request(+item)`, `quotation` / `quotation_invitation` / `quotation_proposal(+item)` com justificativa de escolha
mais cara (I5b, bloqueado pela `DR-0023` C4), `purchase_return` / `supplier_credit`, `supplier_price_history` ampliado (I6).

## 4. Estados e transições

```text
purchase:   DRAFT → AWAITING_APPROVAL → APPROVED            (devolução: AWAITING_APPROVAL → DRAFT, com motivo)
            CANCELLED a partir de qualquer estado sem recebimento ativo; recebimento ativo exige estorno antes
NFE_IMPORT: nasce APPROVED (a nota é o fato comercial) e não é editável.
ORDER / DIRECT: fluxo completo; só o rascunho edita; aprovar exige PURCHASE_APPROVE e, se o aprovador é o autor,
            PURCHASE_APPROVE_OWN (D8). Toda transição confere a version lida pela tela.
recebimento (derivado):  NONE | PARTIAL | COMPLETE   — por Σ quantity_received vs Σ quantity, nunca armazenado.
financeiro  (derivado):  lido do Financeiro por parcela (aberta/parcial/paga/vencida/cancelada).
```

## 5. Invariantes e onde são garantidas

| Invariante | Banco | Aplicação / teste |
| --- | --- | --- |
| Mesma NF-e não vira duas compras efetivas | `UNIQUE(access_key) WHERE voided_at IS NULL` | 409 `DUPLICATE_NFE` com id da compra existente; teste de importação repetida e concorrente |
| Retry HTTP não duplica compra/recebimento/parcela | `idempotency_key UNIQUE` | `Recorded<T>(value, replayed)` como no Financeiro |
| `Σ parcelas = total a pagar`; 3×647,70 = 1.943,10 | — | `InstallmentPlan` em centavos, resíduo na última parcela; teste unitário + integração |
| Pedido/importação não movimenta estoque | — | só `goods_receipt` chama `PurchaseStockEntry`; teste de saldo inalterado após importar |
| Recebimento não ultrapassa o pedido sem aceite | `SELECT … FOR UPDATE` em `purchase_item` | teste de concorrência com duas threads no último saldo |
| Divergência aceita tem justificativa | `ck_*_divergence` | teste |
| XML nunca marca conta como paga | `payable` nasce sem pagamento | teste: após importar, `paid_amount = 0` |
| Histórico não é reescrito | sem `UPDATE`/`DELETE`; triggers `forbid_update` nas tabelas append-only | teste de integração que tenta `UPDATE` direto |
| Falha parcial reverte tudo | uma transação `@Transactional` por caso de uso | teste: falha injetada na criação da 2ª conta a pagar ⇒ nada gravado, nenhum estoque |

## 6. API (planejada; prefixo `/api/purchasing`)

```text
I2  (implementado) GET|POST /suppliers   GET|PUT /suppliers/{id}   POST /suppliers/{id}/inactivate|reactivate
    GET|POST /suppliers/{id}/contacts   PUT|DELETE /suppliers/{id}/contacts/{contactId}   GET /suppliers/{id}/events
I3  POST /nfe/preview            (multipart ou JSON, somente leitura, nada é gravado)
    POST /nfe/confirm            (Idempotency-Key; cria fornecedor opcional, vínculos, compra, documento, contas a pagar)
    GET  /purchases   GET /purchases/{id}   POST /purchases/{id}/cancel   POST /documents/{id}/void
I4  (implementado) GET /purchases/{id}/receiving   GET|POST /purchases/{id}/receipts (Idempotency-Key)
    POST /receipts/{id}/reversal (Idempotency-Key)
I5  (implementado) POST /orders (Idempotency-Key)   PUT /orders/{id}   POST /orders/{id}/submit|approve|return
    (cada mudança carrega a version lida; consulta e cancelamento continuam em /purchases)
```

Erros com código estável (`PurchasingException`) e `@ControllerAdvice` próprio, como `FinanceExceptionHandler`.

## 7. Segurança (AG-09)

- Backend autoriza **toda** operação com `@PreAuthorize("@iamAuthorization.hasPermission(authentication, '…')")`.
  Permissões (códigos da branch antiga preservados): `PURCHASE_VIEW`, `PURCHASE_SUPPLIER_MANAGE` (V22, entregues),
  `PURCHASE_ORDER_MANAGE`, `PURCHASE_RECEIVE`, `PURCHASE_DIVERGENCE_ACCEPT`, `PURCHASE_CANCEL`, `PURCHASE_IMPORT_XML`
  `PURCHASE_APPROVE` e `PURCHASE_APPROVE_OWN` (V25; esta última só para `DONO`, exceção da D8) e, em I6, `PURCHASE_RETURN`.
  Perfis: `DONO` e `GERENTE_ADMINISTRATIVO` recebem todas as demais;
  `GERENTE_FINANCEIRO` recebe `PURCHASE_VIEW`. Pagar a conta continua exigindo `FINANCE_PAYABLE`.
- A criação das contas a pagar **derivadas** da compra ocorre dentro do caso de uso de importação, autorizado por
  `PURCHASE_IMPORT_XML`; isso é deliberado (o gerente administrativo não tem `FINANCE_PAYABLE`), fica registrado em
  `purchase_event` e **não** concede poder de pagar ou cancelar contas.
- XML: parser sem DOCTYPE/entidades externas, limite de 2 MB, só decimal simples, nunca registrado em log; o XML
  completo é guardado apenas em `purchase_document.xml_content` (auditoria) e exposto somente a `PURCHASE_VIEW`.
- CPF de fornecedor PF é dado pessoal: mascarado em listagens, completo só em detalhe para `SUPPLIER_MANAGE`.
- Sessão por cookie (Spring Session) permanece; nada de JWT; CSRF segue a configuração atual.
- Nenhum acesso entre bancos: o módulo não conhece o Supabase da outra empresa.

## 8. Plano de testes

Unitário: `BrazilianTaxId`, `Gtin`, `NfeAccessKey`, `NfeXmlParser`, `NfeValidator` (**entregues**, 31 testes), plano de
parcelas, custo de aquisição/rateio, unidade/conversão. Arquitetura: sem float; `ModularityTest`; `purchasing` não
usa repositório de outro módulo. Integração (PostgreSQL 18 + Testcontainers, só em checkpoint): importação, repetição,
concorrência, rollback, permissões negadas, estorno. Frontend: Vitest + Testing Library por tela. NF-e real: teste de
aceite com o arquivo de referência quando fornecido (hoje apenas XML sintético).

## 9. Impacto em Supabase e Render

- Supabase: migrations **aditivas** (schema novo + colunas nulas + novo valor permitido de `source_type`); nenhuma
  alteração destrutiva. O histórico Flyway fica em `erp_meta`; a numeração `V22+` só será fixada depois de confirmada a
  versão realmente aplicada em produção. Rollback = correção para frente (Flyway não desfaz); restore do backup é o
  último recurso e deve ser ensaiado em cópia antes do primeiro deploy.
- Render: nenhuma variável nova obrigatória. `OWN_TAX_ID` é dado de configuração no banco, não segredo.

## 10. Riscos

1. Regras de custo/tributo (DR-0022 D1/D2) sem validação do contador ⇒ custo médio incorreto; mitigação: parâmetros,
   rótulo "não validado", e nenhum recálculo retroativo silencioso.
2. Validação oficial ausente ⇒ nota cancelada/denegada depois da importação não é detectada; mitigação: alerta
   permanente e Task fiscal futura.
3. Contas a pagar criadas por contrato interno ampliam a superfície de privilégio; mitigação: evento auditável + teste de
   que `GERENTE_ADMINISTRATIVO` não consegue pagar.
4. Concorrência no recebimento; mitigação: bloqueio de linha + teste.
5. Pedido e NF-e sem ligação (`DR-0023`): estoque em dobro e custo divergente; **mitigado por bloqueio** — só a compra de NF-e
   recebe. A entrada manual do Estoque é caminho à parte e depende de disciplina do operador.
6. Defeito achado só pelo banco real: literais SQL sem aspas no adaptador do pedido passaram em compilação e em testes unitários e
   foram acusados pelo CI (PostgreSQL 18). Regra mantida: mudança de SQL só vale depois de executar o teste de integração.
