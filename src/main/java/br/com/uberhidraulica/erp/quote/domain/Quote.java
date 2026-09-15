package br.com.uberhidraulica.erp.quote.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Identidade lógica do orçamento de uma Ordem de Serviço.
 *
 * <p>O orçamento não é uma apresentação: ele agrupa as apresentações ({@link QuoteRevision}) e os
 * itens comerciais ({@link QuoteItem}). É aqui que vive a derivação de obsolescência da DR-0001,
 * porque responder "esta versão ainda pode receber decisão?" exige olhar todas as apresentações.</p>
 */
public record Quote(UUID id, UUID workOrderId, long version, Instant createdAt, UUID createdBy,
                    List<QuoteRevision> revisions, List<QuoteItem> items) {

    public Quote {
        if (id == null || workOrderId == null || createdAt == null || createdBy == null)
            throw new QuoteException("INVALID_QUOTE", "Dados obrigatórios ausentes");
        revisions = revisions == null ? List.of()
                : revisions.stream().sorted(Comparator.comparingInt(QuoteRevision::revisionNumber)).toList();
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static Quote open(UUID workOrderId, Instant now, UUID author) {
        return new Quote(UUID.randomUUID(), workOrderId, 0L, now, author, List.of(), List.of());
    }

    public int nextRevisionNumber() {
        return revisions.stream().mapToInt(QuoteRevision::revisionNumber).max().orElse(0) + 1;
    }

    public Optional<QuoteRevision> revision(UUID revisionId) {
        return revisions.stream().filter(revision -> revision.id().equals(revisionId)).findFirst();
    }

    public Optional<QuoteItem> item(UUID itemId) {
        return items.stream().filter(item -> item.id().equals(itemId)).findFirst();
    }

    public Optional<QuoteItemRevision> itemRevision(UUID itemRevisionId) {
        return items.stream().flatMap(item -> item.revisions().stream())
                .filter(revision -> revision.id().equals(itemRevisionId)).findFirst();
    }

    /** A apresentação mais recente entre as efetivamente apresentadas, se houver. */
    public Optional<QuoteRevision> latestPresentedRevision() {
        return revisions.stream().filter(QuoteRevision::presented)
                .max(Comparator.comparingInt(QuoteRevision::revisionNumber));
    }

    /**
     * Uma nova apresentação não pode ter número menor que outra já apresentada, senão a sequência
     * do que o cliente recebeu deixaria de ser legível.
     */
    public void checkPresentable(QuoteRevision revision) {
        latestPresentedRevision().ifPresent(presented -> {
            if (revision.revisionNumber() < presented.revisionNumber())
                throw new QuoteException("QUOTE_REVISION_OUT_OF_ORDER",
                        "Revisão anterior à última apresentada não pode ser apresentada");
        });
    }

    /**
     * Regra oficial da DR-0001, opção B: a versão perde a aptidão a decisão apenas quando outra
     * versão do <b>mesmo item</b>, com sequência superior, foi efetivamente apresentada.
     *
     * <p>Rascunho não substitui (RN-19) e a simples existência de uma revisão global posterior
     * também não (RN-21): um complemento que reaproveita a mesma versão a mantém decidível (RN-22).</p>
     */
    public boolean superseded(QuoteItemRevision itemRevision) {
        return items.stream()
                .filter(item -> item.id().equals(itemRevision.quoteItemId()))
                .flatMap(item -> item.revisions().stream())
                .anyMatch(candidate -> candidate.revisionSequence() > itemRevision.revisionSequence()
                        && presentedSomewhere(candidate.id()));
    }

    public boolean presentedSomewhere(UUID itemRevisionId) {
        return revisions.stream().anyMatch(revision -> revision.presented() && revision.contains(itemRevisionId));
    }

    /**
     * Apresentações que contêm esta versão e ainda estão dentro da validade comercial.
     *
     * <p>Uma mesma versão pode aparecer em mais de uma apresentação por complemento; basta que uma
     * delas esteja válida para que a decisão continue possível.</p>
     */
    private boolean withinValidity(UUID itemRevisionId, Instant now) {
        return revisions.stream().anyMatch(revision -> revision.presented()
                && revision.contains(itemRevisionId) && !revision.expiredAt(now));
    }

    /** Estado derivado de aptidão a decisão; nenhuma coluna guarda este valor. */
    public DecisionAvailability availability(QuoteItemRevision itemRevision, Instant now) {
        if (!presentedSomewhere(itemRevision.id())) return DecisionAvailability.NOT_PRESENTED;
        if (superseded(itemRevision)) return DecisionAvailability.SUPERSEDED;
        if (!withinValidity(itemRevision.id(), now)) return DecisionAvailability.EXPIRED;
        return DecisionAvailability.AVAILABLE;
    }
}
