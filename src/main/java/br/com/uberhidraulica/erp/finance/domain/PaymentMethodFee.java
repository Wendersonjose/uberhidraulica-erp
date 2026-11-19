package br.com.uberhidraulica.erp.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Taxa de uma forma de pagamento para um número de parcelas (DR-0024, Q4): percentual sobre o valor bruto e prazo,
 * em dias corridos, até o repasse. Configuração gerencial: não há integração com adquirente (DR-0021).
 */
public record PaymentMethodFee(int installments, BigDecimal feePercent, int settlementDays) {
    public static final int MAX_INSTALLMENTS = 24;
    public static final int MAX_SETTLEMENT_DAYS = 365;

    public PaymentMethodFee {
        if (installments < 1 || installments > MAX_INSTALLMENTS)
            throw new FinanceException("INVALID_PAYMENT_METHOD_FEE", "Parcelas devem estar entre 1 e " + MAX_INSTALLMENTS);
        if (feePercent == null || feePercent.signum() < 0 || feePercent.compareTo(BigDecimal.valueOf(100)) >= 0
                || feePercent.stripTrailingZeros().scale() > 2)
            throw new FinanceException("INVALID_PAYMENT_METHOD_FEE", "Taxa deve estar entre 0 e 99,99%, com até duas casas");
        if (settlementDays < 0 || settlementDays > MAX_SETTLEMENT_DAYS)
            throw new FinanceException("INVALID_PAYMENT_METHOD_FEE", "Prazo de repasse deve estar entre 0 e " + MAX_SETTLEMENT_DAYS + " dias");
        feePercent = feePercent.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Uma linha por número de parcelas: duas linhas para o mesmo número tornariam a taxa ambígua. */
    public static List<PaymentMethodFee> checkSchedule(List<PaymentMethodFee> fees) {
        List<PaymentMethodFee> copy = List.copyOf(fees == null ? List.of() : fees);
        Set<Integer> seen = new HashSet<>();
        for (PaymentMethodFee fee : copy)
            if (!seen.add(fee.installments()))
                throw new FinanceException("INVALID_PAYMENT_METHOD_FEE", "Número de parcelas repetido: " + fee.installments());
        return copy.stream().sorted(java.util.Comparator.comparingInt(PaymentMethodFee::installments)).toList();
    }

    /** Taxa do recebimento, arredondada uma vez em HALF_UP (DR-0007), e a data prevista do repasse. */
    public Settlement.CardFee apply(BigDecimal grossAmount, LocalDate receivedOn) {
        BigDecimal amount = grossAmount.multiply(feePercent).divide(BigDecimal.valueOf(100), Money.SCALE, RoundingMode.HALF_UP);
        return new Settlement.CardFee(installments, feePercent, amount, receivedOn.plusDays(settlementDays));
    }
}
