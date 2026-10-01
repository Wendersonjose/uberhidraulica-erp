package br.com.uberhidraulica.erp.quote.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Apresentação global do orçamento.
 *
 * <p>{@code DRAFT} é trabalho interno e não substitui a proposta que o cliente recebeu (DR-0001,
 * opção B). {@code PRESENTED} é o que ele recebeu, e só isso produz efeito comercial.</p>
 */
public record QuoteRevision(UUID id, UUID quoteId, int revisionNumber, RevisionStatus status,
                            Instant presentedAt, Instant validUntil, Instant createdAt, UUID createdBy,
                            long version, List<Entry> entries) {

    /** Validade comercial padrão da proposta apresentada, conforme REQ-ORC-001 seção 29. */
    public static final Duration DEFAULT_VALIDITY = Duration.ofDays(7);

    /** Uma versão comercial incluída nesta apresentação, na posição em que foi exibida. */
    public record Entry(UUID quoteItemRevisionId, int displayOrder) {
        public Entry {
            if (quoteItemRevisionId == null) throw invalid("Versão comercial ausente na apresentação");
            if (displayOrder <= 0) throw invalid("Ordem de exibição inválida");
        }
    }

    public QuoteRevision {
        if (id == null || quoteId == null || status == null || createdAt == null || createdBy == null)
            throw invalid("Dados obrigatórios ausentes");
        if (revisionNumber <= 0) throw invalid("Número da revisão inválido");
        if (status == RevisionStatus.PRESENTED) {
            if (presentedAt == null || validUntil == null) throw invalid("Apresentação exige instante e validade");
            if (validUntil.isBefore(presentedAt)) throw invalid("Validade comercial anterior à apresentação");
        }
        entries = entries == null ? List.of()
                : entries.stream().sorted(Comparator.comparingInt(Entry::displayOrder)).toList();
    }

    public static QuoteRevision draft(UUID quoteId, int revisionNumber, List<Entry> entries, Instant now, UUID author) {
        return new QuoteRevision(UUID.randomUUID(), quoteId, revisionNumber, RevisionStatus.DRAFT,
                null, null, now, author, 0L, entries);
    }

    /**
     * Apresenta a proposta ao cliente.
     *
     * <p>Exige pelo menos um item: apresentar uma proposta vazia daria ao cliente algo para decidir
     * que não existe, e iniciaria uma validade comercial sem conteúdo.</p>
     */
    public QuoteRevision present(Instant now, Duration validity) {
        if (status != RevisionStatus.PRESENTED) {
            if (entries.isEmpty()) throw new QuoteException("QUOTE_REVISION_EMPTY",
                    "Apresentação exige pelo menos um item comercial");
            return new QuoteRevision(id, quoteId, revisionNumber, RevisionStatus.PRESENTED,
                    now, now.plus(validity), createdAt, createdBy, version, entries);
        }
        throw new QuoteException("QUOTE_REVISION_ALREADY_PRESENTED", "Revisão já foi apresentada");
    }

    public boolean presented() { return status == RevisionStatus.PRESENTED; }

    /** Expiração é derivada: o estado nunca é gravado, e o relógio do servidor é a autoridade. */
    public boolean expiredAt(Instant now) {
        return presented() && validUntil.isBefore(now);
    }

    public boolean contains(UUID quoteItemRevisionId) {
        return entries.stream().anyMatch(entry -> entry.quoteItemRevisionId().equals(quoteItemRevisionId));
    }

    private static QuoteException invalid(String message) {
        return new QuoteException("INVALID_QUOTE_REVISION", message);
    }
}
