package br.com.uberhidraulica.erp.purchasing.application;

import br.com.uberhidraulica.erp.iam.CurrentUser;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasingException;
import br.com.uberhidraulica.erp.purchasing.domain.Supplier;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierContact;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierEvent;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierEvent.Change;
import br.com.uberhidraulica.erp.purchasing.port.SupplierRepositoryPort;
import br.com.uberhidraulica.erp.purchasing.port.SupplierRepositoryPort.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cadastro de fornecedores (AG-05 §§6-14). Toda mudança grava um evento imutável na mesma transação; edições
 * concorrentes são detectadas pela versão; o fornecedor nunca é apagado.
 */
@Service
public class SupplierService {
    public static final int MAX_PAGE_SIZE = 100;

    private final SupplierRepositoryPort repository;
    private final CurrentUser currentUser;
    private final Clock clock;

    @Autowired
    public SupplierService(SupplierRepositoryPort repository, CurrentUser currentUser) {
        this(repository, currentUser, Clock.systemUTC());
    }

    SupplierService(SupplierRepositoryPort repository, CurrentUser currentUser, Clock clock) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ fornecedor

    @Transactional
    public Supplier create(Supplier.Draft draft) {
        UUID actor = currentUser.requireId();
        Instant now = Instant.now(clock);
        Supplier supplier = Supplier.register(draft, actor, now);
        requireDocumentAvailable(supplier.document(), null);
        repository.insert(supplier);
        repository.insertEvent(SupplierEvent.of(supplier.id(), SupplierEvent.Type.CREATED, actor, now, null, Map.of()));
        return load(supplier.id());
    }

    @Transactional(readOnly = true)
    public Supplier get(UUID id) { return load(id); }

    @Transactional(readOnly = true)
    public Page<Supplier> search(String text, Supplier.PersonType personType, Supplier.Status status, Boolean relatedParty, int page, int size) {
        String trimmed = text == null || text.isBlank() ? null : text.trim();
        return repository.search(new SupplierRepositoryPort.Filter(trimmed, personType, status, relatedParty), Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }

    @Transactional
    public Supplier update(UUID id, long expectedVersion, Supplier.Draft draft) {
        UUID actor = currentUser.requireId();
        Instant now = Instant.now(clock);
        Supplier current = lock(id);
        if (current.version() != expectedVersion) throw versionConflict();
        Supplier revised = current.revise(draft, actor, now);
        Map<String, Change> changes = revised.changesFrom(current);
        if (changes.isEmpty()) return current;
        if (revised.document() != null && !revised.document().equals(current.document())) requireDocumentAvailable(revised.document(), id);
        if (!repository.update(revised, expectedVersion)) throw versionConflict();
        repository.insertEvent(SupplierEvent.of(id, SupplierEvent.Type.UPDATED, actor, now, null, changes));
        return load(id);
    }

    @Transactional
    public Supplier inactivate(UUID id, String reason) {
        UUID actor = currentUser.requireId();
        Instant now = Instant.now(clock);
        Supplier current = lock(id);
        Supplier inactive = current.inactivate(reason, actor, now);
        if (!repository.update(inactive, current.version())) throw versionConflict();
        repository.insertEvent(SupplierEvent.of(id, SupplierEvent.Type.INACTIVATED, actor, now, inactive.inactivationReason(),
                Map.of("status", new Change(Supplier.Status.ACTIVE.name(), Supplier.Status.INACTIVE.name()))));
        return load(id);
    }

    @Transactional
    public Supplier reactivate(UUID id) {
        UUID actor = currentUser.requireId();
        Instant now = Instant.now(clock);
        Supplier current = lock(id);
        Supplier active = current.reactivate(actor, now);
        if (!repository.update(active, current.version())) throw versionConflict();
        repository.insertEvent(SupplierEvent.of(id, SupplierEvent.Type.REACTIVATED, actor, now, null,
                Map.of("status", new Change(Supplier.Status.INACTIVE.name(), Supplier.Status.ACTIVE.name()))));
        return load(id);
    }

    @Transactional(readOnly = true)
    public Page<SupplierEvent> events(UUID supplierId, int page, int size) {
        load(supplierId);
        return repository.events(supplierId, Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }

    // ------------------------------------------------------------------ contatos

    @Transactional(readOnly = true)
    public List<SupplierContact> contacts(UUID supplierId, boolean includeInactive) {
        load(supplierId);
        return repository.contacts(supplierId, includeInactive);
    }

    @Transactional
    public SupplierContact addContact(UUID supplierId, SupplierContact.Draft draft) {
        UUID actor = currentUser.requireId();
        Instant now = Instant.now(clock);
        lock(supplierId);
        SupplierContact contact = SupplierContact.create(supplierId, draft, actor, now);
        if (contact.primary()) repository.clearPrimaryContact(supplierId, contact.id());
        repository.insertContact(contact);
        repository.insertEvent(SupplierEvent.of(supplierId, SupplierEvent.Type.CONTACT_ADDED, actor, now, null,
                Map.of("contact", new Change(null, contact.name() + (contact.primary() ? " (principal)" : "")))));
        return contact;
    }

    @Transactional
    public SupplierContact updateContact(UUID supplierId, UUID contactId, SupplierContact.Draft draft) {
        UUID actor = currentUser.requireId();
        Instant now = Instant.now(clock);
        lock(supplierId);
        SupplierContact current = activeContact(supplierId, contactId);
        SupplierContact revised = current.revise(draft, now);
        Map<String, Change> changes = contactChanges(current, revised);
        if (changes.isEmpty()) return current;
        if (revised.primary()) repository.clearPrimaryContact(supplierId, contactId);
        repository.updateContact(revised);
        repository.insertEvent(SupplierEvent.of(supplierId, SupplierEvent.Type.CONTACT_UPDATED, actor, now, null, changes));
        return revised;
    }

    /** Remover inativa o contato; repetir a remoção não falha nem duplica o evento. */
    @Transactional
    public void removeContact(UUID supplierId, UUID contactId) {
        UUID actor = currentUser.requireId();
        Instant now = Instant.now(clock);
        lock(supplierId);
        SupplierContact current = repository.findContact(supplierId, contactId)
                .orElseThrow(() -> new PurchasingException("SUPPLIER_CONTACT_NOT_FOUND", "Contato não encontrado"));
        if (!current.active()) return;
        repository.updateContact(current.remove(now));
        repository.insertEvent(SupplierEvent.of(supplierId, SupplierEvent.Type.CONTACT_REMOVED, actor, now, null,
                Map.of("contact", new Change(current.name(), null))));
    }

    // ------------------------------------------------------------------ apoio

    private Supplier load(UUID id) {
        return repository.find(id).orElseThrow(SupplierService::notFound);
    }

    private Supplier lock(UUID id) {
        return repository.lock(id).orElseThrow(SupplierService::notFound);
    }

    private SupplierContact activeContact(UUID supplierId, UUID contactId) {
        SupplierContact contact = repository.findContact(supplierId, contactId)
                .orElseThrow(() -> new PurchasingException("SUPPLIER_CONTACT_NOT_FOUND", "Contato não encontrado"));
        if (!contact.active()) throw new PurchasingException("SUPPLIER_CONTACT_NOT_FOUND", "Contato removido não pode ser alterado");
        return contact;
    }

    private void requireDocumentAvailable(String document, UUID excludingId) {
        if (document != null && repository.documentInUse(document, excludingId))
            throw new PurchasingException("SUPPLIER_DOCUMENT_DUPLICATE", "Já existe fornecedor com este CPF/CNPJ");
    }

    private static Map<String, Change> contactChanges(SupplierContact before, SupplierContact after) {
        Map<String, Change> changes = new LinkedHashMap<>();
        contactDiff(changes, "contact.name", before.name(), after.name());
        contactDiff(changes, "contact.role", before.role(), after.role());
        contactDiff(changes, "contact.phone", before.phone(), after.phone());
        contactDiff(changes, "contact.email", before.email(), after.email());
        contactDiff(changes, "contact.primary", String.valueOf(before.primary()), String.valueOf(after.primary()));
        return changes;
    }

    private static void contactDiff(Map<String, Change> changes, String field, String before, String after) {
        if (!java.util.Objects.equals(before, after)) changes.put(field, new Change(before, after));
    }

    private static PurchasingException notFound() { return new PurchasingException("SUPPLIER_NOT_FOUND", "Fornecedor não encontrado"); }

    private static PurchasingException versionConflict() {
        return new PurchasingException("SUPPLIER_VERSION_CONFLICT", "O cadastro foi alterado por outra pessoa; recarregue e tente novamente");
    }
}
