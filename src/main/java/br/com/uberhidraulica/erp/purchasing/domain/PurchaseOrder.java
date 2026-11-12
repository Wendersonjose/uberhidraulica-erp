package br.com.uberhidraulica.erp.purchasing.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Regras do pedido de compra e da compra direta (`TASK-0021` I5, `DR-0022` D8).
 *
 * <p>Pedido <b>não movimenta estoque</b> e <b>não gera conta a pagar</b>. Estoque só entra pelo recebimento físico
 * (I4). A obrigação financeira nasce com a nota (I3): gerar conta aqui e importar a nota depois contaria o mesmo
 * valor duas vezes, e a conciliação pedido × NF-e ainda não tem regra aprovada (DR-0023).</p>
 *
 * <p>Os valores de um pedido são <b>estimativa de compra</b>: preço combinado, desconto e as despesas informadas.
 * Imposto (IPI, ICMS-ST) só existe na nota; por isso o custo de aquisição calculado aqui não os inclui.</p>
 *
 * <p>Ciclo: {@code DRAFT → AWAITING_APPROVAL → APPROVED}, com devolução {@code AWAITING_APPROVAL → DRAFT}.
 * Só o rascunho é editável. Cancelar é o caminho comum de {@link Purchase#checkCancellation()}.</p>
 */
public final class PurchaseOrder {
    public static final int MAX_LINES = 200;
    /** Limites de sanidade: um erro de digitação (1000000 no lugar de 100) não deve virar pedido aprovado. */
    public static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000");
    public static final BigDecimal MAX_UNIT_PRICE = new BigDecimal("1000000");
    public static final BigDecimal MAX_EXPENSE = new BigDecimal("100000000");
    public static final int MAX_DESCRIPTION = 300;
    public static final int MAX_SUPPLIER_CODE = 60;

    private PurchaseOrder() {}

    /** Linha como o operador a informa. A unidade é sempre a do catálogo: pedido não precisa de conversão. */
    public record Line(UUID productId, String supplierCode, BigDecimal quantity, BigDecimal unitPrice,
                       BigDecimal discount, UUID workOrderItemId) {}

    /** Linha com o produto do catálogo já resolvido pela aplicação (descrição e unidade da época do pedido). */
    public record ResolvedLine(Line line, String description, String unit, String ncm, String gtin) {}

    public record Expenses(BigDecimal freight, BigDecimal insurance, BigDecimal other) {
        public static Expenses none() { return new Expenses(null, null, null); }
    }

    public record Priced(List<PurchaseItem> items, BigDecimal goodsTotal, BigDecimal discountTotal,
                         BigDecimal freightTotal, BigDecimal insuranceTotal, BigDecimal otherTotal,
                         BigDecimal payableTotal) {}

    // ------------------------------------------------------------------ cálculo

    /**
     * Valoriza as linhas: bruto = quantidade × preço, líquido = bruto − desconto, e as despesas do cabeçalho são
     * rateadas proporcionalmente ao líquido (D2, com fechamento exato dos centavos). O total a pagar é a soma das
     * parcelas já arredondadas (DR-0007), de modo que {@link Purchase#requireTotalsClose()} sempre fecha.
     */
    public static Priced price(UUID purchaseId, UUID workOrderId, List<ResolvedLine> lines, Expenses expenses) {
        if (lines == null || lines.isEmpty()) {
            throw new PurchasingException("PURCHASE_ORDER_LINES_REQUIRED", "Informe ao menos um item");
        }
        if (lines.size() > MAX_LINES) {
            throw new PurchasingException("PURCHASE_ORDER_TOO_MANY_LINES", "Um pedido aceita até " + MAX_LINES + " itens");
        }
        BigDecimal freight = expense(expenses == null ? null : expenses.freight(), "Frete");
        BigDecimal insurance = expense(expenses == null ? null : expenses.insurance(), "Seguro");
        BigDecimal other = expense(expenses == null ? null : expenses.other(), "Outras despesas");

        int count = lines.size();
        List<BigDecimal> quantities = new ArrayList<>(count);
        List<BigDecimal> prices = new ArrayList<>(count);
        List<BigDecimal> grosses = new ArrayList<>(count);
        List<BigDecimal> discounts = new ArrayList<>(count);
        List<BigDecimal> nets = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Line line = lines.get(index).line();
            String where = "Item " + (index + 1) + ": ";
            if (line.productId() == null) {
                throw new PurchasingException("PURCHASE_ORDER_LINE_INVALID", where + "informe o produto");
            }
            BigDecimal quantity = PurchaseMoney.quantity(line.quantity());
            if (line.quantity() == null || quantity.signum() <= 0 || quantity.compareTo(MAX_QUANTITY) > 0) {
                throw new PurchasingException("PURCHASE_ORDER_LINE_INVALID",
                        where + "quantidade deve ser maior que zero e até " + MAX_QUANTITY.toPlainString());
            }
            BigDecimal price = PurchaseMoney.unit(line.unitPrice());
            if (line.unitPrice() == null || price.signum() < 0 || price.compareTo(MAX_UNIT_PRICE) > 0) {
                throw new PurchasingException("PURCHASE_ORDER_LINE_INVALID",
                        where + "preço unitário deve estar entre zero e " + MAX_UNIT_PRICE.toPlainString());
            }
            BigDecimal gross = PurchaseMoney.charged(quantity.multiply(price));
            BigDecimal discount = PurchaseMoney.charged(line.discount());
            if (discount.signum() < 0 || discount.compareTo(gross) > 0) {
                throw new PurchasingException("PURCHASE_ORDER_LINE_INVALID",
                        where + "desconto não pode ser negativo nem maior que o valor do item");
            }
            quantities.add(quantity);
            prices.add(price);
            grosses.add(gross);
            discounts.add(discount);
            nets.add(gross.subtract(discount));
        }

        List<BigDecimal> freightShares = ExpenseAllocation.proportionalToValue(nets, freight);
        List<BigDecimal> insuranceShares = ExpenseAllocation.proportionalToValue(nets, insurance);
        List<BigDecimal> otherShares = ExpenseAllocation.proportionalToValue(nets, other);

        List<PurchaseItem> items = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ResolvedLine resolved = lines.get(index);
            Line line = resolved.line();
            AcquisitionCost cost = AcquisitionCost.of(grosses.get(index), discounts.get(index), BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, freightShares.get(index), insuranceShares.get(index),
                    otherShares.get(index), quantities.get(index), AcquisitionCost.Parameters.approvedDefault());
            items.add(new PurchaseItem(UUID.randomUUID(), purchaseId, index + 1, line.productId(),
                    supplierCode(line.supplierCode()), description(resolved.description()), resolved.unit(),
                    quantities.get(index), BigDecimal.ONE, quantities.get(index), prices.get(index), cost.unitCost(),
                    cost, discounts.get(index), nets.get(index), resolved.ncm(), null, resolved.gtin(), workOrderId,
                    line.workOrderItemId()));
        }

        BigDecimal goods = PurchaseMoney.sumCharged(grosses);
        BigDecimal discountTotal = PurchaseMoney.sumCharged(discounts);
        BigDecimal payable = PurchaseMoney.sumCharged(List.of(goods, freight, insurance, other))
                .subtract(discountTotal);
        if (payable.signum() <= 0) {
            throw new PurchasingException("PURCHASE_ORDER_TOTAL_INVALID", "O total do pedido deve ser maior que zero");
        }
        return new Priced(List.copyOf(items), goods, discountTotal, freight, insurance, other, payable);
    }

    private static BigDecimal expense(BigDecimal value, String label) {
        BigDecimal charged = PurchaseMoney.charged(value);
        if (charged.signum() < 0 || charged.compareTo(MAX_EXPENSE) > 0) {
            throw new PurchasingException("PURCHASE_ORDER_EXPENSE_INVALID",
                    label + " deve estar entre zero e " + MAX_EXPENSE.toPlainString());
        }
        return charged;
    }

    private static String supplierCode(String code) {
        if (code == null || code.isBlank()) return null;
        String trimmed = code.trim();
        if (trimmed.length() > MAX_SUPPLIER_CODE) {
            throw new PurchasingException("PURCHASE_ORDER_LINE_INVALID",
                    "Código do fornecedor aceita até " + MAX_SUPPLIER_CODE + " caracteres");
        }
        return trimmed;
    }

    private static String description(String description) {
        String text = description == null ? "" : description.trim();
        return text.length() > MAX_DESCRIPTION ? text.substring(0, MAX_DESCRIPTION) : text;
    }

    // ------------------------------------------------------------------ fluxo

    /** Só o rascunho de pedido ou compra direta é editável. */
    public static void requireEditable(Purchase purchase) {
        if (!purchase.needsApproval()) {
            throw new PurchasingException("PURCHASE_ORDER_NOT_EDITABLE",
                    "Compra importada de NF-e não é editável: ela reflete o documento fiscal");
        }
        if (purchase.status() != Purchase.Status.DRAFT) {
            throw new PurchasingException("PURCHASE_ORDER_NOT_EDITABLE",
                    "Só o rascunho pode ser alterado (situação atual: " + purchase.status() + ")");
        }
    }

    /**
     * Controle otimista: a tela que o operador viu pode já estar velha. Em especial na aprovação — quem aprova
     * precisa estar aprovando exatamente o que viu, e não a versão que alguém reeditou depois.
     */
    public static void requireVersion(Purchase purchase, long expected) {
        if (purchase.version() != expected) {
            throw new PurchasingException("PURCHASE_ORDER_VERSION_CONFLICT",
                    "O pedido foi alterado por outra pessoa (versão " + purchase.version()
                            + ", você viu a " + expected + "): recarregue antes de continuar");
        }
    }

    public static void requireSubmittable(Purchase purchase) {
        requireEditable(purchase);
        if (purchase.items().isEmpty()) {
            throw new PurchasingException("PURCHASE_ORDER_LINES_REQUIRED", "Informe ao menos um item");
        }
    }

    public static void requireAwaitingApproval(Purchase purchase) {
        if (!purchase.needsApproval() || purchase.status() != Purchase.Status.AWAITING_APPROVAL) {
            throw new PurchasingException("PURCHASE_ORDER_NOT_AWAITING_APPROVAL",
                    "O pedido não está aguardando aprovação (situação atual: " + purchase.status() + ")");
        }
    }

    /**
     * Segregação de funções (D8): quem criou o pedido não o aprova, exceto quem tem a permissão de aprovar o
     * próprio. A exceção é permissão, e não perfil escrito no código: o proprietário a concede ou retira sem deploy.
     *
     * @return verdadeiro quando a aprovação só foi possível pela exceção do próprio autor — vai para a trilha
     */
    public static boolean checkApprover(Purchase purchase, UUID approver, boolean mayApproveOwn) {
        boolean own = purchase.createdBy().equals(approver);
        if (own && !mayApproveOwn) {
            throw new PurchasingException("PURCHASE_APPROVAL_SELF_FORBIDDEN",
                    "Quem criou o pedido não pode aprová-lo: peça a outra pessoa com permissão de aprovar");
        }
        return own;
    }
}
