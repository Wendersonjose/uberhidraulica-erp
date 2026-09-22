package br.com.uberhidraulica.erp.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Conta a pagar (DR-0015, F-12): descrição, fornecedor em texto livre, categoria, valor e vencimento
 * obrigatório. Pagamentos seguem as mesmas regras dos recebimentos; cancelar preserva o histórico.
 */
public record Payable(UUID id, String description, String supplier, UUID categoryId, String categoryName, BigDecimal amount,
                      LocalDate dueDate, String notes, Instant createdAt, UUID createdBy, String idempotencyKey,
                      Instant cancelledAt, UUID cancelledBy, String cancellationReason, List<Settlement> payments) {

    public Payable {
        if (id == null || categoryId == null || createdAt == null || createdBy == null || idempotencyKey == null)
            throw new FinanceException("INVALID_PAYABLE", "Dados obrigatórios da conta a pagar ausentes");
        if (dueDate == null) throw new FinanceException("INVALID_FINANCE_ENTRY", "Vencimento é obrigatório");
        description = Money.requiredText(description, "Descrição", 200);
        supplier = Money.optionalText(supplier, "Fornecedor", 200);
        notes = Money.optionalText(notes, "Observação", 500);
        amount = Money.positive(amount, "Valor");
        payments = List.copyOf(payments == null ? List.of() : payments);
    }

    public BigDecimal paidAmount() { return Money.sum(payments.stream().filter(Settlement::active).map(Settlement::amount).toList()); }

    public BigDecimal outstandingBalance() { return amount.subtract(paidAmount()); }

    public boolean cancelled() { return cancelledAt != null; }

    public FinancialStatus status(LocalDate today) {
        return FinancialStatus.derive(cancelled(), paidAmount(), outstandingBalance(), dueDate, today);
    }

    /** Mesmo pedido de criação, para a resposta idempotente a um retry. */
    public boolean sameRequest(String otherDescription, UUID otherCategory, BigDecimal otherAmount, LocalDate otherDueDate) {
        return description.equals(Money.requiredText(otherDescription, "Descrição", 200)) && categoryId.equals(otherCategory)
                && amount.compareTo(otherAmount) == 0 && dueDate.equals(otherDueDate);
    }

    public void checkPayment(BigDecimal value) {
        requireNotCancelled();
        if (value.compareTo(outstandingBalance()) > 0)
            throw new FinanceException("AMOUNT_EXCEEDS_BALANCE", "Valor maior que o saldo em aberto de " + outstandingBalance().toPlainString());
    }

    /** Equivalente à F-08: conta com pagamento não estornado não é cancelada. */
    public void checkCancellation() {
        requireNotCancelled();
        if (payments.stream().anyMatch(Settlement::active))
            throw new FinanceException("PAYABLE_HAS_PAYMENTS", "A conta tem pagamentos; estorne-os antes de cancelar");
    }

    private void requireNotCancelled() {
        if (cancelled()) throw new FinanceException("PAYABLE_CANCELLED", "Conta a pagar cancelada não aceita lançamentos");
    }
}
