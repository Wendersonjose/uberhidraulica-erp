package br.com.uberhidraulica.erp.quote.infrastructure.persistence;

import br.com.uberhidraulica.erp.quote.domain.Quote;
import br.com.uberhidraulica.erp.quote.domain.QuoteItem;
import br.com.uberhidraulica.erp.quote.domain.QuoteItemRevision;
import br.com.uberhidraulica.erp.quote.domain.QuoteRevision;
import br.com.uberhidraulica.erp.quote.port.QuoteRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class JpaQuoteRepositoryAdapter implements QuoteRepositoryPort {
    private final QuoteJpaRepository quotes;
    private final QuoteRevisionJpaRepository revisions;
    private final QuoteItemJpaRepository items;
    private final QuoteItemRevisionJpaRepository itemRevisions;
    private final QuoteRevisionItemJpaRepository revisionItems;

    JpaQuoteRepositoryAdapter(QuoteJpaRepository quotes, QuoteRevisionJpaRepository revisions,
                              QuoteItemJpaRepository items, QuoteItemRevisionJpaRepository itemRevisions,
                              QuoteRevisionItemJpaRepository revisionItems) {
        this.quotes = quotes;
        this.revisions = revisions;
        this.items = items;
        this.itemRevisions = itemRevisions;
        this.revisionItems = revisionItems;
    }

    @Override
    public Quote create(Quote quote) {
        QuoteEntity entity = new QuoteEntity();
        entity.id = quote.id();
        entity.workOrderId = quote.workOrderId();
        entity.version = quote.version();
        entity.createdAt = quote.createdAt();
        entity.createdBy = quote.createdBy();
        quotes.saveAndFlush(entity);
        return load(entity);
    }

    @Override
    public Optional<Quote> findById(UUID id) { return quotes.findById(id).map(this::load); }

    @Override
    public List<Quote> findByWorkOrderId(UUID workOrderId) {
        return quotes.findByWorkOrderIdOrderByCreatedAtAscIdAsc(workOrderId).stream().map(this::load).toList();
    }

    @Override
    public QuoteItem createItem(QuoteItem item) {
        QuoteItemEntity entity = new QuoteItemEntity();
        entity.id = item.id();
        entity.quoteId = item.quoteId();
        entity.workOrderServiceId = item.workOrderServiceId();
        entity.createdAt = item.createdAt();
        entity.createdBy = item.createdBy();
        items.saveAndFlush(entity);
        return item;
    }

    @Override
    public QuoteItemRevision createItemRevision(QuoteItemRevision revision) {
        QuoteItemRevisionEntity entity = new QuoteItemRevisionEntity();
        entity.id = revision.id();
        entity.quoteId = revision.quoteId();
        entity.quoteItemId = revision.quoteItemId();
        entity.revisionSequence = revision.revisionSequence();
        entity.description = revision.description();
        entity.quantity = revision.quantity();
        entity.unitPrice = revision.unitPrice();
        entity.totalPrice = revision.totalPrice();
        entity.revisionReason = revision.revisionReason();
        entity.createdAt = revision.createdAt();
        entity.createdBy = revision.createdBy();
        itemRevisions.saveAndFlush(entity);
        return revision;
    }

    @Override
    public QuoteRevision createRevision(QuoteRevision revision) {
        QuoteRevisionEntity entity = new QuoteRevisionEntity();
        entity.id = revision.id();
        entity.quoteId = revision.quoteId();
        entity.revisionNumber = revision.revisionNumber();
        entity.status = revision.status();
        entity.presentedAt = revision.presentedAt();
        entity.validUntil = revision.validUntil();
        entity.createdAt = revision.createdAt();
        entity.createdBy = revision.createdBy();
        entity.version = revision.version();
        revisions.saveAndFlush(entity);
        for (QuoteRevision.Entry entry : revision.entries()) {
            QuoteRevisionItemEntity link = new QuoteRevisionItemEntity();
            link.quoteRevisionId = revision.id();
            link.quoteItemRevisionId = entry.quoteItemRevisionId();
            link.quoteId = revision.quoteId();
            link.displayOrder = entry.displayOrder();
            revisionItems.saveAndFlush(link);
        }
        return revision;
    }

    @Override
    public boolean present(QuoteRevision revision, long expectedVersion) {
        return revisions.present(revision.id(), revision.presentedAt(), revision.validUntil(), expectedVersion) == 1;
    }

    @Override
    public boolean touch(UUID quoteId, long expectedVersion) {
        return quotes.touch(quoteId, expectedVersion) == 1;
    }

    private Quote load(QuoteEntity entity) {
        List<QuoteRevisionItemEntity> links = revisionItems.findByQuoteIdOrderByDisplayOrderAsc(entity.id);
        Map<UUID, List<QuoteRevision.Entry>> entriesByRevision = links.stream()
                .collect(Collectors.groupingBy(link -> link.quoteRevisionId,
                        Collectors.mapping(link -> new QuoteRevision.Entry(link.quoteItemRevisionId, link.displayOrder),
                                Collectors.toList())));
        // Posição em que cada versão comercial foi exibida; usada para ordenar os itens do orçamento.
        Map<UUID, Integer> displayOrderByItemRevision = links.stream()
                .collect(Collectors.toMap(link -> link.quoteItemRevisionId, link -> link.displayOrder,
                        Math::min));
        Map<UUID, List<QuoteItemRevision>> revisionsByItem =
                itemRevisions.findByQuoteIdOrderByRevisionSequenceAsc(entity.id).stream()
                        .map(this::map)
                        .collect(Collectors.groupingBy(QuoteItemRevision::quoteItemId, Collectors.toList()));

        List<QuoteRevision> quoteRevisions = revisions.findByQuoteIdOrderByRevisionNumberAsc(entity.id).stream()
                .map(revision -> new QuoteRevision(revision.id, revision.quoteId, revision.revisionNumber,
                        revision.status, revision.presentedAt, revision.validUntil, revision.createdAt,
                        revision.createdBy, revision.version,
                        entriesByRevision.getOrDefault(revision.id, List.of())))
                .toList();

        List<QuoteItem> quoteItems = items.findByQuoteIdOrderByCreatedAtAscIdAsc(entity.id).stream()
                .map(item -> new QuoteItem(item.id, item.quoteId, item.workOrderServiceId, item.createdAt,
                        item.createdBy, revisionsByItem.getOrDefault(item.id, List.of())))
                .sorted(Comparator
                        .comparingInt((QuoteItem item) -> displayOrder(item, displayOrderByItemRevision))
                        .thenComparing(QuoteItem::createdAt)
                        .thenComparing(item -> item.id().toString()))
                .toList();

        return new Quote(entity.id, entity.workOrderId, entity.version, entity.createdAt, entity.createdBy,
                quoteRevisions, quoteItems);
    }

    /** Menor posição de exibição entre as versões do item; itens nunca exibidos vão para o fim. */
    private static int displayOrder(QuoteItem item, Map<UUID, Integer> displayOrderByItemRevision) {
        return item.revisions().stream()
                .map(revision -> displayOrderByItemRevision.get(revision.id()))
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    private QuoteItemRevision map(QuoteItemRevisionEntity entity) {
        return new QuoteItemRevision(entity.id, entity.quoteId, entity.quoteItemId, entity.revisionSequence,
                entity.description, entity.quantity, entity.unitPrice, entity.totalPrice, entity.revisionReason,
                entity.createdAt, entity.createdBy);
    }
}
