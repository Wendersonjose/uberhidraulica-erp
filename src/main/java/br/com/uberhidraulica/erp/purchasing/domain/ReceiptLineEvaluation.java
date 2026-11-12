package br.com.uberhidraulica.erp.purchasing.domain;

import java.math.BigDecimal;

/**
 * Avalia uma linha de recebimento contra o saldo pendente (`DR-0022` D3, aprovada: divergências exigem autorização e
 * justificativa; o estoque recebe somente a quantidade física conferida).
 *
 * <p>Receber menos do que o pendente <b>não</b> é divergência: é recebimento parcial (AG-05 §57), e o saldo continua
 * pendente para uma próxima remessa. Só vira divergência quando o operador declara que o resto <b>não virá</b>
 * ({@code closeRemaining}), ou quando chega <b>mais</b> do que o pendente. Em ambos os casos a diferença fica
 * registrada em campos próprios — pedido original, recebido e cancelado —, sem reescrever a linha da compra.</p>
 *
 * <p>Esta classe não tem I/O: é a regra de negócio isolada, provada sem banco.</p>
 */
public final class ReceiptLineEvaluation {
    private ReceiptLineEvaluation() {}

    /**
     * @param expected  saldo pendente antes deste recebimento (nunca negativo: pendente negativo é excesso já aceito)
     * @param divergence tipo de divergência desta linha
     * @param cancelled quantidade encerrada sem chegar; zero fora de {@code SHORT_CLOSED}
     */
    public record Result(BigDecimal expected, GoodsReceipt.Divergence divergence, BigDecimal cancelled) {
        public boolean divergent() { return divergence != GoodsReceipt.Divergence.NONE; }
    }

    /**
     * @param pending        pendente atual da linha: pedido − recebido ativo − encerrado ativo (pode ser negativo)
     * @param received       quantidade fisicamente recebida agora, na unidade do catálogo
     * @param closeRemaining verdadeiro quando o fornecedor não enviará o restante
     */
    public static Result evaluate(BigDecimal pending, BigDecimal received, boolean closeRemaining) {
        BigDecimal quantity = PurchaseMoney.quantity(received);
        PurchaseMoney.requirePositive(quantity, "Quantidade recebida");
        BigDecimal expected = PurchaseMoney.quantity(pending.signum() < 0 ? BigDecimal.ZERO : pending);

        int comparison = quantity.compareTo(expected);
        if (comparison > 0) {
            // Chegou mais do que o pendente. Entra no estoque o que chegou — o saldo físico é a verdade —,
            // mas só com autorização e justificativa; a conta a pagar segue a nota, não o recebido.
            return new Result(expected, GoodsReceipt.Divergence.OVER, PurchaseMoney.quantity(BigDecimal.ZERO));
        }
        if (comparison < 0 && closeRemaining) {
            return new Result(expected, GoodsReceipt.Divergence.SHORT_CLOSED, PurchaseMoney.quantity(expected.subtract(quantity)));
        }
        return new Result(expected, GoodsReceipt.Divergence.NONE, PurchaseMoney.quantity(BigDecimal.ZERO));
    }
}
