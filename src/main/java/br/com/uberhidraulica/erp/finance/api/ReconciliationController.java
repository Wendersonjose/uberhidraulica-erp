package br.com.uberhidraulica.erp.finance.api;

import br.com.uberhidraulica.erp.finance.application.ReconciliationService;
import br.com.uberhidraulica.erp.finance.application.Recorded;
import br.com.uberhidraulica.erp.finance.domain.FinancialAccount;
import br.com.uberhidraulica.erp.finance.domain.Reconciliation;
import br.com.uberhidraulica.erp.finance.port.ReconciliationRepositoryPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static br.com.uberhidraulica.erp.finance.api.ReceivableController.IDEMPOTENCY_HEADER;
import static br.com.uberhidraulica.erp.finance.api.ReceivableController.REPLAY_HEADER;

/** Contas financeiras e conciliação manual (DR-0024, Q7). Sem importação de OFX ou CSV. */
@RestController
@RequestMapping("/api/finance")
class ReconciliationController {
    private final ReconciliationService reconciliation;

    ReconciliationController(ReconciliationService reconciliation) { this.reconciliation = reconciliation; }

    @GetMapping("/accounts")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    List<FinancialAccount> accounts() { return reconciliation.accounts(); }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_CONFIG')")
    FinancialAccount createAccount(@Valid @RequestBody AccountRequest request) {
        return reconciliation.createAccount(request.name(), request.type(), request.bankName(), request.branch(), request.accountNumber());
    }

    @PutMapping("/accounts/{id}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_CONFIG')")
    FinancialAccount updateAccount(@PathVariable UUID id, @Valid @RequestBody AccountUpdateRequest request) {
        return reconciliation.updateAccount(id, request.name(), request.bankName(), request.branch(), request.accountNumber(), request.active());
    }

    @GetMapping("/reconciliation/entries")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    List<EntryResponse> entries(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                @RequestParam(required = false) Reconciliation.EntryType type,
                                @RequestParam(required = false) Boolean reconciled, @RequestParam(required = false) UUID accountId) {
        return reconciliation.entries(new ReconciliationRepositoryPort.EntryFilter(from, to, type, reconciled, accountId)).stream()
                .map(EntryResponse::from).toList();
    }

    @PostMapping("/reconciliations")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_RECONCILE')")
    ResponseEntity<ReconciliationResponse> reconcile(@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                                     @Valid @RequestBody ReconcileRequest request) {
        Recorded<Reconciliation> result = reconciliation.reconcile(new ReconciliationService.Request(request.accountId(), request.entryType(),
                request.entryId(), request.statementDate(), request.statementAmount(), request.statementReference(), request.differenceReason()), key);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed())).body(ReconciliationResponse.from(result.value()));
    }

    @PostMapping("/reconciliations/{id}/reversal")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_RECONCILE')")
    ResponseEntity<ReconciliationResponse> reverse(@PathVariable UUID id, @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                                   @Valid @RequestBody ReceivableController.ReversalRequest request) {
        Recorded<Reconciliation> result = reconciliation.reverse(id, request.reason(), key);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed())).body(ReconciliationResponse.from(result.value()));
    }

    record AccountRequest(@NotBlank @Size(max = 80) String name, @NotNull FinancialAccount.Type type, @Size(max = 80) String bankName,
                          @Size(max = 20) String branch, @Size(max = 30) String accountNumber) {}

    record AccountUpdateRequest(@NotBlank @Size(max = 80) String name, @Size(max = 80) String bankName, @Size(max = 20) String branch,
                                @Size(max = 30) String accountNumber, @NotNull Boolean active) {}

    record ReconcileRequest(@NotNull UUID accountId, @NotNull Reconciliation.EntryType entryType, @NotNull UUID entryId,
                            @NotNull LocalDate statementDate, @NotNull BigDecimal statementAmount,
                            @NotBlank @Size(max = 100) String statementReference, @Size(max = 500) String differenceReason) {}

    record ReconciliationResponse(UUID id, UUID accountId, Reconciliation.EntryType entryType, UUID entryId, LocalDate statementDate,
                                  BigDecimal statementAmount, String statementReference, BigDecimal expectedAmount, BigDecimal difference,
                                  String differenceReason, Instant recordedAt, UUID recordedBy, boolean active, Instant reversedAt,
                                  String reversalReason) {
        static ReconciliationResponse from(Reconciliation r) {
            return r == null ? null : new ReconciliationResponse(r.id(), r.accountId(), r.entryType(), r.entryId(), r.statementDate(),
                    r.statementAmount(), r.statementReference(), r.expectedAmount(), r.difference(), r.differenceReason(), r.recordedAt(),
                    r.recordedBy(), r.active(), r.reversedAt(), r.reversalReason());
        }
    }

    record EntryResponse(Reconciliation.EntryType type, UUID id, UUID ownerId, String ownerLabel, LocalDate effectiveOn, String paymentMethodName,
                         BigDecimal amount, BigDecimal netAmount, LocalDate expectedSettlementOn, String accountName,
                         ReconciliationResponse reconciliation) {
        static EntryResponse from(ReconciliationRepositoryPort.EntryRow e) {
            return new EntryResponse(e.type(), e.id(), e.ownerId(), e.ownerLabel(), e.effectiveOn(), e.paymentMethodName(), e.amount(),
                    e.netAmount(), e.expectedSettlementOn(), e.accountName(), ReconciliationResponse.from(e.reconciliation()));
        }
    }
}
