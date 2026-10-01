package br.com.uberhidraulica.erp.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Sessão de custódia física de dinheiro (TASK-0016 / DR-0018). */
public record CashSession(
        UUID id,
        Status status,
        ConferenceStatus conferenceStatus,
        Instant openedAt,
        UUID openedBy,
        BigDecimal openingExpectedBalance,
        BigDecimal openingCountedBalance,
        String openingDifferenceReason,
        Instant closedAt,
        UUID closedBy,
        BigDecimal closingExpectedBalance,
        BigDecimal closingCountedBalance,
        String closingDifferenceReason,
        Instant checkedAt,
        UUID checkedBy,
        BigDecimal checkedCountedBalance,
        String checkedDifferenceReason) {

    public enum Status { OPEN, CLOSED, AUTO_CLOSED }
    public enum ConferenceStatus { CHECKED, NOT_CHECKED }

    public CashSession {
        if (id == null || status == null || conferenceStatus == null || openedAt == null || openedBy == null)
            throw new FinanceException("INVALID_CASH_SESSION", "Dados obrigatórios da sessão de caixa ausentes");
        openingExpectedBalance = money(openingExpectedBalance, "Saldo esperado de abertura");
        openingCountedBalance = money(openingCountedBalance, "Saldo contado de abertura");
    }

    public boolean open() { return status == Status.OPEN; }

    public boolean autoClosedPendingCheck() {
        return status == Status.AUTO_CLOSED && conferenceStatus == ConferenceStatus.NOT_CHECKED;
    }

    public BigDecimal openingDifference() {
        return openingCountedBalance.subtract(openingExpectedBalance);
    }

    private static BigDecimal money(BigDecimal value, String field) {
        if (value == null || value.signum() < 0)
            throw new FinanceException("INVALID_CASH_SESSION", field + " deve ser maior ou igual a zero");
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
