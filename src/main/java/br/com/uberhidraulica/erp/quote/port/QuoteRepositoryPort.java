package br.com.uberhidraulica.erp.quote.port;

import br.com.uberhidraulica.erp.quote.domain.Quote;
import br.com.uberhidraulica.erp.quote.domain.QuoteItem;
import br.com.uberhidraulica.erp.quote.domain.QuoteItemRevision;
import br.com.uberhidraulica.erp.quote.domain.QuoteRevision;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuoteRepositoryPort {
    Quote create(Quote quote);

    /** Orçamento completo: apresentações, itens e versões comerciais, necessários à derivação da DR-0001. */
    Optional<Quote> findById(UUID id);

    List<Quote> findByWorkOrderId(UUID workOrderId);

    QuoteItem createItem(QuoteItem item);

    QuoteItemRevision createItemRevision(QuoteItemRevision revision);

    QuoteRevision createRevision(QuoteRevision revision);

    /**
     * Grava a apresentação usando controle otimista sobre a versão lida.
     *
     * @return {@code false} quando a revisão mudou desde a leitura, sem aplicar nada
     */
    boolean present(QuoteRevision revision, long expectedVersion);
}
