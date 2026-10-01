package br.com.uberhidraulica.erp.quote.application;

import br.com.uberhidraulica.erp.iam.CurrentUser;
import br.com.uberhidraulica.erp.iam.IamAuthorization;
import br.com.uberhidraulica.erp.workorder.WorkOrderCommercialEvents;
import br.com.uberhidraulica.erp.quote.domain.*;
import br.com.uberhidraulica.erp.quote.port.QuoteRepositoryPort;
import br.com.uberhidraulica.erp.workorder.WorkOrderQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class QuoteApplicationService {
    private final QuoteRepositoryPort repository;
    private final WorkOrderQuery workOrders;
    private final CurrentUser currentUser;
    private final IamAuthorization authorization;
    private final WorkOrderCommercialEvents workOrderEvents;
    private final Clock clock;

    public QuoteApplicationService(QuoteRepositoryPort repository, WorkOrderQuery workOrders,
                                   CurrentUser currentUser, IamAuthorization authorization,
                                   WorkOrderCommercialEvents workOrderEvents, Clock clock) {
        this.repository = repository;
        this.workOrders = workOrders;
        this.currentUser = currentUser;
        this.authorization = authorization;
        this.workOrderEvents = workOrderEvents;
        this.clock = clock;
    }

    /** Condição comercial pedida para um item em uma nova apresentação. */
    public record ItemSpec(UUID quoteItemId, UUID workOrderServiceId, UUID workOrderProductId, String description,
                           BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount, String revisionReason) {}

    public static final String DISCOUNT_PERMISSION = "QUOTE_DISCOUNT";

    @Transactional
    public Quote open(UUID workOrderId) {
        workOrders.workOrder(workOrderId)
                .orElseThrow(() -> new QuoteException("WORK_ORDER_NOT_FOUND", "Ordem de Serviço não encontrada"));
        return repository.create(Quote.open(workOrderId, Instant.now(clock), currentUser.requireId()));
    }

    @Transactional(readOnly = true)
    public List<Quote> listByWorkOrder(UUID workOrderId) {
        workOrders.workOrder(workOrderId)
                .orElseThrow(() -> new QuoteException("WORK_ORDER_NOT_FOUND", "Ordem de Serviço não encontrada"));
        return repository.findByWorkOrderId(workOrderId);
    }

    /**
     * Carrega o orçamento exigindo que ele pertença à OS informada.
     *
     * <p>A verificação não é redundante: sem ela, o identificador de um orçamento de outra OS seria
     * suficiente para lê-lo por um caminho que aparenta ser restrito àquela OS.</p>
     */
    @Transactional(readOnly = true)
    public Quote get(UUID workOrderId, UUID quoteId) {
        Quote quote = repository.findById(quoteId)
                .orElseThrow(() -> new QuoteException("QUOTE_NOT_FOUND", "Orçamento não encontrado"));
        if (!quote.workOrderId().equals(workOrderId))
            throw new QuoteException("QUOTE_NOT_FOUND", "Orçamento não encontrado");
        return quote;
    }

    /**
     * Cria uma nova apresentação em rascunho a partir das condições comerciais pedidas.
     *
     * <p>Para cada item, a versão comercial vigente é reaproveitada quando descrição, quantidade e
     * preço coincidem — é o complemento da RN-22, que mantém decidível o que o cliente já recebeu.
     * Qualquer divergência nesses três campos cria uma nova versão, nunca uma atualização.</p>
     */
    @Transactional
    public Quote createRevision(UUID workOrderId, UUID quoteId, List<ItemSpec> specs) {
        if (specs == null || specs.isEmpty())
            throw new QuoteException("QUOTE_REVISION_EMPTY", "Revisão exige pelo menos um item comercial");
        Quote quote = get(workOrderId, quoteId);
        Instant now = Instant.now(clock);
        UUID author = currentUser.requireId();
        // Desconto só "quando permitido" (cartão Trello, DR-0013): a permissão é conferida no backend.
        if (specs.stream().anyMatch(spec -> spec.discount() != null && spec.discount().signum() > 0)
                && !authorization.hasPermission(author, DISCOUNT_PERMISSION))
            throw new QuoteException("QUOTE_DISCOUNT_NOT_ALLOWED", "Seu perfil não pode conceder desconto em orçamento");

        List<QuoteRevision.Entry> entries = new ArrayList<>();
        Set<UUID> touchedItems = new HashSet<>();
        Set<UUID> linkedProducts = new HashSet<>();
        quote.items().stream().map(QuoteItem::workOrderProductId).filter(java.util.Objects::nonNull).forEach(linkedProducts::add);
        int displayOrder = 1;
        for (ItemSpec spec : specs) {
            UUID itemRevisionId = spec.quoteItemId() == null
                    ? createItemWithFirstRevision(quote, spec, linkedProducts, now, author)
                    : revisionForExistingItem(quote, spec, touchedItems, now, author);
            entries.add(new QuoteRevision.Entry(itemRevisionId, displayOrder++));
        }
        repository.createRevision(QuoteRevision.draft(quoteId, quote.nextRevisionNumber(), entries, now, author));
        return get(workOrderId, quoteId);
    }

    private UUID createItemWithFirstRevision(Quote quote, ItemSpec spec, Set<UUID> linkedProducts, Instant now, UUID author) {
        if (spec.workOrderServiceId() != null && workOrders.serviceItems(quote.workOrderId()).stream()
                .noneMatch(item -> item.id().equals(spec.workOrderServiceId())))
            throw new QuoteException("WORK_ORDER_SERVICE_NOT_FOUND",
                    "Serviço informado não pertence à Ordem de Serviço deste orçamento");
        if (spec.workOrderProductId() != null) {
            if (workOrders.productItems(quote.workOrderId()).stream().noneMatch(item -> item.id().equals(spec.workOrderProductId())))
                throw new QuoteException("WORK_ORDER_PRODUCT_NOT_FOUND",
                        "Item físico informado não pertence à Ordem de Serviço deste orçamento");
            // DR-0008: o mesmo item físico uma vez por orçamento. O índice único é a autoridade no caso concorrente.
            if (!linkedProducts.add(spec.workOrderProductId()))
                throw new QuoteException("QUOTE_ITEM_PRODUCT_ALREADY_LINKED",
                        "Este item físico já é cobrado por outro item deste orçamento");
        }
        QuoteItem item = repository.createItem(
                QuoteItem.create(quote.id(), spec.workOrderServiceId(), spec.workOrderProductId(), now, author));
        return repository.createItemRevision(QuoteItemRevision.first(quote.id(), item.id(), spec.description(),
                spec.quantity(), spec.unitPrice(), spec.discount(), spec.revisionReason(), now, author)).id();
    }

    private UUID revisionForExistingItem(Quote quote, ItemSpec spec, Set<UUID> touchedItems, Instant now, UUID author) {
        if (!touchedItems.add(spec.quoteItemId()))
            throw new QuoteException("QUOTE_ITEM_DUPLICATED",
                    "O mesmo item comercial foi informado mais de uma vez na revisão");
        QuoteItem item = quote.item(spec.quoteItemId())
                .orElseThrow(() -> new QuoteException("QUOTE_ITEM_NOT_FOUND", "Item comercial não encontrado"));
        QuoteItemRevision current = item.latestRevision();
        if (current.sameCommercialTerms(spec.description(), spec.quantity(), spec.unitPrice(), spec.discount())) return current.id();
        return repository.createItemRevision(current.next(spec.description(), spec.quantity(), spec.unitPrice(), spec.discount(),
                spec.revisionReason(), now, author)).id();
    }

    /**
     * Apresenta a revisão ao cliente, iniciando a validade comercial padrão de sete dias.
     *
     * <p>A validade não é parametrizável: REQ-ORC-001 seção 29 admite outro prazo apenas por
     * configuração aprovada, e nenhuma existe. Aceitar o prazo por requisição deixaria a validade
     * comercial na mão de quem chama a API.</p>
     */
    @Transactional
    public Quote present(UUID workOrderId, UUID quoteId, UUID revisionId) {
        // Serializa com a finalização da OS antes de ler o orçamento (revisão TASK-0015, F1).
        workOrderEvents.lockForCommercialChange(workOrderId);
        Quote quote = get(workOrderId, quoteId);
        QuoteRevision revision = quote.revision(revisionId)
                .orElseThrow(() -> new QuoteException("QUOTE_REVISION_NOT_FOUND", "Revisão não encontrada"));
        quote.checkPresentable(revision);
        QuoteRevision presented = revision.present(Instant.now(clock), QuoteRevision.DEFAULT_VALIDITY);
        // Apresentar e decidir avançam a mesma versão do orçamento. É isso que impede que uma decisão
        // seja aceita com base numa obsolescência lida antes desta apresentação.
        if (!repository.touch(quote.id(), quote.version()) || !repository.present(presented, revision.version()))
            throw new QuoteException("QUOTE_REVISION_CONCURRENTLY_MODIFIED",
                    "A revisão foi alterada por outra operação; recarregue o orçamento");
        workOrderEvents.quotePresented(workOrderId);
        return get(workOrderId, quoteId);
    }

    public Instant now() { return Instant.now(clock); }
}
