package br.com.uberhidraulica.erp.purchasing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Compra (`TASK-0021` I3). O {@code status} é o ciclo <b>comercial</b>; a situação de recebimento e a
 * financeira não moram aqui.
 *
 * <p>Não existe campo "paga": a situação financeira é lida do Financeiro, parcela por parcela. E não existe
 * campo de recebimento: ele é derivado de Σ recebido contra Σ pedido. Guardar qualquer um dos dois criaria
 * duas verdades sobre o mesmo fato — exatamente o que a arquitetura do projeto evita.</p>
 */
public record Purchase(UUID id, long number, UUID supplierId, Origin origin, Status status,
                       BigDecimal goodsTotal, BigDecimal discountTotal, BigDecimal freightTotal,
                       BigDecimal insuranceTotal, BigDecimal otherTotal, BigDecimal taxAddedTotal,
                       BigDecimal payableTotal, UUID expenseCategoryId, String commercialSnapshot,
                       UUID workOrderId, String notes, String idempotencyKey, Instant createdAt, UUID createdBy,
                       Instant cancelledAt, UUID cancelledBy, String cancellationReason, long version,
                       Instant updatedAt, UUID updatedBy, Instant approvedAt, UUID approvedBy,
                       List<PurchaseItem> items) {

    /** De onde a compra nasceu. A nota importada nasce {@code APPROVED}: ela já é o fato comercial. */
    public enum Origin { NFE_IMPORT, DIRECT, ORDER }

    public enum Status { DRAFT, AWAITING_APPROVAL, APPROVED, CANCELLED }

    public Purchase {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean cancelled() {
        return status == Status.CANCELLED;
    }

    /** Pedido e compra direta passam pelo fluxo de aprovação; a nota importada nasce aprovada. */
    public boolean needsApproval() {
        return origin != Origin.NFE_IMPORT;
    }

    /**
     * Recusa cancelar o que já foi cancelado. Cancelar com recebimento ativo é barrado no caso de uso, que é
     * quem sabe dos recebimentos (`D5`: anular exige compra cancelada e sem recebimento ativo).
     */
    public void checkCancellation() {
        if (cancelled()) {
            throw new PurchasingException("PURCHASE_ALREADY_CANCELLED", "Compra já está cancelada");
        }
    }

    /**
     * Confere que o total a pagar fecha com a composição dos totais da nota. Falhar aqui significa que a
     * compra gravaria obrigação diferente do documento — é o que a `D4` manda recusar.
     */
    public void requireTotalsClose() {
        BigDecimal expected = PurchaseMoney.sumCharged(List.of(goodsTotal, freightTotal, insuranceTotal,
                otherTotal, taxAddedTotal)).subtract(PurchaseMoney.charged(discountTotal));
        if (PurchaseMoney.charged(payableTotal).compareTo(PurchaseMoney.charged(expected)) != 0) {
            throw new PurchasingException("PURCHASE_TOTALS_DO_NOT_CLOSE",
                    "Total a pagar (" + PurchaseMoney.charged(payableTotal) + ") difere da composição da nota ("
                            + PurchaseMoney.charged(expected) + ")");
        }
    }
}
