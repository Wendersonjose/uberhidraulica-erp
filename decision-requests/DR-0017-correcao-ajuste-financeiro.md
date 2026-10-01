# DR-0017 — Correção de desconto ou acréscimo financeiro lançado por engano

- Tipo: `FINANCIAL`
- Status: `DECIDED`
- Task: `TASK-0015`
- Origem: `AG-06 — Financeiro`, na formalização da `DR-0015`
- Responsável pela decisão: proprietário do produto
- Criada em: `2026-09-22`
- Decidida em: `2026-09-22`, pelo Owner, na revisão externa da `TASK-0015`

## Problema

A `DR-0015` (F-06) permite desconto e acréscimo financeiros manuais no recebível, com valor, motivo,
usuário, instante e permissão `FINANCE_ADJUST`, e proíbe sobrescrever o valor original. A F-07 define
estorno **de recebimento e de pagamento**. Nenhuma das duas diz como corrigir um **ajuste** lançado
errado — por exemplo, desconto de R$ 50,00 digitado como R$ 500,00.

As saídas possíveis mudam o histórico financeiro de formas diferentes, por isso não é detalhe técnico.

## Opções

- **A — estorno do ajuste:** registro novo, ligado ao ajuste original, total, com motivo e permissão
  (mesmo desenho da F-07). O ajuste estornado deixa de compor o valor ajustado.
- **B — ajuste compensatório:** corrigir desconto errado lançando acréscimo do mesmo valor. Não exige
  estrutura nova, mas registra como "acréscimo" algo que foi correção, e distorce os totais de
  descontos e acréscimos concedidos.
- **C — ajuste é definitivo:** não há correção no MVP.

## Comportamento enquanto a DR estiver aberta

Nada foi inventado: o ajuste é **imutável e sem estorno**. Não existe endpoint de correção. Os
ajustes aparecem no histórico do recebível com autor, instante e motivo.

## Recomendação

Opção A, pela simetria com a F-07. A recomendação não é decisão.

## Pergunta final

Desconto ou acréscimo financeiro lançado por engano deve ser (A) estornado por registro próprio,
(B) compensado por ajuste inverso ou (C) mantido sem correção no MVP?

## Decisão final do Owner

- Data: `2026-09-22`
- Opção escolhida: **A — estorno próprio do ajuste**
- Decisão: `DECIDED`

Desconto ou acréscimo lançado incorretamente não é apagado, editado nem corrigido por um lançamento que
finja ser outro tipo de operação. A correção é um registro de estorno ligado ao ajuste original.

### Regras

1. Estorno sempre **total** daquele ajuste; o ajuste original permanece imutável.
2. Motivo obrigatório, instante do servidor, usuário responsável, `Idempotency-Key` obrigatório.
3. No máximo um estorno por ajuste, protegido no banco (`UNIQUE (adjustment_id)`).
4. Permissão `FINANCE_REVERSE`.
5. Ajuste estornado deixa de compor `discountAmount` ou `surchargeAmount`; o histórico mostra o ajuste
   e o seu estorno.
6. **Invariante de saldo.** Estornar desconto aumenta o saldo e é sempre seguro. Estornar acréscimo
   reduz o valor ajustado: se depois do estorno `receivedAmount > adjustedAmount`, o estorno é recusado
   (`409 ADJUSTMENT_REVERSAL_EXCEEDS_RECEIVED`). Não se cria saldo negativo nem crédito; o usuário estorna
   antes os recebimentos excedentes.

### Implementação

- `V19`: `finance.receivable_adjustment_reversal` com `UNIQUE (adjustment_id)` e `UNIQUE (idempotency_key)`.
- `POST /api/finance/adjustments/{adjustmentId}/reversal` com `Idempotency-Key` e `{reason}`.
- Bloqueio da linha do recebível antes da verificação; retries da mesma chave serializados por
  `pg_advisory_xact_lock`.
- Testes: `FinanceDomainTest` (derivados, invariante do acréscimo, estorno único) e
  `Task0015FinanceIntegrationTest` (retry, estorno concorrente, acréscimo acima do recebido, permissão,
  constraint da `V19`).
