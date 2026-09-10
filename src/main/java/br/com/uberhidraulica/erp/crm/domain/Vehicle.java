package br.com.uberhidraulica.erp.crm.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public record Vehicle(UUID id, UUID customerId, String plate, String manufacturer, String model,
                      int modelYear, Long mileage, String steeringGearManufacturer,
                      Instant createdAt, Instant updatedAt) {
    public Vehicle {
        if (customerId == null) throw invalid("Cliente é obrigatório");
        plate = normalizePlate(plate);
        manufacturer = required(manufacturer, "fabricante", 100);
        model = required(model, "modelo", 100);
        if (modelYear <= 0) throw invalid("Ano inválido");
        if (mileage != null && mileage < 0) throw invalid("Quilometragem inválida");
        steeringGearManufacturer = optional(steeringGearManufacturer, 100);
    }
    public static Vehicle create(UUID customerId, String plate, String manufacturer, String model, int year,
                                 Long mileage, String steering, Instant now) {
        return new Vehicle(UUID.randomUUID(), customerId, plate, manufacturer, model, year, mileage, steering, now, now);
    }
    public Vehicle update(String plate, String manufacturer, String model, int year, Long mileage, String steering, Instant now) {
        return new Vehicle(id, customerId, plate, manufacturer, model, year, mileage, steering, createdAt, now);
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
    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > max) throw invalid("Fabricante da caixa inválido");
        return value.trim();
    }
    private static CrmException invalid(String message) { return new CrmException("INVALID_VEHICLE", message); }
}
