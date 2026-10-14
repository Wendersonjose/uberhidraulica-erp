package br.com.uberhidraulica.erp.purchasing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Rateio de frete, seguro e outras despesas entre os itens da nota (`DR-0022` D2, aprovada: proporcional ao
 * valor, com fechamento exato dos centavos).
 *
 * <p>Trabalha em centavos inteiros justamente para que a soma dos rateios seja <b>exatamente</b> o valor
 * rateado: proporção em {@code BigDecimal} e arredondamento por item deixariam sobra ou falta de centavos, e
 * aí o estoque valorizado não fecharia com a nota. O resíduo vai para o item de maior valor líquido, conforme
 * decidido; empate é resolvido pelo menor índice para o resultado não depender da ordem de iteração.</p>
 */
public final class ExpenseAllocation {
    private ExpenseAllocation() {}

    /**
     * Distribui {@code amount} entre os itens proporcionalmente a {@code netValues}.
     *
     * @param netValues valor líquido de cada item, na ordem dos itens
     * @param amount    valor total a ratear (frete, seguro ou outras despesas)
     * @return valor rateado por item, na mesma ordem, cuja soma é exatamente {@code amount}
     */
    public static List<BigDecimal> proportionalToValue(List<BigDecimal> netValues, BigDecimal amount) {
        List<BigDecimal> result = new ArrayList<>(netValues.size());
        long total = PurchaseMoney.cents(amount);
        if (netValues.isEmpty()) {
            if (total != 0L) {
                throw new PurchasingException("PURCHASE_ALLOCATION_IMPOSSIBLE",
                        "Não há item para ratear despesa de " + PurchaseMoney.charged(amount));
            }
            return result;
        }
        if (total == 0L) {
            for (int i = 0; i < netValues.size(); i++) result.add(PurchaseMoney.charged(BigDecimal.ZERO));
            return result;
        }

        long[] base = new long[netValues.size()];
        long baseSum = 0L;
        for (int i = 0; i < netValues.size(); i++) {
            base[i] = PurchaseMoney.cents(netValues.get(i));
            if (base[i] < 0L) throw new PurchasingException("PURCHASE_INVALID", "Valor de item não pode ser negativo");
            baseSum += base[i];
        }

        long[] share = new long[netValues.size()];
        long distributed = 0L;
        if (baseSum == 0L) {
            // Nota em que todos os itens têm valor zero (bonificação, brinde). Proporção por valor não existe:
            // divide igualmente, que é o único critério possível, e o resíduo segue a mesma regra do item-âncora.
            long each = total / netValues.size();
            for (int i = 0; i < share.length; i++) { share[i] = each; distributed += each; }
        } else {
            for (int i = 0; i < share.length; i++) {
                // Multiplica antes de dividir: inverter a ordem perderia precisão em divisão inteira.
                share[i] = BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(base[i]))
                        .divide(BigDecimal.valueOf(baseSum), 0, RoundingMode.DOWN).longValueExact();
                distributed += share[i];
            }
        }
        share[anchorIndex(base)] += total - distributed;

        for (long cents : share) result.add(PurchaseMoney.fromCents(cents));
        requireExactClosure(result, amount);
        return result;
    }

    /** Item que recebe o resíduo: o de maior valor líquido; empate pelo menor índice, para ser determinístico. */
    private static int anchorIndex(long[] base) {
        int anchor = 0;
        for (int i = 1; i < base.length; i++) if (base[i] > base[anchor]) anchor = i;
        return anchor;
    }

    /**
     * Asserção de fechamento: a soma do rateio tem de ser exatamente o valor rateado. Falhar aqui é defeito de
     * programação, não entrada inválida — por isso é verificado sempre, e não só em teste.
     */
    public static void requireExactClosure(List<BigDecimal> shares, BigDecimal amount) {
        BigDecimal sum = PurchaseMoney.sumCharged(shares);
        if (sum.compareTo(PurchaseMoney.charged(amount)) != 0) {
            throw new PurchasingException("PURCHASE_ALLOCATION_NOT_CLOSED",
                    "Rateio não fechou: soma " + sum + " para valor " + PurchaseMoney.charged(amount));
        }
    }
}
