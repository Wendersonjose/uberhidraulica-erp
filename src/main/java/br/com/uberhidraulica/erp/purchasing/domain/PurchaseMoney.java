package br.com.uberhidraulica.erp.purchasing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Política monetária de Compras (`DR-0007`). Quatro casas no que é interno (custo unitário, preço unitário,
 * fator de conversão), duas no que é cobrado, {@link RoundingMode#HALF_UP} sempre, e total = soma de parcelas
 * já arredondadas — nunca arredondamento de operando antes da conta.
 */
public final class PurchaseMoney {
    /** Casas do que é cobrado e apresentado. */
    public static final int CHARGED_SCALE = 2;
    /** Casas do que é interno: custo e preço unitário, fator de conversão. */
    public static final int UNIT_SCALE = 4;
    /** Casas de quantidade física. */
    public static final int QUANTITY_SCALE = 3;

    private PurchaseMoney() {}

    /** Arredonda para o que é cobrado (2 casas, HALF_UP). */
    public static BigDecimal charged(BigDecimal value) {
        return nullToZero(value).setScale(CHARGED_SCALE, RoundingMode.HALF_UP);
    }

    /** Arredonda para valor unitário interno (4 casas, HALF_UP). */
    public static BigDecimal unit(BigDecimal value) {
        return nullToZero(value).setScale(UNIT_SCALE, RoundingMode.HALF_UP);
    }

    /** Arredonda quantidade física (3 casas, HALF_UP). */
    public static BigDecimal quantity(BigDecimal value) {
        return nullToZero(value).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Soma de parcelas já arredondadas. Recebe valores na escala final e não re-arredonda operando: é a regra
     * do `DR-0007` para que o total apresentado seja exatamente a soma do que foi apresentado em cada linha.
     */
    public static BigDecimal sumCharged(Collection<BigDecimal> parcels) {
        return parcels.stream().map(PurchaseMoney::charged).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CHARGED_SCALE, RoundingMode.UNNECESSARY);
    }

    /** Converte para centavos inteiros. Usado no plano de parcelas, onde o resíduo não pode se perder. */
    public static long cents(BigDecimal value) {
        return charged(value).movePointRight(CHARGED_SCALE).longValueExact();
    }

    /** Volta de centavos inteiros para valor cobrado. */
    public static BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents, CHARGED_SCALE);
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    public static void requirePositive(BigDecimal value, String label) {
        if (!isPositive(value)) throw new PurchasingException("PURCHASE_INVALID", label + " deve ser maior que zero");
    }
}
