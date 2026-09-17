package br.com.uberhidraulica.erp.quote.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Decisão do cliente registrada por um usuário interno (DR-0013): o cliente decidiu pessoalmente, por
 * telefone ou mensagem, e alguém da oficina registra.
 *
 * <p>A evidência é o usuário autenticado que registrou, o canal de contato e o instante do servidor —
 * não há IP nem documento do cliente, porque não foi o cliente quem enviou.</p>
 */
public record InternalDecisionSubmission(UUID id, UUID quoteRevisionId, UUID quoteId, UUID recordedBy,
                                         String contactChannel, String authorizedBy, String notes,
                                         Instant occurredAt, List<DecisionSubmission.Decision> decisions) {
    public static final Set<String> CONTACT_CHANNELS = Set.of("PRESENCIAL", "TELEFONE", "WHATSAPP", "EMAIL", "OUTRO");

    public InternalDecisionSubmission {
        if (id == null || quoteRevisionId == null || quoteId == null || recordedBy == null || occurredAt == null)
            throw invalid("Dados obrigatórios ausentes");
        if (contactChannel == null || !CONTACT_CHANNELS.contains(contactChannel))
            throw invalid("Canal de contato inválido");
        authorizedBy = optional(authorizedBy, 200, "Nome de quem autorizou");
        notes = optional(notes, 500, "Observações");
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        if (decisions.isEmpty()) throw invalid("O registro precisa conter ao menos uma decisão");
    }

    private static String optional(String value, int max, String label) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > max) throw invalid(label + " excede o tamanho máximo");
        return value.trim();
    }

    private static QuoteException invalid(String message) { return new QuoteException("VALIDATION_ERROR", message); }
}
