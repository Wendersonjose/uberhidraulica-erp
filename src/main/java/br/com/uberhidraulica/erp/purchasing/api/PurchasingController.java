package br.com.uberhidraulica.erp.purchasing.api;

import br.com.uberhidraulica.erp.purchasing.application.SupplierService;
import br.com.uberhidraulica.erp.purchasing.domain.Supplier;
import br.com.uberhidraulica.erp.purchasing.port.SupplierRepositoryPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/purchasing/suppliers")
@PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_VIEW')")
public class PurchasingController {
    private final SupplierService service;

    public PurchasingController(SupplierService service) { this.service = service; }

    @PostMapping
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_SUPPLIER_MANAGE')")
    ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequest request) {
        Supplier saved = service.create(request.personType(), request.legalName(), request.tradeName(), request.document(),
                request.phone(), request.email(), request.addressValue(), request.termsValue());
        var response = SupplierResponse.from(saved);
        return ResponseEntity.created(URI.create("/api/purchasing/suppliers/" + saved.id())).body(response);
    }

    @GetMapping("/{id}")
    SupplierResponse get(@PathVariable UUID id) { return SupplierResponse.from(service.get(id)); }

    @GetMapping("/search")
    PageResponse<SupplierResponse> search(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) Supplier.PersonType personType,
                                          @RequestParam(required = false) Supplier.Status status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        SupplierRepositoryPort.Page<Supplier> result = service.search(q, personType, status, page, size);
        return new PageResponse<>(result.items().stream().map(SupplierResponse::from).toList(), result.totalItems(),
                result.page(), result.size(), result.totalPages());
    }

    @PutMapping("/{id}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_SUPPLIER_MANAGE')")
    SupplierResponse update(@PathVariable UUID id, @Valid @RequestBody SupplierRequest request) {
        return SupplierResponse.from(service.update(id, request.personType(), request.legalName(), request.tradeName(),
                request.document(), request.phone(), request.email(), request.addressValue(), request.termsValue()));
    }

    @PostMapping("/{id}/inactivate")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_SUPPLIER_MANAGE')")
    SupplierResponse inactivate(@PathVariable UUID id) { return SupplierResponse.from(service.inactivate(id)); }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_SUPPLIER_MANAGE')")
    SupplierResponse reactivate(@PathVariable UUID id) { return SupplierResponse.from(service.reactivate(id)); }

    public record SupplierRequest(
            @NotNull Supplier.PersonType personType,
            @NotBlank @Size(max = 160) String legalName,
            @Size(max = 160) String tradeName,
            @Size(max = 24) String document,
            @Size(max = 24) String phone,
            @Email @Size(max = 254) String email,
            @Valid AddressPayload address,
            @Valid CommercialTermsPayload commercialTerms) {
        Supplier.Address addressValue() {
            return address == null ? null : new Supplier.Address(address.zipCode(), address.street(), address.number(),
                    address.complement(), address.district(), address.city(), address.state());
        }
        Supplier.CommercialTerms termsValue() {
            if (commercialTerms == null) return new Supplier.CommercialTerms(null, null, null, null, null);
            return new Supplier.CommercialTerms(commercialTerms.paymentTerms(), commercialTerms.preferredPaymentMethod(),
                    commercialTerms.usualDueDay(), commercialTerms.creditLimit(), commercialTerms.notes());
        }
    }

    public record AddressPayload(@Size(max = 10) String zipCode, @Size(max = 160) String street,
                                 @Size(max = 20) String number, @Size(max = 80) String complement,
                                 @Size(max = 80) String district, @Size(max = 80) String city,
                                 @Size(max = 2) String state) {}

    public record CommercialTermsPayload(@Size(max = 160) String paymentTerms,
                                         @Size(max = 80) String preferredPaymentMethod,
                                         @Min(1) @Max(31) Integer usualDueDay,
                                         BigDecimal creditLimit,
                                         @Size(max = 1000) String notes) {}

    public record SupplierResponse(UUID id, Supplier.PersonType personType, String legalName, String tradeName,
                                   String document, String phone, String email, AddressPayload address,
                                   CommercialTermsPayload commercialTerms, Supplier.Status status,
                                   Instant createdAt, Instant updatedAt) {
        static SupplierResponse from(Supplier s) {
            var a = s.address();
            var c = s.commercialTerms();
            return new SupplierResponse(s.id(), s.personType(), s.legalName(), s.tradeName(), s.document(), s.phone(), s.email(),
                    a == null ? null : new AddressPayload(a.zipCode(), a.street(), a.number(), a.complement(), a.district(), a.city(), a.state()),
                    new CommercialTermsPayload(c.paymentTerms(), c.preferredPaymentMethod(), c.usualDueDay(), c.creditLimit(), c.notes()),
                    s.status(), s.createdAt(), s.updatedAt());
        }
    }

    public record PageResponse<T>(List<T> items, long totalItems, int page, int size, int totalPages) {}
}
