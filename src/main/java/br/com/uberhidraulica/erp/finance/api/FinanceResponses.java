package br.com.uberhidraulica.erp.finance.api;

import br.com.uberhidraulica.erp.finance.domain.FinancialStatus;
import br.com.uberhidraulica.erp.finance.domain.Payable;
import br.com.uberhidraulica.erp.finance.domain.Receivable;
import br.com.uberhidraulica.erp.finance.domain.Settlement;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort;
import br.com.uberhidraulica.erp.quote.QuoteBillingQuery;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Representações da API do Financeiro. Todo valor derivado é calculado a partir dos lançamentos. */
final class FinanceResponses {
    private FinanceResponses() {}

    record ReversalResponse(UUID id, String reason, Instant reversedAt, UUID reversedBy) {
        static ReversalResponse from(Settlement.Reversal reversal) {
            return reversal == null ? null
                    : new ReversalResponse(reversal.id(), reversal.reason(), reversal.reversedAt(), reversal.reversedBy());
        }
    }

    /** {@code cardFee} é nulo quando a forma não tem taxa; {@code netAmount} é o bruto menos a taxa. */
    record SettlementResponse(UUID id, UUID ownerId, BigDecimal amount, UUID paymentMethodId, String paymentMethodName,
                              LocalDate effectiveOn, String notes, Instant recordedAt, UUID recordedBy, boolean reversed,
                              ReversalResponse reversal, Settlement.CardFee cardFee, BigDecimal netAmount) {
        static SettlementResponse from(Settlement s) {
            return new SettlementResponse(s.id(), s.ownerId(), s.amount(), s.paymentMethodId(), s.paymentMethodName(), s.effectiveOn(),
                    s.notes(), s.recordedAt(), s.recordedBy(), s.reversed(), ReversalResponse.from(s.reversal()), s.cardFee(), s.netAmount());
        }
    }

    /**
     * {@code dueDate} é o vencimento corrente (primeira parcela com saldo). {@code workOrderId}, {@code workOrderNumber} e
     * {@code billingQuoteId} são nulos no recebível avulso, que traz descrição e categoria de receita.
     */
    record ReceivableResponse(UUID id, Receivable.Origin origin, UUID workOrderId, Long workOrderNumber, UUID customerId, String customerName,
                              UUID billingQuoteId, String description, UUID revenueCategoryId, String revenueCategoryName, String notes,
                              BigDecimal originalAmount, BigDecimal discountAmount, BigDecimal surchargeAmount, BigDecimal adjustedAmount,
                              BigDecimal receivedAmount, BigDecimal outstandingBalance, BigDecimal principalOutstanding,
                              BigDecimal surchargeOutstanding, LocalDate issuedOn, LocalDate dueDate,
                              FinancialStatus status, Instant createdAt, Instant cancelledAt, String cancellationReason,
                              List<Receivable.Line> lines, InstallmentPlanResponse installmentPlan, List<AdjustmentResponse> adjustments,
                              List<DueDateChangeResponse> dueDateChanges, List<SettlementResponse> receipts) {
        static ReceivableResponse from(Receivable r, String customerName, LocalDate today) {
            return new ReceivableResponse(r.id(), r.origin(), r.workOrderId(), r.workOrderNumber(), r.customerId(), customerName,
                    r.billingQuoteId(), r.description(), r.revenueCategoryId(), r.revenueCategoryName(), r.notes(),
                    r.originalAmount(), r.discountAmount(), r.surchargeAmount(), r.adjustedAmount(), r.receivedAmount(),
                    r.outstandingBalance(), r.principalOutstanding(), r.surchargeOutstanding(), r.issuedOn(), r.currentDueDate(),
                    r.status(today), r.createdAt(), r.cancelledAt(),
                    r.cancellationReason(), r.lines(), InstallmentPlanResponse.from(r, today),
                    r.adjustments().stream().map(AdjustmentResponse::from).toList(),
                    r.dueDateChanges().stream().map(DueDateChangeResponse::from).toList(),
                    r.receipts().stream().map(SettlementResponse::from).toList());
        }
    }

    /** Plano corrente; versão 0 é o plano implícito de parcela única, que não está gravado. */
    record InstallmentPlanResponse(int version, String reason, Instant createdAt, boolean editable, List<InstallmentResponse> installments) {
        static InstallmentPlanResponse from(Receivable r, LocalDate today) {
            boolean editable = !r.cancelled() && r.receipts().stream().noneMatch(Settlement::active);
            return new InstallmentPlanResponse(r.plan().version(), r.plan().reason(), r.plan().implicit() ? null : r.plan().createdAt(),
                    editable, r.installmentPositions().stream().map(p -> new InstallmentResponse(p.number(), p.amount(), p.dueDate(),
                    p.openPrincipal(), p.open() ? (p.dueDate().isBefore(today) ? FinancialStatus.VENCIDO
                    : p.openPrincipal().compareTo(p.amount()) < 0 ? FinancialStatus.PARCIAL : FinancialStatus.ABERTO)
                    : r.cancelled() ? FinancialStatus.CANCELADO : FinancialStatus.QUITADO)).toList());
        }
    }

    record InstallmentResponse(int number, BigDecimal amount, LocalDate dueDate, BigDecimal openAmount, FinancialStatus status) {}

    /** A chave de idempotência não é devolvida: ela é do cliente que fez o pedido. */
    record AdjustmentResponse(UUID id, Receivable.AdjustmentType type, Receivable.AdjustmentOrigin origin, BigDecimal amount, String reason,
                              Instant recordedAt, UUID recordedBy, boolean reversed, ReversalResponse reversal) {
        static AdjustmentResponse from(Receivable.Adjustment a) {
            return new AdjustmentResponse(a.id(), a.type(), a.origin(), a.amount(), a.reason(), a.recordedAt(), a.recordedBy(), !a.active(),
                    ReversalResponse.from(a.reversal()));
        }
    }

    record DueDateChangeResponse(UUID id, Integer installmentNumber, LocalDate previousDueDate, LocalDate newDueDate, String reason,
                                 Instant changedAt, UUID changedBy) {
        static DueDateChangeResponse from(Receivable.DueDateChange c) {
            return new DueDateChangeResponse(c.id(), c.installmentNumber(), c.previousDueDate(), c.newDueDate(), c.reason(), c.changedAt(),
                    c.changedBy());
        }
    }

    record ReceivableSummaryResponse(UUID id, Receivable.Origin origin, UUID workOrderId, Long workOrderNumber, String description,
                                     UUID customerId, String customerName,
                                     BigDecimal originalAmount, BigDecimal discountAmount, BigDecimal surchargeAmount,
                                     BigDecimal receivedAmount, BigDecimal outstandingBalance, LocalDate issuedOn, LocalDate dueDate,
                                     FinancialStatus status, int installmentCount) {
        static ReceivableSummaryResponse from(FinanceRepositoryPort.ReceivableSummary s, String customerName) {
            return new ReceivableSummaryResponse(s.id(), s.origin(), s.workOrderId(), s.workOrderNumber(), s.description(), s.customerId(),
                    customerName, s.originalAmount(), s.discountAmount(), s.surchargeAmount(), s.receivedAmount(), s.outstandingBalance(),
                    s.issuedOn(), s.dueDate(), s.status(), s.installmentCount());
        }
    }

    record PayableResponse(UUID id, String description, String supplier, UUID supplierId, UUID categoryId, String categoryName,
                           BigDecimal amount, BigDecimal paidAmount, BigDecimal outstandingBalance, LocalDate dueDate, FinancialStatus status,
                           String notes, Instant createdAt, UUID createdBy, Instant cancelledAt, String cancellationReason,
                           List<SettlementResponse> payments, Payable.Origin origin, UUID groupId, Integer installmentNumber,
                           Payable.ApprovalStatus approvalStatus, Payable.Approval approval) {
        static PayableResponse from(Payable p, LocalDate today) {
            return new PayableResponse(p.id(), p.description(), p.supplier(), p.supplierId(), p.categoryId(), p.categoryName(), p.amount(),
                    p.paidAmount(), p.outstandingBalance(), p.dueDate(), p.status(today), p.notes(), p.createdAt(), p.createdBy(),
                    p.cancelledAt(), p.cancellationReason(), p.payments().stream().map(SettlementResponse::from).toList(), p.origin(),
                    p.groupId(), p.installmentNumber(), p.approvalStatus(), p.approval());
        }
    }

    record BillingCandidateResponse(UUID quoteId, BigDecimal approvedTotal, List<QuoteBillingQuery.BillingLine> lines) {
        static BillingCandidateResponse from(QuoteBillingQuery.BillingCandidate c) {
            return new BillingCandidateResponse(c.quoteId(), c.approvedTotal(), c.lines());
        }
    }

    record PageResponse<T>(List<T> items, long totalItems, int page, int size, int totalPages) {}
}
