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

    /**
     * Avança a versão do orçamento, falhando se ele já tiver sido alterado desde a leitura.
     *
     * <p>É o ponto de serialização entre apresentar e decidir: as duas operações tocam a mesma linha,
     * então a que chegar depois encontra a versão mudada em vez de gravar sobre uma leitura vencida.</p>
     *
     * @return {@code false} quando outra operação alterou o orçamento no intervalo
     */
    boolean touch(UUID quoteId, long expectedVersion);
}
