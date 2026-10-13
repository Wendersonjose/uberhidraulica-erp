package br.com.uberhidraulica.erp.purchasing.application;

import br.com.uberhidraulica.erp.purchasing.domain.PurchasingException;
import br.com.uberhidraulica.erp.purchasing.domain.Supplier;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierContact;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierEvent;
import br.com.uberhidraulica.erp.purchasing.port.SupplierRepositoryPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Dublê em memória da porta para testar as regras do serviço sem banco; imita a versão e a unicidade do documento. */
class InMemorySupplierRepository implements SupplierRepositoryPort {
    final Map<UUID, Supplier> suppliers = new LinkedHashMap<>();
    final Map<UUID, SupplierContact> contacts = new LinkedHashMap<>();
    final List<SupplierEvent> events = new ArrayList<>();

    @Override
    public void insert(Supplier s) {
        if (documentInUse(s.document(), null)) throw new PurchasingException("SUPPLIER_DOCUMENT_DUPLICATE", "duplicado");
        suppliers.put(s.id(), s);
    }

    @Override
    public boolean update(Supplier s, long expectedVersion) {
        Supplier current = suppliers.get(s.id());
        if (current == null || current.version() != expectedVersion) return false;
        if (documentInUse(s.document(), s.id())) throw new PurchasingException("SUPPLIER_DOCUMENT_DUPLICATE", "duplicado");
        suppliers.put(s.id(), withVersion(s, expectedVersion + 1));
        return true;
    }

    private static Supplier withVersion(Supplier s, long version) {
        return new Supplier(s.id(), s.personType(), s.legalName(), s.tradeName(), s.document(), s.stateRegistration(), s.phone(), s.email(),
                s.address(), s.terms(), s.relatedParty(), s.relatedPartyNote(), s.status(), s.inactivatedAt(), s.inactivatedBy(),
                s.inactivationReason(), version, s.createdAt(), s.createdBy(), s.updatedAt(), s.updatedBy());
    }

    @Override public Optional<Supplier> find(UUID id) { return Optional.ofNullable(suppliers.get(id)); }

    @Override public Optional<Supplier> lock(UUID id) { return find(id); }

    @Override
    public Page<Supplier> search(Filter filter, int page, int size) {
        List<Supplier> all = suppliers.values().stream()
                .filter(s -> filter.personType() == null || s.personType() == filter.personType())
                .filter(s -> filter.status() == null || s.status() == filter.status())
                .filter(s -> filter.relatedParty() == null || s.relatedParty() == filter.relatedParty())
                .filter(s -> filter.text() == null || s.legalName().toLowerCase().contains(filter.text().toLowerCase()))
                .sorted(Comparator.comparing(s -> s.legalName().toLowerCase())).toList();
        int from = Math.min(page * size, all.size());
        return new Page<>(all.subList(from, Math.min(from + size, all.size())), all.size(), page, size);
    }

    @Override
    public boolean documentInUse(String document, UUID excludingId) {
        return document != null && suppliers.values().stream().anyMatch(s -> document.equals(s.document()) && !s.id().equals(excludingId));
    }

    @Override
    public List<SupplierContact> contacts(UUID supplierId, boolean includeInactive) {
        return contacts.values().stream().filter(c -> c.supplierId().equals(supplierId) && (includeInactive || c.active())).toList();
    }

    @Override
    public Optional<SupplierContact> findContact(UUID supplierId, UUID contactId) {
        return Optional.ofNullable(contacts.get(contactId)).filter(c -> c.supplierId().equals(supplierId));
    }

    @Override public void insertContact(SupplierContact contact) { contacts.put(contact.id(), contact); }

    @Override public void updateContact(SupplierContact contact) { contacts.put(contact.id(), contact); }

    @Override
    public void clearPrimaryContact(UUID supplierId, UUID exceptContactId) {
        contacts.replaceAll((id, c) -> c.supplierId().equals(supplierId) && c.primary() && !id.equals(exceptContactId)
                ? new SupplierContact(c.id(), c.supplierId(), c.name(), c.role(), c.phone(), c.email(), false, c.active(), c.createdAt(), c.createdBy(), c.updatedAt())
                : c);
    }

    @Override public void insertEvent(SupplierEvent event) { events.add(event); }

    @Override
    public Page<SupplierEvent> events(UUID supplierId, int page, int size) {
        List<SupplierEvent> mine = events.stream().filter(e -> e.supplierId().equals(supplierId)).toList();
        return new Page<>(mine, mine.size(), page, size);
    }
}
