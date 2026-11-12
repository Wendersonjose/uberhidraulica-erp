package br.com.uberhidraulica.erp.purchasing.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/** Contato comercial do fornecedor (vendedor, financeiro, faturamento…). Remover apenas inativa o contato. */
public record SupplierContact(UUID id, UUID supplierId, String name, String role, String phone, String email, boolean primary,
                              boolean active, Instant createdAt, UUID createdBy, Instant updatedAt) {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public SupplierContact {
        if (id == null || supplierId == null || name == null || createdAt == null || createdBy == null || updatedAt == null)
            throw new PurchasingException("SUPPLIER_CONTACT_INVALID", "Dados obrigatórios do contato ausentes");
        if (primary && !active) throw new PurchasingException("SUPPLIER_CONTACT_INVALID", "Contato inativo não pode ser o principal");
    }

    /** Dados informados pelo usuário. */
    public record Draft(String name, String role, String phone, String email, boolean primary) {
        public Draft validated() {
            String contactName = Supplier.text(name, "Nome do contato", 160);
            if (contactName == null) throw new PurchasingException("SUPPLIER_CONTACT_INVALID", "Nome do contato é obrigatório");
            String mail = Supplier.text(email, "E-mail do contato", 254);
            if (mail != null && !EMAIL.matcher(mail).matches())
                throw new PurchasingException("SUPPLIER_CONTACT_INVALID", "E-mail do contato inválido");
            return new Draft(contactName, Supplier.text(role, "Função do contato", 80), Supplier.text(phone, "Telefone do contato", 24), mail, primary);
        }
    }

    public static SupplierContact create(UUID supplierId, Draft draft, UUID actor, Instant now) {
        Draft d = draft.validated();
        return new SupplierContact(UUID.randomUUID(), supplierId, d.name(), d.role(), d.phone(), d.email(), d.primary(), true, now, actor, now);
    }

    public SupplierContact revise(Draft draft, Instant now) {
        Draft d = draft.validated();
        return new SupplierContact(id, supplierId, d.name(), d.role(), d.phone(), d.email(), d.primary(), active, createdAt, createdBy, now);
    }

    public SupplierContact remove(Instant now) {
        return new SupplierContact(id, supplierId, name, role, phone, email, false, false, createdAt, createdBy, now);
    }
}
