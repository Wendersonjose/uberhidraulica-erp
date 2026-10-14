package br.com.uberhidraulica.erp.purchasing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Recebimento físico de uma compra (`TASK-0021` I4): o que efetivamente chegou, quando e quem conferiu.
 *
 * <p>É fato imutável. Corrigir é estornar (registro próprio e único, {@link Reversal}), nunca alterar. Pedido,
 * recebimento e pagamento são fatos diferentes: este registro nunca marca nada como pago e só o recebimento
 * confirmado movimenta o estoque (`AG-05` §130).</p>
 */
public record GoodsReceipt(UUID id, long number, UUID purchaseId, UUID documentId, LocalDate receivedOn, String notes,
                           String idempotencyKey, String requestHash, UUID receivedBy, Instant createdAt,
                           List<Item> items, Reversal reversal) {

    public GoodsReceipt {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Divergência de quantidade. Preço e item divergentes não têm regra aprovada e ficam fora desta Task. */
    public enum Divergence { NONE, OVER, SHORT_CLOSED }

    /**
     * Linha recebida. {@code expectedQuantity} é o pendente antes deste recebimento; {@code cancelledQuantity} é o
     * que foi encerrado sem chegar. {@code unitCost} é o custo de aquisição do item da compra, congelado aqui.
     */
    public record Item(UUID id, UUID receiptId, UUID purchaseItemId, UUID productId, BigDecimal quantityReceived,
                       BigDecimal unitCost, BigDecimal expectedQuantity, Divergence divergence,
                       BigDecimal cancelledQuantity, String justification, UUID acceptedBy, UUID stockMovementId) {

        public Item {
            PurchaseMoney.requirePositive(quantityReceived, "Quantidade recebida");
            if (divergence != Divergence.NONE
                    && (justification == null || justification.isBlank() || acceptedBy == null)) {
                throw new PurchasingException("PURCHASE_DIVERGENCE_JUSTIFICATION_REQUIRED",
                        "Divergência exige justificativa e autorização");
            }
        }
    }

    public record Reversal(UUID id, UUID receiptId, String reason, Instant reversedAt, UUID reversedBy,
                           String idempotencyKey) {}

    public boolean reversed() {
        return reversal != null;
    }

    /** Situação de recebimento da compra, sempre derivada de Σ recebido ativo contra Σ pedido. */
    public enum Status { NONE, PARTIAL, COMPLETE }
}
