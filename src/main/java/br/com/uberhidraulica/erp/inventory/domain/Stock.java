package br.com.uberhidraulica.erp.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/** Saldo físico e custo médio ponderado de um produto (DR-0014). */
public record Stock(UUID productId, BigDecimal quantity, BigDecimal averageCost, Instant updatedAt) {
    /** Escala da quantidade, alinhada ao catálogo. */
    public static final int QUANTITY_SCALE = 3;
    /** Escala do custo interno, conforme a DR-0007. */
    public static final int COST_SCALE = 4;

    public Stock {
        if (productId == null) throw new InventoryException("INVALID_STOCK", "Produto é obrigatório");
        quantity = quantity == null ? BigDecimal.ZERO.setScale(QUANTITY_SCALE) : quantity.setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
        if (quantity.signum() < 0) throw new InventoryException("INVALID_STOCK", "Saldo não pode ser negativo");
    }

    public static Stock empty(UUID productId, Instant now) { return new Stock(productId, BigDecimal.ZERO, null, now); }

    /**
     * Entrada com custo recalcula o custo médio ponderado; entrada sem custo preserva o médio atual,
     * porque um custo desconhecido não é um custo zero.
     */
    public Stock add(BigDecimal amount, BigDecimal unitCost, Instant now) {
        BigDecimal newQuantity = quantity.add(amount);
        if (unitCost == null) return new Stock(productId, newQuantity, averageCost, now);
        BigDecimal currentValue = (averageCost == null ? BigDecimal.ZERO : averageCost).multiply(quantity);
        BigDecimal incoming = unitCost.multiply(amount);
        BigDecimal newAverage = newQuantity.signum() == 0 ? unitCost
                : currentValue.add(incoming).divide(newQuantity, COST_SCALE, RoundingMode.HALF_UP);
        return new Stock(productId, newQuantity, newAverage, now);
    }

    /** Saída não altera o custo médio; sem saldo suficiente, nada é movimentado. */
    public Stock remove(BigDecimal amount, Instant now) {
        if (quantity.compareTo(amount) < 0)
            throw new InventoryException("INSUFFICIENT_STOCK",
                    "Saldo insuficiente: disponível " + quantity.stripTrailingZeros().toPlainString());
        return new Stock(productId, quantity.subtract(amount), averageCost, now);
    }
}
