package br.com.uberhidraulica.erp.purchasing.api;

import br.com.uberhidraulica.erp.purchasing.application.GoodsReceiptService;
import br.com.uberhidraulica.erp.purchasing.domain.GoodsReceipt;
import br.com.uberhidraulica.erp.purchasing.domain.ReceivingLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Recebimento físico de compra (`TASK-0021` I4). Consultar exige {@code PURCHASE_VIEW}; receber,
 * {@code PURCHASE_RECEIVE}; estornar, {@code PURCHASE_CANCEL}. Aceitar divergência exige ainda
 * {@code PURCHASE_DIVERGENCE_ACCEPT}, verificada no caso de uso porque só ele sabe se a linha é divergente.
 *
 * <p>Registrar e estornar exigem {@code Idempotency-Key}: sem ela, um retry de rede lançaria o estoque em dobro.</p>
 */
@RestController
@RequestMapping("/api/purchasing")
class GoodsReceiptController {
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String REPLAY_HEADER = "Idempotent-Replay";

    private final GoodsReceiptService receipts;

    GoodsReceiptController(GoodsReceiptService receipts) { this.receipts = receipts; }

    @GetMapping("/purchases/{id}/receiving")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_VIEW')")
    SheetResponse sheet(@PathVariable UUID id) {
        return SheetResponse.from(receipts.sheet(id));
    }

    @GetMapping("/purchases/{id}/receipts")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_VIEW')")
    List<ReceiptResponse> list(@PathVariable UUID id) {
        return receipts.receipts(id).stream().map(ReceiptResponse::from).toList();
    }

    @PostMapping("/purchases/{id}/receipts")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_RECEIVE')")
    ResponseEntity<ReceiptResponse> register(@PathVariable UUID id,
                                             @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String key,
                                             @Valid @RequestBody ReceiptRequest request) {
        GoodsReceiptService.Result result = receipts.register(new GoodsReceiptService.Command(id, key, request.receivedOn(),
                request.documentId(), request.notes(), request.lines().stream()
                .map(line -> new GoodsReceiptService.Line(line.purchaseItemId(), line.quantity(), line.closeRemaining(),
                        line.justification())).toList()));
        return respond(result);
    }

    @PostMapping("/receipts/{id}/reversal")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_CANCEL')")
    ResponseEntity<ReceiptResponse> reverse(@PathVariable UUID id,
                                            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String key,
                                            @Valid @RequestBody ReasonRequest request) {
        return respond(receipts.reverse(id, request.reason(), key));
    }

    private static ResponseEntity<ReceiptResponse> respond(GoodsReceiptService.Result result) {
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed())).body(ReceiptResponse.from(result.receipt()));
    }

    // ------------------------------------------------------------------ contratos

    record ReceiptRequest(LocalDate receivedOn, UUID documentId, @Size(max = 1000) String notes,
                          @NotEmpty List<@Valid @NotNull LineRequest> lines) {}

    record LineRequest(@NotNull UUID purchaseItemId, @NotNull BigDecimal quantity, Boolean closeRemaining,
                       @Size(max = 500) String justification) {}

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    record SheetResponse(UUID purchaseId, String status, List<LineResponse> lines) {
        static SheetResponse from(GoodsReceiptService.Sheet sheet) {
            return new SheetResponse(sheet.purchaseId(), sheet.status().name(),
                    sheet.lines().stream().map(LineResponse::from).toList());
        }
    }

    /** {@code pending} negativo é recebimento acima do pedido, aceito com justificativa. */
    record LineResponse(UUID purchaseItemId, int lineNo, UUID productId, String description, String unitOriginal,
                        String productUnit, BigDecimal ordered, BigDecimal received, BigDecimal closed,
                        BigDecimal pending, BigDecimal unitCost) {
        static LineResponse from(ReceivingLine l) {
            return new LineResponse(l.purchaseItemId(), l.lineNo(), l.productId(), l.description(), l.unitOriginal(),
                    l.productUnit(), l.ordered(), l.received(), l.closed(), l.pending(), l.unitCost());
        }
    }

    record ReceiptResponse(UUID id, long number, UUID purchaseId, UUID documentId, LocalDate receivedOn, String notes,
                           UUID receivedBy, Instant createdAt, List<ItemResponse> items, ReversalResponse reversal) {
        static ReceiptResponse from(GoodsReceipt r) {
            return new ReceiptResponse(r.id(), r.number(), r.purchaseId(), r.documentId(), r.receivedOn(), r.notes(),
                    r.receivedBy(), r.createdAt(), r.items().stream().map(ItemResponse::from).toList(),
                    r.reversal() == null ? null : ReversalResponse.from(r.reversal()));
        }
    }

    record ItemResponse(UUID id, UUID purchaseItemId, UUID productId, BigDecimal quantityReceived, BigDecimal unitCost,
                        BigDecimal expectedQuantity, String divergence, BigDecimal cancelledQuantity,
                        String justification, UUID acceptedBy, UUID stockMovementId) {
        static ItemResponse from(GoodsReceipt.Item i) {
            return new ItemResponse(i.id(), i.purchaseItemId(), i.productId(), i.quantityReceived(), i.unitCost(),
                    i.expectedQuantity(), i.divergence().name(), i.cancelledQuantity(), i.justification(),
                    i.acceptedBy(), i.stockMovementId());
        }
    }

    record ReversalResponse(UUID id, String reason, Instant reversedAt, UUID reversedBy) {
        static ReversalResponse from(GoodsReceipt.Reversal r) {
            return new ReversalResponse(r.id(), r.reason(), r.reversedAt(), r.reversedBy());
        }
    }
}
