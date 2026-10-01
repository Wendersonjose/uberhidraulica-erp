package br.com.uberhidraulica.erp.servicecatalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Cadastros de apoio ao catálogo de serviços: categorias, grupos de veículos e preços específicos. */
public final class CatalogSetup {
    private CatalogSetup() {
    }

    public record Category(UUID id, String name, boolean active, Instant createdAt, Instant updatedAt) {
        public Category {
            name = CatalogService.required(name, "Nome da categoria", 100);
        }

        public static Category create(String name, Instant now) { return new Category(UUID.randomUUID(), name, true, now, now); }
        public Category rename(String newName, Instant now) { return new Category(id, newName, active, createdAt, now); }

        public Category withActive(boolean value, Instant now) {
            if (value == active)
                throw new ServiceCatalogException(value ? "CATEGORY_ALREADY_ACTIVE" : "CATEGORY_ALREADY_INACTIVE",
                        value ? "Categoria já está ativa" : "Categoria já está inativa");
            return new Category(id, name, value, createdAt, now);
        }
    }

    public record VehicleGroup(UUID id, String name, String description, boolean active, Instant createdAt, Instant updatedAt) {
        public VehicleGroup {
            name = CatalogService.required(name, "Nome do grupo", 100);
            description = CatalogService.optional(description, "Descrição do grupo", 500);
        }

        public static VehicleGroup create(String name, String description, Instant now) {
            return new VehicleGroup(UUID.randomUUID(), name, description, true, now, now);
        }

        public VehicleGroup update(String newName, String newDescription, Instant now) {
            return new VehicleGroup(id, newName, newDescription, active, createdAt, now);
        }

        public VehicleGroup withActive(boolean value, Instant now) {
            if (value == active)
                throw new ServiceCatalogException(value ? "VEHICLE_GROUP_ALREADY_ACTIVE" : "VEHICLE_GROUP_ALREADY_INACTIVE",
                        value ? "Grupo já está ativo" : "Grupo já está inativo");
            return new VehicleGroup(id, name, description, value, createdAt, now);
        }
    }

    /** Preço de um serviço para um veículo específico ou para um grupo; exatamente um dos dois. */
    public record ServicePrice(UUID id, UUID serviceId, UUID vehicleId, UUID vehicleGroupId, BigDecimal price,
                               Instant createdAt, Instant updatedAt) {
        public ServicePrice {
            if (serviceId == null || (vehicleId == null) == (vehicleGroupId == null))
                throw CatalogService.invalid("Preço deve referenciar um veículo ou um grupo");
            if (price == null || price.signum() < 0 || price.scale() > 2)
                throw CatalogService.invalid("Preço deve ser não negativo e possuir no máximo duas casas decimais");
        }
    }

    /** Origem do preço sugerido, na ordem de prioridade do cartão Trello e da DR-0011. */
    public enum PriceSource { VEHICLE, GROUP, BASE, NONE }
}
