package br.com.uberhidraulica.erp.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;

/** Política monetária do Financeiro (DR-0007): valores cobrados com duas casas, sem arredondar entrada. */
public final class Money {
    public static final int SCALE = 2;
    /** Fuso da oficina (DR-0009): define "hoje", o vencimento e a data efetiva padrão. */
    public static final ZoneId WORKSHOP_ZONE = ZoneId.of("America/Sao_Paulo");

    private Money() {}

    public static BigDecimal zero() { return BigDecimal.ZERO.setScale(SCALE); }

    /** Valor positivo com no máximo duas casas; nunca arredonda o que o usuário informou. */
    public static BigDecimal positive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > SCALE)
            throw new FinanceException("INVALID_FINANCE_ENTRY", label + " deve ser positivo e possuir no máximo duas casas decimais");
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal sum(Collection<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static String requiredText(String value, String label, int max) {
        if (value == null || value.isBlank())
            throw new FinanceException("INVALID_FINANCE_ENTRY", label + " é obrigatório");
        String trimmed = value.trim();
        if (trimmed.length() > max) throw new FinanceException("INVALID_FINANCE_ENTRY", label + " excede " + max + " caracteres");
        return trimmed;
    }

    public static String optionalText(String value, String label, int max) {
        if (value == null || value.isBlank()) return null;
        return requiredText(value, label, max);
    }

    /** Data efetiva de recebimento ou pagamento: padrão hoje, nunca futura. */
    public static LocalDate effectiveDate(LocalDate informed, LocalDate today) {
        LocalDate date = informed == null ? today : informed;
        if (date.isAfter(today)) throw new FinanceException("INVALID_FINANCE_ENTRY", "Data efetiva não pode ser futura");
        return date;
    }
}
