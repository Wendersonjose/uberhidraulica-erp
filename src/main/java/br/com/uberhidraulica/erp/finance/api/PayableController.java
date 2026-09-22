package br.com.uberhidraulica.erp.finance.api;

import br.com.uberhidraulica.erp.finance.api.FinanceResponses.PageResponse;
import br.com.uberhidraulica.erp.finance.api.FinanceResponses.PayableResponse;
import br.com.uberhidraulica.erp.finance.api.FinanceResponses.SettlementResponse;
import br.com.uberhidraulica.erp.finance.application.PayableService;
import br.com.uberhidraulica.erp.finance.application.Recorded;
import br.com.uberhidraulica.erp.finance.domain.FinancialStatus;
import br.com.uberhidraulica.erp.finance.domain.Payable;
import br.com.uberhidraulica.erp.finance.domain.Settlement;
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
import java.util.UUID;

import static br.com.uberhidraulica.erp.finance.api.ReceivableController.IDEMPOTENCY_HEADER;
import static br.com.uberhidraulica.erp.finance.api.ReceivableController.REPLAY_HEADER;

/** Contas a pagar (DR-0015, F-12). */
@RestController
@RequestMapping("/api/finance")
class PayableController {
    private final PayableService payables;

    PayableController(PayableService payables) { this.payables = payables; }

    @GetMapping("/payables")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    PageResponse<FinanceRepositoryPort.PayableSummary> list(@RequestParam(required = false) FinancialStatus status,
                                                            @RequestParam(required = false) UUID categoryId,
                                                            @RequestParam(required = false) LocalDate dueFrom,
                                                            @RequestParam(required = false) LocalDate dueTo,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        var result = payables.list(new FinanceRepositoryPort.PayableFilter(status, categoryId, dueFrom, dueTo), page, size);
        return new PageResponse<>(result.items(), result.totalItems(), result.page(), result.size(), result.totalPages());
    }

    @GetMapping("/payables/{id}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    PayableResponse get(@PathVariable UUID id) { return respond(payables.get(id)); }

    @PostMapping("/payables")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_PAYABLE')")
    ResponseEntity<PayableResponse> create(@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                           @Valid @RequestBody PayableRequest request) {
        Recorded<Payable> result = payables.create(request.description(), request.supplier(), request.categoryId(), request.amount(),
                request.dueDate(), request.notes(), key);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed())).body(respond(payables.get(result.value().id())));
    }

    @PostMapping("/payables/{id}/payments")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_PAYABLE')")
    ResponseEntity<SettlementResponse> pay(@PathVariable UUID id, @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                           @Valid @RequestBody PaymentRequest request) {
        return settled(payables.pay(id, request.amount(), request.paymentMethodId(), request.paidOn(), request.notes(), key));
    }

    @PostMapping("/payments/{id}/reversal")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_REVERSE')")
    ResponseEntity<SettlementResponse> reverse(@PathVariable UUID id, @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                               @Valid @RequestBody ReceivableController.ReversalRequest request) {
        return settled(payables.reversePayment(id, request.reason(), key));
    }

    @PostMapping("/payables/{id}/cancel")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_PAYABLE')")
    PayableResponse cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return respond(payables.cancel(id, request.reason()));
    }

    private PayableResponse respond(Payable payable) {
        return PayableResponse.from(payable, java.time.LocalDate.now(java.time.Clock.systemUTC().withZone(
                br.com.uberhidraulica.erp.finance.domain.Money.WORKSHOP_ZONE)));
    }

    private static ResponseEntity<SettlementResponse> settled(Recorded<Settlement> result) {
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed())).body(SettlementResponse.from(result.value()));
    }

    record PayableRequest(@NotBlank @Size(max = 200) String description, @Size(max = 200) String supplier, @NotNull UUID categoryId,
                          @NotNull BigDecimal amount, @NotNull LocalDate dueDate, @Size(max = 500) String notes) {}

    record PaymentRequest(@NotNull BigDecimal amount, @NotNull UUID paymentMethodId, LocalDate paidOn, @Size(max = 500) String notes) {}

    record CancelRequest(@NotBlank @Size(max = 500) String reason) {}
}
