package br.com.uberhidraulica.erp.productcatalog.domain;

import java.math.BigDecimal;

/**
 * Unidade-base de estoque do item (AG-04, seções 21 e 22).
 *
 * <p>Toda quantidade — de catálogo, de OS e de movimentação — é expressa nesta unidade, de modo que
 * não existe soma entre unidades diferentes. A precisão admitida depende da unidade (DR-0006, opção C):
 * unidades contáveis aceitam somente inteiros; unidades contínuas aceitam até três casas decimais.</p>
 */
public enum ProductUnit {
    UNIDADE(true), LITRO(false), METRO(false), QUILOGRAMA(false),
    /** Embalagens fechadas de fluido, para quem controla por galão ou balde (DR-0014). */
    GALAO_5L(true), BALDE_20L(true);

    /** Casas decimais aceitas nas unidades contínuas, igual à escala persistida {@code NUMERIC(15,3)}. */
    public static final int CONTINUOUS_SCALE = 3;

    private final boolean countable;

    ProductUnit(boolean countable) { this.countable = countable; }

    /** Contável: {@code UNIDADE}, {@code GALAO_5L} e {@code BALDE_20L} não admitem fração. */
    public boolean countable() { return countable; }

    public int maxScale() { return countable ? 0 : CONTINUOUS_SCALE; }

    /** Verdadeiro quando a quantidade não tem mais casas decimais significativas do que a unidade admite. */
    public boolean accepts(BigDecimal quantity) {
        return quantity != null && Math.max(quantity.stripTrailingZeros().scale(), 0) <= maxScale();
    }

    public String precisionRule() {
        return countable ? "a unidade " + name() + " aceita somente quantidade inteira"
                : "a unidade " + name() + " aceita no máximo três casas decimais";
    }
}
