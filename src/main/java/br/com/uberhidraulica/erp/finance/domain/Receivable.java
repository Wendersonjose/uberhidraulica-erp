package br.com.uberhidraulica.erp.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Conta a receber principal de uma OS (DR-0015).
 *
 * <p>{@code originalAmount} é congelado na geração a partir das linhas aprovadas do orçamento de
 * faturamento e conferido contra elas ao carregar. Desconto, acréscimo, total recebido e saldo são
 * sempre derivados dos lançamentos — nunca gravados, nunca sobrescrevem o original.</p>
 */
public record Receivable(UUID id, UUID workOrderId, long workOrderNumber, UUID customerId, UUID billingQuoteId,
                         BigDecimal originalAmount, LocalDate issuedOn, LocalDate dueDate, Instant createdAt, UUID createdBy,
                         Instant cancelledAt, UUID cancelledBy, String cancellationReason,
                         List<Line> lines, List<Adjustment> adjustments, List<DueDateChange> dueDateChanges,
                         List<Settlement> receipts) {

    public enum AdjustmentType { DISCOUNT, SURCHARGE }

    /** Linha aprovada do orçamento, copiada no instante da geração. */
    public record Line(UUID id, UUID quoteItemRevisionId, String description, BigDecimal quantity, BigDecimal unitPrice,
                       BigDecimal discountAmount, BigDecimal totalAmount, int displayOrder) {}

    /**
     * Desconto ou acréscimo financeiro manual; imutável. Correção é estorno total próprio (DR-0017, opção A):
     * o ajuste estornado deixa de compor descontos ou acréscimos, e o histórico mostra os dois registros.
     */
    public record Adjustment(UUID id, AdjustmentType type, BigDecimal amount, String reason, Instant recordedAt, UUID recordedBy,
                             String idempotencyKey, Settlement.Reversal reversal) {
        public boolean active() { return reversal == null; }

        public Adjustment {
            if (id == null || type == null || recordedAt == null || recordedBy == null || idempotencyKey == null)
                throw new FinanceException("INVALID_FINANCE_ENTRY", "Dados obrigatórios do ajuste ausentes");
            amount = Money.positive(amount, "Valor do ajuste");
            reason = Money.requiredText(reason, "Motivo do ajuste", 500);
        }
    }

    public record DueDateChange(UUID id, LocalDate previousDueDate, LocalDate newDueDate, String reason, Instant changedAt, UUID changedBy,
                                String idempotencyKey) {}

    public Receivable {
        if (id == null || workOrderId == null || customerId == null || billingQuoteId == null || issuedOn == null
                || dueDate == null || createdAt == null)
            throw new FinanceException("INVALID_RECEIVABLE", "Dados obrigatórios do recebível ausentes");
        lines = List.copyOf(lines == null ? List.of() : lines).stream().sorted(Comparator.comparingInt(Line::displayOrder)).toList();
        adjustments = List.copyOf(adjustments == null ? List.of() : adjustments);
        dueDateChanges = List.copyOf(dueDateChanges == null ? List.of() : dueDateChanges);
        receipts = List.copyOf(receipts == null ? List.of() : receipts);
        if (lines.isEmpty()) throw new FinanceException("INVALID_RECEIVABLE", "Recebível sem linha aprovada");
        if (originalAmount == null || originalAmount.signum() < 0)
            throw new FinanceException("INVALID_RECEIVABLE", "Valor original inválido");
        // O valor congelado é conferido, não recalculado: divergência fica visível em vez de corrigida em silêncio.
        if (Money.sum(lines.stream().map(Line::totalAmount).toList()).compareTo(originalAmount) != 0)
            throw new FinanceException("RECEIVABLE_TOTAL_MISMATCH", "Valor original diverge das linhas aprovadas");
        originalAmount = originalAmount.setScale(Money.SCALE, java.math.RoundingMode.UNNECESSARY);
    }

    // ------------------------------------------------------------------ derivados

    public BigDecimal discountAmount() { return adjustmentTotal(AdjustmentType.DISCOUNT); }

    public BigDecimal surchargeAmount() { return adjustmentTotal(AdjustmentType.SURCHARGE); }

    public BigDecimal adjustedAmount() { return originalAmount.subtract(discountAmount()).add(surchargeAmount()); }

    public BigDecimal receivedAmount() {
        return Money.sum(receipts.stream().filter(Settlement::active).map(Settlement::amount).toList());
    }

    public BigDecimal outstandingBalance() { return adjustedAmount().subtract(receivedAmount()); }

    public boolean cancelled() { return cancelledAt != null; }

    public FinancialStatus status(LocalDate today) {
        return FinancialStatus.derive(cancelled(), receivedAmount(), outstandingBalance(), dueDate, today);
    }

    private BigDecimal adjustmentTotal(AdjustmentType type) {
        return Money.sum(adjustments.stream().filter(adjustment -> adjustment.active() && adjustment.type() == type)
                .map(Adjustment::amount).toList());
    }

    // ------------------------------------------------------------------ regras

    /** DR-0015, F-05: parcial permitido; acima do saldo, recusado — sem troco, crédito ou saldo avulso. */
    public void checkReceipt(BigDecimal amount) {
        requireNotCancelled();
        if (amount.compareTo(outstandingBalance()) > 0)
            throw new FinanceException("AMOUNT_EXCEEDS_BALANCE", "Valor maior que o saldo em aberto de " + outstandingBalance().toPlainString());
    }

    /** DR-0015, F-06: desconto limitado ao saldo, para que valor ajustado e saldo nunca fiquem negativos. */
    public void checkAdjustment(AdjustmentType type, BigDecimal amount) {
        requireNotCancelled();
        if (type == AdjustmentType.DISCOUNT && amount.compareTo(outstandingBalance()) > 0)
            throw new FinanceException("AMOUNT_EXCEEDS_BALANCE", "Desconto maior que o saldo em aberto de " + outstandingBalance().toPlainString());
    }

    /**
     * DR-0017: estorno total e único do ajuste. Estornar desconto só aumenta o saldo. Estornar acréscimo reduz o
     * valor ajustado, e é recusado se o recebido passaria a exceder o novo valor: nada de saldo negativo ou crédito.
     */
    public void checkAdjustmentReversal(UUID adjustmentId) {
        requireNotCancelled();
        Adjustment adjustment = adjustments.stream().filter(candidate -> candidate.id().equals(adjustmentId)).findFirst()
                .orElseThrow(() -> new FinanceException("ADJUSTMENT_NOT_FOUND", "Ajuste não encontrado"));
        if (!adjustment.active()) throw new FinanceException("ADJUSTMENT_ALREADY_REVERSED", "Este ajuste já foi estornado");
        if (adjustment.type() == AdjustmentType.SURCHARGE
                && receivedAmount().compareTo(adjustedAmount().subtract(adjustment.amount())) > 0)
            throw new FinanceException("ADJUSTMENT_REVERSAL_EXCEEDS_RECEIVED",
                    "Estornar este acréscimo deixaria o recebido acima do valor devido; estorne antes os recebimentos excedentes");
    }

    /** DR-0015, F-04: ajuste individual de vencimento só enquanto houver saldo em aberto. */
    public void checkDueDateChange(LocalDate newDueDate) {
        requireNotCancelled();
        if (outstandingBalance().signum() == 0)
            throw new FinanceException("RECEIVABLE_NOT_OPEN", "Recebível quitado não tem vencimento a alterar");
        if (newDueDate == null) throw new FinanceException("INVALID_FINANCE_ENTRY", "Novo vencimento é obrigatório");
        if (newDueDate.equals(dueDate)) throw new FinanceException("INVALID_FINANCE_ENTRY", "O vencimento informado já é o atual");
    }

    /** DR-0015, F-08: dinheiro recebido não some por mudança de status da OS. */
    public void checkCancellation() {
        requireNotCancelled();
        if (receipts.stream().anyMatch(Settlement::active))
            throw new FinanceException("RECEIVABLE_HAS_RECEIPTS",
                    "O recebível da OS tem recebimentos; estorne-os antes de cancelar a OS");
    }

    private void requireNotCancelled() {
        if (cancelled()) throw new FinanceException("RECEIVABLE_CANCELLED", "Recebível cancelado não aceita lançamentos");
    }
}
