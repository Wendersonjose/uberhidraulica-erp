package br.com.uberhidraulica.erp.finance.api;

import br.com.uberhidraulica.erp.crm.CustomerVehicleQuery;
import br.com.uberhidraulica.erp.finance.api.FinanceResponses.*;
import br.com.uberhidraulica.erp.finance.application.ReceivableService;
import br.com.uberhidraulica.erp.finance.application.Recorded;
import br.com.uberhidraulica.erp.finance.domain.FinancialStatus;
import br.com.uberhidraulica.erp.finance.domain.Receivable;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contas a receber da OS (DR-0015). Toda rota exige a permissão financeira correspondente, no backend. */
@RestController
@RequestMapping("/api/finance")
class ReceivableController {
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String REPLAY_HEADER = "Idempotent-Replay";

    private final ReceivableService receivables;
    private final CustomerVehicleQuery customers;

    ReceivableController(ReceivableService receivables, CustomerVehicleQuery customers) {
        this.receivables = receivables;
        this.customers = customers;
    }

    @GetMapping("/receivables")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    PageResponse<ReceivableSummaryResponse> list(@RequestParam(required = false) FinancialStatus status,
                                                 @RequestParam(required = false) UUID customerId,
                                                 @RequestParam(required = false) Long workOrderNumber,
                                                 @RequestParam(required = false) LocalDate dueFrom,
                                                 @RequestParam(required = false) LocalDate dueTo,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        var result = receivables.list(new FinanceRepositoryPort.ReceivableFilter(status, customerId, workOrderNumber, dueFrom, dueTo), page, size);
        Map<UUID, String> names = new HashMap<>();
        return new PageResponse<>(result.items().stream()
                .map(item -> ReceivableSummaryResponse.from(item, names.computeIfAbsent(item.customerId(), this::customerName))).toList(),
                result.totalItems(), result.page(), result.size(), result.totalPages());
    }

    @GetMapping("/receivables/{id}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    ReceivableResponse get(@PathVariable UUID id) { return respond(receivables.get(id)); }

    @GetMapping("/work-orders/{workOrderId}/receivable")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    ReceivableResponse byWorkOrder(@PathVariable UUID workOrderId) { return respond(receivables.byWorkOrder(workOrderId)); }

    @GetMapping("/work-orders/{workOrderId}/billing-candidates")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    List<BillingCandidateResponse> billingCandidates(@PathVariable UUID workOrderId) {
        return receivables.billingCandidates(workOrderId).stream().map(BillingCandidateResponse::from).toList();
    }

    @PostMapping("/receivables/{id}/receipts")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_RECEIVE')")
    ResponseEntity<SettlementResponse> receive(@PathVariable UUID id, @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                               @Valid @RequestBody ReceiptRequest request) {
        return settled(receivables.receive(id, request.amount(), request.paymentMethodId(), request.receivedOn(), request.notes(), key));
    }

    @PostMapping("/receipts/{id}/reversal")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_REVERSE')")
    ResponseEntity<SettlementResponse> reverse(@PathVariable UUID id, @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                               @Valid @RequestBody ReversalRequest request) {
        return settled(receivables.reverseReceipt(id, request.reason(), key));
    }

    @PostMapping("/receivables/{id}/adjustments")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_ADJUST')")
    ResponseEntity<ReceivableResponse> adjust(@PathVariable UUID id, @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                              @Valid @RequestBody AdjustmentRequest request) {
        Recorded<Receivable> result = receivables.adjust(id, request.type(), request.amount(), request.reason(), key);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed())).body(respond(receivables.get(id)));
    }

    @PutMapping("/receivables/{id}/due-date")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_ADJUST')")
    ReceivableResponse changeDueDate(@PathVariable UUID id, @Valid @RequestBody DueDateRequest request) {
        return respond(receivables.changeDueDate(id, request.dueDate(), request.reason()));
    }

    private ResponseEntity<SettlementResponse> settled(Recorded<br.com.uberhidraulica.erp.finance.domain.Settlement> result) {
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed())).body(SettlementResponse.from(result.value()));
    }

    private ReceivableResponse respond(Receivable receivable) {
        return ReceivableResponse.from(receivable, customerName(receivable.customerId()), receivables.today());
    }

    private String customerName(UUID customerId) {
        return customers.customer(customerId).map(CustomerVehicleQuery.CustomerReference::name).orElse(null);
    }

    record ReceiptRequest(@NotNull BigDecimal amount, @NotNull UUID paymentMethodId, LocalDate receivedOn, @Size(max = 500) String notes) {}

    record ReversalRequest(@NotBlank @Size(max = 500) String reason) {}

    record AdjustmentRequest(@NotNull Receivable.AdjustmentType type, @NotNull BigDecimal amount, @NotBlank @Size(max = 500) String reason) {}

    record DueDateRequest(@NotNull LocalDate dueDate, @NotBlank @Size(max = 500) String reason) {}
}
