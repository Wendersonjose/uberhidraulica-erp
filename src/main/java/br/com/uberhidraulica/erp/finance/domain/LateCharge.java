package br.com.uberhidraulica.erp.finance.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Juros e multa por atraso (DR-0024, Q3): calculados e sugeridos, nunca cobrados sem decisão do operador.
 *
 * <p>Base: o principal em aberto de cada parcela vencida — nunca o acréscimo, para não cobrar juros sobre juros.
 * Multa: percentual único por parcela, uma vez. Juros: percentual mensal <i>pro rata die</i> (mês comercial de 30
 * dias), contados do vencimento ou do último cálculo já decidido para a parcela. Cada valor é arredondado uma única
 * vez, em HALF_UP, no momento em que se torna cobrável (DR-0007).</p>
 *
 * <p>Decidido = aplicado (o acréscimo existe e não foi estornado) ou dispensado com motivo. Estornar o acréscimo
 * devolve a parcela à sugestão, como se aquele cálculo não tivesse existido.</p>
 */
public final class LateCharge {
    public static final BigDecimal MAX_FEE_PERCENT = new BigDecimal("20.00");
    public static final BigDecimal MAX_INTEREST_PERCENT = new BigDecimal("10.00");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal COMMERCIAL_MONTH = BigDecimal.valueOf(30);

    private LateCharge() {}

    public enum Decision { APPLIED, WAIVED }

    /** Parcela de um cálculo já decidido. */
    public record RecordedLine(int planVersion, int installmentNumber, LocalDate calculatedOn, BigDecimal feeAmount) {}

    /** Cálculo decidido; {@code counts} é falso quando foi aplicado e o acréscimo, estornado depois. */
    public record Recorded(UUID id, Decision decision, LocalDate calculatedOn, BigDecimal feePercent, BigDecimal interestPercent,
                           BigDecimal feeAmount, BigDecimal interestAmount, BigDecimal totalAmount, String reason, UUID adjustmentId,
                           Instant recordedAt, UUID recordedBy, boolean counts, List<RecordedLine> lines) {}

    public record Line(int installmentNumber, LocalDate dueDate, BigDecimal baseAmount, LocalDate interestFrom, long days,
                       BigDecimal feeAmount, BigDecimal interestAmount) {
        public BigDecimal total() { return feeAmount.add(interestAmount); }
    }

    public record Suggestion(LocalDate calculatedOn, int planVersion, BigDecimal feePercent, BigDecimal interestPercent, List<Line> lines) {
        public BigDecimal feeAmount() { return Money.sum(lines.stream().map(Line::feeAmount).toList()); }

        public BigDecimal interestAmount() { return Money.sum(lines.stream().map(Line::interestAmount).toList()); }

        public BigDecimal total() { return feeAmount().add(interestAmount()); }

        public boolean due() { return total().signum() > 0; }
    }

    /** Percentual de configuração: de 0 a {@code max}, no máximo duas casas. */
    public static BigDecimal percent(BigDecimal value, BigDecimal max, String label) {
        if (value == null || value.signum() < 0 || value.compareTo(max) > 0 || value.stripTrailingZeros().scale() > 2)
            throw new FinanceException("INVALID_FINANCE_SETTING", label + " deve estar entre 0 e " + max.toPlainString() + "%, com até duas casas");
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * Calcula a sugestão em {@code on} para o recebível, descontando o que já foi decidido no plano corrente.
     * Parcela sem multa nem juros a cobrar fica de fora.
     */
    public static Suggestion suggest(Receivable receivable, List<Recorded> history, LocalDate on, BigDecimal feePercent,
                                     BigDecimal interestPercent) {
        int planVersion = receivable.plan().version();
        List<RecordedLine> decided = history.stream().filter(Recorded::counts).flatMap(r -> r.lines().stream())
                .filter(line -> line.planVersion() == planVersion).toList();
        List<Line> lines = new ArrayList<>();
        if (!receivable.cancelled())
            for (Receivable.InstallmentPosition position : receivable.installmentPositions()) {
                if (!position.open() || !position.dueDate().isBefore(on)) continue;
                List<RecordedLine> previous = decided.stream().filter(l -> l.installmentNumber() == position.number()).toList();
                BigDecimal base = position.openPrincipal();
                BigDecimal fee = previous.isEmpty() ? round(base.multiply(feePercent).divide(HUNDRED, MathContext.DECIMAL128)) : Money.zero();
                LocalDate from = previous.stream().map(RecordedLine::calculatedOn).max(Comparator.naturalOrder())
                        .filter(last -> last.isAfter(position.dueDate())).orElse(position.dueDate());
                long days = Math.max(0, ChronoUnit.DAYS.between(from, on));
                BigDecimal interest = round(base.multiply(interestPercent).multiply(BigDecimal.valueOf(days))
                        .divide(HUNDRED.multiply(COMMERCIAL_MONTH), MathContext.DECIMAL128));
                if (fee.signum() > 0 || interest.signum() > 0)
                    lines.add(new Line(position.number(), position.dueDate(), base, from, days, fee, interest));
            }
        return new Suggestion(on, planVersion, feePercent, interestPercent, List.copyOf(lines));
    }

    private static BigDecimal round(BigDecimal value) { return value.setScale(Money.SCALE, RoundingMode.HALF_UP); }

    /** Texto do acréscimo gerado: deixa no histórico a regra e o período, sem depender do registro do cálculo. */
    public static String adjustmentReason(Suggestion suggestion, String note) {
        String base = "Juros e multa por atraso até " + suggestion.calculatedOn().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                + " (multa " + suggestion.feePercent().toPlainString().replace('.', ',') + "%, juros "
                + suggestion.interestPercent().toPlainString().replace('.', ',') + "% a.m.)";
        return note == null || note.isBlank() ? base : base + ": " + note.trim();
    }
}
