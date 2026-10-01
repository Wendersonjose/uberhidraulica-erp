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

    record SettlementResponse(UUID id, UUID ownerId, BigDecimal amount, UUID paymentMethodId, String paymentMethodName,
                              LocalDate effectiveOn, String notes, Instant recordedAt, UUID recordedBy, boolean reversed,
                              ReversalResponse reversal) {
        static SettlementResponse from(Settlement s) {
            return new SettlementResponse(s.id(), s.ownerId(), s.amount(), s.paymentMethodId(), s.paymentMethodName(), s.effectiveOn(),
                    s.notes(), s.recordedAt(), s.recordedBy(), s.reversed(), ReversalResponse.from(s.reversal()));
        }
    }

    record ReceivableResponse(UUID id, UUID workOrderId, long workOrderNumber, UUID customerId, String customerName, UUID billingQuoteId,
                              BigDecimal originalAmount, BigDecimal discountAmount, BigDecimal surchargeAmount, BigDecimal adjustedAmount,
                              BigDecimal receivedAmount, BigDecimal outstandingBalance, LocalDate issuedOn, LocalDate dueDate,
                              FinancialStatus status, Instant createdAt, Instant cancelledAt, String cancellationReason,
                              List<Receivable.Line> lines, List<AdjustmentResponse> adjustments,
                              List<DueDateChangeResponse> dueDateChanges, List<SettlementResponse> receipts) {
        static ReceivableResponse from(Receivable r, String customerName, LocalDate today) {
            return new ReceivableResponse(r.id(), r.workOrderId(), r.workOrderNumber(), r.customerId(), customerName, r.billingQuoteId(),
                    r.originalAmount(), r.discountAmount(), r.surchargeAmount(), r.adjustedAmount(), r.receivedAmount(),
                    r.outstandingBalance(), r.issuedOn(), r.dueDate(), r.status(today), r.createdAt(), r.cancelledAt(),
                    r.cancellationReason(), r.lines(), r.adjustments().stream().map(AdjustmentResponse::from).toList(),
                    r.dueDateChanges().stream().map(DueDateChangeResponse::from).toList(),
                    r.receipts().stream().map(SettlementResponse::from).toList());
        }
    }

    /** A chave de idempotência não é devolvida: ela é do cliente que fez o pedido. */
    record AdjustmentResponse(UUID id, Receivable.AdjustmentType type, BigDecimal amount, String reason, Instant recordedAt, UUID recordedBy,
                              boolean reversed, ReversalResponse reversal) {
        static AdjustmentResponse from(Receivable.Adjustment a) {
            return new AdjustmentResponse(a.id(), a.type(), a.amount(), a.reason(), a.recordedAt(), a.recordedBy(), !a.active(),
                    ReversalResponse.from(a.reversal()));
        }
    }

    record DueDateChangeResponse(UUID id, LocalDate previousDueDate, LocalDate newDueDate, String reason, Instant changedAt, UUID changedBy) {
        static DueDateChangeResponse from(Receivable.DueDateChange c) {
            return new DueDateChangeResponse(c.id(), c.previousDueDate(), c.newDueDate(), c.reason(), c.changedAt(), c.changedBy());
        }
    }

    record ReceivableSummaryResponse(UUID id, UUID workOrderId, long workOrderNumber, UUID customerId, String customerName,
                                     BigDecimal originalAmount, BigDecimal discountAmount, BigDecimal surchargeAmount,
                                     BigDecimal receivedAmount, BigDecimal outstandingBalance, LocalDate issuedOn, LocalDate dueDate,
                                     FinancialStatus status) {
        static ReceivableSummaryResponse from(FinanceRepositoryPort.ReceivableSummary s, String customerName) {
            return new ReceivableSummaryResponse(s.id(), s.workOrderId(), s.workOrderNumber(), s.customerId(), customerName,
                    s.originalAmount(), s.discountAmount(), s.surchargeAmount(), s.receivedAmount(), s.outstandingBalance(),
                    s.issuedOn(), s.dueDate(), s.status());
        }
    }

    record PayableResponse(UUID id, String description, String supplier, UUID categoryId, String categoryName, BigDecimal amount,
                           BigDecimal paidAmount, BigDecimal outstandingBalance, LocalDate dueDate, FinancialStatus status, String notes,
                           Instant createdAt, Instant cancelledAt, String cancellationReason, List<SettlementResponse> payments) {
        static PayableResponse from(Payable p, LocalDate today) {
            return new PayableResponse(p.id(), p.description(), p.supplier(), p.categoryId(), p.categoryName(), p.amount(), p.paidAmount(),
                    p.outstandingBalance(), p.dueDate(), p.status(today), p.notes(), p.createdAt(), p.cancelledAt(), p.cancellationReason(),
                    p.payments().stream().map(SettlementResponse::from).toList());
        }
    }

    record BillingCandidateResponse(UUID quoteId, BigDecimal approvedTotal, List<QuoteBillingQuery.BillingLine> lines) {
        static BillingCandidateResponse from(QuoteBillingQuery.BillingCandidate c) {
            return new BillingCandidateResponse(c.quoteId(), c.approvedTotal(), c.lines());
        }
    }

    record PageResponse<T>(List<T> items, long totalItems, int page, int size, int totalPages) {}
}
