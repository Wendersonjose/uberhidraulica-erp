package br.com.uberhidraulica.erp.crm.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Cliente da oficina (PF ou PJ).
 *
 * <p>Documento é opcional (DR-0010) e, quando presente, segue a DR-0005. Telefone é obrigatório
 * para criar e para atualizar; o registro reconstituído do banco aceita telefone ausente apenas
 * para clientes cadastrados antes dessa regra.</p>
 */
public record Customer(UUID id, PersonType personType, String name, String document, String phone,
                       String email, Address address, Status status, Instant createdAt, Instant updatedAt) {
    public enum PersonType { PF, PJ }
    public enum Status { ACTIVE, INACTIVE }

    public Customer {
        if (id == null || personType == null || status == null) throw invalid("Tipo e status são obrigatórios");
        name = required(name, "Nome", 160);
        document = normalizeDocument(document);
        if (document != null && document.length() != (personType == PersonType.PF ? 11 : 14))
            throw invalid("Documento incompatível com o tipo de pessoa");
        phone = normalizePhone(phone);
        email = normalizeEmail(email);
        address = address == null || address.isEmpty() ? null : address;
    }

    public static Customer create(PersonType type, String name, String document, String phone, String email,
                                  Address address, Instant now) {
        requirePhone(phone);
        return new Customer(UUID.randomUUID(), type, name, document, phone, email, address, Status.ACTIVE, now, now);
    }

    /** Atualiza dados cadastrais; o status só muda por {@link #inactivate} e {@link #reactivate}. */
    public Customer update(PersonType type, String name, String document, String phone, String email,
                           Address address, Instant now) {
        requirePhone(phone);
        return new Customer(id, type, name, document, phone, email, address, status, createdAt, now);
    }

    public Customer inactivate(Instant now) {
        if (status == Status.INACTIVE) throw new CrmException("CUSTOMER_ALREADY_INACTIVE", "Cliente já está inativo");
        return new Customer(id, personType, name, document, phone, email, address, Status.INACTIVE, createdAt, now);
    }

    public Customer reactivate(Instant now) {
        if (status == Status.ACTIVE) throw new CrmException("CUSTOMER_ALREADY_ACTIVE", "Cliente já está ativo");
        return new Customer(id, personType, name, document, phone, email, address, Status.ACTIVE, createdAt, now);
    }

    /** Somente dígitos; vazio ou ausente significa "sem documento". */
    public static String normalizeDocument(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.codePoints().anyMatch(Character::isLetter)) throw invalid("Documento deve conter somente dígitos e formatação");
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) throw invalid("Documento inválido");
        return digits;
    }

    public static String normalizePhone(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.codePoints().anyMatch(Character::isLetter)) throw invalid("Telefone deve conter somente dígitos e formatação");
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() < 10 || digits.length() > 13) throw invalid("Telefone deve conter DDD e de 10 a 13 dígitos");
        return digits;
    }

    private static String normalizeEmail(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw invalid("E-mail inválido");
        return normalized;
    }

    private static void requirePhone(String phone) {
        if (normalizePhone(phone) == null) throw invalid("Telefone é obrigatório");
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw invalid(label + " inválido");
        return value.trim();
    }

    static CrmException invalid(String message) { return new CrmException("INVALID_CUSTOMER", message); }

    /** Endereço opcional; cada parte é independente porque a oficina frequentemente conhece só a cidade. */
    public record Address(String zipCode, String street, String number, String complement, String district,
                          String city, String state) {
        public Address {
            zipCode = zipCode == null || zipCode.isBlank() ? null : zipCode.replaceAll("[^0-9]", "");
            if (zipCode != null && zipCode.length() != 8) throw invalid("CEP deve conter 8 dígitos");
            street = optional(street, "Logradouro", 160);
            number = optional(number, "Número", 20);
            complement = optional(complement, "Complemento", 80);
            district = optional(district, "Bairro", 80);
            city = optional(city, "Cidade", 80);
            state = optional(state, "UF", 2);
            if (state != null) {
                state = state.toUpperCase(Locale.ROOT);
                if (!state.matches("^[A-Z]{2}$")) throw invalid("UF deve conter duas letras");
            }
        }

        public boolean isEmpty() {
            return zipCode == null && street == null && number == null && complement == null
                    && district == null && city == null && state == null;
        }

        private static String optional(String value, String label, int max) {
            if (value == null || value.isBlank()) return null;
            if (value.trim().length() > max) throw invalid(label + " inválido");
            return value.trim();
        }
    }
}
