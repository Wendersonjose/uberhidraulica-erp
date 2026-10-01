package br.com.uberhidraulica.erp.productcatalog;

import br.com.uberhidraulica.erp.productcatalog.domain.ProductUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** DR-0006, opção C: contáveis só inteiros; contínuas até três casas. Sem banco. */
class ProductQuantityRuleTest {

    @ParameterizedTest
    @CsvSource({
            "UNIDADE, 1", "UNIDADE, 15", "UNIDADE, 2.000", "GALAO_5L, 2", "BALDE_20L, 3",
            "LITRO, 0.500", "LITRO, 0.5", "METRO, 2.750", "QUILOGRAMA, 1.125", "QUILOGRAMA, 4",
    })
    void accepts(String unit, String quantity) {
        assertThat(ProductQuantityRule.violation(unit, new BigDecimal(quantity))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "UNIDADE, 0.5", "GALAO_5L, 1.5", "BALDE_20L, 2.25", "UNIDADE, 1.001",
            "LITRO, 0.0001", "METRO, 2.7505", "QUILOGRAMA, 1.1251",
    })
    void rejects(String unit, String quantity) {
        assertThat(ProductQuantityRule.violation(unit, new BigDecimal(quantity))).isPresent();
    }

    @ParameterizedTest
    @CsvSource({"UNIDADE, true", "GALAO_5L, true", "BALDE_20L, true", "LITRO, false", "METRO, false", "QUILOGRAMA, false"})
    void countableUnits(ProductUnit unit, boolean countable) {
        assertThat(unit.countable()).isEqualTo(countable);
    }
}
