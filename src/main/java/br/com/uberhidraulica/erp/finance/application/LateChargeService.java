package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.finance.domain.FinanceException;
import br.com.uberhidraulica.erp.finance.domain.LateCharge;
import br.com.uberhidraulica.erp.finance.domain.Money;
import br.com.uberhidraulica.erp.finance.domain.Receivable;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort;
import br.com.uberhidraulica.erp.iam.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Juros e multa por atraso (DR-0024, Q3). A sugestão é só leitura; aplicar ou dispensar é decisão do operador,
 * registrada com autor, instante e motivo. O valor é sempre recalculado no servidor e conferido contra o que o
 * operador viu: se mudou entre a tela e a confirmação, a decisão é recusada em vez de cobrar outro valor.
 */
@Service
public class LateChargeService {
    public static final String FEE_KEY = "LATE_FEE_PERCENT";
    public static final String INTEREST_KEY = "LATE_INTEREST_MONTHLY_PERCENT";

    private final FinanceRepositoryPort repository;
    private final ReceivableService receivables;
    private final CurrentUser currentUser;
    private final Clock clock = Clock.systemUTC();

    public LateChargeService(FinanceRepositoryPort repository, ReceivableService receivables, CurrentUser currentUser) {
        this.repository = repository;
        this.receivables = receivables;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public LateCharge.Suggestion suggest(UUID receivableId, LocalDate on) {
        return suggest(receivables.get(receivableId), date(on));
    }

    @Transactional(readOnly = true)
    public List<LateCharge.Recorded> history(UUID receivableId) { return repository.lateCharges(receivableId); }

    /**
     * Aplica (vira acréscimo de origem {@code LATE_CHARGE}) ou dispensa (exige motivo) o valor sugerido em {@code on}.
     *
     * @param expectedTotal o total que o operador viu; diferente do recalculado, a decisão é recusada
     */
    @Transactional
    public Recorded<Receivable> decide(UUID receivableId, LateCharge.Decision decision, LocalDate on, BigDecimal expectedTotal, String reason,
                                       String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        if (decision == null) throw new FinanceException("INVALID_FINANCE_ENTRY", "Informe se os juros e a multa serão aplicados ou dispensados");
        if (expectedTotal == null) throw new FinanceException("INVALID_FINANCE_ENTRY", "Informe o total exibido ao operador");
        LocalDate date = date(on);
        String note = decision == LateCharge.Decision.WAIVED ? Money.requiredText(reason, "Motivo da dispensa", 500)
                : Money.optionalText(reason, "Observação", 300);
        repository.lockIdempotencyKey(key);
        Receivable receivable = repository.lockReceivable(receivableId)
                .orElseThrow(() -> new FinanceException("RECEIVABLE_NOT_FOUND", "Recebível não encontrado"));
        var previous = repository.lateChargeWithKey(key);
        if (previous.isPresent()) {
            var found = previous.get();
            if (!found.receivableId().equals(receivableId) || found.decision() != decision || !found.calculatedOn().equals(date)
                    || found.total().compareTo(expectedTotal) != 0) throw Idempotency.reused();
            return new Recorded<>(receivable, true);
        }
        if (receivable.cancelled()) throw new FinanceException("RECEIVABLE_CANCELLED", "Recebível cancelado não aceita lançamentos");
        LateCharge.Suggestion suggestion = suggest(receivable, date);
        if (!suggestion.due()) throw new FinanceException("LATE_CHARGE_NOT_DUE", "Não há juros nem multa a cobrar nesta data");
        if (suggestion.total().compareTo(expectedTotal) != 0)
            throw new FinanceException("LATE_CHARGE_CHANGED", "O valor de juros e multa mudou para " + suggestion.total().toPlainString()
                    + "; confira e confirme de novo");
        Instant now = clock.instant();
        UUID author = currentUser.requireId();
        UUID adjustmentId = null;
        String recordedReason = decision == LateCharge.Decision.WAIVED ? note : LateCharge.adjustmentReason(suggestion, note);
        try {
            if (decision == LateCharge.Decision.APPLIED) {
                Receivable.Adjustment adjustment = new Receivable.Adjustment(UUID.randomUUID(), Receivable.AdjustmentType.SURCHARGE,
                        suggestion.total(), recordedReason, now, author, key + ":acrescimo", null, Receivable.AdjustmentOrigin.LATE_CHARGE);
                repository.insertAdjustment(receivableId, adjustment);
                adjustmentId = adjustment.id();
            }
            repository.insertLateCharge(receivableId, new LateCharge.Recorded(UUID.randomUUID(), decision, date, suggestion.feePercent(),
                    suggestion.interestPercent(), suggestion.feeAmount(), suggestion.interestAmount(), suggestion.total(), recordedReason,
                    adjustmentId, now, author, true, List.of()), suggestion, key);
        } catch (DataIntegrityViolationException collision) {
            throw Idempotency.reused();
        }
        return new Recorded<>(repository.findReceivable(receivableId).orElseThrow(), false);
    }

    private LateCharge.Suggestion suggest(Receivable receivable, LocalDate on) {
        return LateCharge.suggest(receivable, repository.lateCharges(receivable.id()), on, new BigDecimal(repository.setting(FEE_KEY)),
                new BigDecimal(repository.setting(INTEREST_KEY)));
    }

    private LocalDate date(LocalDate on) {
        LocalDate today = receivables.today();
        LocalDate date = on == null ? today : on;
        if (date.isAfter(today)) throw new FinanceException("INVALID_FINANCE_ENTRY", "A data do cálculo não pode ser futura");
        return date;
    }
}
