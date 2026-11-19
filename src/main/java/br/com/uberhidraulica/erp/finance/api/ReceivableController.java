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
                                                 @RequestParam(required = false) Receivable.Origin origin,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        var result = receivables.list(new FinanceRepositoryPort.ReceivableFilter(status, customerId, workOrderNumber, dueFrom, dueTo, origin),
                page, size);
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
        return settled(receivables.receive(id, request.amount(), request.paymentMethodId(), request.receivedOn(), request.notes(),
                request.cashTendered(), request.installments(), key));
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
    ResponseEntity<ReceivableResponse> changeDueDate(@PathVariable UUID id, @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                                     @Valid @RequestBody DueDateRequest request) {
        Recorded<Receivable> result = receivables.changeDueDate(id, request.installmentNumber(), request.dueDate(), request.reason(), key);
        return ResponseEntity.ok().header(REPLAY_HEADER, String.valueOf(result.replayed())).body(respond(result.value()));
    }

    /** DR-0024, Q1: novo plano de parcelas, só sem recebimento ativo. Mesma permissão de vencimento e ajuste. */
    @PutMapping("/receivables/{id}/installments")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_ADJUST')")
    ResponseEntity<ReceivableResponse> replan(@PathVariable UUID id, @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                              @Valid @RequestBody InstallmentPlanRequest request) {
        List<Receivable.Installment> installments = new java.util.ArrayList<>();
        for (int i = 0; i < request.installments().size(); i++) {
            InstallmentRequest item = request.installments().get(i);
            if (item == null || item.amount() == null || item.dueDate() == null)
                throw new br.com.uberhidraulica.erp.finance.domain.FinanceException("INVALID_INSTALLMENT", "Informe valor e vencimento de cada parcela");
            installments.add(new Receivable.Installment(null, i + 1, item.amount(), item.dueDate()));
        }
        Recorded<Receivable> result = receivables.replan(id, installments, request.reason(), key);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed())).body(respond(result.value()));
    }

    /** DR-0024, Q2: conta a receber avulsa, sem OS. */
    @PostMapping("/receivables")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_RECEIVABLE_MANUAL')")
    ResponseEntity<ReceivableResponse> createManual(@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                                    @Valid @RequestBody ManualReceivableRequest request) {
        Recorded<Receivable> result = receivables.createManual(new ReceivableService.ManualRequest(request.customerId(), request.description(),
                request.revenueCategoryId(), request.amount(), request.issuedOn(), request.dueDate(), request.notes(), request.installments()), key);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed())).body(respond(result.value()));
    }

    @PostMapping("/receivables/{id}/cancel")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_RECEIVABLE_MANUAL')")
    ReceivableResponse cancelManual(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return respond(receivables.cancelManual(id, request.reason()));
    }

    @PostMapping("/adjustments/{id}/reversal")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_REVERSE')")
    ResponseEntity<ReceivableResponse> reverseAdjustment(@PathVariable UUID id, @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                                         @Valid @RequestBody ReversalRequest request) {
        Recorded<Receivable> result = receivables.reverseAdjustment(id, request.reason(), key);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed())).body(respond(receivables.get(result.value().id())));
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

    /** {@code installments}: parcelas do cartão, quando a forma tem tabela de taxas (DR-0024, Q4). */
    record ReceiptRequest(@NotNull BigDecimal amount, @NotNull UUID paymentMethodId, LocalDate receivedOn,
                          @Size(max = 500) String notes, BigDecimal cashTendered, Integer installments) {}

    record ReversalRequest(@NotBlank @Size(max = 500) String reason) {}

    record AdjustmentRequest(@NotNull Receivable.AdjustmentType type, @NotNull BigDecimal amount, @NotBlank @Size(max = 500) String reason) {}

    record DueDateRequest(@NotNull LocalDate dueDate, @NotBlank @Size(max = 500) String reason, Integer installmentNumber) {}

    record InstallmentRequest(BigDecimal amount, LocalDate dueDate) {}

    record InstallmentPlanRequest(@NotNull @Size(min = 1, max = Receivable.MAX_INSTALLMENTS) List<InstallmentRequest> installments,
                                  @NotBlank @Size(max = 500) String reason) {}

    record ManualReceivableRequest(@NotNull UUID customerId, @NotBlank @Size(max = 200) String description, @NotNull UUID revenueCategoryId,
                                   @NotNull BigDecimal amount, LocalDate issuedOn, @NotNull LocalDate dueDate, @Size(max = 500) String notes,
                                   Integer installments) {}

    record CancelRequest(@NotBlank @Size(max = 500) String reason) {}
}
