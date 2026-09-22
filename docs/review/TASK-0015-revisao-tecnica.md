# TASK-0015 — Revisão técnica (revisão externa + correções + AG-15)

- Data: `2026-09-22`
- Resultado da revisão externa: **`CHANGES_REQUIRED`** — três findings obrigatórios e a `DR-0017` decidida.
- Estado após as correções: implementadas e verdes em tudo que roda sem Docker; **checkpoint PostgreSQL
  não executado** (seção 5). A Task permanece em `REVIEW`.

---

## 1. Findings da revisão externa e correções

### F1 — BLOCKER — base comercial do recebível não estava serializada

**Problema.** Apresentação e decisão serializavam entre si pela versão do `Quote` (`touch()`), mas o
`QuoteBillingService` só lia. Corrida possível: T1 (finalização) lê A-v1 como corrente e aprovada; T2
apresenta A-v2 e faz commit; T1 continua com o snapshot antigo e gera o recebível com A-v1 — versão já
substituída antes do commit financeiro. Viola a F-02.

**Correção** (`93a6043`, `d0894d5`):

- Nova operação pública `QuoteBillingQuery.billingBasisForFinalization(workOrderId, billingQuoteId)`,
  `Propagation.MANDATORY` (só roda dentro da transação da finalização):
  1. `SELECT id FROM workshop.quote WHERE work_order_id = ? ORDER BY id FOR UPDATE`;
  2. só então relê apresentações e decisões;
  3. deriva de novo versões correntes e aprovações;
  4. refaz a seleção (`SELECTED`, `NO_BASIS`, `SELECTION_REQUIRED`, `NOT_ELIGIBLE`);
  5. devolve o snapshot ao Financeiro. O bloqueio dura até o commit que grava o recebível.
- `billingCandidates()` continua read-only e só informativa (tela de finalização).
- `touch()` de apresentação e decisão é um `UPDATE` na mesma linha: espera quem segura o `FOR UPDATE`.
- **Ordem de bloqueio.** A finalização bloqueia a OS e depois os orçamentos. A apresentação bloqueava o
  orçamento e depois a OS (pela automação de status) — ordem inversa, portanto deadlock possível.
  Apresentação, decisão pública e decisão interna passaram a chamar
  `WorkOrderCommercialEvents.lockForCommercialChange(workOrderId)` **antes** de ler o orçamento; a decisão
  interna relê o orçamento depois do bloqueio. As duas operações disputam os bloqueios na mesma ordem.
- Defesa adicional: `PessimisticLockingFailureException` responde `409 CONCURRENT_MODIFICATION` nos
  endpoints do Financeiro e da OS, em vez de `500`.

**Testes** (`fbe5556`, PostgreSQL): uma transação aberta em outra conexão reproduz a operação
concorrente parada no meio do caminho, o que torna a corrida determinística:

| Teste | Prova |
| --- | --- |
| `finalizationWaitsForAConcurrentPresentationAndNeverBillsTheSupersededVersion` | com A-v2 sendo apresentada e sem commit, a finalização **espera**; após o commit ela relê, A-v1 está substituída e A-v2 sem decisão → `409`, nenhum recebível, OS em execução |
| `presentationWaitsWhileAFinalizationHoldsTheCommercialBasis` | com o bloqueio da finalização mantido, a apresentação real **espera** e só conclui após o commit |
| `finalizationWaitsForAConcurrentDecisionAndBillsWhatWasDecided` | decisão concorrente sem commit: a finalização espera e cobra os dois itens aprovados |
| `aSecondCandidateApprovedConcurrentlyForcesTheExplicitChoice` | segundo orçamento aprovado concorrentemente: a finalização espera e responde `BILLING_QUOTE_SELECTION_REQUIRED` |
| `realConcurrentFinalizationAndPresentationNeverDeadlockOrLeakAServerError` | corrida real pela API, quatro rodadas: só `200`/`409`, e quando há recebível ele nunca cobra A-v2 |

### F2 — HIGH — forma customizada contornava a sessão de caixa

**Problema.** Toda forma criada nascia com `cashSessionRequired = false`; "Dinheiro balcão" contornava a F-10.

**Correção** (`d0894d5`, `fdb6e33`), sem migration de coluna (reuso de `cash_session_required`):

- `POST /api/finance/payment-methods` exige `cashSessionRequired` (`@NotNull`); ausente → `400`.
- A natureza nunca é inferida do nome e nunca muda: `PaymentMethod.withNameAndActive` preserva o campo;
  a atualização não o grava; a `V19` acrescenta trigger `tg_payment_method_cash_session_immutable` que
  recusa a alteração no banco.
- `DINHEIRO` continua `true`; as formas eletrônicas semeadas continuam `false`.
- Frontend: escolha obrigatória "Exige sessão de caixa / movimenta dinheiro físico?".

**Testes**: `customPaymentMethodThatMovesPhysicalCashIsRefusedAndItsNatureNeverChanges` (recebimento e
pagamento recusados com `CASH_SESSION_REQUIRED`, renomear/inativar/reativar não mudam a natureza,
trigger no banco) e `renamingOrInactivatingAPaymentMethodNeverChangesItsNature` (unitário).

### F3 — HIGH — finalização criava recebível sem permissão financeira

**Correção** (`d0894d5`, `fdb6e33`):

- `V19`: `FINANCE_BILL` — "Gerar faturamento/recebível na finalização da Ordem de Serviço" — para `DONO`,
  `GERENTE_FINANCEIRO` e `GERENTE_ADMINISTRATIVO`. Exceções individuais do IAM continuam.
- `POST /api/work-orders/{id}/finish` com `@PreAuthorize(... 'FINANCE_BILL')`.
- Defesa em profundidade: `ReceivableService.generateForFinishedWorkOrder` recusa (`403 FINANCE_BILL_REQUIRED`)
  ator sem a permissão **ou ator ausente**, então nenhuma finalização por outro caminho gera recebível
  sem autorização.
- Frontend: "Finalizar OS" só aparece com `FINANCE_BILL`.

**Teste**: `finishingWithoutFinanceBillIsForbiddenAndNothingIsBilled` — sessão real com exceção `DENY`
recebe `403`; usuário autenticado sem identidade do IAM recebe `403`; o caminho interno recusa ator sem
permissão e ator nulo; nenhum recebível nasce; a OS continua `EM_EXECUCAO`; usuário autorizado finaliza.

---

### F4 — MEDIUM, bloqueante para `DONE` — identidade do pedido idempotente incompleta

Encontrado na nova revisão externa, que confirmou F1–F3. A regra "mesma chave + outro conteúdo →
`IDEMPOTENCY_KEY_REUSED`" não valia em todas as mutações: ajuste comparava só o recebível; vencimento não
comparava o motivo; estornos só o alvo; recebimento e pagamento ignoravam a observação; conta a pagar
ignorava fornecedor e observação; e um retry sem data depois da meia-noite virava conflito porque o
"hoje" recalculado mudava.

**Correção** — sem migration; os campos persistidos reconstroem o pedido:

- ajuste: recebível, tipo, valor e motivo normalizado;
- vencimento: recebível, novo vencimento e motivo normalizado;
- estornos (recebimento, pagamento, ajuste): alvo e motivo normalizado;
- recebimento e pagamento: dono, valor, forma, data efetiva e observação normalizada;
- conta a pagar: descrição, fornecedor, categoria, valor, vencimento e observação normalizados;
- data omitida: a intenção é o dia da oficina no instante do pedido original, reconstruído de
  `recorded_at` em `America/Sao_Paulo` — nunca o "hoje" do retry.

Testes: `settlementIdentityIncludesNotesAndReconstructsTheOmittedDateFromTheOriginalInstant`,
`payableIdentityIncludesSupplierAndNotes` (unitários, verdes) e, no PostgreSQL,
`adjustmentKeyReusedWithDifferentAmountOrReasonIsRefused`, `dueDateKeyReusedWithSameDateButDifferentReasonIsRefused`,
`reversalKeysReusedWithSameTargetButDifferentReasonAreRefused`, `receiptAndPaymentKeysReusedWithDifferentNotesAreRefused`,
`payableKeyReusedWithDifferentSupplierOrNotesIsRefused` e `retryOfAReceiptWithoutDateAfterMidnightReturnsTheOriginal`.

## 2. DR-0017 — decidida (opção A) e implementada

- `V19`: `finance.receivable_adjustment_reversal` com `UNIQUE (adjustment_id)` e `UNIQUE (idempotency_key)`.
- `POST /api/finance/adjustments/{adjustmentId}/reversal`, `FINANCE_REVERSE`, `Idempotency-Key` e `{reason}`.
- Estorno total; ajuste original intacto; ajuste estornado sai de `discountAmount`/`surchargeAmount` (no
  domínio e no SQL da listagem); histórico mostra os dois.
- Estorno de acréscimo recusado se `receivedAmount > adjustedAmount` depois dele
  (`409 ADJUSTMENT_REVERSAL_EXCEEDS_RECEIVED`), sem saldo negativo nem crédito.
- Recebível cancelado não aceita estorno de ajuste (`RECEIVABLE_CANCELLED`), coerente com os demais lançamentos.

Testes: `adjustmentReversalIsTotalUniqueIdempotentAndKeepsTheOriginal`,
`surchargeReversalThatWouldLeaveReceiptsAboveTheAdjustedAmountIsRefused`,
`concurrentReversalsOfTheSameAdjustmentReverseOnce`, `v19GuardsSingleAdjustmentReversalAndUniqueDueDateKeys`
e três unitários em `FinanceDomainTest`.

## 3. Decisões ratificadas e hardening

- Idempotência de desconto/acréscimo: **mantida**.
- Data efetiva nunca futura, "hoje" em `America/Sao_Paulo`: **mantida**.
- Recebível de valor zero: **mantido** — nasce `QUITADO`, com as linhas aprovadas, sem recebimento
  artificial, fora do realizado e do previsto. Teste:
  `approvedZeroTotalCreatesASettledReceivableWithoutArtificialReceiptOrCashFlow`.
- **Vencimento idempotente**: `Idempotency-Key` obrigatório; `V19` acrescenta
  `receivable_due_date_change.idempotency_key NOT NULL UNIQUE` (linhas anteriores recebem chave técnica
  `legacy-<id>`, sem alterar o conteúdo do histórico). Retry devolve o resultado aplicado
  (`Idempotent-Replay: true`); mesma chave com outra data → `409 IDEMPOTENCY_KEY_REUSED`. Teste:
  `dueDateChangeRetryReturnsTheAppliedResultAndReusedKeyIsRefused`.

`V17` e `V18` não foram alteradas.

---

## 4. Varredura AG-15 depois das correções

| Ponto | Resultado |
| --- | --- |
| Fronteiras Modulith | `ModularityTest` verde; `quote` usa só o contrato público da OS; `finance` só os contratos de `quote`, `workorder`, `crm` e `iam` |
| Ordem de bloqueio | finalização: OS → orçamentos; apresentação/decisão: OS → orçamento. Sem inversão |
| Contexto de persistência | a finalização não carrega orçamento antes do `FOR UPDATE`, então a releitura não vem do cache do JPA. Documentado em `QuoteBillingService`. **Observação (MEDIUM, não bloqueante):** código futuro que ler orçamento na mesma transação antes da finalização precisa preservar essa ordem |
| Apresentar orçamento de OS já finalizada | continua permitido (comportamento anterior à Task); não altera o recebível, que já foi congelado. **Observação (LOW)** para a Task de fluxo da OS |
| Leitura dupla do orçamento na decisão pública | uma consulta extra para obter a OS antes do bloqueio. **LOW**, aceito |
| Dinheiro em ponto flutuante | `FinanceMonetaryArchitectureTest` e `QuoteMonetaryArchitectureTest` verdes |
| HIGH/CRITICAL remanescente no código | nenhum encontrado. A única pendência de severidade alta é **de verificação**: os testes PostgreSQL ainda não rodaram (seção 5) |

---

## 5. Gates

| Gate | Resultado |
| --- | --- |
| `mvn compile`, `mvn test-compile` | verdes |
| `FinanceDomainTest` (17), `QuoteBillingServiceTest` (7), `FinanceMonetaryArchitectureTest`, `ModularityTest` | verdes, sem Docker |
| `npm test -- --run` | 12 arquivos, 110 testes verdes |
| `npx tsc --noEmit`, `npm run build`, `npm run lint` | limpos (aviso de chunk > 500 kB preexistente) |
| `git diff --check` | limpo |
| **Testes de integração PostgreSQL e suíte backend completa** | **não executados no checkpoint** — o Docker Desktop 4.48.0 não iniciou o engine nesta sessão |

Sobre o Docker: o log (`%LOCALAPPDATA%\Docker\log\host\com.docker.backend.exe.log`) mostra
`monitor exited: exit status 150` e diálogos de erro na abertura; às 16:04 UTC (13:04 no horário local)
uma ação **"Reset to factory defaults"** foi disparada a partir do diálogo da interface — não por esta
sessão. Uma reinicialização limpa (processos encerrados, `wsl --shutdown`) repetiu o erro. Não foi feito
nenhum clique em diálogo do Docker. Conforme a instrução do Owner, isto é **"não executado no
checkpoint"**, não falha funcional — e também não é evidência de que os testes passam.

---

## 6. Critérios de encerramento

| Critério | Situação |
| --- | --- |
| 1. Findings F1–F4 corrigidos | feito (F1–F3 confirmados pela revisão externa; F4 aguarda o checkpoint) |
| 2. DR-0017 `DECIDED` e implementada | feito |
| 3. Testes concorrentes PostgreSQL verdes | **pendente** — não executados |
| 4. Suíte backend completa verde | **pendente** — não executada |
| 5. Frontend verde | feito |
| 6. AG-15 sem HIGH/CRITICAL remanescente | feito no código; verificação depende de 3 e 4 |

**A TASK-0015 permanece em `REVIEW`.** Não pode ir para `DONE` antes dos itens 3 e 4.
