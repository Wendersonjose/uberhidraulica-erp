package br.com.uberhidraulica.erp.productcatalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Produto físico do catálogo: peça, fluido, insumo, componente ou kit.
 *
 * <p>O catálogo descreve o item e sua referência comercial. Saldo físico, reservado, disponível,
 * movimentação e custo médio pertencem ao módulo de Estoque e não são representados aqui.</p>
 */
public record CatalogProduct(UUID id, String description, String internalCode, String category,
                             ProductType type, ProductUnit unit, BigDecimal referenceCost,
                             BigDecimal salePrice, BigDecimal minimumStock, boolean active,
                             Instant createdAt, Instant updatedAt) {

    private static final int MONEY_SCALE = 2;
    private static final int QUANTITY_SCALE = 3;

    public CatalogProduct {
        if (id == null || createdAt == null || updatedAt == null) throw invalid("Dados obrigatórios ausentes");
        description = required(description, "descrição", 200);
        internalCode = normalizeInternalCode(internalCode);
        category = optional(category, "categoria", 100);
        if (type == null) throw invalid("tipo é obrigatório");
        if (unit == null) throw invalid("unidade é obrigatória");
        referenceCost = money(referenceCost, "custo de referência");
        salePrice = money(salePrice, "preço de venda");
        minimumStock = quantity(minimumStock, "estoque mínimo");
    }

    /** Todo produto novo nasce ativo; inativação só ocorre por atualização explícita. */
    public static CatalogProduct create(String description, String internalCode, String category, ProductType type,
                                        ProductUnit unit, BigDecimal referenceCost, BigDecimal salePrice,
                                        BigDecimal minimumStock, Instant now) {
        return new CatalogProduct(UUID.randomUUID(), description, internalCode, category, type, unit,
                referenceCost, salePrice, minimumStock, true, now, now);
    }

    public CatalogProduct update(String description, String internalCode, String category, ProductType type,
                                 ProductUnit unit, BigDecimal referenceCost, BigDecimal salePrice,
                                 BigDecimal minimumStock, Boolean active, Instant now) {
        return new CatalogProduct(id, description, internalCode, category, type, unit, referenceCost, salePrice,
                minimumStock, active == null ? this.active : active, createdAt, now);
    }

    /** Código interno é livre, mas normalizado para que a unicidade não dependa de caixa ou espaços. */
    public static String normalizeInternalCode(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase();
        if (normalized.length() > 60) throw invalid("código interno inválido");
        if (!normalized.matches("[A-Z0-9][A-Z0-9._-]*")) throw invalid("código interno inválido");
        return normalized;
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw invalid(label + " inválida");
        return value.trim();
    }

    private static String optional(String value, String label, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > max) throw invalid(label + " inválida");
        return value.trim();
    }

    private static BigDecimal money(BigDecimal value, String label) {
        if (value == null) return null;
        if (value.signum() < 0 || value.scale() > MONEY_SCALE)
            throw invalid(label + " deve ser não negativo e possuir no máximo duas casas decimais");
        return value;
    }

    private static BigDecimal quantity(BigDecimal value, String label) {
        if (value == null) return null;
        if (value.signum() < 0 || value.scale() > QUANTITY_SCALE)
            throw invalid(label + " deve ser não negativo e possuir no máximo três casas decimais");
        return value;
    }

    private static ProductCatalogException invalid(String message) {
        return new ProductCatalogException("INVALID_PRODUCT", message);
    }
}
