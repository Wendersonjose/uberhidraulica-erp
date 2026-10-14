package br.com.uberhidraulica.erp.purchasing.application;

import br.com.uberhidraulica.erp.iam.CurrentUser;
import br.com.uberhidraulica.erp.iam.IamAuthorization;
import br.com.uberhidraulica.erp.inventory.PurchaseStockEntry;
import br.com.uberhidraulica.erp.inventory.PurchaseStockException;
import br.com.uberhidraulica.erp.productcatalog.ProductCatalogQuery;
import br.com.uberhidraulica.erp.productcatalog.ProductQuantityRule;
import br.com.uberhidraulica.erp.purchasing.domain.GoodsReceipt;
import br.com.uberhidraulica.erp.purchasing.domain.Purchase;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseDocument;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseEvent;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseMoney;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasingException;
import br.com.uberhidraulica.erp.purchasing.domain.ReceiptLineEvaluation;
import br.com.uberhidraulica.erp.purchasing.domain.ReceivingLine;
import br.com.uberhidraulica.erp.purchasing.port.GoodsReceiptRepositoryPort;
import br.com.uberhidraulica.erp.purchasing.port.PurchaseRepositoryPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Recebimento físico de compra (`TASK-0021` I4, decisões aprovadas da `DR-0022`).
 *
 * <p><b>O estoque recebe somente a quantidade física conferida</b> (`D3`). Pedido e importação da nota não movimentam
 * nada; é este caso de uso que chama o contrato do Estoque. Receber menos do que o pendente é recebimento parcial
 * e não exige nada; receber <i>mais</i>, ou encerrar o saldo que não virá, é divergência e exige justificativa
 * <b>e</b> a permissão {@code PURCHASE_DIVERGENCE_ACCEPT}.</p>
 *
 * <p><b>Uma transação.</b> Qualquer falha — inclusive a entrada da segunda linha no Estoque — desfaz o recebimento
 * inteiro. <b>Idempotência</b>: a mesma {@code Idempotency-Key} com o mesmo conteúdo devolve o mesmo recebimento;
 * com conteúdo diferente é recusada. <b>Concorrência</b>: a compra é travada ({@code FOR UPDATE}) antes de ler o
 * pendente, de modo que dois recebimentos simultâneos do último saldo não passam os dois.</p>
 */
@Service
public class GoodsReceiptService {
    public static final String DIVERGENCE_PERMISSION = "PURCHASE_DIVERGENCE_ACCEPT";
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private final PurchaseRepositoryPort purchases;
    private final GoodsReceiptRepositoryPort receipts;
    private final PurchaseStockEntry stock;
    private final ProductCatalogQuery catalog;
    private final IamAuthorization authorization;
    private final CurrentUser currentUser;
    private final Clock clock;

    @Autowired
    public GoodsReceiptService(PurchaseRepositoryPort purchases, GoodsReceiptRepositoryPort receipts,
                               PurchaseStockEntry stock, ProductCatalogQuery catalog, IamAuthorization authorization,
                               CurrentUser currentUser) {
        this(purchases, receipts, stock, catalog, authorization, currentUser, Clock.systemUTC());
    }

    GoodsReceiptService(PurchaseRepositoryPort purchases, GoodsReceiptRepositoryPort receipts, PurchaseStockEntry stock,
                        ProductCatalogQuery catalog, IamAuthorization authorization, CurrentUser currentUser,
                        Clock clock) {
        this.purchases = purchases;
        this.receipts = receipts;
        this.stock = stock;
        this.catalog = catalog;
        this.authorization = authorization;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ consulta

    /** Conferência da compra: o que foi pedido, o que já chegou, o que foi encerrado e o que falta. */
    @Transactional(readOnly = true)
    public Sheet sheet(UUID purchaseId) {
        requirePurchase(purchaseId);
        List<ReceivingLine> lines = lines(purchaseId);
        return new Sheet(purchaseId, ReceivingLine.statusOf(lines), lines);
    }

    @Transactional(readOnly = true)
    public List<GoodsReceipt> receipts(UUID purchaseId) {
        requirePurchase(purchaseId);
        return receipts.receiptsOf(purchaseId);
    }

    @Transactional(readOnly = true)
    public GoodsReceipt.Status statusOf(UUID purchaseId) {
        return ReceivingLine.statusOf(receipts.receiving(purchaseId));
    }

    // ------------------------------------------------------------------ recebimento

    @Transactional
    public Result register(Command command) {
        String key = requireKey(command.requestId());
        String hash = hash(command);

        // 1. Retry HTTP não cria recebimento novo. Antes do bloqueio por ser o caminho comum do retry.
        Optional<GoodsReceipt> replay = receipts.findByIdempotencyKey(key);
        if (replay.isPresent()) return replayOf(replay.get(), command.purchaseId(), hash);

        // 2. A compra é travada antes de ler o pendente: é o que serializa dois recebimentos do mesmo saldo.
        Purchase purchase = purchases.lock(command.purchaseId()).orElseThrow(GoodsReceiptService::purchaseNotFound);
        replay = receipts.findByIdempotencyKey(key);
        if (replay.isPresent()) return replayOf(replay.get(), command.purchaseId(), hash);

        if (purchase.status() != Purchase.Status.APPROVED) {
            throw new PurchasingException("PURCHASE_NOT_RECEIVABLE",
                    "Só compra aprovada pode ser recebida (situação atual: " + purchase.status() + ")");
        }
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new PurchasingException("PURCHASE_RECEIPT_EMPTY", "Informe ao menos um item recebido");
        }

        UUID actor = currentUser.requireId();
        Instant now = Instant.now(clock);
        LocalDate receivedOn = command.receivedOn() == null ? LocalDate.ofInstant(now, ZONE) : command.receivedOn();
        if (receivedOn.isAfter(LocalDate.ofInstant(now, ZONE))) {
            throw new PurchasingException("PURCHASE_RECEIPT_DATE_INVALID", "A data do recebimento não pode ser futura");
        }
        UUID documentId = requireDocument(command.documentId(), purchase.id());

        Map<UUID, ReceivingLine> sheet = new LinkedHashMap<>();
        lines(purchase.id()).forEach(line -> sheet.put(line.purchaseItemId(), line));
        boolean mayAccept = authorization.hasPermission(actor, DIVERGENCE_PERMISSION);

        UUID receiptId = UUID.randomUUID();
        List<GoodsReceipt.Item> items = new ArrayList<>();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        int divergent = 0;
        for (Line line : command.lines()) {
            if (line.purchaseItemId() == null || !seen.add(line.purchaseItemId())) {
                throw new PurchasingException("PURCHASE_RECEIPT_LINE_INVALID",
                        "Item de compra ausente ou repetido no recebimento");
            }
            ReceivingLine target = sheet.get(line.purchaseItemId());
            if (target == null) {
                throw new PurchasingException("PURCHASE_RECEIPT_LINE_INVALID", "O item informado não pertence a esta compra");
            }
            if (target.productId() == null) {
                throw new PurchasingException("PURCHASE_ITEM_UNLINKED",
                        "O item " + target.lineNo() + " não está vinculado a um produto do catálogo");
            }
            BigDecimal quantity = PurchaseMoney.quantity(line.quantity());
            PurchaseMoney.requirePositive(quantity, "Quantidade recebida do item " + target.lineNo());
            requireUnitPrecision(target, quantity);

            ReceiptLineEvaluation.Result evaluation = ReceiptLineEvaluation.evaluate(target.pending(), quantity,
                    Boolean.TRUE.equals(line.closeRemaining()));
            String justification = null;
            UUID acceptedBy = null;
            if (evaluation.divergent()) {
                justification = line.justification() == null ? "" : line.justification().trim();
                if (justification.isEmpty() || justification.length() > 500) {
                    throw new PurchasingException("PURCHASE_DIVERGENCE_JUSTIFICATION_REQUIRED",
                            "Item " + target.lineNo() + ": a divergência exige justificativa de até 500 caracteres");
                }
                if (!mayAccept) {
                    throw new PurchasingException("PURCHASE_DIVERGENCE_NOT_AUTHORIZED",
                            "Item " + target.lineNo() + ": aceitar divergência exige a permissão " + DIVERGENCE_PERMISSION);
                }
                acceptedBy = actor;
                divergent++;
            }

            UUID itemId = UUID.randomUUID();
            UUID movementId = enterStock(itemId, target, quantity, purchase);
            items.add(new GoodsReceipt.Item(itemId, receiptId, target.purchaseItemId(), target.productId(), quantity,
                    target.unitCost(), evaluation.expected(), evaluation.divergence(), evaluation.cancelled(),
                    justification, acceptedBy, movementId));
        }

        GoodsReceipt receipt = new GoodsReceipt(receiptId, 0L, purchase.id(), documentId, receivedOn, clean(command.notes()),
                key, hash, actor, now, items, null);
        try {
            receipts.insert(receipt);
        } catch (DuplicateKeyException sameKeyElsewhere) {
            throw new PurchasingException("PURCHASE_IDEMPOTENCY_KEY_REUSED",
                    "Esta chave de idempotência já foi usada para outro recebimento");
        }
        receipts.insertItems(items);

        GoodsReceipt saved = receipts.find(receiptId).orElseThrow();
        purchases.insertEvent(PurchaseEvent.of(purchase.id(), PurchaseEvent.Type.RECEIPT_REGISTERED, actor, now,
                divergent == 0 ? null : divergent + " item(ns) com divergência aceita",
                json(Map.of("receiptId", receiptId.toString(), "number", String.valueOf(saved.number()),
                        "lines", String.valueOf(items.size()), "divergent", String.valueOf(divergent),
                        "receivedOn", receivedOn.toString()))));
        return new Result(saved, false);
    }

    // ------------------------------------------------------------------ estorno

    /**
     * Estorna o recebimento inteiro (registro próprio e único; o original permanece). O Estoque recusa quando a peça
     * já foi consumida — estornar deixaria o saldo negativo —, e a transação inteira volta atrás: nenhuma linha fica
     * estornada pela metade.
     */
    @Transactional
    public Result reverse(UUID receiptId, String reason, String requestId) {
        String key = requireKey(requestId);
        String motive = requireReason(reason);

        GoodsReceipt receipt = receipts.find(receiptId).orElseThrow(() ->
                new PurchasingException("PURCHASE_RECEIPT_NOT_FOUND", "Recebimento não encontrado"));
        // Mesma ordem de bloqueio do registro (compra primeiro), para não haver espera cruzada.
        purchases.lock(receipt.purchaseId()).orElseThrow(GoodsReceiptService::purchaseNotFound);
        receipt = receipts.find(receiptId).orElseThrow();

        if (receipt.reversed()) {
            if (key.equals(receipt.reversal().idempotencyKey())) return new Result(receipt, true);
            throw new PurchasingException("PURCHASE_RECEIPT_ALREADY_REVERSED", "Este recebimento já foi estornado");
        }
        Optional<GoodsReceipt.Reversal> usedElsewhere = receipts.findReversalByKey(key);
        if (usedElsewhere.isPresent()) {
            throw new PurchasingException("PURCHASE_IDEMPOTENCY_KEY_REUSED",
                    "Esta chave de idempotência já foi usada para outro estorno");
        }

        UUID actor = currentUser.requireId();
        Instant now = Instant.now(clock);
        for (GoodsReceipt.Item item : receipt.items()) {
            try {
                stock.reverseEntry(item.id(), "Estorno do recebimento " + receipt.number() + ": " + motive);
            } catch (PurchaseStockException refused) {
                if ("INSUFFICIENT_STOCK".equals(refused.code())) {
                    throw new PurchasingException("PURCHASE_RECEIPT_STOCK_CONSUMED",
                            "Não é possível estornar: parte do material recebido já foi consumida. "
                                    + "Trate como devolução ao fornecedor. " + refused.getMessage());
                }
                throw new PurchasingException("PURCHASE_STOCK_REFUSED", "O Estoque recusou o estorno: " + refused.getMessage());
            }
        }
        try {
            receipts.insertReversal(new GoodsReceipt.Reversal(UUID.randomUUID(), receiptId, motive, now, actor, key));
        } catch (DuplicateKeyException alreadyReversed) {
            throw new PurchasingException("PURCHASE_RECEIPT_ALREADY_REVERSED", "Este recebimento já foi estornado");
        }
        purchases.insertEvent(PurchaseEvent.of(receipt.purchaseId(), PurchaseEvent.Type.RECEIPT_REVERSED, actor, now, motive,
                json(Map.of("receiptId", receiptId.toString(), "number", String.valueOf(receipt.number())))));
        return new Result(receipts.find(receiptId).orElseThrow(), false);
    }

    // ------------------------------------------------------------------ apoio

    /** Entrada no Estoque pelo contrato público; recusa do Estoque vira erro de Compras com código estável. */
    private UUID enterStock(UUID itemId, ReceivingLine line, BigDecimal quantity, Purchase purchase) {
        try {
            return stock.registerEntry(itemId, line.productId(), quantity, line.unitCost(),
                    "Recebimento da compra " + purchase.number()).movementId();
        } catch (PurchaseStockException refused) {
            throw new PurchasingException("PURCHASE_STOCK_REFUSED",
                    "O Estoque recusou a entrada do item " + line.lineNo() + ": " + refused.getMessage());
        }
    }

    private void requireUnitPrecision(ReceivingLine line, BigDecimal quantity) {
        catalog.product(line.productId()).ifPresentOrElse(product ->
                        ProductQuantityRule.violation(product.unit(), quantity).ifPresent(message -> {
                            throw new PurchasingException("PURCHASE_RECEIPT_QUANTITY_INVALID",
                                    "Item " + line.lineNo() + ": " + message);
                        }),
                () -> {
                    throw new PurchasingException("PURCHASE_ITEM_UNLINKED",
                            "O produto do item " + line.lineNo() + " não existe mais no catálogo");
                });
    }

    private List<ReceivingLine> lines(UUID purchaseId) {
        return receipts.receiving(purchaseId).stream()
                .map(line -> line.withProductUnit(line.productId() == null ? null
                        : catalog.product(line.productId()).map(ProductCatalogQuery.ProductReference::unit).orElse(null)))
                .toList();
    }

    private UUID requireDocument(UUID documentId, UUID purchaseId) {
        if (documentId == null) return null;
        PurchaseDocument document = purchases.findDocument(documentId).orElseThrow(() ->
                new PurchasingException("PURCHASE_DOCUMENT_NOT_FOUND", "Documento não encontrado"));
        if (!document.purchaseId().equals(purchaseId) || document.voidedAt() != null) {
            throw new PurchasingException("PURCHASE_RECEIPT_DOCUMENT_INVALID",
                    "O documento informado não pertence a esta compra ou está anulado");
        }
        return documentId;
    }

    private void requirePurchase(UUID purchaseId) {
        purchases.find(purchaseId).orElseThrow(GoodsReceiptService::purchaseNotFound);
    }

    private static Result replayOf(GoodsReceipt existing, UUID purchaseId, String hash) {
        if (!existing.purchaseId().equals(purchaseId) || !existing.requestHash().equals(hash)) {
            throw new PurchasingException("PURCHASE_IDEMPOTENCY_KEY_REUSED",
                    "Esta chave de idempotência já foi usada para outro recebimento");
        }
        return new Result(existing, true);
    }

    private static PurchasingException purchaseNotFound() {
        return new PurchasingException("PURCHASE_NOT_FOUND", "Compra não encontrada");
    }

    private static String requireKey(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.trim().length() > 120) {
            throw new PurchasingException("PURCHASE_IDEMPOTENCY_KEY_REQUIRED",
                    "Informe o cabeçalho Idempotency-Key (até 120 caracteres) para o retry não duplicar o recebimento");
        }
        return requestId.trim();
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new PurchasingException("PURCHASE_REASON_REQUIRED",
                    "Motivo é obrigatório e deve ter no máximo 500 caracteres");
        }
        return reason.trim();
    }

    private static String clean(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.trim();
        if (trimmed.length() > 1000) {
            throw new PurchasingException("PURCHASE_INVALID", "Observação excede 1000 caracteres");
        }
        return trimmed;
    }

    /**
     * Resumo canônico do pedido, independente da ordem das linhas: é o que distingue "o mesmo retry" de "outro
     * pedido com a mesma chave".
     */
    static String hash(Command command) {
        StringBuilder canonical = new StringBuilder(String.valueOf(command.purchaseId())).append('|')
                .append(command.receivedOn()).append('|').append(command.documentId()).append('|')
                .append(command.notes() == null ? "" : command.notes().trim());
        if (command.lines() != null) {
            command.lines().stream()
                    .sorted(java.util.Comparator.comparing(line -> String.valueOf(line.purchaseItemId())))
                    .forEach(line -> canonical.append('|').append(line.purchaseItemId()).append(':')
                            .append(line.quantity() == null ? "" : line.quantity().stripTrailingZeros().toPlainString())
                            .append(':').append(Boolean.TRUE.equals(line.closeRemaining()))
                            .append(':').append(line.justification() == null ? "" : line.justification().trim()));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String json(Map<String, String> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) out.append(',');
            out.append('"').append(escape(entry.getKey())).append("\":\"").append(escape(entry.getValue())).append('"');
            first = false;
        }
        return out.append('}').toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    // ------------------------------------------------------------------ contratos

    /**
     * @param receivedOn data do recebimento; ausente significa hoje (fuso da oficina), nunca futura
     * @param documentId documento fiscal ao qual o recebimento se refere, quando houver
     */
    public record Command(UUID purchaseId, String requestId, LocalDate receivedOn, UUID documentId, String notes,
                          List<Line> lines) {}

    /**
     * @param quantity       quantidade fisicamente recebida, na unidade do catálogo
     * @param closeRemaining o fornecedor não enviará o restante da linha (encerramento, divergência)
     * @param justification  obrigatória quando há divergência
     */
    public record Line(UUID purchaseItemId, BigDecimal quantity, Boolean closeRemaining, String justification) {}

    public record Result(GoodsReceipt receipt, boolean replayed) {}

    public record Sheet(UUID purchaseId, GoodsReceipt.Status status, List<ReceivingLine> lines) {}
}
