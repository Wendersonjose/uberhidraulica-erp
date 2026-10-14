package br.com.uberhidraulica.erp.purchasing.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TASK-0021 I5: valorização do pedido (DR-0007, D2) e regras do fluxo de aprovação (D8), sem banco. */
class PurchaseOrderTest {

    private static BigDecimal bd(String value) { return new BigDecimal(value); }

    private static PurchaseOrder.ResolvedLine line(String quantity, String price, String discount) {
        return new PurchaseOrder.ResolvedLine(new PurchaseOrder.Line(UUID.randomUUID(), null, bd(quantity), bd(price),
                discount == null ? null : bd(discount), null), "Produto", "UNIDADE", null, null);
    }

    private static PurchaseOrder.Priced price(PurchaseOrder.Expenses expenses, PurchaseOrder.ResolvedLine... lines) {
        return PurchaseOrder.price(UUID.randomUUID(), null, List.of(lines), expenses);
    }

    private static String codeOf(Runnable action) {
        try {
            action.run();
        } catch (PurchasingException refused) {
            return refused.code();
        }
        throw new AssertionError("Esperava uma recusa de Compras");
    }

    private static Purchase purchase(Purchase.Origin origin, Purchase.Status status, UUID createdBy, long version) {
        return new Purchase(UUID.randomUUID(), 1L, UUID.randomUUID(), origin, status, bd("10.00"), bd("0.00"),
                bd("0.00"), bd("0.00"), bd("0.00"), bd("0.00"), bd("10.00"), null, null, null, null, "key",
                Instant.parse("2026-10-14T12:00:00Z"), createdBy, null, null, null, version, null, null, null, null,
                List.of());
    }

    // ================================================================ valorização

    @Test
    void singleLineWithoutExpensesCostsExactlyThePrice() {
        PurchaseOrder.Priced priced = price(PurchaseOrder.Expenses.none(), line("3", "10.00", null));

        assertThat(priced.goodsTotal()).isEqualByComparingTo("30.00");
        assertThat(priced.payableTotal()).isEqualByComparingTo("30.00");
        assertThat(priced.items()).hasSize(1);
        PurchaseItem item = priced.items().get(0);
        assertThat(item.unitCost()).isEqualByComparingTo("10.0000");
        assertThat(item.quantityOriginal()).isEqualByComparingTo("3");
        assertThat(item.unitConversionFactor()).isEqualByComparingTo("1");
        assertThat(item.cost().totalAmount()).isEqualByComparingTo("30.00");
    }

    @Test
    void expensesAreAllocatedByValueAndCloseToTheCent() {
        PurchaseOrder.Priced priced = price(new PurchaseOrder.Expenses(bd("10.00"), null, null),
                line("10", "10.00", null), line("5", "10.00", null));

        assertThat(priced.items().get(0).cost().freightShare()).isEqualByComparingTo("6.67");
        assertThat(priced.items().get(1).cost().freightShare()).isEqualByComparingTo("3.33");
        assertThat(priced.items().get(0).unitCost()).isEqualByComparingTo("10.6670");
        assertThat(priced.items().get(1).unitCost()).isEqualByComparingTo("10.6660");
        assertThat(priced.payableTotal()).isEqualByComparingTo("160.00");
        // O custo gravado no item é exatamente a soma das parcelas: é o que o banco também confere.
        priced.items().forEach(item -> assertThat(item.cost().totalAmount()).isEqualByComparingTo(
                item.cost().baseAmount().add(item.cost().freightShare()).add(item.cost().insuranceShare())
                        .add(item.cost().otherShare())));
    }

    @Test
    void discountReducesTheNetValueButNotTheGoodsTotal() {
        PurchaseOrder.Priced priced = price(PurchaseOrder.Expenses.none(), line("4", "25.50", "2.00"));

        assertThat(priced.goodsTotal()).isEqualByComparingTo("102.00");
        assertThat(priced.discountTotal()).isEqualByComparingTo("2.00");
        assertThat(priced.payableTotal()).isEqualByComparingTo("100.00");
        assertThat(priced.items().get(0).lineTotal()).isEqualByComparingTo("100.00");
    }

    @Test
    void grossIsRoundedHalfUpToTheCent() {
        PurchaseOrder.Priced priced = price(PurchaseOrder.Expenses.none(), line("3", "3.3333", null));

        assertThat(priced.goodsTotal()).isEqualByComparingTo("10.00");
    }

    @Test
    void totalsAlwaysCloseWithTheComposition() {
        PurchaseOrder.Priced priced = price(new PurchaseOrder.Expenses(bd("7.31"), bd("1.11"), bd("0.99")),
                line("7", "13.37", "0.50"), line("2", "99.99", null), line("1", "0.01", null));

        Purchase purchase = new Purchase(UUID.randomUUID(), 1L, UUID.randomUUID(), Purchase.Origin.ORDER,
                Purchase.Status.DRAFT, priced.goodsTotal(), priced.discountTotal(), priced.freightTotal(),
                priced.insuranceTotal(), priced.otherTotal(), bd("0.00"), priced.payableTotal(), null, null, null,
                null, "k", Instant.now(), UUID.randomUUID(), null, null, null, 0L, null, null, null, null,
                priced.items());

        purchase.requireTotalsClose();
        BigDecimal itemsTotal = priced.items().stream().map(item -> item.cost().totalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Σ custo dos itens = total do pedido: o estoque valorizado fecha com o que se paga.
        assertThat(itemsTotal).isEqualByComparingTo(priced.payableTotal());
    }

    @Test
    void invalidLinesAreRefusedWithAStableCode() {
        assertThat(codeOf(() -> price(PurchaseOrder.Expenses.none()))).isEqualTo("PURCHASE_ORDER_LINES_REQUIRED");
        assertThat(codeOf(() -> price(PurchaseOrder.Expenses.none(), line("0", "10.00", null))))
                .isEqualTo("PURCHASE_ORDER_LINE_INVALID");
        assertThat(codeOf(() -> price(PurchaseOrder.Expenses.none(), line("1000001", "1.00", null))))
                .isEqualTo("PURCHASE_ORDER_LINE_INVALID");
        assertThat(codeOf(() -> price(PurchaseOrder.Expenses.none(), line("1", "-1.00", null))))
                .isEqualTo("PURCHASE_ORDER_LINE_INVALID");
        assertThat(codeOf(() -> price(PurchaseOrder.Expenses.none(), line("1", "10.00", "10.01"))))
                .isEqualTo("PURCHASE_ORDER_LINE_INVALID");
        assertThat(codeOf(() -> price(PurchaseOrder.Expenses.none(), line("1", "10.00", "-1.00"))))
                .isEqualTo("PURCHASE_ORDER_LINE_INVALID");
    }

    @Test
    void lineWithoutProductOrQuantityIsRefused() {
        PurchaseOrder.ResolvedLine noProduct = new PurchaseOrder.ResolvedLine(new PurchaseOrder.Line(null, null,
                bd("1"), bd("1.00"), null, null), "x", "UNIDADE", null, null);
        PurchaseOrder.ResolvedLine noQuantity = new PurchaseOrder.ResolvedLine(new PurchaseOrder.Line(UUID.randomUUID(),
                null, null, bd("1.00"), null, null), "x", "UNIDADE", null, null);

        assertThat(codeOf(() -> price(PurchaseOrder.Expenses.none(), noProduct))).isEqualTo("PURCHASE_ORDER_LINE_INVALID");
        assertThat(codeOf(() -> price(PurchaseOrder.Expenses.none(), noQuantity))).isEqualTo("PURCHASE_ORDER_LINE_INVALID");
    }

    @Test
    void negativeOrAbsurdExpensesAreRefused() {
        assertThat(codeOf(() -> price(new PurchaseOrder.Expenses(bd("-0.01"), null, null),
                line("1", "10.00", null)))).isEqualTo("PURCHASE_ORDER_EXPENSE_INVALID");
        assertThat(codeOf(() -> price(new PurchaseOrder.Expenses(null, bd("100000001"), null),
                line("1", "10.00", null)))).isEqualTo("PURCHASE_ORDER_EXPENSE_INVALID");
    }

    @Test
    void anOrderWorthNothingIsRefused() {
        assertThat(codeOf(() -> price(PurchaseOrder.Expenses.none(), line("5", "0.00", null))))
                .isEqualTo("PURCHASE_ORDER_TOTAL_INVALID");
    }

    @Test
    void aBonusLineWithNoValueStillSharesTheExpensesOfTheOthers() {
        PurchaseOrder.Priced priced = price(new PurchaseOrder.Expenses(bd("3.00"), null, null),
                line("1", "30.00", null), line("1", "0.00", null));

        assertThat(priced.items().get(0).cost().freightShare()).isEqualByComparingTo("3.00");
        assertThat(priced.items().get(1).cost().freightShare()).isEqualByComparingTo("0.00");
        assertThat(priced.items().get(1).unitCost()).isEqualByComparingTo("0.0000");
    }

    @Test
    void moreThanTheMaximumNumberOfLinesIsRefused() {
        List<PurchaseOrder.ResolvedLine> lines = new ArrayList<>();
        for (int i = 0; i <= PurchaseOrder.MAX_LINES; i++) lines.add(line("1", "1.00", null));

        assertThat(codeOf(() -> PurchaseOrder.price(UUID.randomUUID(), null, lines, PurchaseOrder.Expenses.none())))
                .isEqualTo("PURCHASE_ORDER_TOO_MANY_LINES");
    }

    @Test
    void longDescriptionAndSupplierCodeAreHandledWithoutOverflowingTheColumns() {
        String longDescription = "D".repeat(500);
        PurchaseOrder.ResolvedLine ok = new PurchaseOrder.ResolvedLine(new PurchaseOrder.Line(UUID.randomUUID(),
                "  ABC-1  ", bd("1"), bd("1.00"), null, null), longDescription, "UNIDADE", null, null);

        PurchaseItem item = price(PurchaseOrder.Expenses.none(), ok).items().get(0);

        assertThat(item.description()).hasSize(PurchaseOrder.MAX_DESCRIPTION);
        assertThat(item.supplierCode()).isEqualTo("ABC-1");

        PurchaseOrder.ResolvedLine tooLong = new PurchaseOrder.ResolvedLine(new PurchaseOrder.Line(UUID.randomUUID(),
                "C".repeat(PurchaseOrder.MAX_SUPPLIER_CODE + 1), bd("1"), bd("1.00"), null, null), "x", "UNIDADE",
                null, null);
        assertThat(codeOf(() -> price(PurchaseOrder.Expenses.none(), tooLong))).isEqualTo("PURCHASE_ORDER_LINE_INVALID");
    }

    // ================================================================ fluxo

    @Test
    void onlyADraftOfAnOrderIsEditable() {
        UUID author = UUID.randomUUID();
        PurchaseOrder.requireEditable(purchase(Purchase.Origin.ORDER, Purchase.Status.DRAFT, author, 0));
        PurchaseOrder.requireEditable(purchase(Purchase.Origin.DIRECT, Purchase.Status.DRAFT, author, 0));

        assertThat(codeOf(() -> PurchaseOrder.requireEditable(
                purchase(Purchase.Origin.ORDER, Purchase.Status.AWAITING_APPROVAL, author, 0))))
                .isEqualTo("PURCHASE_ORDER_NOT_EDITABLE");
        assertThat(codeOf(() -> PurchaseOrder.requireEditable(
                purchase(Purchase.Origin.ORDER, Purchase.Status.APPROVED, author, 0))))
                .isEqualTo("PURCHASE_ORDER_NOT_EDITABLE");
        assertThat(codeOf(() -> PurchaseOrder.requireEditable(
                purchase(Purchase.Origin.ORDER, Purchase.Status.CANCELLED, author, 0))))
                .isEqualTo("PURCHASE_ORDER_NOT_EDITABLE");
        // A nota importada reflete o documento fiscal: nem em rascunho ela é editável.
        assertThat(codeOf(() -> PurchaseOrder.requireEditable(
                purchase(Purchase.Origin.NFE_IMPORT, Purchase.Status.DRAFT, author, 0))))
                .isEqualTo("PURCHASE_ORDER_NOT_EDITABLE");
    }

    @Test
    void aStaleVersionIsRefused() {
        Purchase current = purchase(Purchase.Origin.ORDER, Purchase.Status.DRAFT, UUID.randomUUID(), 4);

        PurchaseOrder.requireVersion(current, 4);
        assertThat(codeOf(() -> PurchaseOrder.requireVersion(current, 3))).isEqualTo("PURCHASE_ORDER_VERSION_CONFLICT");
        assertThat(codeOf(() -> PurchaseOrder.requireVersion(current, 5))).isEqualTo("PURCHASE_ORDER_VERSION_CONFLICT");
    }

    @Test
    void onlyAnOrderAwaitingApprovalCanBeApprovedOrReturned() {
        UUID author = UUID.randomUUID();
        PurchaseOrder.requireAwaitingApproval(purchase(Purchase.Origin.ORDER, Purchase.Status.AWAITING_APPROVAL, author, 1));

        for (Purchase.Status status : List.of(Purchase.Status.DRAFT, Purchase.Status.APPROVED, Purchase.Status.CANCELLED)) {
            assertThat(codeOf(() -> PurchaseOrder.requireAwaitingApproval(
                    purchase(Purchase.Origin.ORDER, status, author, 1))))
                    .as("situação " + status).isEqualTo("PURCHASE_ORDER_NOT_AWAITING_APPROVAL");
        }
        assertThat(codeOf(() -> PurchaseOrder.requireAwaitingApproval(
                purchase(Purchase.Origin.NFE_IMPORT, Purchase.Status.AWAITING_APPROVAL, author, 1))))
                .isEqualTo("PURCHASE_ORDER_NOT_AWAITING_APPROVAL");
    }

    @Test
    void theAuthorCannotApproveTheirOwnOrderUnlessTheyHoldTheExceptionPermission() {
        UUID author = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Purchase order = purchase(Purchase.Origin.ORDER, Purchase.Status.AWAITING_APPROVAL, author, 1);

        // Outra pessoa aprova, com ou sem a exceção: a exceção só vale para o próprio autor.
        assertThat(PurchaseOrder.checkApprover(order, other, false)).isFalse();
        assertThat(PurchaseOrder.checkApprover(order, other, true)).isFalse();

        assertThat(codeOf(() -> PurchaseOrder.checkApprover(order, author, false)))
                .isEqualTo("PURCHASE_APPROVAL_SELF_FORBIDDEN");
        // Com a exceção a aprovação passa e fica marcada, para a trilha registrar que foi o próprio autor.
        assertThat(PurchaseOrder.checkApprover(order, author, true)).isTrue();
    }

    @Test
    void submittingRequiresAnEditableOrderWithItems() {
        UUID author = UUID.randomUUID();
        // Sem itens carregados, o envio é recusado: um pedido vazio não tem o que aprovar.
        assertThatThrownBy(() -> PurchaseOrder.requireSubmittable(
                purchase(Purchase.Origin.ORDER, Purchase.Status.DRAFT, author, 0)))
                .isInstanceOf(PurchasingException.class)
                .extracting(e -> ((PurchasingException) e).code()).isEqualTo("PURCHASE_ORDER_LINES_REQUIRED");
    }
}
