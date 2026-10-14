package br.com.uberhidraulica.erp.purchasing.api;

import br.com.uberhidraulica.erp.purchasing.application.PurchaseOrderService;
import br.com.uberhidraulica.erp.purchasing.application.PurchaseService;
import br.com.uberhidraulica.erp.purchasing.domain.Purchase;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Pedido de compra e compra direta (`TASK-0021` I5).
 *
 * <p>Criar e editar o rascunho e enviá-lo para aprovação exigem {@code PURCHASE_ORDER_MANAGE}; aprovar e devolver,
 * {@code PURCHASE_APPROVE}. A segregação de funções (quem criou não aprova, salvo {@code PURCHASE_APPROVE_OWN})
 * é decidida no caso de uso. Consultar e cancelar usam os endpoints de compra, com {@code PURCHASE_VIEW} e
 * {@code PURCHASE_CANCEL}.</p>
 *
 * <p>Toda mudança carrega a {@code version} que a tela leu: aprovar é aprovar o que foi visto.</p>
 */
@RestController
@RequestMapping("/api/purchasing/orders")
class PurchaseOrderController {
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String REPLAY_HEADER = "Idempotent-Replay";

    private final PurchaseOrderService orders;
    private final PurchaseService purchases;

    PurchaseOrderController(PurchaseOrderService orders, PurchaseService purchases) {
        this.orders = orders;
        this.purchases = purchases;
    }

    @PostMapping
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_ORDER_MANAGE')")
    ResponseEntity<PurchaseController.DetailResponse> create(
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String key,
            @Valid @RequestBody CreateRequest request) {
        PurchaseOrderService.Result result = orders.create(new PurchaseOrderService.CreateCommand(key,
                request.origin(), request.toDraft()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed()))
                .body(PurchaseController.DetailResponse.from(purchases.detail(result.purchase().id())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_ORDER_MANAGE')")
    PurchaseController.DetailResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        orders.update(id, request.version(), request.toDraft());
        return detail(id);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_ORDER_MANAGE')")
    PurchaseController.DetailResponse submit(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        orders.submit(id, request.version());
        return detail(id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_APPROVE')")
    PurchaseController.DetailResponse approve(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        orders.approve(id, request.version());
        return detail(id);
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_APPROVE')")
    PurchaseController.DetailResponse giveBack(@PathVariable UUID id, @Valid @RequestBody ReturnRequest request) {
        orders.giveBack(id, request.version(), request.reason());
        return detail(id);
    }

    private PurchaseController.DetailResponse detail(UUID id) {
        return PurchaseController.DetailResponse.from(purchases.detail(id));
    }

    // ------------------------------------------------------------------ contratos

    record LineRequest(@NotNull UUID productId, @Size(max = 60) String supplierCode, @NotNull BigDecimal quantity,
                       @NotNull @PositiveOrZero BigDecimal unitPrice, @PositiveOrZero BigDecimal discount,
                       UUID workOrderItemId) {
        PurchaseOrder.Line toLine() {
            return new PurchaseOrder.Line(productId, supplierCode, quantity, unitPrice, discount, workOrderItemId);
        }
    }

    /** A origem é definida na criação e não muda depois: ORDER (pedido) ou DIRECT (compra direta, sem pedido prévio). */
    record CreateRequest(@NotNull Purchase.Origin origin, @NotNull UUID supplierId, UUID workOrderId,
                         @Size(max = 1000) String notes, @PositiveOrZero BigDecimal freight,
                         @PositiveOrZero BigDecimal insurance, @PositiveOrZero BigDecimal other,
                         @NotEmpty List<@Valid @NotNull LineRequest> lines) {
        PurchaseOrderService.Draft toDraft() {
            return new PurchaseOrderService.Draft(supplierId, workOrderId, notes, freight, insurance, other,
                    lines.stream().map(LineRequest::toLine).toList());
        }
    }

    record UpdateRequest(@NotNull Long version, @NotNull UUID supplierId, UUID workOrderId,
                         @Size(max = 1000) String notes, @PositiveOrZero BigDecimal freight,
                         @PositiveOrZero BigDecimal insurance, @PositiveOrZero BigDecimal other,
                         @NotEmpty List<@Valid @NotNull LineRequest> lines) {
        PurchaseOrderService.Draft toDraft() {
            return new PurchaseOrderService.Draft(supplierId, workOrderId, notes, freight, insurance, other,
                    lines.stream().map(LineRequest::toLine).toList());
        }
    }

    record VersionRequest(@NotNull Long version) {}

    record ReturnRequest(@NotNull Long version, @NotBlank @Size(max = 500) String reason) {}
}
