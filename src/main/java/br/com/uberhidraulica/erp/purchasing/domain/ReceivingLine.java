package br.com.uberhidraulica.erp.purchasing.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Linha da conferência de recebimento: o que foi pedido, o que já chegou, o que foi encerrado sem chegar e o que
 * ainda falta. {@code pending} negativo marca recebimento acima do pedido (aceito com justificativa).
 */
public record ReceivingLine(UUID purchaseItemId, int lineNo, UUID productId, String description,
                            String unitOriginal, String productUnit, BigDecimal ordered, BigDecimal received,
                            BigDecimal closed, BigDecimal pending, BigDecimal unitCost) {

    /** Mesma linha com a unidade do produto no catálogo, que o banco de Compras não guarda. */
    public ReceivingLine withProductUnit(String unit) {
        return new ReceivingLine(purchaseItemId, lineNo, productId, description, unitOriginal, unit, ordered, received,
                closed, pending, unitCost);
    }

    /** Linha sem mais nada a esperar do fornecedor. */
    public boolean settled() {
        return pending.signum() <= 0;
    }

    /** Situação da compra: nenhuma linha recebida, ao menos uma linha ainda pendente, ou tudo resolvido. */
    public static GoodsReceipt.Status statusOf(List<ReceivingLine> lines) {
        boolean anyReceived = lines.stream().anyMatch(line -> line.received().signum() > 0);
        if (!anyReceived) return GoodsReceipt.Status.NONE;
        return lines.stream().allMatch(ReceivingLine::settled) ? GoodsReceipt.Status.COMPLETE : GoodsReceipt.Status.PARTIAL;
    }
}
