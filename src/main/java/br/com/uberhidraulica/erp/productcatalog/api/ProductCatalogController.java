package br.com.uberhidraulica.erp.productcatalog.api;

import br.com.uberhidraulica.erp.productcatalog.application.ProductCatalogApplicationService;
import br.com.uberhidraulica.erp.productcatalog.domain.CatalogProduct;
import br.com.uberhidraulica.erp.productcatalog.domain.ProductType;
import br.com.uberhidraulica.erp.productcatalog.domain.ProductUnit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
public class ProductCatalogController {
    private final ProductCatalogApplicationService application;

    public ProductCatalogController(ProductCatalogApplicationService application) { this.application = application; }

    @PostMapping
    ResponseEntity<Response> create(@Valid @RequestBody CreateRequest request) {
        Response response = Response.from(application.create(request.description(), request.internalCode(),
                request.category(), request.type(), request.unit(), request.referenceCost(), request.salePrice(),
                request.minimumStock()));
        return ResponseEntity.created(URI.create("/api/products/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    Response get(@PathVariable UUID id) { return Response.from(application.get(id)); }

    @GetMapping
    List<Response> list() { return application.list().stream().map(Response::from).toList(); }

    @PutMapping("/{id}")
    Response update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return Response.from(application.update(id, request.description(), request.internalCode(), request.category(),
                request.type(), request.unit(), request.referenceCost(), request.salePrice(), request.minimumStock(),
                request.active()));
    }

    /** Criação não aceita {@code active}: todo produto nasce ativo. */
    public record CreateRequest(@NotBlank @Size(max = 200) String description,
                                @Size(max = 60) String internalCode,
                                @Size(max = 100) String category,
                                @NotNull ProductType type,
                                @NotNull ProductUnit unit,
                                @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal referenceCost,
                                @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal salePrice,
                                @DecimalMin("0.000") @Digits(integer = 12, fraction = 3) BigDecimal minimumStock) {}

    public record UpdateRequest(@NotBlank @Size(max = 200) String description,
                                @Size(max = 60) String internalCode,
                                @Size(max = 100) String category,
                                @NotNull ProductType type,
                                @NotNull ProductUnit unit,
                                @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal referenceCost,
                                @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal salePrice,
                                @DecimalMin("0.000") @Digits(integer = 12, fraction = 3) BigDecimal minimumStock,
                                Boolean active) {}

    public record Response(UUID id, String description, String internalCode, String category, ProductType type,
                           ProductUnit unit, BigDecimal referenceCost, BigDecimal salePrice, BigDecimal minimumStock,
                           boolean active, Instant createdAt, Instant updatedAt) {
        static Response from(CatalogProduct p) {
            return new Response(p.id(), p.description(), p.internalCode(), p.category(), p.type(), p.unit(),
                    p.referenceCost(), p.salePrice(), p.minimumStock(), p.active(), p.createdAt(), p.updatedAt());
        }
    }
}
