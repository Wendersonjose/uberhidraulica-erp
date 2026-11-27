package br.com.uberhidraulica.erp.finance.api;

import br.com.uberhidraulica.erp.finance.api.FinanceResponses.PageResponse;
import br.com.uberhidraulica.erp.finance.api.FinanceResponses.PayableResponse;
import br.com.uberhidraulica.erp.finance.api.FinanceResponses.SettlementResponse;
import br.com.uberhidraulica.erp.finance.PurchasePayables;
import br.com.uberhidraulica.erp.finance.application.PayableService;
import br.com.uberhidraulica.erp.finance.application.Recorded;
import br.com.uberhidraulica.erp.finance.domain.FinanceException;
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
import java.util.List;
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
                                                            @RequestParam(required = false) Payable.ApprovalStatus approval,
                                                            @RequestParam(required = false) UUID groupId,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        var result = payables.list(new FinanceRepositoryPort.PayableFilter(status, categoryId, dueFrom, dueTo, approval, groupId),
                page, size);
        return new PageResponse<>(result.items(), result.totalItems(), result.page(), result.size(), result.totalPages());
    }

    @GetMapping("/payables/{id}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    PayableResponse get(@PathVariable UUID id) { return respond(payables.get(id)); }

    @PostMapping("/payables")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_PAYABLE')")
    ResponseEntity<PayableResponse> create(@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                           @Valid @RequestBody PayableRequest request) {
        // A chave "PURCHASE:..." é de Compras e classifica a conta como compra de mercadoria no resumo
        // gerencial; uma conta manual com ela sairia das despesas sem ser compra.
        if (key != null && key.startsWith(PurchasePayables.IDEMPOTENCY_PREFIX))
            throw new FinanceException("IDEMPOTENCY_KEY_RESERVED",
                    "Chave de idempotência iniciada por " + PurchasePayables.IDEMPOTENCY_PREFIX + " é reservada às contas de Compras");
        Recorded<Payable> result = payables.create(new PayableService.Draft(request.description(), request.supplier(), request.supplierId(),
                request.categoryId(), request.amount(), request.dueDate(), request.notes()), Payable.Origin.MANUAL, key);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed())).body(respond(payables.get(result.value().id())));
    }

    /** DR-0024, Q5: despesa parcelada, com N contas ligadas a um grupo. */
    @PostMapping("/payables/installments")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_PAYABLE')")
    ResponseEntity<GroupResponse> createInstallments(@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                                     @Valid @RequestBody GroupRequest request) {
        Recorded<PayableService.Group> result = payables.createInstallments(new PayableService.GroupDraft(request.description(),
                request.supplier(), request.supplierId(), request.categoryId(), request.totalAmount(), request.installments(),
                request.firstDueDate(), request.notes()), key);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed())).body(group(result.value()));
    }

    @GetMapping("/payable-groups/{id}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    GroupResponse getGroup(@PathVariable UUID id) { return group(payables.group(id)); }

    @PostMapping("/payables/{id}/approval")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_PAYABLE_APPROVE')")
    PayableResponse approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ApprovalRequest request) {
        return respond(payables.approve(id, request == null ? null : request.notes()));
    }

    @PostMapping("/payable-groups/{id}/approval")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_PAYABLE_APPROVE')")
    GroupResponse approveGroup(@PathVariable UUID id, @Valid @RequestBody(required = false) ApprovalRequest request) {
        return group(payables.approveGroup(id, request == null ? null : request.notes()));
    }

    private GroupResponse group(PayableService.Group group) {
        var header = group.header();
        List<PayableResponse> items = group.payables().stream().map(this::respond).toList();
        java.util.function.Function<java.util.function.Function<PayableResponse, BigDecimal>, BigDecimal> total = f -> items.stream()
                .filter(p -> p.status() != FinancialStatus.CANCELADO).map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new GroupResponse(header.id(), header.description(), header.supplier(), header.supplierId(), header.categoryId(),
                header.totalAmount(), header.installmentCount(), header.notes(), header.createdAt(), total.apply(PayableResponse::paidAmount),
                total.apply(PayableResponse::outstandingBalance), items);
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

    PayableResponse respond(Payable payable) {
        return PayableResponse.from(payable, java.time.LocalDate.now(java.time.Clock.systemUTC().withZone(
                br.com.uberhidraulica.erp.finance.domain.Money.WORKSHOP_ZONE)));
    }

    private static ResponseEntity<SettlementResponse> settled(Recorded<Settlement> result) {
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed())).body(SettlementResponse.from(result.value()));
    }

    record PayableRequest(@NotBlank @Size(max = 200) String description, @Size(max = 200) String supplier, UUID supplierId,
                          @NotNull UUID categoryId, @NotNull BigDecimal amount, @NotNull LocalDate dueDate, @Size(max = 500) String notes) {}

    record GroupRequest(@NotBlank @Size(max = 180) String description, @Size(max = 200) String supplier, UUID supplierId,
                        @NotNull UUID categoryId, @NotNull BigDecimal totalAmount, @NotNull Integer installments,
                        @NotNull LocalDate firstDueDate, @Size(max = 500) String notes) {}

    record ApprovalRequest(@Size(max = 500) String notes) {}

    /** Visão consolidada do grupo; pago e em aberto ignoram parcelas canceladas. */
    record GroupResponse(UUID id, String description, String supplier, UUID supplierId, UUID categoryId, BigDecimal totalAmount,
                         int installmentCount, String notes, java.time.Instant createdAt, BigDecimal paidAmount, BigDecimal outstandingBalance,
                         List<PayableResponse> payables) {}

    record PaymentRequest(@NotNull BigDecimal amount, @NotNull UUID paymentMethodId, LocalDate paidOn, @Size(max = 500) String notes) {}

    record CancelRequest(@NotBlank @Size(max = 500) String reason) {}
}
