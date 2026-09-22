package br.com.uberhidraulica.erp.quote.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Identidade lógica permanente de um item comercial do orçamento.
 *
 * <p>O item não carrega preço nem descrição: essas condições pertencem às suas versões. Trocar o
 * preço apresentado não cria outro item, cria outra versão do mesmo item.</p>
 */
public record QuoteItem(UUID id, UUID quoteId, UUID workOrderServiceId, UUID workOrderProductId, Instant createdAt, UUID createdBy,
                        List<QuoteItemRevision> revisions) {

    public QuoteItem {
        if (id == null || quoteId == null || createdAt == null || createdBy == null)
            throw new QuoteException("INVALID_QUOTE_ITEM", "Dados obrigatórios ausentes");
        revisions = revisions == null ? List.of()
                : revisions.stream().sorted(Comparator.comparingInt(QuoteItemRevision::revisionSequence)).toList();
    }

    /**
     * {@code workOrderProductId} é o item físico específico da OS cobrado por este item (DR-0008): no máximo
     * um, da mesma OS do orçamento. Como o vínculo de serviço, pertence à identidade e não muda entre versões.
     */
    public static QuoteItem create(UUID quoteId, UUID workOrderServiceId, UUID workOrderProductId, Instant now, UUID author) {
        return new QuoteItem(UUID.randomUUID(), quoteId, workOrderServiceId, workOrderProductId, now, author, List.of());
    }

    public QuoteItemRevision latestRevision() {
        if (revisions.isEmpty()) throw new QuoteException("INVALID_QUOTE_ITEM", "Item comercial sem versão");
        return revisions.get(revisions.size() - 1);
    }
}
