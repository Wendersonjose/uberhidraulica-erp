package br.com.uberhidraulica.erp.servicecatalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Serviço oferecido pela oficina.
 *
 * <p>Descrição e preço base são opcionais (DR-0011). O preço é definido manualmente e alterá-lo não
 * reescreve OS: a OS guarda o valor praticado no lançamento.</p>
 */
public record CatalogService(UUID id, String name, String description, UUID categoryId,
                             BigDecimal basePrice, int defaultWarrantyDays, boolean active,
                             Instant createdAt, Instant updatedAt) {
    public CatalogService {
        name = required(name, "name", 160);
        description = optional(description, "description", 1000);
        if (basePrice != null && (basePrice.signum() < 0 || basePrice.scale() > 2))
            throw invalid("basePrice deve ser não negativo e possuir no máximo duas casas decimais");
        if (defaultWarrantyDays < 0) throw invalid("defaultWarrantyDays deve ser não negativo");
    }

    public static CatalogService create(String name, String description, UUID categoryId,
                                        BigDecimal basePrice, Integer warrantyDays, Boolean active, Instant now) {
        return new CatalogService(UUID.randomUUID(), name, description, categoryId, basePrice,
                warrantyDays == null ? 90 : warrantyDays, active == null || active, now, now);
    }

    public CatalogService update(String name, String description, UUID categoryId,
                                 BigDecimal basePrice, Integer warrantyDays, Boolean active, Instant now) {
        return new CatalogService(id, name, description, categoryId, basePrice,
                warrantyDays == null ? defaultWarrantyDays : warrantyDays,
                active == null ? this.active : active, createdAt, now);
    }

    public CatalogService inactivate(Instant now) {
        if (!active) throw new ServiceCatalogException("SERVICE_ALREADY_INACTIVE", "Serviço já está inativo");
        return new CatalogService(id, name, description, categoryId, basePrice, defaultWarrantyDays, false, createdAt, now);
    }

    public CatalogService reactivate(Instant now) {
        if (active) throw new ServiceCatalogException("SERVICE_ALREADY_ACTIVE", "Serviço já está ativo");
        return new CatalogService(id, name, description, categoryId, basePrice, defaultWarrantyDays, true, createdAt, now);
    }

    static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw invalid(field + " inválido");
        return value.trim();
    }

    static String optional(String value, String field, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > max) throw invalid(field + " inválido");
        return value.trim();
    }

    static ServiceCatalogException invalid(String message) {
        return new ServiceCatalogException("INVALID_SERVICE", message);
    }
}
