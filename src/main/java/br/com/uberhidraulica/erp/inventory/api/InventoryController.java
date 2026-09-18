package br.com.uberhidraulica.erp.inventory.api;

import br.com.uberhidraulica.erp.inventory.application.InventoryApplicationService;
import br.com.uberhidraulica.erp.inventory.domain.StockMovement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final InventoryApplicationService application;

    public InventoryController(InventoryApplicationService application) { this.application = application; }

    @GetMapping("/stock")
    PageResponse<StockResponse> stock(@RequestParam(required = false) String q, @RequestParam(required = false) String category,
                                      @RequestParam(required = false) Boolean active, @RequestParam(defaultValue = "false") boolean belowMinimum,
                                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var result = application.stock(q, category, active, belowMinimum, page, size);
        return new PageResponse<>(result.items().stream().map(StockResponse::from).toList(), result.totalItems(), result.page(), result.size(), result.totalPages());
    }

    @GetMapping("/products/{productId}/movements")
    PageResponse<MovementResponse> movements(@PathVariable UUID productId, @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        var result = application.movements(productId, page, size);
        return new PageResponse<>(result.items().stream().map(MovementResponse::from).toList(), result.totalItems(), result.page(), result.size(), result.totalPages());
    }

    @PostMapping("/products/{productId}/entries")
    ResponseEntity<MovementResponse> entry(@PathVariable UUID productId, @Valid @RequestBody EntryRequest request) {
        return created(application.registerEntry(productId, request.quantity(), request.unitCost(), request.reason()));
    }

    @PostMapping("/products/{productId}/exits")
    ResponseEntity<MovementResponse> exit(@PathVariable UUID productId, @Valid @RequestBody ExitRequest request) {
        return created(application.registerExit(productId, request.quantity(), request.reason()));
    }

    @PostMapping("/products/{productId}/adjustments")
    ResponseEntity<MovementResponse> adjustment(@PathVariable UUID productId, @Valid @RequestBody AdjustmentRequest request) {
        return created(application.registerAdjustment(productId, request.quantity(), "IN".equals(request.direction()), request.reason()));
    }

    @PostMapping("/movements/{movementId}/reverse")
    ResponseEntity<MovementResponse> reverse(@PathVariable UUID movementId, @RequestBody(required = false) ReverseRequest request) {
        return created(application.reverse(movementId, request == null ? null : request.reason()));
    }

    @GetMapping("/settings")
    Map<String, String> settings() { return Map.of(InventoryApplicationService.WRITE_OFF_SETTING, application.writeOffMode().name()); }

    @PutMapping("/settings/write-off")
    Map<String, String> changeWriteOff(@Valid @RequestBody WriteOffRequest request) {
        return Map.of(InventoryApplicationService.WRITE_OFF_SETTING, application.changeWriteOffMode(request.mode()).name());
    }

    private static ResponseEntity<MovementResponse> created(StockMovement movement) {
        return ResponseEntity.status(HttpStatus.CREATED).body(MovementResponse.from(movement));
    }

    public record EntryRequest(@NotNull @DecimalMin(value = "0.000", inclusive = false) @Digits(integer = 12, fraction = 3) BigDecimal quantity,
                               @DecimalMin("0.0000") @Digits(integer = 15, fraction = 4) BigDecimal unitCost,
                               @Size(max = 500) String reason) {}
    public record ExitRequest(@NotNull @DecimalMin(value = "0.000", inclusive = false) @Digits(integer = 12, fraction = 3) BigDecimal quantity,
                              @NotBlank @Size(max = 500) String reason) {}
    public record AdjustmentRequest(@NotNull @DecimalMin(value = "0.000", inclusive = false) @Digits(integer = 12, fraction = 3) BigDecimal quantity,
                                    @Pattern(regexp = "IN|OUT") String direction, @NotBlank @Size(max = 500) String reason) {}
    public record ReverseRequest(@Size(max = 500) String reason) {}
    public record WriteOffRequest(@NotBlank String mode) {}

    public record StockResponse(UUID productId, String description, String internalCode, String category, String unit,
                                BigDecimal quantity, BigDecimal minimumStock, BigDecimal averageCost, BigDecimal salePrice,
                                boolean active, boolean belowMinimum) {
        static StockResponse from(StockMovement.StockLine line) {
            return new StockResponse(line.productId(), line.description(), line.internalCode(), line.category(), line.unit(),
                    line.quantity(), line.minimumStock(), line.averageCost(), line.salePrice(), line.active(), line.belowMinimum());
        }
    }

    public record MovementResponse(UUID id, UUID productId, StockMovement.Type type, BigDecimal quantity, BigDecimal unitCost,
                                   BigDecimal balanceAfter, BigDecimal averageCostAfter, String reason, StockMovement.Source source,
                                   UUID workOrderId, UUID reversesMovementId, Instant occurredAt, UUID recordedBy, boolean incoming) {
        static MovementResponse from(StockMovement m) {
            return new MovementResponse(m.id(), m.productId(), m.type(), m.quantity(), m.unitCost(), m.balanceAfter(),
                    m.averageCostAfter(), m.reason(), m.source(), m.workOrderId(), m.reversesMovementId(), m.occurredAt(), m.recordedBy(), m.incoming());
        }
    }

    public record PageResponse<T>(List<T> items, long totalItems, int page, int size, int totalPages) {}
}
