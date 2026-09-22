package br.com.uberhidraulica.erp.inventory;

import br.com.uberhidraulica.erp.inventory.domain.InventoryException;
import br.com.uberhidraulica.erp.inventory.domain.Stock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Política de custo médio da DR-0014: custo nulo é desconhecido, nunca zero. Sem banco. */
class StockAverageCostTest {
    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    private static final UUID PRODUCT = UUID.randomUUID();

    private static Stock stock(String quantity, String averageCost) {
        return new Stock(PRODUCT, new BigDecimal(quantity), averageCost == null ? null : new BigDecimal(averageCost), NOW);
    }

    private static BigDecimal amount(String value) { return new BigDecimal(value); }

    @Test
    void caseA_zeroBalanceTakesTheEntryCost() {
        Stock result = stock("0", null).add(amount("4"), amount("12.5000"), NOW);
        assertThat(result.quantity()).isEqualByComparingTo("4");
        assertThat(result.averageCost()).isEqualByComparingTo("12.5000");

        // Saldo zerado com médio antigo também recomeça pelo custo da entrada.
        assertThat(stock("0", "99.0000").add(amount("1"), amount("10.0000"), NOW).averageCost()).isEqualByComparingTo("10.0000");
    }

    @Test
    void caseB_positiveBalanceWithKnownCostUsesWeightedAverageAtFourDecimalsHalfUp() {
        assertThat(stock("10", "20.0000").add(amount("10"), amount("30.0000"), NOW).averageCost()).isEqualByComparingTo("25.0000");
        // (3 × 10 + 1 × 11) ÷ 4 = 10.25; (1 × 10 + 2 × 10.0001) ÷ 3 = 10.0000666… → 10.0001
        assertThat(stock("3", "10.0000").add(amount("1"), amount("11.0000"), NOW).averageCost()).isEqualByComparingTo("10.2500");
        Stock rounded = stock("1", "10.0000").add(amount("2"), amount("10.0001"), NOW);
        assertThat(rounded.averageCost()).isEqualByComparingTo("10.0001");
        assertThat(rounded.averageCost().scale()).isEqualTo(Stock.COST_SCALE);
    }

    @Test
    void caseC_positiveBalanceWithUnknownCostIsInitializedByTheEntryCostNotTreatedAsZero() {
        Stock result = stock("10", null).add(amount("10"), amount("30.0000"), NOW);
        assertThat(result.quantity()).isEqualByComparingTo("20");
        // Tratar o saldo antigo como custo zero daria 15.0000.
        assertThat(result.averageCost()).isEqualByComparingTo("30.0000");
    }

    @Test
    void caseD_entryWithoutCostKeepsTheCurrentAverageEvenWhenUnknown() {
        assertThat(stock("5", "25.0000").add(amount("5"), null, NOW).averageCost()).isEqualByComparingTo("25.0000");
        Stock unknown = stock("5", null).add(amount("5"), null, NOW);
        assertThat(unknown.quantity()).isEqualByComparingTo("10");
        assertThat(unknown.averageCost()).isNull();
        assertThat(stock("0", null).add(amount("2"), null, NOW).averageCost()).isNull();
    }

    @Test
    void exitKeepsTheAverageAndNeverGoesNegative() {
        Stock result = stock("5", "25.0000").remove(amount("2"), NOW);
        assertThat(result.quantity()).isEqualByComparingTo("3");
        assertThat(result.averageCost()).isEqualByComparingTo("25.0000");
        assertThatThrownBy(() -> stock("1", "25.0000").remove(amount("2"), NOW))
                .isInstanceOf(InventoryException.class).extracting("code").isEqualTo("INSUFFICIENT_STOCK");
    }
}
