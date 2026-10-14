package br.com.uberhidraulica.erp.purchasing.application;

import br.com.uberhidraulica.erp.iam.CurrentUser;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasingException;
import br.com.uberhidraulica.erp.purchasing.domain.Supplier;
import br.com.uberhidraulica.erp.purchasing.domain.Supplier.Draft;
import br.com.uberhidraulica.erp.purchasing.domain.Supplier.PersonType;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierContact;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierServiceTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private InMemorySupplierRepository repository;
    private SupplierService service;

    @BeforeEach
    void setUp() {
        repository = new InMemorySupplierRepository();
        CurrentUser user = new CurrentUser() {
            @Override public Optional<UUID> id() { return Optional.of(ACTOR); }
            @Override public UUID requireId() { return ACTOR; }
        };
        service = new SupplierService(repository, user, Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneOffset.UTC));
    }

    private static Draft pj(String name, String document) {
        return new Draft(PersonType.PJ, name, null, document, null, null, null, null, null, false, null);
    }

    private static PurchasingException refused(Runnable action) {
        try {
            action.run();
        } catch (PurchasingException e) {
            return e;
        }
        throw new AssertionError("a operação deveria ter sido recusada");
    }

    @Test
    void registersTheSupplierAndRecordsACreatedEventWithTheActor() {
        Supplier saved = service.create(pj("Fornecedor A", "67.757.350/0001-78"));

        assertThat(saved.document()).isEqualTo("67757350000178");
        assertThat(repository.events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(SupplierEvent.Type.CREATED);
            assertThat(event.supplierId()).isEqualTo(saved.id());
            assertThat(event.actorId()).isEqualTo(ACTOR);
        });
    }

    @Test
    void refusesADuplicateDocumentWithoutWritingAnythingElse() {
        service.create(pj("Fornecedor A", "67757350000178"));
        int events = repository.events.size();

        assertThat(refused(() -> service.create(pj("Outro nome", "67.757.350/0001-78"))).code()).isEqualTo("SUPPLIER_DOCUMENT_DUPLICATE");
        assertThat(repository.suppliers).hasSize(1);
        assertThat(repository.events).hasSize(events);
    }

    @Test
    void updateBumpsTheVersionAndRecordsOnlyTheChangedFields() {
        Supplier created = service.create(pj("Fornecedor A", "67757350000178"));

        Supplier updated = service.update(created.id(), 0, pj("Fornecedor A Renomeado", "67757350000178"));

        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.legalName()).isEqualTo("Fornecedor A Renomeado");
        SupplierEvent event = repository.events.get(repository.events.size() - 1);
        assertThat(event.type()).isEqualTo(SupplierEvent.Type.UPDATED);
        assertThat(event.changes()).containsOnlyKeys("legalName");
        assertThat(event.changes().get("legalName").before()).isEqualTo("Fornecedor A");
    }

    @Test
    void updateWithAStaleVersionIsRefusedAndChangesNothing() {
        Supplier created = service.create(pj("Fornecedor A", "67757350000178"));
        service.update(created.id(), 0, pj("Primeira edição", "67757350000178"));
        int events = repository.events.size();

        assertThat(refused(() -> service.update(created.id(), 0, pj("Segunda edição", "67757350000178"))).code())
                .isEqualTo("SUPPLIER_VERSION_CONFLICT");
        assertThat(repository.suppliers.get(created.id()).legalName()).isEqualTo("Primeira edição");
        assertThat(repository.events).hasSize(events);
    }

    @Test
    void anUpdateThatChangesNothingKeepsTheVersionAndWritesNoEvent() {
        Supplier created = service.create(pj("Fornecedor A", "67757350000178"));
        int events = repository.events.size();

        Supplier same = service.update(created.id(), 0, pj("Fornecedor A", "67757350000178"));

        assertThat(same.version()).isZero();
        assertThat(repository.events).hasSize(events);
    }

    @Test
    void cannotMoveADocumentToOneAlreadyUsedByAnotherSupplier() {
        service.create(pj("Fornecedor A", "67757350000178"));
        Supplier other = service.create(pj("Fornecedor B", "01637003000101"));

        assertThat(refused(() -> service.update(other.id(), 0, pj("Fornecedor B", "67757350000178"))).code())
                .isEqualTo("SUPPLIER_DOCUMENT_DUPLICATE");
    }

    @Test
    void inactivateNeedsAReasonAndKeepsTheHistoryReactivateRestoresTheSupplier() {
        Supplier created = service.create(pj("Fornecedor A", "67757350000178"));

        assertThat(refused(() -> service.inactivate(created.id(), " ")).code()).isEqualTo("SUPPLIER_INVALID");
        Supplier inactive = service.inactivate(created.id(), "Deixou de atender");
        assertThat(inactive.active()).isFalse();
        assertThat(inactive.inactivationReason()).isEqualTo("Deixou de atender");
        assertThat(refused(() -> service.inactivate(created.id(), "de novo")).code()).isEqualTo("SUPPLIER_ALREADY_INACTIVE");

        Supplier active = service.reactivate(created.id());
        assertThat(active.active()).isTrue();
        assertThat(repository.events).extracting(SupplierEvent::type)
                .containsExactly(SupplierEvent.Type.CREATED, SupplierEvent.Type.INACTIVATED, SupplierEvent.Type.REACTIVATED);
        assertThat(repository.events.get(1).reason()).isEqualTo("Deixou de atender");
        assertThat(repository.suppliers).containsKey(created.id());
    }

    @Test
    void anUnknownSupplierIsNotFoundForEveryOperation() {
        UUID missing = UUID.randomUUID();
        assertThat(refused(() -> service.get(missing)).code()).isEqualTo("SUPPLIER_NOT_FOUND");
        assertThat(refused(() -> service.update(missing, 0, pj("X", null))).code()).isEqualTo("SUPPLIER_NOT_FOUND");
        assertThat(refused(() -> service.inactivate(missing, "x")).code()).isEqualTo("SUPPLIER_NOT_FOUND");
        assertThat(refused(() -> service.contacts(missing, false)).code()).isEqualTo("SUPPLIER_NOT_FOUND");
        assertThat(refused(() -> service.addContact(missing, new SupplierContact.Draft("A", null, null, null, false))).code())
                .isEqualTo("SUPPLIER_NOT_FOUND");
    }

    @Test
    void onlyOneContactIsPrimaryAtATime() {
        Supplier supplier = service.create(pj("Fornecedor A", "67757350000178"));
        SupplierContact first = service.addContact(supplier.id(), new SupplierContact.Draft("Vendedor", "vendas", "34999990000", null, true));
        SupplierContact second = service.addContact(supplier.id(), new SupplierContact.Draft("Financeiro", null, null, "fin@exemplo.test", true));

        assertThat(service.contacts(supplier.id(), false)).filteredOn(SupplierContact::primary).extracting(SupplierContact::id).containsExactly(second.id());
        service.updateContact(supplier.id(), first.id(), new SupplierContact.Draft("Vendedor", "vendas", "34999990000", null, true));
        assertThat(service.contacts(supplier.id(), false)).filteredOn(SupplierContact::primary).extracting(SupplierContact::id).containsExactly(first.id());
    }

    @Test
    void removingAContactDeactivatesItOnceAndNeverFailsOnRepeat() {
        Supplier supplier = service.create(pj("Fornecedor A", "67757350000178"));
        SupplierContact contact = service.addContact(supplier.id(), new SupplierContact.Draft("Vendedor", null, null, null, true));

        service.removeContact(supplier.id(), contact.id());
        service.removeContact(supplier.id(), contact.id());

        assertThat(service.contacts(supplier.id(), false)).isEmpty();
        SupplierContact stored = service.contacts(supplier.id(), true).get(0);
        assertThat(stored.active()).isFalse();
        assertThat(stored.primary()).isFalse();
        assertThat(repository.events).filteredOn(e -> e.type() == SupplierEvent.Type.CONTACT_REMOVED).hasSize(1);
        assertThat(refused(() -> service.updateContact(supplier.id(), contact.id(), new SupplierContact.Draft("Novo", null, null, null, false))).code())
                .isEqualTo("SUPPLIER_CONTACT_NOT_FOUND");
    }

    @Test
    void contactNameIsRequiredAndAnotherSuppliersContactIsNotReachable() {
        Supplier a = service.create(pj("Fornecedor A", "67757350000178"));
        Supplier b = service.create(pj("Fornecedor B", "01637003000101"));
        SupplierContact contact = service.addContact(a.id(), new SupplierContact.Draft("Vendedor", null, null, null, false));

        assertThat(refused(() -> service.addContact(a.id(), new SupplierContact.Draft(" ", null, null, null, false))).code())
                .isEqualTo("SUPPLIER_CONTACT_INVALID");
        assertThat(refused(() -> service.removeContact(b.id(), contact.id())).code()).isEqualTo("SUPPLIER_CONTACT_NOT_FOUND");
    }

    @Test
    void searchPagesAreCappedAndFilterByStatus() {
        for (int i = 0; i < 3; i++) service.create(pj("Fornecedor " + i, null));
        Supplier first = repository.suppliers.values().iterator().next();
        service.inactivate(first.id(), "teste");

        assertThat(service.search(null, null, Supplier.Status.ACTIVE, null, 0, 1000).size()).isEqualTo(SupplierService.MAX_PAGE_SIZE);
        assertThat(service.search(null, null, Supplier.Status.ACTIVE, null, 0, 10).totalItems()).isEqualTo(2);
        assertThat(service.search(null, null, Supplier.Status.INACTIVE, null, 0, 10).items()).extracting(Supplier::id).containsExactly(first.id());
    }
}
