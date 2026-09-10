package br.com.uberhidraulica.erp.servicecatalog.api;

import br.com.uberhidraulica.erp.servicecatalog.application.ServiceCatalogApplicationService;
import br.com.uberhidraulica.erp.servicecatalog.domain.CatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/services")
public class ServiceCatalogController {
    private final ServiceCatalogApplicationService application;
    public ServiceCatalogController(ServiceCatalogApplicationService application) { this.application = application; }

    @PostMapping
    ResponseEntity<Response> create(@Valid @RequestBody Request request) {
        Response response = Response.from(application.create(request.name(), request.description(), request.category(),
                request.basePrice(), request.defaultWarrantyDays(), request.active()));
        return ResponseEntity.created(URI.create("/api/services/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    Response get(@PathVariable UUID id) { return Response.from(application.get(id)); }

    @GetMapping
    List<Response> list() { return application.list().stream().map(Response::from).toList(); }

    @PutMapping("/{id}")
    Response update(@PathVariable UUID id, @Valid @RequestBody Request request) {
        return Response.from(application.update(id, request.name(), request.description(), request.category(),
                request.basePrice(), request.defaultWarrantyDays(), request.active()));
    }

    public record Request(@NotBlank @Size(max=160) String name,
                          @NotBlank @Size(max=1000) String description,
                          @Size(max=100) String category,
                          @NotNull @DecimalMin("0.00") @Digits(integer=13, fraction=2) BigDecimal basePrice,
                          @PositiveOrZero Integer defaultWarrantyDays,
                          Boolean active) {}

    public record Response(UUID id, String name, String description, String category, BigDecimal basePrice,
                           int defaultWarrantyDays, boolean active, Instant createdAt, Instant updatedAt) {
        static Response from(CatalogService s) { return new Response(s.id(), s.name(), s.description(), s.category(),
                s.basePrice(), s.defaultWarrantyDays(), s.active(), s.createdAt(), s.updatedAt()); }
    }
}
