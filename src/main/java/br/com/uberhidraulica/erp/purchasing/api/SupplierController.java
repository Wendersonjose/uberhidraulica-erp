package br.com.uberhidraulica.erp.purchasing.api;

import br.com.uberhidraulica.erp.iam.CurrentUser;
import br.com.uberhidraulica.erp.iam.IamAuthorization;
import br.com.uberhidraulica.erp.purchasing.application.SupplierService;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasingException;
import br.com.uberhidraulica.erp.purchasing.domain.Supplier;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierContact;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierEvent;
import br.com.uberhidraulica.erp.purchasing.port.SupplierRepositoryPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fornecedores (TASK-0021, AG-05 §§6-14). Consultar exige PURCHASE_VIEW; escrever, PURCHASE_SUPPLIER_MANAGE. */
@RestController
@RequestMapping("/api/purchasing/suppliers")
class SupplierController {
    static final String MANAGE = "PURCHASE_SUPPLIER_MANAGE";

    private final SupplierService suppliers;
    private final IamAuthorization authorization;
    private final CurrentUser currentUser;

    SupplierController(SupplierService suppliers, IamAuthorization authorization, CurrentUser currentUser) {
        this.suppliers = suppliers;
        this.authorization = authorization;
        this.currentUser = currentUser;
    }

    @GetMapping
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_VIEW')")
    PageResponse<SupplierResponse> list(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) Supplier.PersonType personType,
                                        @RequestParam(required = false) Supplier.Status status,
                                        @RequestParam(required = false) Boolean relatedParty,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        boolean personal = mayViewPersonalData();
        var result = suppliers.search(q, personType, status, relatedParty, page, size);
        return new PageResponse<>(result.items().stream().map(s -> SupplierResponse.from(s, personal)).toList(), result.totalItems(),
                result.page(), result.size(), result.totalPages());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_VIEW')")
    SupplierResponse get(@PathVariable UUID id) { return SupplierResponse.from(suppliers.get(id), mayViewPersonalData()); }

    @PostMapping
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_SUPPLIER_MANAGE')")
    ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequest request) {
        Supplier saved = suppliers.create(request.draft());
        return ResponseEntity.created(URI.create("/api/purchasing/suppliers/" + saved.id())).body(SupplierResponse.from(saved, true));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_SUPPLIER_MANAGE')")
    SupplierResponse update(@PathVariable UUID id, @Valid @RequestBody SupplierRequest request) {
        if (request.version() == null) throw new PurchasingException("SUPPLIER_VERSION_REQUIRED", "A versão do cadastro é obrigatória para alterar");
        return SupplierResponse.from(suppliers.update(id, request.version(), request.draft()), true);
    }

    @PostMapping("/{id}/inactivate")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_SUPPLIER_MANAGE')")
    SupplierResponse inactivate(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return SupplierResponse.from(suppliers.inactivate(id, request.reason()), true);
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_SUPPLIER_MANAGE')")
    SupplierResponse reactivate(@PathVariable UUID id) { return SupplierResponse.from(suppliers.reactivate(id), true); }

    @GetMapping("/{id}/events")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_SUPPLIER_MANAGE')")
    PageResponse<EventResponse> events(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var result = suppliers.events(id, page, size);
        return new PageResponse<>(result.items().stream().map(EventResponse::from).toList(), result.totalItems(), result.page(), result.size(),
                result.totalPages());
    }

    // ------------------------------------------------------------------ contatos

    @GetMapping("/{id}/contacts")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_VIEW')")
    List<ContactResponse> contacts(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean includeInactive) {
        return suppliers.contacts(id, includeInactive).stream().map(ContactResponse::from).toList();
    }

    @PostMapping("/{id}/contacts")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_SUPPLIER_MANAGE')")
    ResponseEntity<ContactResponse> addContact(@PathVariable UUID id, @Valid @RequestBody ContactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ContactResponse.from(suppliers.addContact(id, request.draft())));
    }

    @PutMapping("/{id}/contacts/{contactId}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_SUPPLIER_MANAGE')")
    ContactResponse updateContact(@PathVariable UUID id, @PathVariable UUID contactId, @Valid @RequestBody ContactRequest request) {
        return ContactResponse.from(suppliers.updateContact(id, contactId, request.draft()));
    }

    @DeleteMapping("/{id}/contacts/{contactId}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_SUPPLIER_MANAGE')")
    ResponseEntity<Void> removeContact(@PathVariable UUID id, @PathVariable UUID contactId) {
        suppliers.removeContact(id, contactId);
        return ResponseEntity.noContent().build();
    }

    /** CPF de pessoa física só é visto completo por quem pode gerenciar fornecedores (dado pessoal, AG-09). */
    private boolean mayViewPersonalData() {
        return authorization.hasPermission(currentUser.requireId(), MANAGE);
    }

    // ------------------------------------------------------------------ contratos

    record AddressPayload(@Size(max = 10) String zipCode, @Size(max = 160) String street, @Size(max = 20) String number,
                          @Size(max = 80) String complement, @Size(max = 80) String district, @Size(max = 80) String city,
                          @Size(max = 2) String state) {
        Supplier.Address value() { return new Supplier.Address(zipCode, street, number, complement, district, city, state); }

        static AddressPayload from(Supplier.Address a) {
            return new AddressPayload(a.zipCode(), a.street(), a.number(), a.complement(), a.district(), a.city(), a.state());
        }
    }

    record TermsPayload(@Size(max = 160) String paymentTerms, @Size(max = 80) String preferredPaymentMethod,
                        @Min(1) @Max(31) Integer usualDueDay, BigDecimal creditLimit, @Size(max = 1000) String notes) {
        Supplier.CommercialTerms value() { return new Supplier.CommercialTerms(paymentTerms, preferredPaymentMethod, usualDueDay, creditLimit, notes); }

        static TermsPayload from(Supplier.CommercialTerms t) {
            return new TermsPayload(t.paymentTerms(), t.preferredPaymentMethod(), t.usualDueDay(), t.creditLimit(), t.notes());
        }
    }

    /** {@code version} é obrigatório para alterar e ignorado ao cadastrar. */
    record SupplierRequest(@NotNull Supplier.PersonType personType, @NotBlank @Size(max = 160) String legalName,
                           @Size(max = 160) String tradeName, @Size(max = 24) String document, @Size(max = 30) String stateRegistration,
                           @Size(max = 24) String phone, @Size(max = 254) String email, @Valid AddressPayload address,
                           @Valid TermsPayload commercialTerms, boolean relatedParty, @Size(max = 300) String relatedPartyNote, Long version) {
        Supplier.Draft draft() {
            return new Supplier.Draft(personType, legalName, tradeName, document, stateRegistration, phone, email,
                    address == null ? null : address.value(), commercialTerms == null ? null : commercialTerms.value(),
                    relatedParty, relatedPartyNote);
        }
    }

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    record ContactRequest(@NotBlank @Size(max = 160) String name, @Size(max = 80) String role, @Size(max = 24) String phone,
                          @Size(max = 254) String email, boolean primary) {
        SupplierContact.Draft draft() { return new SupplierContact.Draft(name, role, phone, email, primary); }
    }

    record SupplierResponse(UUID id, Supplier.PersonType personType, String legalName, String tradeName, String document,
                            boolean documentMasked, String stateRegistration, String phone, String email, AddressPayload address,
                            TermsPayload commercialTerms, boolean relatedParty, String relatedPartyNote, Supplier.Status status,
                            Instant inactivatedAt, String inactivationReason, long version, Instant createdAt, Instant updatedAt) {
        static SupplierResponse from(Supplier s, boolean personalData) {
            boolean masked = s.document() != null && s.personType() == Supplier.PersonType.PF && !personalData;
            return new SupplierResponse(s.id(), s.personType(), s.legalName(), s.tradeName(), s.documentFor(personalData), masked,
                    s.stateRegistration(), s.phone(), s.email(), AddressPayload.from(s.address()), TermsPayload.from(s.terms()),
                    s.relatedParty(), s.relatedPartyNote(), s.status(), s.inactivatedAt(), s.inactivationReason(), s.version(),
                    s.createdAt(), s.updatedAt());
        }
    }

    record ContactResponse(UUID id, UUID supplierId, String name, String role, String phone, String email, boolean primary,
                           boolean active, Instant createdAt, Instant updatedAt) {
        static ContactResponse from(SupplierContact c) {
            return new ContactResponse(c.id(), c.supplierId(), c.name(), c.role(), c.phone(), c.email(), c.primary(), c.active(),
                    c.createdAt(), c.updatedAt());
        }
    }

    record EventResponse(UUID id, SupplierEvent.Type type, UUID actorId, Instant occurredAt, String reason,
                         Map<String, SupplierEvent.Change> changes) {
        static EventResponse from(SupplierEvent e) {
            return new EventResponse(e.id(), e.type(), e.actorId(), e.occurredAt(), e.reason(), e.changes());
        }
    }

    record PageResponse<T>(List<T> items, long totalItems, int page, int size, int totalPages) {}
}
