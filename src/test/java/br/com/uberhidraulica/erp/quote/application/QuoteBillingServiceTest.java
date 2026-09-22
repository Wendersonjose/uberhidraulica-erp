package br.com.uberhidraulica.erp.quote.application;

import br.com.uberhidraulica.erp.quote.QuoteBillingQuery;
import br.com.uberhidraulica.erp.quote.domain.DecisionType;
import br.com.uberhidraulica.erp.quote.domain.Quote;
import br.com.uberhidraulica.erp.quote.domain.QuoteItem;
import br.com.uberhidraulica.erp.quote.domain.QuoteItemRevision;
import br.com.uberhidraulica.erp.quote.domain.QuoteRevision;
import br.com.uberhidraulica.erp.quote.domain.RevisionStatus;
import br.com.uberhidraulica.erp.quote.port.PublicQuoteRepositoryPort;
import br.com.uberhidraulica.erp.quote.port.QuoteRepositoryPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Base comercial de faturamento (DR-0015, F-02): só entra a versão corrente de cada item com decisão
 * APPROVE — nunca rejeitada, pendente, substituída ou só em rascunho. Sem banco.
 */
class QuoteBillingServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    private static final UUID USER = UUID.randomUUID();
    private static final UUID WORK_ORDER = UUID.randomUUID();

    private final QuoteRepositoryPort quotes = mock(QuoteRepositoryPort.class);
    private final PublicQuoteRepositoryPort decisions = mock(PublicQuoteRepositoryPort.class);
    private final QuoteBillingService service = new QuoteBillingService(quotes, decisions);

    /** Monta um orçamento com itens e apresentações a partir de uma descrição compacta. */
    private final class Builder {
        final UUID quoteId = UUID.randomUUID();
        final List<QuoteItem> items = new ArrayList<>();
        final List<QuoteRevision> revisions = new ArrayList<>();
        final Map<UUID, DecisionType> decided = new HashMap<>();

        QuoteItemRevision item(String description, String price, int versions) {
            UUID itemId = UUID.randomUUID();
            List<QuoteItemRevision> list = new ArrayList<>();
            QuoteItemRevision current = QuoteItemRevision.first(quoteId, itemId, description, BigDecimal.ONE, new BigDecimal(price), null, null, NOW, USER);
            list.add(current);
            for (int i = 1; i < versions; i++) {
                current = current.next(description + " v" + (i + 1), BigDecimal.ONE, new BigDecimal(price).add(BigDecimal.TEN), null, null, NOW, USER);
                list.add(current);
            }
            items.add(new QuoteItem(itemId, quoteId, null, null, NOW, USER, list));
            return list.get(0);
        }

        QuoteItemRevision versionOf(QuoteItemRevision first, int sequence) {
            return items.stream().filter(item -> item.id().equals(first.quoteItemId())).findFirst().orElseThrow()
                    .revisions().get(sequence - 1);
        }

        void present(QuoteItemRevision... itemRevisions) {
            List<QuoteRevision.Entry> entries = new ArrayList<>();
            for (int i = 0; i < itemRevisions.length; i++) entries.add(new QuoteRevision.Entry(itemRevisions[i].id(), i + 1));
            revisions.add(new QuoteRevision(UUID.randomUUID(), quoteId, revisions.size() + 1, RevisionStatus.PRESENTED, NOW,
                    NOW.plusSeconds(3600), NOW, USER, 0, entries));
        }

        void draft(QuoteItemRevision... itemRevisions) {
            List<QuoteRevision.Entry> entries = new ArrayList<>();
            for (int i = 0; i < itemRevisions.length; i++) entries.add(new QuoteRevision.Entry(itemRevisions[i].id(), i + 1));
            revisions.add(new QuoteRevision(UUID.randomUUID(), quoteId, revisions.size() + 1, RevisionStatus.DRAFT, null, null,
                    NOW, USER, 0, entries));
        }

        Quote build() {
            when(decisions.decisionsByQuote(quoteId)).thenReturn(decided);
            return new Quote(quoteId, WORK_ORDER, 0, NOW, USER, revisions, items);
        }
    }

    private List<QuoteBillingQuery.BillingCandidate> candidates(Quote... quoteList) {
        when(quotes.findByWorkOrderId(WORK_ORDER)).thenReturn(List.of(quoteList));
        return service.billingCandidates(WORK_ORDER);
    }

    @Test
    void onlyApprovedItemsCountAndRejectedOrPendingAreLeftOut() {
        Builder quote = new Builder();
        var approved = quote.item("Troca de retentor", "350.00", 1);
        var rejected = quote.item("Pintura", "900.00", 1);
        var pending = quote.item("Alinhamento", "120.00", 1);
        quote.present(approved, rejected, pending);
        quote.decided.put(approved.id(), DecisionType.APPROVE);
        quote.decided.put(rejected.id(), DecisionType.REJECT);

        var result = candidates(quote.build());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).approvedTotal()).isEqualByComparingTo("350.00");
        assertThat(result.get(0).lines()).extracting(QuoteBillingQuery.BillingLine::quoteItemRevisionId).containsExactly(approved.id());
    }

    @Test
    void approvalOfASupersededVersionIsNotTransferredToTheCurrentOne() {
        Builder quote = new Builder();
        var first = quote.item("Bomba", "1000.00", 2);
        quote.present(first);
        quote.decided.put(first.id(), DecisionType.APPROVE);
        quote.present(quote.versionOf(first, 2));

        assertThat(candidates(quote.build())).isEmpty();
    }

    @Test
    void aDraftOnlyVersionDoesNotReplaceTheApprovedPresentedOne() {
        Builder quote = new Builder();
        var first = quote.item("Bomba", "1000.00", 2);
        quote.present(first);
        quote.decided.put(first.id(), DecisionType.APPROVE);
        quote.draft(quote.versionOf(first, 2));

        var result = candidates(quote.build());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).approvedTotal()).isEqualByComparingTo("1000.00");
    }

    @Test
    void approvedCurrentVersionOfARevisedItemIsBilledAtItsOwnPrice() {
        Builder quote = new Builder();
        var first = quote.item("Bomba", "1000.00", 2);
        quote.present(first);
        quote.decided.put(first.id(), DecisionType.REJECT);
        var second = quote.versionOf(first, 2);
        quote.present(second);
        quote.decided.put(second.id(), DecisionType.APPROVE);

        assertThat(candidates(quote.build()).get(0).approvedTotal()).isEqualByComparingTo("1010.00");
    }

    @Test
    void everyQuoteWithAnApprovalIsACandidateAndNoneIsChosenHere() {
        Builder one = new Builder();
        var a = one.item("A", "10.00", 1);
        one.present(a);
        one.decided.put(a.id(), DecisionType.APPROVE);
        Builder other = new Builder();
        var b = other.item("B", "20.00", 1);
        other.present(b);
        other.decided.put(b.id(), DecisionType.APPROVE);
        Builder none = new Builder();
        var c = none.item("C", "30.00", 1);
        none.present(c);

        assertThat(candidates(one.build(), other.build(), none.build()))
                .extracting(QuoteBillingQuery.BillingCandidate::quoteId).containsExactly(one.quoteId, other.quoteId);
    }
}
