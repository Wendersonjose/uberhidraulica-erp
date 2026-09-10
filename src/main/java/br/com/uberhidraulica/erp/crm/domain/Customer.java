package br.com.uberhidraulica.erp.crm.domain;

import java.time.Instant;
import java.util.UUID;

public record Customer(UUID id, PersonType personType, String name, String document, Status status,
                       Instant createdAt, Instant updatedAt) {
    public enum PersonType { PF, PJ }
    public enum Status { ACTIVE, INACTIVE }

    public Customer {
        if (personType == null || status == null) throw invalid("Tipo e status são obrigatórios");
        name = required(name, "nome", 160);
        document = normalizeDocument(document);
        int requiredLength = personType == PersonType.PF ? 11 : 14;
        if (document.length() != requiredLength) throw invalid("Documento incompatível com o tipo de pessoa");
    }

    public static Customer create(PersonType type, String name, String document, Status status, Instant now) {
        return new Customer(UUID.randomUUID(), type, name, document, status == null ? Status.ACTIVE : status, now, now);
    }

    public Customer update(PersonType type, String name, String document, Status status, Instant now) {
        return new Customer(id, type, name, document, status, createdAt, now);
    }

    public static String normalizeDocument(String value) {
        if (value == null) throw invalid("Documento é obrigatório");
        if (value.codePoints().anyMatch(Character::isLetter)) throw invalid("Documento deve conter somente dígitos e formatação");
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isBlank()) throw invalid("Documento é obrigatório");
        return digits;
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw invalid(label + " inválido");
        return value.trim();
    }
    private static CrmException invalid(String message) { return new CrmException("INVALID_CUSTOMER", message); }
}
