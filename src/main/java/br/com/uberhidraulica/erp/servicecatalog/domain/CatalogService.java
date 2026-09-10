package br.com.uberhidraulica.erp.servicecatalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CatalogService(UUID id, String name, String description, String category,
                             BigDecimal basePrice, int defaultWarrantyDays, boolean active,
                             Instant createdAt, Instant updatedAt) {
    public CatalogService {
        name = required(name, "name", 160);
        description = required(description, "description", 1000);
        category = optional(category, 100);
        if (basePrice == null || basePrice.signum() < 0 || basePrice.scale() > 2)
            throw invalid("basePrice deve ser não negativo e possuir no máximo duas casas decimais");
        if (defaultWarrantyDays < 0) throw invalid("defaultWarrantyDays deve ser não negativo");
    }

    public static CatalogService create(String name, String description, String category,
                                        BigDecimal basePrice, Integer warrantyDays, Boolean active, Instant now) {
        return new CatalogService(UUID.randomUUID(), name, description, category, basePrice,
                warrantyDays == null ? 90 : warrantyDays, active == null || active, now, now);
    }

    public CatalogService update(String name, String description, String category,
                                 BigDecimal basePrice, Integer warrantyDays, Boolean active, Instant now) {
        return new CatalogService(id, name, description, category, basePrice,
                warrantyDays == null ? defaultWarrantyDays : warrantyDays,
                active == null ? this.active : active, createdAt, now);
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw invalid(field + " inválido");
        return value.trim();
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > max) throw invalid("category inválida");
        return value.trim();
    }

    private static ServiceCatalogException invalid(String message) {
        return new ServiceCatalogException("INVALID_SERVICE", message);
    }
}
