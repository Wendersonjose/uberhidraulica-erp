package br.com.uberhidraulica.erp.quote.application;

import br.com.uberhidraulica.erp.quote.QuoteBillingQuery;
import br.com.uberhidraulica.erp.quote.domain.DecisionType;
import br.com.uberhidraulica.erp.quote.domain.Quote;
import br.com.uberhidraulica.erp.quote.domain.QuoteItem;
import br.com.uberhidraulica.erp.quote.domain.QuoteItemRevision;
import br.com.uberhidraulica.erp.quote.port.PublicQuoteRepositoryPort;
import br.com.uberhidraulica.erp.quote.port.QuoteRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Base comercial efetiva de faturamento, derivada das apresentações e decisões já gravadas. */
@Service
class QuoteBillingService implements QuoteBillingQuery {
    private final QuoteRepositoryPort quotes;
    private final PublicQuoteRepositoryPort decisions;

    QuoteBillingService(QuoteRepositoryPort quotes, PublicQuoteRepositoryPort decisions) {
        this.quotes = quotes;
        this.decisions = decisions;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BillingCandidate> billingCandidates(UUID workOrderId) {
        List<BillingCandidate> candidates = new ArrayList<>();
        for (Quote quote : quotes.findByWorkOrderId(workOrderId)) {
            Map<UUID, DecisionType> decided = decisions.decisionsByQuote(quote.id());
            List<BillingLine> lines = quote.items().stream()
                    .map(item -> approvedCurrentRevision(quote, item, decided).map(revision -> line(item, revision)))
                    .flatMap(Optional::stream)
                    .toList();
            if (lines.isEmpty()) continue;
            BigDecimal total = lines.stream().map(BillingLine::totalPrice).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(QuoteItemRevision.CHARGED_SCALE, QuoteItemRevision.CHARGED_ROUNDING);
            candidates.add(new BillingCandidate(quote.id(), quote.workOrderId(), total, lines));
        }
        return candidates;
    }

    /**
     * Versão corrente = a de maior sequência entre as efetivamente apresentadas, isto é, a não substituída.
     * Só conta quando é ela, e não uma anterior, que tem decisão {@code APPROVE}.
     */
    private static Optional<QuoteItemRevision> approvedCurrentRevision(Quote quote, QuoteItem item, Map<UUID, DecisionType> decided) {
        return item.revisions().stream()
                .filter(revision -> quote.presentedSomewhere(revision.id()))
                .max(Comparator.comparingInt(QuoteItemRevision::revisionSequence))
                .filter(current -> decided.get(current.id()) == DecisionType.APPROVE);
    }

    private static BillingLine line(QuoteItem item, QuoteItemRevision revision) {
        return new BillingLine(item.id(), revision.id(), revision.description(), revision.quantity(), revision.unitPrice(),
                revision.discountAmount(), revision.totalPrice(), item.workOrderServiceId(), item.workOrderProductId());
    }
}
