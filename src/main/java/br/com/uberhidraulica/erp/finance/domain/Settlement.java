package br.com.uberhidraulica.erp.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Liquidação efetiva — recebimento de um recebível ou pagamento de uma conta a pagar.
 *
 * <p>Imutável depois de gravada. Correção de valor, data ou forma é estorno total e nova liquidação
 * (DR-0015, F-07). {@code paymentMethodName} é snapshot: renomear a forma não reescreve o histórico.</p>
 */
public record Settlement(UUID id, UUID ownerId, BigDecimal amount, UUID paymentMethodId, String paymentMethodName,
                         LocalDate effectiveOn, String notes, Instant recordedAt, UUID recordedBy, String idempotencyKey,
                         Reversal reversal) {

    public Settlement {
        if (id == null || ownerId == null || paymentMethodId == null || effectiveOn == null || recordedAt == null
                || recordedBy == null || idempotencyKey == null)
            throw new FinanceException("INVALID_FINANCE_ENTRY", "Dados obrigatórios da liquidação ausentes");
        amount = Money.positive(amount, "Valor");
        paymentMethodName = Money.requiredText(paymentMethodName, "Forma de pagamento", 80);
        notes = Money.optionalText(notes, "Observação", 500);
    }

    /** Estorno total da liquidação: registro próprio, nunca edição da original. */
    public record Reversal(UUID id, String reason, Instant reversedAt, UUID reversedBy, String idempotencyKey) {
        public Reversal {
            if (id == null || reversedAt == null || reversedBy == null || idempotencyKey == null)
                throw new FinanceException("INVALID_FINANCE_ENTRY", "Dados obrigatórios do estorno ausentes");
            reason = Money.requiredText(reason, "Motivo do estorno", 500);
        }
    }

    public boolean reversed() { return reversal != null; }

    public boolean active() { return reversal == null; }

    /**
     * Mesmo pedido que originou esta liquidação (revisão TASK-0015, F4): dono, valor, forma, data efetiva e
     * observação normalizada.
     *
     * <p>Data omitida significa "hoje" <b>no instante do pedido original</b>, não no instante do retry: ela é
     * reconstruída de {@code recordedAt} no fuso da oficina. Um retry depois da meia-noite da mesma requisição
     * sem data continua sendo o mesmo pedido.</p>
     *
     * @param requestedDate data informada no retry, ou nulo quando omitida
     */
    public boolean sameRequest(UUID otherOwner, BigDecimal otherAmount, UUID otherMethod, LocalDate requestedDate, String otherNotes) {
        LocalDate intended = requestedDate != null ? requestedDate : LocalDate.ofInstant(recordedAt, Money.WORKSHOP_ZONE);
        return ownerId.equals(otherOwner) && amount.compareTo(otherAmount) == 0 && paymentMethodId.equals(otherMethod)
                && effectiveOn.equals(intended) && java.util.Objects.equals(notes, Money.optionalText(otherNotes, "Observação", 500));
    }
}
