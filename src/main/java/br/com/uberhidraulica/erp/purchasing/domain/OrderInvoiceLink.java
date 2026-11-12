package br.com.uberhidraulica.erp.purchasing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ligação entre um pedido (ou compra direta) aprovado e a compra da NF-e que o atende (`DR-0023`, decidida em
 * 2026-11-12: C1-A, C2-A, C3-A).
 *
 * <p>É a <b>identidade da aquisição</b> que faltava ao pedido: com o vínculo ativo, o pedido está encerrado pela nota
 * e a mercadoria entra uma única vez, pela compra da nota, ao custo real. O estado "encerrado" é derivado daqui —
 * não existe coluna na compra dizendo a mesma coisa.</p>
 *
 * <p>O vínculo nunca é apagado. Cancelar a compra da nota o <b>libera</b> ({@code releasedAt}), e o pedido volta a
 * poder ser vinculado a outra nota.</p>
 *
 * @param orderNumber   número do pedido, só para leitura (nulo ao gravar)
 * @param invoiceNumber número da compra da nota, só para leitura (nulo ao gravar)
 */
public record OrderInvoiceLink(UUID id, UUID orderPurchaseId, Long orderNumber, UUID invoicePurchaseId,
                               Long invoiceNumber, Instant linkedAt, UUID linkedBy, boolean divergent,
                               String divergenceJustification, Instant releasedAt, UUID releasedBy,
                               String releaseReason, List<Difference> differences) {

    public OrderInvoiceLink {
        differences = differences == null ? List.of() : List.copyOf(differences);
    }

    public boolean active() {
        return releasedAt == null;
    }

    /** O que diferiu entre o pedido e a nota. {@code TOTAL} não tem produto; os demais têm. */
    public enum Kind { TOTAL, QUANTITY, UNIT_PRICE, ITEM_NOT_IN_INVOICE, ITEM_NOT_IN_ORDER }

    /**
     * Uma diferença. Para item ausente, o lado que não existe vem nulo; a quantidade do lado que existe vai no valor.
     *
     * @param orderValue   valor do pedido: total (2 casas), quantidade ou preço unitário (4 casas)
     * @param invoiceValue o mesmo, lido da nota
     */
    public record Difference(Kind kind, UUID productId, String description, BigDecimal orderValue,
                             BigDecimal invoiceValue) {}

    // ------------------------------------------------------------------ regras

    /** Só pedido ou compra direta <b>aprovado</b> e do mesmo fornecedor da nota pode ser vinculado. */
    public static void requireLinkable(Purchase order, UUID invoiceSupplierId) {
        if (!order.needsApproval()) {
            throw new PurchasingException("PURCHASE_ORDER_LINK_INVALID",
                    "Só pedido ou compra direta pode ser vinculado a uma nota");
        }
        if (order.status() != Purchase.Status.APPROVED) {
            throw new PurchasingException("PURCHASE_ORDER_LINK_INVALID",
                    "Só pedido aprovado pode ser vinculado à nota (situação atual: " + order.status() + ")");
        }
        if (!order.supplierId().equals(invoiceSupplierId)) {
            throw new PurchasingException("PURCHASE_ORDER_LINK_INVALID",
                    "O pedido é de outro fornecedor: só se vincula pedido do emitente da nota");
        }
    }

    /**
     * Compara o pedido com a compra da nota, produto a produto (C2-A/C3-A). Não há tolerância: qualquer diferença
     * aparece. As linhas do mesmo produto são somadas, e o preço comparado é o médio ponderado (4 casas), porque a
     * nota pode repetir o produto em mais de uma linha.
     *
     * <p>Item substituído aparece como duas diferenças — o original ausente da nota e o substituto ausente do pedido
     * —, e é assim que o histórico guarda os dois (C3-A).</p>
     */
    public static List<Difference> compare(Purchase order, Purchase invoice) {
        List<Difference> differences = new ArrayList<>();
        BigDecimal orderTotal = PurchaseMoney.charged(order.payableTotal());
        BigDecimal invoiceTotal = PurchaseMoney.charged(invoice.payableTotal());
        if (orderTotal.compareTo(invoiceTotal) != 0) {
            differences.add(new Difference(Kind.TOTAL, null, "Total a pagar", orderTotal, invoiceTotal));
        }

        Map<UUID, Line> ordered = aggregate(order.items());
        Map<UUID, Line> invoiced = aggregate(invoice.items());
        for (Map.Entry<UUID, Line> entry : ordered.entrySet()) {
            Line mine = entry.getValue();
            Line theirs = invoiced.get(entry.getKey());
            if (theirs == null) {
                differences.add(new Difference(Kind.ITEM_NOT_IN_INVOICE, entry.getKey(), mine.description,
                        mine.quantity, null));
                continue;
            }
            if (mine.quantity.compareTo(theirs.quantity) != 0) {
                differences.add(new Difference(Kind.QUANTITY, entry.getKey(), mine.description, mine.quantity,
                        theirs.quantity));
            }
            if (mine.unitPrice().compareTo(theirs.unitPrice()) != 0) {
                differences.add(new Difference(Kind.UNIT_PRICE, entry.getKey(), mine.description, mine.unitPrice(),
                        theirs.unitPrice()));
            }
        }
        for (Map.Entry<UUID, Line> entry : invoiced.entrySet()) {
            if (!ordered.containsKey(entry.getKey())) {
                differences.add(new Difference(Kind.ITEM_NOT_IN_ORDER, entry.getKey(), entry.getValue().description,
                        null, entry.getValue().quantity));
            }
        }
        return List.copyOf(differences);
    }

    /** Divergência exige justificativa (3 a 500 caracteres); sem divergência, a justificativa é ignorada. */
    public static String requireJustification(List<Difference> differences, String justification) {
        if (differences.isEmpty()) return null;
        String text = justification == null ? "" : justification.trim();
        if (text.length() < 3 || text.length() > 500) {
            throw new PurchasingException("PURCHASE_ORDER_LINK_JUSTIFICATION_REQUIRED",
                    "A nota difere do pedido em " + differences.size()
                            + " ponto(s): informe a justificativa (3 a 500 caracteres) para vincular");
        }
        return text;
    }

    private static Map<UUID, Line> aggregate(List<PurchaseItem> items) {
        Map<UUID, Line> lines = new LinkedHashMap<>();
        for (PurchaseItem item : items) {
            if (item.productId() == null) continue;
            Line line = lines.computeIfAbsent(item.productId(), id -> new Line(item.description()));
            line.quantity = line.quantity.add(PurchaseMoney.quantity(item.quantity()));
            line.value = line.value.add(PurchaseMoney.quantity(item.quantity())
                    .multiply(PurchaseMoney.unit(item.unitPrice())));
        }
        return lines;
    }

    private static final class Line {
        private final String description;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal value = BigDecimal.ZERO;

        private Line(String description) { this.description = description; }

        private BigDecimal unitPrice() {
            return quantity.signum() == 0 ? PurchaseMoney.unit(BigDecimal.ZERO)
                    : PurchaseMoney.unit(value.divide(quantity, 10, java.math.RoundingMode.HALF_UP));
        }
    }
}
