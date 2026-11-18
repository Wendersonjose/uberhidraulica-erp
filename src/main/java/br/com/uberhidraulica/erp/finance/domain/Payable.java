package br.com.uberhidraulica.erp.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Conta a pagar (DR-0015, F-12): descrição, fornecedor, categoria, valor e vencimento obrigatório. Pagamentos
 * seguem as mesmas regras dos recebimentos; cancelar preserva o histórico.
 *
 * <p>DR-0024: o fornecedor pode estar vinculado ao cadastro de Compras ({@code supplierId}), com o nome guardado
 * como snapshot (Q6); a conta pode ser uma parcela de um grupo (Q5); e, acima do limite configurado, só é paga
 * depois de aprovada por outro usuário (Q5).</p>
 */
public record Payable(UUID id, String description, String supplier, UUID categoryId, String categoryName, BigDecimal amount,
                      LocalDate dueDate, String notes, Instant createdAt, UUID createdBy, String idempotencyKey,
                      Instant cancelledAt, UUID cancelledBy, String cancellationReason, List<Settlement> payments,
                      UUID supplierId, Origin origin, UUID groupId, Integer installmentNumber, boolean approvalRequired, Approval approval) {

    public enum Origin { MANUAL, PURCHASE }

    public enum ApprovalStatus { NOT_REQUIRED, PENDING, APPROVED }

    public record Approval(UUID id, Instant approvedAt, UUID approvedBy, String notes) {}

    public Payable(UUID id, String description, String supplier, UUID categoryId, String categoryName, BigDecimal amount,
                   LocalDate dueDate, String notes, Instant createdAt, UUID createdBy, String idempotencyKey,
                   Instant cancelledAt, UUID cancelledBy, String cancellationReason, List<Settlement> payments) {
        this(id, description, supplier, categoryId, categoryName, amount, dueDate, notes, createdAt, createdBy, idempotencyKey,
                cancelledAt, cancelledBy, cancellationReason, payments, null, Origin.MANUAL, null, null, false, null);
    }

    public Payable {
        if (id == null || categoryId == null || createdAt == null || createdBy == null || idempotencyKey == null)
            throw new FinanceException("INVALID_PAYABLE", "Dados obrigatórios da conta a pagar ausentes");
        if (dueDate == null) throw new FinanceException("INVALID_FINANCE_ENTRY", "Vencimento é obrigatório");
        description = Money.requiredText(description, "Descrição", 200);
        supplier = Money.optionalText(supplier, "Fornecedor", 200);
        notes = Money.optionalText(notes, "Observação", 500);
        amount = Money.positive(amount, "Valor");
        payments = List.copyOf(payments == null ? List.of() : payments);
        if (origin == null) origin = Origin.MANUAL;
        if ((groupId == null) != (installmentNumber == null))
            throw new FinanceException("INVALID_PAYABLE", "Parcela de grupo exige grupo e número");
        if (approval != null && !approvalRequired) throw new FinanceException("INVALID_PAYABLE", "Aprovação de conta que não a exige");
    }

    public BigDecimal paidAmount() { return Money.sum(payments.stream().filter(Settlement::active).map(Settlement::amount).toList()); }

    public BigDecimal outstandingBalance() { return amount.subtract(paidAmount()); }

    public boolean cancelled() { return cancelledAt != null; }

    public FinancialStatus status(LocalDate today) {
        return FinancialStatus.derive(cancelled(), paidAmount(), outstandingBalance(), dueDate, today);
    }

    public ApprovalStatus approvalStatus() {
        return !approvalRequired ? ApprovalStatus.NOT_REQUIRED : approval == null ? ApprovalStatus.PENDING : ApprovalStatus.APPROVED;
    }

    /** Mesmo pedido de criação: todos os dados persistidos que o usuário informou, normalizados (revisão F4). */
    public boolean sameRequest(String otherDescription, String otherSupplier, UUID otherCategory, BigDecimal otherAmount,
                               LocalDate otherDueDate, String otherNotes) {
        return description.equals(Money.requiredText(otherDescription, "Descrição", 200))
                && java.util.Objects.equals(supplier, Money.optionalText(otherSupplier, "Fornecedor", 200))
                && categoryId.equals(otherCategory) && amount.compareTo(otherAmount) == 0 && dueDate.equals(otherDueDate)
                && java.util.Objects.equals(notes, Money.optionalText(otherNotes, "Observação", 500));
    }

    /** Pagamento: conta aberta, dentro do saldo e, se exigida, já aprovada (DR-0024, Q5). */
    public void checkPayment(BigDecimal value) {
        requireNotCancelled();
        if (approvalStatus() == ApprovalStatus.PENDING)
            throw new FinanceException("PAYABLE_APPROVAL_REQUIRED", "Esta conta precisa ser aprovada antes do pagamento");
        if (value.compareTo(outstandingBalance()) > 0)
            throw new FinanceException("AMOUNT_EXCEEDS_BALANCE", "Valor maior que o saldo em aberto de " + outstandingBalance().toPlainString());
    }

    /**
     * Aprovação segregada (DR-0024, Q5, no padrão da aprovação de pedido de Compras, V25): quem lançou não aprova, salvo
     * com a permissão de exceção, que no seed é só do Dono.
     */
    public void checkApproval(UUID approver, boolean mayApproveOwn) {
        requireNotCancelled();
        if (!approvalRequired) throw new FinanceException("PAYABLE_APPROVAL_NOT_REQUIRED", "Esta conta não exige aprovação");
        if (approval != null) throw new FinanceException("PAYABLE_ALREADY_APPROVED", "Esta conta já foi aprovada");
        if (approver.equals(createdBy) && !mayApproveOwn)
            throw new FinanceException("PAYABLE_APPROVAL_SEGREGATION", "Quem lançou a conta não pode aprová-la; peça a outro usuário");
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
