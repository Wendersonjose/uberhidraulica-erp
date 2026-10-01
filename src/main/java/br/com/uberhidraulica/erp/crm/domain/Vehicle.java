package br.com.uberhidraulica.erp.crm.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Veículo atendido pela oficina.
 *
 * <p>{@code customerId} é o proprietário atual. A troca de proprietário não reescreve o passado:
 * o vínculo anterior é encerrado no histórico de propriedade e as OS já abertas mantêm o cliente
 * que tinham na abertura.</p>
 */
public record Vehicle(UUID id, UUID customerId, String plate, String manufacturer, String model,
                      Integer modelYear, Long mileage, String steeringGearManufacturer, String color, String notes,
                      boolean active, Instant createdAt, Instant updatedAt) {
    public Vehicle {
        if (id == null) throw invalid("Identificador é obrigatório");
        if (customerId == null) throw invalid("Cliente é obrigatório");
        plate = normalizePlate(plate);
        manufacturer = required(manufacturer, "Marca", 100);
        model = required(model, "Modelo", 100);
        if (modelYear != null && (modelYear < 1900 || modelYear > 2100)) throw invalid("Ano inválido");
        if (mileage != null && mileage < 0) throw invalid("Quilometragem inválida");
        steeringGearManufacturer = optional(steeringGearManufacturer, "Fabricante da caixa", 100);
        color = optional(color, "Cor", 40);
        notes = optional(notes, "Observações", 1000);
    }

    public static Vehicle create(UUID customerId, String plate, String manufacturer, String model, Integer year,
                                 Long mileage, String steering, String color, String notes, Instant now) {
        return new Vehicle(UUID.randomUUID(), customerId, plate, manufacturer, model, year, mileage, steering, color, notes, true, now, now);
    }

    public Vehicle update(String plate, String manufacturer, String model, Integer year, Long mileage, String steering,
                          String color, String notes, Instant now) {
        return new Vehicle(id, customerId, plate, manufacturer, model, year, mileage, steering, color, notes, active, createdAt, now);
    }

    public Vehicle transferTo(UUID newCustomerId, Instant now) {
        if (newCustomerId == null) throw invalid("Novo proprietário é obrigatório");
        if (newCustomerId.equals(customerId)) throw new CrmException("VEHICLE_ALREADY_OWNED_BY_CUSTOMER", "O veículo já pertence a este cliente");
        return new Vehicle(id, newCustomerId, plate, manufacturer, model, modelYear, mileage, steeringGearManufacturer, color, notes, active, createdAt, now);
    }

    public Vehicle inactivate(Instant now) {
        if (!active) throw new CrmException("VEHICLE_ALREADY_INACTIVE", "Veículo já está inativo");
        return withActive(false, now);
    }

    public Vehicle reactivate(Instant now) {
        if (active) throw new CrmException("VEHICLE_ALREADY_ACTIVE", "Veículo já está ativo");
        return withActive(true, now);
    }

    private Vehicle withActive(boolean value, Instant now) {
        return new Vehicle(id, customerId, plate, manufacturer, model, modelYear, mileage, steeringGearManufacturer, color, notes, value, createdAt, now);
    }

    public static String normalizePlate(String value) {
        if (value == null) throw invalid("Placa é obrigatória");
        String normalized = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 10) throw invalid("Placa inválida");
        return normalized;
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw invalid(label + " inválido");
        return value.trim();
    }

    private static String optional(String value, String label, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > max) throw invalid(label + " inválido");
        return value.trim();
    }

    private static CrmException invalid(String message) { return new CrmException("INVALID_VEHICLE", message); }

    /** Período em que um cliente foi proprietário do veículo; {@code endedAt} nulo indica o proprietário atual. */
    public record Ownership(UUID id, UUID vehicleId, UUID customerId, Instant startedAt, Instant endedAt, UUID changedBy) {}
}
