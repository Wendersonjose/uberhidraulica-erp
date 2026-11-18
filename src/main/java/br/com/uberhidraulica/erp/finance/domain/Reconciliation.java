package br.com.uberhidraulica.erp.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Conciliação manual (DR-0024, Q7): liga um recebimento ou pagamento a uma linha de extrato de uma conta financeira.
 *
 * <p>O valor esperado é o que de fato entra ou sai da conta: o líquido da taxa de cartão no recebimento e o valor do
 * pagamento. Diferença entre o extrato e o esperado é permitida, mas exige motivo. Desfazer é estorno, com motivo; o
 * registro original fica.</p>
 */
public record Reconciliation(UUID id, UUID accountId, EntryType entryType, UUID entryId, LocalDate statementDate, BigDecimal statementAmount,
                             String statementReference, BigDecimal expectedAmount, String differenceReason, Instant recordedAt, UUID recordedBy,
                             Instant reversedAt, UUID reversedBy, String reversalReason) {

    public enum EntryType { RECEIPT, PAYMENT }

    public Reconciliation {
        if (id == null || accountId == null || entryType == null || entryId == null || statementDate == null || expectedAmount == null
                || recordedAt == null || recordedBy == null)
            throw new FinanceException("INVALID_RECONCILIATION", "Dados obrigatórios da conciliação ausentes");
        statementAmount = Money.positive(statementAmount, "Valor do extrato");
        statementReference = Money.requiredText(statementReference, "Identificador do extrato", 100);
        differenceReason = Money.optionalText(differenceReason, "Motivo da diferença", 500);
        if (statementAmount.compareTo(expectedAmount) != 0 && differenceReason == null)
            throw new FinanceException("RECONCILIATION_DIFFERENCE_REASON_REQUIRED",
                    "O extrato difere do lançamento em " + statementAmount.subtract(expectedAmount).toPlainString() + "; informe o motivo");
    }

    public BigDecimal difference() { return statementAmount.subtract(expectedAmount); }

    public boolean active() { return reversedAt == null; }
}
