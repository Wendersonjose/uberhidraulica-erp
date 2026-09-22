# TASK-0015 — Revisão da especificação (AG-02, AG-06, AG-08)

Objeto: `tasks/in-progress/TASK-0015-financeiro.md` e
`docs/architecture/financeiro/TASK-0015-modelo-financeiro.md`, contra `DR-0015` e `DR-0008` decididas.
Data: `2026-09-22`. Revisão feita antes de qualquer código financeiro.

---

## AG-02 — Arquitetura

| Ponto | Resultado |
| --- | --- |
| Dependência circular | Nenhuma. `finance → {workorder, quote, crm, iam}`; `quote → workorder`; a OS não depende de ninguém novo |
| OS conhecer Financeiro ou Orçamento | Não. `billingQuoteId` atravessa a OS como UUID opaco no evento `Finished` |
| Acesso a tabela de outro módulo pela aplicação | Nenhum. Base comercial por `QuoteBillingQuery`; OS por `WorkOrderQuery`/eventos; cliente por `CustomerVehicleQuery` |
| FKs entre schemas | Aceitas, no padrão já existente (`workshop → workorder`, `workorder → productcatalog`). Aqui elas são a defesa contra cross-OS e contra cobrança sem aprovação, que o Java sozinho não garante |
| Atomicidade entre módulos | Ouvintes na transação da OS (`MANDATORY`): finalização, baixa de estoque e recebível são tudo-ou-nada |
| Segunda fonte de verdade | Saldos e situação derivados. `original_amount` é gravado por ser **congelado** por decisão (F-02), e é conferido contra a soma das linhas ao carregar |
| Paginação | Listagens paginadas no SQL (não em memória, diferente do Estoque) |

**Achado A-01 (acatado):** a primeira versão previa verificar "orçamento pertence à OS" apenas no Java.
A especificação passou a exigir FK composta `(billing_quote_id, work_order_id) → workshop.quote(id,
work_order_id)`, reaproveitando o `UNIQUE` que a `V17` cria para a `DR-0008`.

**Achado A-02 (acatado):** "gerar recebível usando item rejeitado" era protegido só pela aplicação. A
especificação passou a exigir FK `(quote_item_revision_id, 'APPROVE') → workshop.quote_decision`, que
torna a linha sem aprovação impossível no banco. A regra de "versão corrente" (supersessão) continua na
aplicação, por depender de apresentações; está coberta por teste.

## AG-06 — Financeiro

| Invariante do AG-06 / DR-0015 | Onde é protegida |
| --- | --- |
| Competência ≠ conta ≠ pagamento ≠ movimentação | Recebível, recebimento e fluxo são entidades e consultas distintas; nenhuma competência modelada |
| Finalizar ≠ receber | Geração cria só o recebível; teste verifica zero recebimentos |
| Dinheiro em `BigDecimal` | ArchUnit no módulo; `NUMERIC(19,2)` |
| Sem saldo negativo / pagamento > saldo | Bloqueio de linha + verificação; teste concorrente real |
| Estorno total, próprio, único | Tabela própria com `UNIQUE(receipt_id)` |
| Idempotência de retry | `Idempotency-Key` com `UNIQUE`, verificada após o bloqueio |
| Dinheiro sem sessão de caixa | `cash_session_required`, recusado em receber e pagar |
| Sem regras inventadas | Juros, multa, parcelas, troco, crédito, competência, saldo inicial, bloqueio de inadimplente: ausentes |

**Achado F-01 (acatado → DR-0017):** correção de ajuste lançado por engano não é coberta pela DR-0015.
Não foi inventado estorno de ajuste; aberta `DR-0017`.

**Achado F-02 (acatado, registrado como interpretação 4 da DR-0015):** a regra de cancelamento de conta
a pagar com pagamento ativo é a equivalência de F-08 que a F-07 manda aplicar.

**Achado F-03 (registrado):** F-08 é inalcançável no fluxo atual (`DR-0012`). A especificação mantém a
regra e exige teste direto no serviço financeiro, para que ela não apodreça sem cobertura.

## AG-08 — Fiscal

| Ponto | Resultado |
| --- | --- |
| Recebível confundido com documento fiscal | Não. Recebível não emite, não numera e não referencia NFS-e |
| Tributo, alíquota, Simples Nacional, retenção | Ausentes. Nenhum campo fiscal no esquema |
| Valor do recebível como base fiscal futura | Não é assumido. A futura NFS-e deverá decidir sua própria base; o snapshot de linhas do recebível ajuda, mas não substitui essa decisão |
| Desconto financeiro × desconto comercial | Separados: o comercial vive no orçamento (DR-0013); o financeiro no recebível. Tratamento fiscal de cada um fica para a Task fiscal |

Sem achados bloqueantes.

## Conclusão

Especificação aprovada para implementação, com A-01, A-02 e F-01 incorporados antes do código.
