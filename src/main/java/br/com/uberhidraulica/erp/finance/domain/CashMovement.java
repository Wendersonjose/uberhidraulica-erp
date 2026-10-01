package br.com.uberhidraulica.erp.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Movimento físico de caixa append-only. */
public record CashMovement(
        UUID id,
        UUID cashSessionId,
        Type type,
        Direction direction,
        BigDecimal amount,
        String reason,
        UUID receiptId,
        UUID payablePaymentId,
        UUID reversedMovementId,
        Instant recordedAt,
        UUID recordedBy,
        String idempotencyKey) {

    public enum Type { RECEIPT, CHANGE, PAYABLE_PAYMENT, SUPPLY, WITHDRAWAL, ADJUSTMENT, REVERSAL }
    public enum Direction { IN, OUT }

    public CashMovement {
        if (id == null || cashSessionId == null || type == null || direction == null || recordedAt == null || recordedBy == null)
            throw new FinanceException("INVALID_CASH_MOVEMENT", "Dados obrigatórios do movimento de caixa ausentes");
        if (amount == null || amount.signum() <= 0)
            throw new FinanceException("INVALID_CASH_MOVEMENT", "Valor do movimento deve ser maior que zero");
        amount = amount.setScale(2, java.math.RoundingMode.HALF_UP);
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new FinanceException("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key é obrigatório");
    }

    public BigDecimal signedAmount() {
        return direction == Direction.IN ? amount : amount.negate();
    }
}
