package br.com.uberhidraulica.erp.finance.api;

import br.com.uberhidraulica.erp.crm.CustomerVehicleQuery;
import br.com.uberhidraulica.erp.finance.api.FinanceResponses.ReceivableResponse;
import br.com.uberhidraulica.erp.finance.application.LateChargeService;
import br.com.uberhidraulica.erp.finance.application.ReceivableService;
import br.com.uberhidraulica.erp.finance.application.Recorded;
import br.com.uberhidraulica.erp.finance.domain.LateCharge;
import br.com.uberhidraulica.erp.finance.domain.Receivable;
import jakarta.validation.Valid;
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

/** Juros e multa por atraso (DR-0024, Q3): sugestão para leitura; aplicar ou dispensar exige FINANCE_ADJUST. */
@RestController
@RequestMapping("/api/finance/receivables/{id}")
class LateChargeController {
    private final LateChargeService lateCharges;
    private final ReceivableService receivables;
    private final CustomerVehicleQuery customers;

    LateChargeController(LateChargeService lateCharges, ReceivableService receivables, CustomerVehicleQuery customers) {
        this.lateCharges = lateCharges;
        this.receivables = receivables;
        this.customers = customers;
    }

    @GetMapping("/late-charge")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    SuggestionResponse suggestion(@PathVariable UUID id, @RequestParam(required = false) LocalDate on) {
        LateCharge.Suggestion s = lateCharges.suggest(id, on);
        return new SuggestionResponse(s.calculatedOn(), s.feePercent(), s.interestPercent(), s.feeAmount(), s.interestAmount(), s.total(),
                s.lines());
    }

    @GetMapping("/late-charges")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    List<HistoryResponse> history(@PathVariable UUID id) {
        return lateCharges.history(id).stream().map(r -> new HistoryResponse(r.id(), r.decision(), r.calculatedOn(), r.feePercent(),
                r.interestPercent(), r.feeAmount(), r.interestAmount(), r.totalAmount(), r.reason(), r.adjustmentId(), r.recordedAt(),
                r.recordedBy(), r.counts())).toList();
    }

    @PostMapping("/late-charge")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_ADJUST')")
    ResponseEntity<ReceivableResponse> decide(@PathVariable UUID id, @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                              @Valid @RequestBody DecisionRequest request) {
        Recorded<Receivable> result = lateCharges.decide(id, request.decision(), request.calculatedOn(), request.expectedTotal(),
                request.reason(), key);
        Receivable receivable = receivables.get(id);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed()))
                .body(ReceivableResponse.from(receivable, customers.customer(receivable.customerId())
                        .map(CustomerVehicleQuery.CustomerReference::name).orElse(null), receivables.today()));
    }

    record SuggestionResponse(LocalDate calculatedOn, BigDecimal feePercent, BigDecimal interestMonthlyPercent, BigDecimal feeAmount,
                              BigDecimal interestAmount, BigDecimal total, List<LateCharge.Line> lines) {}

    /** {@code counts} é falso quando o acréscimo aplicado foi estornado depois: o período volta a ser sugerido. */
    record HistoryResponse(UUID id, LateCharge.Decision decision, LocalDate calculatedOn, BigDecimal feePercent, BigDecimal interestMonthlyPercent,
                           BigDecimal feeAmount, BigDecimal interestAmount, BigDecimal total, String reason, UUID adjustmentId,
                           Instant recordedAt, UUID recordedBy, boolean counts) {}

    /** {@code expectedTotal}: o total que o operador viu na tela. Motivo obrigatório para dispensar. */
    record DecisionRequest(@NotNull LateCharge.Decision decision, LocalDate calculatedOn, @NotNull BigDecimal expectedTotal,
                           @Size(max = 500) String reason) {}
}
