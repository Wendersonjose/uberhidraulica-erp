package br.com.uberhidraulica.erp.finance.api;

import br.com.uberhidraulica.erp.finance.application.CashSessionService;
import br.com.uberhidraulica.erp.finance.application.Recorded;
import br.com.uberhidraulica.erp.finance.domain.CashMovement;
import br.com.uberhidraulica.erp.finance.domain.CashSession;
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
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/finance/cash")
class CashSessionController {
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String REPLAY_HEADER = "Idempotent-Replay";

    private final CashSessionService service;

    CashSessionController(CashSessionService service) {
        this.service = service;
    }

    @GetMapping("/suggested-opening-balance")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    BalanceResponse suggestedOpeningBalance() {
        return new BalanceResponse(service.suggestedOpeningBalance());
    }

    @GetMapping("/sessions/open")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    CashSessionResponse openSession() {
        return response(service.openSession());
    }

    @GetMapping("/sessions/{id}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    CashSessionResponse get(@PathVariable UUID id) {
        return response(service.get(id));
    }

    @GetMapping("/sessions/{id}/movements")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    List<CashMovementResponse> movements(@PathVariable UUID id) {
        return service.movements(id).stream().map(CashMovementResponse::from).toList();
    }

    @PostMapping("/sessions")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'CASH_SESSION_OPEN')")
    ResponseEntity<CashSessionResponse> open(@Valid @RequestBody OpenRequest request) {
        CashSession created = service.open(request.countedBalance(), request.differenceReason());
        return ResponseEntity.status(HttpStatus.CREATED).body(response(created));
    }

    @PostMapping("/sessions/{id}/close")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'CASH_SESSION_CLOSE')")
    CashSessionResponse close(@PathVariable UUID id, @Valid @RequestBody CloseRequest request) {
        return response(service.close(id, request.countedBalance(), request.differenceReason()));
    }

    @PostMapping("/sessions/{id}/check")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'CASH_SESSION_CLOSE')")
    CashSessionResponse check(@PathVariable UUID id, @Valid @RequestBody CloseRequest request) {
        return response(service.checkAutomaticallyClosed(id, request.countedBalance(), request.differenceReason()));
    }

    @PostMapping("/movements/supply")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'CASH_SUPPLY')")
    ResponseEntity<CashMovementResponse> supply(@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                                @Valid @RequestBody MovementRequest request) {
        return movement(service.supply(request.amount(), request.reason(), key));
    }

    @PostMapping("/movements/withdrawal")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'CASH_WITHDRAWAL')")
    ResponseEntity<CashMovementResponse> withdrawal(@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                                    @Valid @RequestBody MovementRequest request) {
        return movement(service.withdrawal(request.amount(), request.reason(), key));
    }

    @PostMapping("/movements/{id}/reversal")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'CASH_REVERSAL')")
    ResponseEntity<CashMovementResponse> reverse(@PathVariable UUID id,
                                                 @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                                 @Valid @RequestBody ReversalRequest request) {
        return movement(service.reverse(id, request.reason(), key));
    }

    private CashSessionResponse response(CashSession session) {
        return CashSessionResponse.from(session, service.expectedBalance(session.id()));
    }

    private static ResponseEntity<CashMovementResponse> movement(Recorded<CashMovement> result) {
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed())).body(CashMovementResponse.from(result.value()));
    }

    record OpenRequest(@NotNull BigDecimal countedBalance, @Size(max = 500) String differenceReason) {}
    record CloseRequest(@NotNull BigDecimal countedBalance, @Size(max = 500) String differenceReason) {}
    record MovementRequest(@NotNull BigDecimal amount, @NotBlank @Size(max = 500) String reason) {}
    record ReversalRequest(@NotBlank @Size(max = 500) String reason) {}
    record BalanceResponse(BigDecimal amount) {}

    record CashSessionResponse(UUID id, CashSession.Status status, CashSession.ConferenceStatus conferenceStatus,
                               Instant openedAt, UUID openedBy, BigDecimal openingExpectedBalance,
                               BigDecimal openingCountedBalance, String openingDifferenceReason,
                               Instant closedAt, UUID closedBy, BigDecimal closingExpectedBalance,
                               BigDecimal closingCountedBalance, String closingDifferenceReason,
                               Instant checkedAt, UUID checkedBy, BigDecimal checkedCountedBalance,
                               String checkedDifferenceReason, BigDecimal currentExpectedBalance) {
        static CashSessionResponse from(CashSession session, BigDecimal currentExpectedBalance) {
            return new CashSessionResponse(session.id(), session.status(), session.conferenceStatus(), session.openedAt(),
                    session.openedBy(), session.openingExpectedBalance(), session.openingCountedBalance(),
                    session.openingDifferenceReason(), session.closedAt(), session.closedBy(), session.closingExpectedBalance(),
                    session.closingCountedBalance(), session.closingDifferenceReason(), session.checkedAt(), session.checkedBy(),
                    session.checkedCountedBalance(), session.checkedDifferenceReason(), currentExpectedBalance);
        }
    }

    record CashMovementResponse(UUID id, UUID cashSessionId, CashMovement.Type type, CashMovement.Direction direction,
                                BigDecimal amount, String reason, UUID receiptId, UUID payablePaymentId,
                                UUID reversedMovementId, Instant recordedAt, UUID recordedBy) {
        static CashMovementResponse from(CashMovement movement) {
            return new CashMovementResponse(movement.id(), movement.cashSessionId(), movement.type(), movement.direction(),
                    movement.amount(), movement.reason(), movement.receiptId(), movement.payablePaymentId(),
                    movement.reversedMovementId(), movement.recordedAt(), movement.recordedBy());
        }
    }
}
