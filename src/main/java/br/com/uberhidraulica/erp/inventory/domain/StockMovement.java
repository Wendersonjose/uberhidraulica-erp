package br.com.uberhidraulica.erp.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Movimentação de estoque: fato consumado e imutável.
 *
 * <p>Correção não altera nem apaga uma movimentação — cria outra, de estorno, que referencia a
 * original (cartão "Entradas e ajustes de estoque").</p>
 */
public record StockMovement(UUID id, UUID productId, Type type, BigDecimal quantity, BigDecimal unitCost,
                            BigDecimal balanceAfter, BigDecimal averageCostAfter, String reason, Source source,
                            UUID workOrderId, UUID workOrderItemId, UUID reversesMovementId,
                            Instant occurredAt, UUID recordedBy, boolean incoming) {

    public enum Type {
        ENTRY(true), EXIT(false), ADJUSTMENT_IN(true), ADJUSTMENT_OUT(false),
        WORK_ORDER_OUT(false), WORK_ORDER_RETURN(true), REVERSAL(true);

        private final boolean incoming;

        Type(boolean incoming) { this.incoming = incoming; }

        public boolean incoming() { return incoming; }

        public boolean adjustment() { return this == ADJUSTMENT_IN || this == ADJUSTMENT_OUT; }
    }

    public enum Source { MANUAL, WORK_ORDER }

    public StockMovement {
        if (id == null || productId == null || type == null || source == null || occurredAt == null)
            throw invalid("Dados obrigatórios ausentes");
        quantity = positive(quantity);
        if (unitCost != null && (unitCost.signum() < 0 || unitCost.stripTrailingZeros().scale() > Stock.COST_SCALE))
            throw invalid("Custo unitário deve ser não negativo e possuir no máximo quatro casas decimais");
        reason = reason == null || reason.isBlank() ? null : reason.trim();
        if (reason != null && reason.length() > 500) throw invalid("Motivo excede 500 caracteres");
        if (type.adjustment() && reason == null) throw invalid("Ajuste exige motivo");
        if ((source == Source.WORK_ORDER) != (workOrderId != null)) throw invalid("Origem e Ordem de Serviço inconsistentes");
    }

    private static BigDecimal positive(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > Stock.QUANTITY_SCALE)
            throw invalid("Quantidade deve ser positiva e possuir no máximo três casas decimais");
        return value.setScale(Stock.QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }

    private static InventoryException invalid(String message) { return new InventoryException("INVALID_MOVEMENT", message); }

    /** Linha da consulta de estoque: produto do catálogo com o saldo do módulo. */
    public record StockLine(UUID productId, String description, String internalCode, String category, String unit,
                            BigDecimal minimumStock, BigDecimal salePrice, boolean active,
                            BigDecimal quantity, BigDecimal averageCost) {
        public boolean belowMinimum() { return minimumStock != null && quantity.compareTo(minimumStock) < 0; }
    }
}
