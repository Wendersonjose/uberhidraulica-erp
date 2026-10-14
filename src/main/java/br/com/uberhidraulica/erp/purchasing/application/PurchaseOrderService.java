package br.com.uberhidraulica.erp.purchasing.application;

import br.com.uberhidraulica.erp.iam.CurrentUser;
import br.com.uberhidraulica.erp.iam.IamAuthorization;
import br.com.uberhidraulica.erp.productcatalog.ProductCatalogQuery;
import br.com.uberhidraulica.erp.purchasing.domain.Purchase;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseEvent;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseItem;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseOrder;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasingException;
import br.com.uberhidraulica.erp.purchasing.domain.Supplier;
import br.com.uberhidraulica.erp.purchasing.port.PurchaseRepositoryPort;
import br.com.uberhidraulica.erp.purchasing.port.SupplierRepositoryPort;
import br.com.uberhidraulica.erp.workorder.WorkOrderQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pedido de compra e compra direta (`TASK-0021` I5, `DR-0022` D8).
 *
 * <p><b>Não movimenta estoque nem cria conta a pagar.</b> O pedido aprovado apenas autoriza receber (I4). A
 * obrigação financeira nasce com a nota fiscal (I3); ligar pedido e nota é decisão pendente (DR-0023), e por isso
 * nada aqui tenta adivinhar essa ligação.</p>
 *
 * <p>Toda transição trava a compra ({@code SELECT … FOR UPDATE}) e confere a versão que o operador viu: aprovar é
 * aprovar exatamente o que foi lido, nunca o que alguém reeditou entre a leitura e o clique.</p>
 */
@Service
public class PurchaseOrderService {
    public static final String APPROVE_OWN_PERMISSION = "PURCHASE_APPROVE_OWN";

    private final PurchaseRepositoryPort purchases;
    private final SupplierRepositoryPort suppliers;
    private final ProductCatalogQuery catalog;
    private final WorkOrderQuery workOrders;
    private final IamAuthorization authorization;
    private final CurrentUser currentUser;
    private final Clock clock;

    @Autowired
    public PurchaseOrderService(PurchaseRepositoryPort purchases, SupplierRepositoryPort suppliers,
                                ProductCatalogQuery catalog, WorkOrderQuery workOrders,
                                IamAuthorization authorization, CurrentUser currentUser) {
        this(purchases, suppliers, catalog, workOrders, authorization, currentUser, Clock.systemUTC());
    }

    PurchaseOrderService(PurchaseRepositoryPort purchases, SupplierRepositoryPort suppliers,
                         ProductCatalogQuery catalog, WorkOrderQuery workOrders, IamAuthorization authorization,
                         CurrentUser currentUser, Clock clock) {
        this.purchases = purchases;
        this.suppliers = suppliers;
        this.catalog = catalog;
        this.workOrders = workOrders;
        this.authorization = authorization;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ criação

    /** Cria o rascunho. Retry com a mesma {@code requestId} devolve o rascunho já criado, sem duplicar. */
    @Transactional
    public Result create(CreateCommand command) {
        String key = requireKey(command.requestId());
        UUID actor = currentUser.requireId();
        Draft draft = command.draft();

        Optional<Purchase> replay = purchases.findByIdempotencyKey(key);
        if (replay.isPresent()) return new Result(replayOf(replay.get(), actor, command), true);

        Purchase.Origin origin = command.origin();
        if (origin != Purchase.Origin.ORDER && origin != Purchase.Origin.DIRECT) {
            throw new PurchasingException("PURCHASE_ORDER_ORIGIN_INVALID",
                    "A origem deve ser ORDER (pedido) ou DIRECT (compra direta)");
        }
        Instant now = Instant.now(clock);
        Supplier supplier = requireActiveSupplier(draft.supplierId());

        UUID purchaseId = UUID.randomUUID();
        PurchaseOrder.Priced priced = price(purchaseId, draft);

        Purchase purchase = new Purchase(purchaseId, 0L, supplier.id(), origin, Purchase.Status.DRAFT,
                priced.goodsTotal(), priced.discountTotal(), priced.freightTotal(), priced.insuranceTotal(),
                priced.otherTotal(), BigDecimal.ZERO.setScale(2), priced.payableTotal(), null,
                PurchaseJson.commercialSnapshot(supplier), draft.workOrderId(), notes(draft.notes()), key, now,
                actor, null, null, null, 0L, null, null, null, null, priced.items());
        purchase.requireTotalsClose();

        purchases.insert(purchase);
        purchases.insertItems(priced.items());
        purchases.insertEvent(PurchaseEvent.of(purchaseId, PurchaseEvent.Type.CREATED, actor, now, null,
                PurchaseJson.of(Map.of("origin", origin.name(), "items", String.valueOf(priced.items().size()),
                        "payableTotal", priced.payableTotal().toPlainString()))));
        return new Result(reload(purchaseId), false);
    }

    private Purchase replayOf(Purchase existing, UUID actor, CreateCommand command) {
        // A chave é do cliente. Reaproveitá-la para outra coisa não pode devolver o pedido de outra pessoa.
        if (existing.origin() == Purchase.Origin.NFE_IMPORT || !existing.createdBy().equals(actor)
                || !existing.supplierId().equals(command.draft().supplierId())
                || existing.origin() != command.origin()) {
            throw new PurchasingException("PURCHASE_IDEMPOTENCY_KEY_REUSED",
                    "Esta chave de idempotência já foi usada em outra compra");
        }
        return existing;
    }

    // ------------------------------------------------------------------ edição do rascunho

    @Transactional
    public Purchase update(UUID id, long expectedVersion, Draft draft) {
        Purchase current = lock(id);
        PurchaseOrder.requireEditable(current);
        PurchaseOrder.requireVersion(current, expectedVersion);

        UUID actor = currentUser.requireId();
        Instant now = Instant.now(clock);
        Supplier supplier = requireActiveSupplier(draft.supplierId());
        PurchaseOrder.Priced priced = price(id, draft);

        Purchase updated = new Purchase(id, current.number(), supplier.id(), current.origin(), current.status(),
                priced.goodsTotal(), priced.discountTotal(), priced.freightTotal(), priced.insuranceTotal(),
                priced.otherTotal(), BigDecimal.ZERO.setScale(2), priced.payableTotal(), null,
                PurchaseJson.commercialSnapshot(supplier), draft.workOrderId(), notes(draft.notes()),
                current.idempotencyKey(), current.createdAt(), current.createdBy(), null, null, null,
                current.version(), null, null, null, null, priced.items());
        updated.requireTotalsClose();

        purchases.updateDraft(updated, now, actor);
        purchases.replaceItems(id, priced.items());
        purchases.insertEvent(PurchaseEvent.of(id, PurchaseEvent.Type.ORDER_UPDATED, actor, now, null,
                PurchaseJson.of(Map.of("items", String.valueOf(priced.items().size()),
                        "payableTotal", priced.payableTotal().toPlainString(),
                        "previousPayableTotal", current.payableTotal().toPlainString()))));
        return reload(id);
    }

    // ------------------------------------------------------------------ fluxo de aprovação

    @Transactional
    public Purchase submit(UUID id, long expectedVersion) {
        Purchase current = lock(id);
        PurchaseOrder.requireSubmittable(current);
        PurchaseOrder.requireVersion(current, expectedVersion);
        requireActiveSupplier(current.supplierId());

        UUID actor = currentUser.requireId();
        Instant now = Instant.now(clock);
        purchases.transition(id, Purchase.Status.DRAFT, Purchase.Status.AWAITING_APPROVAL, now, actor);
        purchases.insertEvent(PurchaseEvent.of(id, PurchaseEvent.Type.ORDER_SUBMITTED, actor, now, null, null));
        return reload(id);
    }

    /**
     * Aprova o pedido. {@code PURCHASE_APPROVE} é exigida na borda; aqui valem a segregação de funções (D8) e a
     * revalidação do que pode ter mudado desde o envio: fornecedor desativado ou produto inativo.
     */
    @Transactional
    public Purchase approve(UUID id, long expectedVersion) {
        Purchase current = lock(id);
        PurchaseOrder.requireAwaitingApproval(current);
        PurchaseOrder.requireVersion(current, expectedVersion);

        UUID actor = currentUser.requireId();
        boolean ownApproval = PurchaseOrder.checkApprover(current, actor,
                authorization.hasPermission(actor, APPROVE_OWN_PERMISSION));

        requireActiveSupplier(current.supplierId());
        requireActiveProducts(current.items());

        Instant now = Instant.now(clock);
        purchases.transition(id, Purchase.Status.AWAITING_APPROVAL, Purchase.Status.APPROVED, now, actor);
        purchases.insertEvent(PurchaseEvent.of(id, PurchaseEvent.Type.ORDER_APPROVED, actor, now, null,
                PurchaseJson.of(Map.of("ownApproval", String.valueOf(ownApproval),
                        "payableTotal", current.payableTotal().toPlainString()))));
        return reload(id);
    }

    /** Devolve ao rascunho, com motivo, para ser corrigido e reenviado. Não exige ser pessoa diferente do autor. */
    @Transactional
    public Purchase giveBack(UUID id, long expectedVersion, String reason) {
        String motive = requireReason(reason);
        Purchase current = lock(id);
        PurchaseOrder.requireAwaitingApproval(current);
        PurchaseOrder.requireVersion(current, expectedVersion);

        UUID actor = currentUser.requireId();
        Instant now = Instant.now(clock);
        purchases.transition(id, Purchase.Status.AWAITING_APPROVAL, Purchase.Status.DRAFT, now, actor);
        purchases.insertEvent(PurchaseEvent.of(id, PurchaseEvent.Type.ORDER_RETURNED, actor, now, motive, null));
        return reload(id);
    }

    // ------------------------------------------------------------------ apoio

    private Purchase lock(UUID id) {
        return purchases.lock(id).orElseThrow(() -> new PurchasingException("PURCHASE_NOT_FOUND",
                "Compra não encontrada"));
    }

    private Purchase reload(UUID id) {
        return purchases.find(id).orElseThrow(() -> new PurchasingException("PURCHASE_NOT_FOUND",
                "Compra não encontrada"));
    }

    private Supplier requireActiveSupplier(UUID supplierId) {
        if (supplierId == null) {
            throw new PurchasingException("PURCHASE_SUPPLIER_UNRESOLVED", "Informe o fornecedor");
        }
        Supplier supplier = suppliers.find(supplierId).orElseThrow(() -> new PurchasingException(
                "PURCHASE_SUPPLIER_UNRESOLVED", "Fornecedor não encontrado"));
        if (supplier.status() == Supplier.Status.INACTIVE) {
            throw new PurchasingException("PURCHASE_SUPPLIER_INACTIVE",
                    "Fornecedor " + supplier.legalName() + " está inativo: reative o cadastro antes de comprar");
        }
        return supplier;
    }

    private void requireActiveProducts(List<PurchaseItem> items) {
        for (PurchaseItem item : items) {
            ProductCatalogQuery.ProductReference product = item.productId() == null ? null
                    : catalog.product(item.productId()).orElse(null);
            if (product == null || !product.active()) {
                throw new PurchasingException("PURCHASE_ITEM_PRODUCT_INACTIVE",
                        "O produto do item " + item.lineNo() + " (" + item.description()
                                + ") não está ativo no catálogo");
            }
        }
    }

    /** Resolve produtos e vínculo com a OS, depois valoriza. Produto inativo ou inexistente não entra em pedido. */
    private PurchaseOrder.Priced price(UUID purchaseId, Draft draft) {
        List<PurchaseOrder.Line> lines = draft.lines() == null ? List.of() : draft.lines();
        if (lines.size() > PurchaseOrder.MAX_LINES) {
            throw new PurchasingException("PURCHASE_ORDER_TOO_MANY_LINES",
                    "Um pedido aceita até " + PurchaseOrder.MAX_LINES + " itens");
        }
        Map<UUID, WorkOrderQuery.ProductItemReference> workOrderItems = workOrderItems(draft.workOrderId());

        List<PurchaseOrder.ResolvedLine> resolved = new ArrayList<>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            PurchaseOrder.Line line = lines.get(index);
            String where = "Item " + (index + 1) + ": ";
            ProductCatalogQuery.ProductReference product = line.productId() == null ? null
                    : catalog.product(line.productId()).orElse(null);
            if (product == null) {
                throw new PurchasingException("PURCHASE_ORDER_LINE_INVALID", where + "produto não existe no catálogo");
            }
            if (!product.active()) {
                throw new PurchasingException("PURCHASE_ITEM_PRODUCT_INACTIVE",
                        where + "produto " + product.description() + " está inativo e não recebe compra");
            }
            if (line.workOrderItemId() != null) {
                WorkOrderQuery.ProductItemReference target = workOrderItems.get(line.workOrderItemId());
                if (target == null || !line.productId().equals(target.productId())) {
                    throw new PurchasingException("PURCHASE_ORDER_LINE_INVALID",
                            where + "o item da OS informado não existe nela ou é de outro produto");
                }
            }
            resolved.add(new PurchaseOrder.ResolvedLine(line, product.description(), product.unit(), product.ncm(),
                    product.gtin()));
        }
        return PurchaseOrder.price(purchaseId, draft.workOrderId(), resolved,
                new PurchaseOrder.Expenses(draft.freight(), draft.insurance(), draft.other()));
    }

    /** Itens físicos da OS informada, por id. Vazio quando não há OS; OS inexistente é recusada. */
    private Map<UUID, WorkOrderQuery.ProductItemReference> workOrderItems(UUID workOrderId) {
        if (workOrderId == null) return Map.of();
        if (workOrders.workOrder(workOrderId).isEmpty()) {
            throw new PurchasingException("PURCHASE_ORDER_WORK_ORDER_INVALID", "A OS informada não existe");
        }
        return workOrders.productItems(workOrderId).stream().collect(Collectors.toMap(
                WorkOrderQuery.ProductItemReference::id, item -> item, (first, second) -> first, LinkedHashMap::new));
    }

    private static String requireKey(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new PurchasingException("PURCHASE_IDEMPOTENCY_KEY_REQUIRED",
                    "Informe o cabeçalho Idempotency-Key para o pedido não ser criado em duplicidade");
        }
        return requestId.trim();
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.trim().length() < 3 || reason.trim().length() > 500) {
            throw new PurchasingException("PURCHASE_REASON_REQUIRED",
                    "Motivo é obrigatório (3 a 500 caracteres)");
        }
        return reason.trim();
    }

    private static String notes(String notes) {
        if (notes == null || notes.isBlank()) return null;
        String trimmed = notes.trim();
        if (trimmed.length() > 1000) {
            throw new PurchasingException("PURCHASE_ORDER_NOTES_TOO_LONG", "Observação aceita até 1000 caracteres");
        }
        return trimmed;
    }

    // ------------------------------------------------------------------ contratos

    /** Conteúdo editável do pedido: o mesmo na criação e na edição. */
    public record Draft(UUID supplierId, UUID workOrderId, String notes, BigDecimal freight, BigDecimal insurance,
                        BigDecimal other, List<PurchaseOrder.Line> lines) {}

    public record CreateCommand(String requestId, Purchase.Origin origin, Draft draft) {}

    public record Result(Purchase purchase, boolean replayed) {}
}
