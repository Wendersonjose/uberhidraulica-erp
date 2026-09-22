package br.com.uberhidraulica.erp.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Situação derivada de um recebível ou de uma conta a pagar; nenhuma coluna guarda este valor.
 *
 * <p>{@code VENCIDO} é {@code vencimento < hoje} com saldo em aberto (DR-0015, F-11) e não bloqueia nada.</p>
 */
public enum FinancialStatus {
    ABERTO, PARCIAL, VENCIDO, QUITADO, CANCELADO;

    public static FinancialStatus derive(boolean cancelled, BigDecimal settled, BigDecimal outstanding, LocalDate dueDate, LocalDate today) {
        if (cancelled) return CANCELADO;
        if (outstanding.signum() == 0) return QUITADO;
        if (dueDate.isBefore(today)) return VENCIDO;
        if (settled.signum() > 0) return PARCIAL;
        return ABERTO;
    }
}
