package br.com.uberhidraulica.erp.purchasing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Supplier(
        UUID id,
        PersonType personType,
        String legalName,
        String tradeName,
        String document,
        String phone,
        String email,
        Address address,
        CommercialTerms commercialTerms,
        Status status,
        Instant createdAt,
        Instant updatedAt) {

    public enum PersonType { PF, PJ }
    public enum Status { ACTIVE, INACTIVE }

    public record Address(String zipCode, String street, String number, String complement,
                          String district, String city, String state) {}

    public record CommercialTerms(String paymentTerms, String preferredPaymentMethod,
                                  Integer usualDueDay, BigDecimal creditLimit, String notes) {
        public CommercialTerms {
            if (usualDueDay != null && (usualDueDay < 1 || usualDueDay > 31))
                throw new IllegalArgumentException("Dia usual de vencimento deve estar entre 1 e 31");
            if (creditLimit != null && creditLimit.signum() < 0)
                throw new IllegalArgumentException("Limite de crédito não pode ser negativo");
        }
    }

    public Supplier {
        if (id == null || personType == null || status == null || createdAt == null || updatedAt == null)
            throw new IllegalArgumentException("Fornecedor incompleto");
        legalName = required(legalName, "Razão social/nome");
        tradeName = clean(tradeName);
        document = clean(document);
        phone = clean(phone);
        email = clean(email);
        if (commercialTerms == null) commercialTerms = new CommercialTerms(null, null, null, null, null);
    }

    public static Supplier create(PersonType type, String legalName, String tradeName, String document,
                                  String phone, String email, Address address, CommercialTerms terms, Instant now) {
        return new Supplier(UUID.randomUUID(), type, legalName, tradeName, document, phone, email,
                address, terms, Status.ACTIVE, now, now);
    }

    public Supplier update(PersonType type, String legalName, String tradeName, String document,
                           String phone, String email, Address address, CommercialTerms terms, Instant now) {
        return new Supplier(id, type, legalName, tradeName, document, phone, email, address, terms, status, createdAt, now);
    }

    public Supplier inactivate(Instant now) {
        return status == Status.INACTIVE ? this : new Supplier(id, personType, legalName, tradeName, document, phone, email,
                address, commercialTerms, Status.INACTIVE, createdAt, now);
    }

    public Supplier reactivate(Instant now) {
        return status == Status.ACTIVE ? this : new Supplier(id, personType, legalName, tradeName, document, phone, email,
                address, commercialTerms, Status.ACTIVE, createdAt, now);
    }

    private static String required(String value, String field) {
        String cleaned = clean(value);
        if (cleaned == null) throw new IllegalArgumentException(field + " é obrigatório");
        return cleaned;
    }
    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
