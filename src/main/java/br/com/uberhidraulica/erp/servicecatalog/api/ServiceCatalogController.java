package br.com.uberhidraulica.erp.servicecatalog.api;

import br.com.uberhidraulica.erp.servicecatalog.ServiceCatalogQuery;
import br.com.uberhidraulica.erp.servicecatalog.application.CatalogSetupApplicationService;
import br.com.uberhidraulica.erp.servicecatalog.application.ServiceCatalogApplicationService;
import br.com.uberhidraulica.erp.servicecatalog.domain.CatalogService;
import br.com.uberhidraulica.erp.servicecatalog.domain.CatalogSetup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/services")
public class ServiceCatalogController {
    private final ServiceCatalogApplicationService application;
    private final CatalogSetupApplicationService setup;

    public ServiceCatalogController(ServiceCatalogApplicationService application, CatalogSetupApplicationService setup) {
        this.application = application;
        this.setup = setup;
    }

    @PostMapping
    ResponseEntity<Response> create(@Valid @RequestBody Request request) {
        Response response = responses(List.of(application.create(request.name(), request.description(), request.categoryId(),
                request.basePrice(), request.defaultWarrantyDays(), request.active()))).get(0);
        return ResponseEntity.created(URI.create("/api/services/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    Response get(@PathVariable UUID id) { return responses(List.of(application.get(id))).get(0); }

    @GetMapping
    List<Response> list() { return responses(application.list()); }

    @GetMapping("/search")
    PageResponse<Response> search(@RequestParam(required = false) String q, @RequestParam(required = false) UUID categoryId,
                                  @RequestParam(required = false) Boolean active, @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        var result = application.search(q, categoryId, active, page, size);
        return new PageResponse<>(responses(result.items()), result.totalItems(), result.page(), result.size(), result.totalPages());
    }

    @PutMapping("/{id}")
    Response update(@PathVariable UUID id, @Valid @RequestBody Request request) {
        return responses(List.of(application.update(id, request.name(), request.description(), request.categoryId(),
                request.basePrice(), request.defaultWarrantyDays(), request.active()))).get(0);
    }

    @PostMapping("/{id}/inactivate") Response inactivate(@PathVariable UUID id) { return responses(List.of(application.inactivate(id))).get(0); }
    @PostMapping("/{id}/reactivate") Response reactivate(@PathVariable UUID id) { return responses(List.of(application.reactivate(id))).get(0); }

    @GetMapping("/{id}/prices")
    List<PriceResponse> prices(@PathVariable UUID id) {
        Map<UUID, String> groups = setup.groups().stream().collect(Collectors.toMap(CatalogSetup.VehicleGroup::id, CatalogSetup.VehicleGroup::name));
        return application.prices(id).stream().map(p -> PriceResponse.from(p, p.vehicleGroupId() == null ? null : groups.get(p.vehicleGroupId()))).toList();
    }

    @PutMapping("/{id}/prices/vehicles/{vehicleId}")
    PriceResponse setVehiclePrice(@PathVariable UUID id, @PathVariable UUID vehicleId, @Valid @RequestBody PriceRequest request) {
        return PriceResponse.from(application.setVehiclePrice(id, vehicleId, request.price()), null);
    }

    @PutMapping("/{id}/prices/groups/{groupId}")
    PriceResponse setGroupPrice(@PathVariable UUID id, @PathVariable UUID groupId, @Valid @RequestBody PriceRequest request) {
        return PriceResponse.from(application.setGroupPrice(id, groupId, request.price()), setup.group(groupId).name());
    }

    @DeleteMapping("/{id}/prices/{priceId}")
    ResponseEntity<Void> deletePrice(@PathVariable UUID id, @PathVariable UUID priceId) {
        application.deletePrice(id, priceId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/price-suggestion")
    ServiceCatalogQuery.PriceSuggestion suggestion(@PathVariable UUID id, @RequestParam(required = false) UUID vehicleId) {
        return application.suggestPrice(id, vehicleId);
    }

    private List<Response> responses(List<CatalogService> services) {
        Map<UUID, CatalogSetup.Category> categories = setup.categories().stream()
                .collect(Collectors.toMap(CatalogSetup.Category::id, Function.identity()));
        return services.stream().map(s -> Response.from(s, s.categoryId() == null ? null : categories.get(s.categoryId()))).toList();
    }

    public record Request(@NotBlank @Size(max=160) String name,
                          @Size(max=1000) String description,
                          UUID categoryId,
                          @DecimalMin("0.00") @Digits(integer=13, fraction=2) BigDecimal basePrice,
                          @PositiveOrZero Integer defaultWarrantyDays,
                          Boolean active) {}

    public record Response(UUID id, String name, String description, UUID categoryId, String category, BigDecimal basePrice,
                           int defaultWarrantyDays, boolean active, Instant createdAt, Instant updatedAt) {
        static Response from(CatalogService s, CatalogSetup.Category category) {
            return new Response(s.id(), s.name(), s.description(), s.categoryId(), category == null ? null : category.name(),
                    s.basePrice(), s.defaultWarrantyDays(), s.active(), s.createdAt(), s.updatedAt());
        }
    }

    public record PageResponse<T>(List<T> items, long totalItems, int page, int size, int totalPages) {}

    public record PriceRequest(@NotNull @DecimalMin("0.00") @Digits(integer=13, fraction=2) BigDecimal price) {}

    public record PriceResponse(UUID id, UUID serviceId, UUID vehicleId, UUID vehicleGroupId, String vehicleGroupName, BigDecimal price, Instant updatedAt) {
        static PriceResponse from(CatalogSetup.ServicePrice p, String groupName) {
            return new PriceResponse(p.id(), p.serviceId(), p.vehicleId(), p.vehicleGroupId(), groupName, p.price(), p.updatedAt());
        }
    }
}
