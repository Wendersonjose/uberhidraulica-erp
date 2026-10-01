package br.com.uberhidraulica.erp.productcatalog;

import br.com.uberhidraulica.erp.productcatalog.domain.ProductUnit;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Precisão de quantidade por unidade do produto (DR-0006, opção C), exposta para os módulos que
 * recebem quantidade de item físico — OS e Estoque — sem que precisem conhecer o enum interno.
 *
 * <p>A regra vale para quantidades novas. Quantidades persistidas antes da decisão não são
 * revalidadas ao serem lidas, estornadas ou devolvidas.</p>
 */
public final class ProductQuantityRule {
    private ProductQuantityRule() {}

    /** Mensagem da violação, ou vazio quando a quantidade respeita a unidade informada. */
    public static Optional<String> violation(String unit, BigDecimal quantity) {
        ProductUnit parsed;
        try {
            parsed = ProductUnit.valueOf(unit);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return Optional.of("Unidade do produto desconhecida");
        }
        return parsed.accepts(quantity) ? Optional.empty() : Optional.of("Quantidade inválida: " + parsed.precisionRule());
    }
}
