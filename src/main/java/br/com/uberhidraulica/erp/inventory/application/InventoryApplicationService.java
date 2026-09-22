package br.com.uberhidraulica.erp.inventory.application;

import br.com.uberhidraulica.erp.iam.CurrentUser;
import br.com.uberhidraulica.erp.inventory.domain.InventoryException;
import br.com.uberhidraulica.erp.inventory.domain.Stock;
import br.com.uberhidraulica.erp.inventory.domain.StockMovement;
import br.com.uberhidraulica.erp.inventory.port.StockRepositoryPort;
import br.com.uberhidraulica.erp.productcatalog.ProductCatalogQuery;
import br.com.uberhidraulica.erp.productcatalog.ProductQuantityRule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Movimentações e saldo do estoque.
 *
 * <p>Toda movimentação bloqueia a linha do saldo do produto antes de calcular, grava o saldo e o custo
 * médio resultantes e nunca altera um lançamento anterior (DR-0014).</p>
 */
@Service
public class InventoryApplicationService {
    public static final String WRITE_OFF_SETTING = "WORK_ORDER_WRITE_OFF";
    public static final int MAX_PAGE_SIZE = 100;

    private final StockRepositoryPort repository;
    private final ProductCatalogQuery catalog;
    private final CurrentUser currentUser;
    private final Clock clock = Clock.systemUTC();

    public InventoryApplicationService(StockRepositoryPort repository, ProductCatalogQuery catalog, CurrentUser currentUser) {
        this.repository = repository;
        this.catalog = catalog;
        this.currentUser = currentUser;
    }

    /** Modos de baixa pela OS; o padrão é baixar no lançamento do item. */
    public enum WriteOffMode { ITEM_LAUNCH, WORK_ORDER_FINISH, DISABLED }

    // ------------------------------------------------------------------ consulta

    @Transactional(readOnly = true)
    public Page<StockMovement.StockLine> stock(String text, String category, Boolean active, boolean belowMinimum, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE)
            throw new InventoryException("INVALID_STOCK", "Página inválida ou tamanho fora de 1 a " + MAX_PAGE_SIZE);
        Map<UUID, Stock> balances = repository.balances();
        String term = text == null || text.isBlank() ? null : text.trim().toLowerCase(Locale.ROOT);
        List<StockMovement.StockLine> lines = catalog.products().stream()
                .map(product -> line(product, balances.get(product.id())))
                .filter(line -> term == null || line.description().toLowerCase(Locale.ROOT).contains(term)
                        || (line.internalCode() != null && line.internalCode().toLowerCase(Locale.ROOT).contains(term)))
                .filter(line -> category == null || category.equalsIgnoreCase(line.category()))
                .filter(line -> active == null || active == line.active())
                .filter(line -> !belowMinimum || line.belowMinimum())
                .sorted(Comparator.comparing(line -> line.description().toLowerCase(Locale.ROOT)))
                .toList();
        int from = Math.min(page * size, lines.size());
        return new Page<>(lines.subList(from, Math.min(from + size, lines.size())), lines.size(), page, size);
    }

    private StockMovement.StockLine line(ProductCatalogQuery.ProductReference product, Stock stock) {
        BigDecimal quantity = stock == null ? BigDecimal.ZERO.setScale(Stock.QUANTITY_SCALE) : stock.quantity();
        return new StockMovement.StockLine(product.id(), product.description(), product.internalCode(), product.category(),
                product.unit(), product.minimumStock(), product.salePrice(), product.active(), quantity,
                stock == null ? null : stock.averageCost());
    }

    @Transactional(readOnly = true)
    public Page<StockMovement> movements(UUID productId, int page, int size) {
        requireProduct(productId);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw new InventoryException("INVALID_STOCK", "Paginação inválida");
        return new Page<>(repository.movementsOfProduct(productId, size, page * size),
                repository.countMovementsOfProduct(productId), page, size);
    }

    // ------------------------------------------------------------------ movimentações manuais

    @Transactional
    public StockMovement registerEntry(UUID productId, BigDecimal quantity, BigDecimal unitCost, String reason) {
        requireUnitPrecision(productId, quantity);
        return apply(productId, StockMovement.Type.ENTRY, quantity, unitCost, reason, StockMovement.Source.MANUAL, null, null, null);
    }

    @Transactional
    public StockMovement registerExit(UUID productId, BigDecimal quantity, String reason) {
        requireUnitPrecision(productId, quantity);
        return apply(productId, StockMovement.Type.EXIT, quantity, null, reason, StockMovement.Source.MANUAL, null, null, null);
    }

    @Transactional
    public StockMovement registerAdjustment(UUID productId, BigDecimal quantity, boolean increase, String reason) {
        requireUnitPrecision(productId, quantity);
        return apply(productId, increase ? StockMovement.Type.ADJUSTMENT_IN : StockMovement.Type.ADJUSTMENT_OUT,
                quantity, null, reason, StockMovement.Source.MANUAL, null, null, null);
    }

    /** Estorno: movimento novo, em sentido inverso, ligado ao original; cada original é estornado uma vez. */
    @Transactional
    public StockMovement reverse(UUID movementId, String reason) {
        StockMovement original = repository.findMovement(movementId)
                .orElseThrow(() -> new InventoryException("MOVEMENT_NOT_FOUND", "Movimentação não encontrada"));
        if (original.type() == StockMovement.Type.REVERSAL)
            throw new InventoryException("MOVEMENT_ALREADY_REVERSAL", "Estorno não pode ser estornado");
        try {
            return apply(original.productId(), StockMovement.Type.REVERSAL, original.quantity(), null,
                    reason == null || reason.isBlank() ? "Estorno de movimentação" : reason, original.source(),
                    original.workOrderId(), null, original.id(), !original.type().incoming());
        } catch (org.springframework.dao.DataIntegrityViolationException alreadyReversed) {
            // O índice único do estorno é a autoridade: duas correções simultâneas não geram duas devoluções.
            throw new InventoryException("MOVEMENT_ALREADY_REVERSED", "Esta movimentação já foi estornada");
        }
    }

    // ------------------------------------------------------------------ integração com a OS

    @Transactional(readOnly = true)
    public WriteOffMode writeOffMode() { return WriteOffMode.valueOf(repository.setting(WRITE_OFF_SETTING)); }

    @Transactional
    public WriteOffMode changeWriteOffMode(String mode) {
        WriteOffMode parsed;
        try { parsed = WriteOffMode.valueOf(mode); }
        catch (IllegalArgumentException invalid) { throw new InventoryException("INVALID_WRITE_OFF_MODE", "Modo de baixa inválido"); }
        repository.saveSetting(WRITE_OFF_SETTING, parsed.name());
        return parsed;
    }

    /**
     * Baixa o item físico da OS uma única vez.
     *
     * <p>A chave de idempotência é o item da OS: reenvio HTTP, duplo clique, reprocesso do evento e
     * troca do modo de baixa entre o lançamento e a finalização chegam todos aqui, e só o primeiro
     * desconta. O índice único {@code uq_stock_movement_work_order_item} é a autoridade final,
     * para o caso de duas transações concorrentes passarem juntas pela verificação.</p>
     */
    @Transactional
    public void writeOffForWorkOrder(UUID workOrderId, UUID itemId, UUID productId, BigDecimal quantity) {
        if (repository.workOrderExitExists(itemId)) return;
        try {
            apply(productId, StockMovement.Type.WORK_ORDER_OUT, quantity, null, null, StockMovement.Source.WORK_ORDER, workOrderId, itemId, null);
        } catch (org.springframework.dao.DataIntegrityViolationException concurrent) {
            // Outra transação baixou este mesmo item primeiro. Desfazer é o certo: seguir em frente
            // deixaria a OS acreditando que baixou algo que não baixou.
            throw new InventoryException("STOCK_WRITE_OFF_CONFLICT",
                    "Este item da OS já teve baixa de estoque registrada");
        }
    }

    /** Devolve ao estoque tudo o que a OS baixou e ainda não foi estornado. */
    @Transactional
    public void returnWorkOrderStock(UUID workOrderId) {
        for (StockMovement exit : repository.pendingWorkOrderExits(workOrderId))
            apply(exit.productId(), StockMovement.Type.WORK_ORDER_RETURN, exit.quantity(), null,
                    "Devolução por cancelamento da OS", StockMovement.Source.WORK_ORDER, workOrderId, exit.workOrderItemId(), exit.id());
    }

    private StockMovement apply(UUID productId, StockMovement.Type type, BigDecimal quantity, BigDecimal unitCost, String reason,
                                StockMovement.Source source, UUID workOrderId, UUID itemId, UUID reverses) {
        return apply(productId, type, quantity, unitCost, reason, source, workOrderId, itemId, reverses, type.incoming());
    }

    private StockMovement apply(UUID productId, StockMovement.Type type, BigDecimal quantity, BigDecimal unitCost, String reason,
                                StockMovement.Source source, UUID workOrderId, UUID itemId, UUID reverses, boolean incoming) {
        var product = requireProduct(productId);
        // DR-0016: inativar impede nova compra; saída, ajustes com motivo, estorno e devolução seguem permitidos.
        if (type == StockMovement.Type.ENTRY && !product.active())
            throw new InventoryException("PRODUCT_INACTIVE", "Produto inativo não recebe entrada");
        Instant now = clock.instant();
        Stock current = repository.lockBalance(productId);
        Stock updated = incoming ? current.add(quantity, unitCost, now) : current.remove(quantity, now);
        repository.saveBalance(updated);
        StockMovement movement = new StockMovement(UUID.randomUUID(), productId, type, quantity, unitCost,
                updated.quantity(), updated.averageCost(), reason, source, workOrderId, itemId, reverses, now,
                currentUser.id().orElse(null), incoming);
        return repository.record(movement);
    }

    /**
     * Precisão por unidade (DR-0006) nas movimentações manuais. A baixa pela OS herda a quantidade já
     * validada no lançamento do item; estorno e devolução repetem a quantidade do movimento original.
     */
    private void requireUnitPrecision(UUID productId, BigDecimal quantity) {
        if (quantity == null) return;
        ProductQuantityRule.violation(requireProduct(productId).unit(), quantity).ifPresent(message -> {
            throw new InventoryException("INVALID_QUANTITY_FOR_UNIT", message);
        });
    }

    private ProductCatalogQuery.ProductReference requireProduct(UUID productId) {
        return catalog.product(productId).orElseThrow(() -> new InventoryException("PRODUCT_NOT_FOUND", "Produto não encontrado"));
    }

    public record Page<T>(List<T> items, long totalItems, int page, int size) {
        public int totalPages() { return (int) ((totalItems + size - 1) / size); }
    }
}
