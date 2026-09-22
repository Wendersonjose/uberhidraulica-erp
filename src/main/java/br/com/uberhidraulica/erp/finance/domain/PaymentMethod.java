package br.com.uberhidraulica.erp.finance.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Forma de pagamento configurável (DR-0015, F-09): cadastrada, renomeada e inativada, nunca excluída.
 *
 * <p>{@code cashSessionRequired} marca o dinheiro: sem sessão de caixa, a forma não pode ser usada em
 * novo recebimento ou pagamento (F-10). A marca não é editável pela API.</p>
 */
public record PaymentMethod(UUID id, String code, String name, boolean active, boolean cashSessionRequired,
                            Instant createdAt, Instant updatedAt) {

    public PaymentMethod {
        if (id == null || createdAt == null || updatedAt == null)
            throw new FinanceException("INVALID_PAYMENT_METHOD", "Dados obrigatórios da forma de pagamento ausentes");
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,39}"))
            throw new FinanceException("INVALID_PAYMENT_METHOD", "Código deve ter letras maiúsculas, números e sublinhado");
        name = Money.requiredText(name, "Nome da forma de pagamento", 80);
    }

    /** Renomear, inativar ou reativar nunca mudam a natureza da forma (revisão TASK-0015, F2). */
    public PaymentMethod withNameAndActive(String newName, boolean newActive, Instant now) {
        return new PaymentMethod(id, code, newName, newActive, cashSessionRequired, createdAt, now);
    }

    /** Forma utilizável agora em nova liquidação. */
    public void requireUsable() {
        if (!active) throw new FinanceException("PAYMENT_METHOD_INACTIVE", "Forma de pagamento inativa");
        if (cashSessionRequired)
            throw new FinanceException("CASH_SESSION_REQUIRED",
                    "Recebimento e pagamento em dinheiro exigem sessão de caixa, ainda indisponível; use outra forma");
    }
}
