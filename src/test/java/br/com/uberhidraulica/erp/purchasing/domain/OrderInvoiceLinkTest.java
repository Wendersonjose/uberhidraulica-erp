package br.com.uberhidraulica.erp.purchasing.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** `DR-0023` C1-A/C2-A/C3-A: o que pode ser vinculado e o que conta como diferença entre o pedido e a nota. */
class OrderInvoiceLinkTest {
    private static final UUID SUPPLIER = UUID.randomUUID();
    private static final UUID FILTER = UUID.randomUUID();
    private static final UUID OIL = UUID.randomUUID();

    private static PurchaseItem item(int line, UUID product, String quantity, String unitPrice) {
        return new PurchaseItem(UUID.randomUUID(), UUID.randomUUID(), line, product, null, "Produto " + line, "UNIDADE",
                new BigDecimal(quantity), BigDecimal.ONE, new BigDecimal(quantity), new BigDecimal(unitPrice),
                new BigDecimal(unitPrice), null, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, null);
    }

    private static Purchase purchase(Purchase.Origin origin, Purchase.Status status, UUID supplier, String total,
                                     PurchaseItem... items) {
        BigDecimal payable = new BigDecimal(total);
        return new Purchase(UUID.randomUUID(), 1L, supplier, origin, status, payable, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, payable, null, null, null, null, "key",
                Instant.parse("2026-11-12T12:00:00Z"), UUID.randomUUID(), null, null, null, 0L, null, null, null, null,
                List.of(items));
    }

    private static Purchase order(String total, PurchaseItem... items) {
        return purchase(Purchase.Origin.ORDER, Purchase.Status.APPROVED, SUPPLIER, total, items);
    }

    private static Purchase invoice(String total, PurchaseItem... items) {
        return purchase(Purchase.Origin.NFE_IMPORT, Purchase.Status.APPROVED, SUPPLIER, total, items);
    }

    @Test
    void anInvoiceWithTheSameProductsQuantitiesPricesAndTotalHasNoDifference() {
        // A nota repete o produto em duas linhas: o que conta é a soma por produto.
        List<OrderInvoiceLink.Difference> differences = OrderInvoiceLink.compare(
                order("100.00", item(1, FILTER, "4", "25.00")),
                invoice("100.00", item(1, FILTER, "1", "25.00"), item(2, FILTER, "3", "25.00")));

        assertThat(differences).isEmpty();
        assertThat(OrderInvoiceLink.requireJustification(differences, null)).isNull();
    }

    @Test
    void totalQuantityAndPriceDifferencesAreReportedWithoutAnyTolerance() {
        List<OrderInvoiceLink.Difference> differences = OrderInvoiceLink.compare(
                order("100.00", item(1, FILTER, "4", "25.00")),
                invoice("100.05", item(1, FILTER, "5", "20.01")));

        assertThat(differences).extracting(OrderInvoiceLink.Difference::kind).containsExactly(
                OrderInvoiceLink.Kind.TOTAL, OrderInvoiceLink.Kind.QUANTITY, OrderInvoiceLink.Kind.UNIT_PRICE);
        assertThat(differences.get(0).orderValue()).isEqualByComparingTo("100.00");
        assertThat(differences.get(0).invoiceValue()).isEqualByComparingTo("100.05");
        assertThat(differences.get(2).invoiceValue()).isEqualByComparingTo("20.01");
    }

    @Test
    void aSubstitutedItemIsTheOriginalMissingFromTheInvoiceAndTheSubstituteMissingFromTheOrder() {
        List<OrderInvoiceLink.Difference> differences = OrderInvoiceLink.compare(
                order("50.00", item(1, FILTER, "2", "25.00")), invoice("50.00", item(1, OIL, "2", "25.00")));

        assertThat(differences).extracting(OrderInvoiceLink.Difference::kind).containsExactly(
                OrderInvoiceLink.Kind.ITEM_NOT_IN_INVOICE, OrderInvoiceLink.Kind.ITEM_NOT_IN_ORDER);
        assertThat(differences.get(0).productId()).isEqualTo(FILTER);
        assertThat(differences.get(0).invoiceValue()).isNull();
        assertThat(differences.get(1).productId()).isEqualTo(OIL);
        assertThat(differences.get(1).orderValue()).isNull();
    }

    @Test
    void aDifferenceRequiresAJustificationOfThreeToFiveHundredCharacters() {
        List<OrderInvoiceLink.Difference> differences = OrderInvoiceLink.compare(
                order("50.00", item(1, FILTER, "2", "25.00")), invoice("75.00", item(1, FILTER, "3", "25.00")));

        for (String invalid : new String[] {null, "  ", "ab", "x".repeat(501)}) {
            assertThatThrownBy(() -> OrderInvoiceLink.requireJustification(differences, invalid))
                    .isInstanceOf(PurchasingException.class)
                    .extracting(e -> ((PurchasingException) e).code())
                    .isEqualTo("PURCHASE_ORDER_LINK_JUSTIFICATION_REQUIRED");
        }
        assertThat(OrderInvoiceLink.requireJustification(differences, "  veio uma a mais  ")).isEqualTo("veio uma a mais");
    }

    @Test
    void onlyAnApprovedOrderOrDirectPurchaseOfTheSameSupplierIsLinkable() {
        OrderInvoiceLink.requireLinkable(order("10.00", item(1, FILTER, "1", "10.00")), SUPPLIER);
        OrderInvoiceLink.requireLinkable(purchase(Purchase.Origin.DIRECT, Purchase.Status.APPROVED, SUPPLIER, "10.00",
                item(1, FILTER, "1", "10.00")), SUPPLIER);

        Purchase otherSupplier = order("10.00", item(1, FILTER, "1", "10.00"));
        Purchase draft = purchase(Purchase.Origin.ORDER, Purchase.Status.DRAFT, SUPPLIER, "10.00");
        Purchase cancelled = purchase(Purchase.Origin.ORDER, Purchase.Status.CANCELLED, SUPPLIER, "10.00");
        Purchase imported = invoice("10.00");
        assertThatThrownBy(() -> OrderInvoiceLink.requireLinkable(otherSupplier, UUID.randomUUID()))
                .hasMessageContaining("outro fornecedor");
        for (Purchase refused : List.of(draft, cancelled, imported)) {
            assertThatThrownBy(() -> OrderInvoiceLink.requireLinkable(refused, SUPPLIER))
                    .isInstanceOf(PurchasingException.class)
                    .extracting(e -> ((PurchasingException) e).code()).isEqualTo("PURCHASE_ORDER_LINK_INVALID");
        }
    }
}
