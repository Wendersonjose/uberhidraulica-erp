package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.finance.domain.FinanceException;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort.DailyAmount;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort.DashboardReceivableTotals;
import br.com.uberhidraulica.erp.inventory.InventoryCostQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Resumo financeiro gerencial: cálculo de lucro bruto, resultado operacional e margens. Sem banco — a
 * base de faturamento/a receber/vencido e a lista de OS do período vêm mockadas do repositório.
 */
class FinanceDashboardServiceTest {
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);
    private static final UUID WORK_ORDER = UUID.randomUUID();

    private final FinanceRepositoryPort repository = mock(FinanceRepositoryPort.class);
    private final InventoryCostQuery inventoryCost = mock(InventoryCostQuery.class);
    private final FinanceDashboardService service = new FinanceDashboardService(repository, inventoryCost);

    @Test
    void computesGrossAndOperatingResultWithMargins() {
        when(repository.dashboardReceivableTotals(eq(FROM), eq(TO), any()))
                .thenReturn(new DashboardReceivableTotals(bd("1000.00"), bd("400.00"), bd("100.00"), List.of(WORK_ORDER)));
        when(repository.realizedInflows(FROM, TO)).thenReturn(List.of(new DailyAmount(FROM, bd("600.00"))));
        when(repository.realizedOutflows(eq(FROM), eq(TO), isNull())).thenReturn(List.of(new DailyAmount(FROM, bd("150.00"))));
        when(repository.dashboardExpensesRegistered(FROM, TO)).thenReturn(bd("200.00"));
        when(inventoryCost.partsCostForWorkOrders(List.of(WORK_ORDER))).thenReturn(bd("300.00"));

        FinanceDashboardService.Dashboard dashboard = service.dashboard(FROM, TO);

        assertThat(dashboard.revenue()).isEqualByComparingTo("1000.00");
        assertThat(dashboard.received()).isEqualByComparingTo("600.00");
        assertThat(dashboard.receivableOpen()).isEqualByComparingTo("400.00");
        assertThat(dashboard.overdue()).isEqualByComparingTo("100.00");
        assertThat(dashboard.partsCost()).isEqualByComparingTo("300.00");
        assertThat(dashboard.expensesRegistered()).isEqualByComparingTo("200.00");
        assertThat(dashboard.expensesPaid()).isEqualByComparingTo("150.00");
        // lucroBruto = faturamento - custoDePecas = 1000 - 300 = 700
        assertThat(dashboard.grossProfit()).isEqualByComparingTo("700.00");
        // resultadoOperacional = lucroBruto - despesasRegistradas = 700 - 200 = 500
        assertThat(dashboard.operatingResult()).isEqualByComparingTo("500.00");
        // margemBruta = 700 / 1000 * 100 = 70.00
        assertThat(dashboard.grossMargin()).isEqualByComparingTo("70.00");
        // margemOperacional = 500 / 1000 * 100 = 50.00
        assertThat(dashboard.operatingMargin()).isEqualByComparingTo("50.00");
    }

    @Test
    void zeroRevenueNeverDividesByZeroOrProducesNaN() {
        when(repository.dashboardReceivableTotals(eq(FROM), eq(TO), any()))
                .thenReturn(new DashboardReceivableTotals(bd("0.00"), bd("0.00"), bd("0.00"), List.of()));
        when(repository.realizedInflows(FROM, TO)).thenReturn(List.of());
        when(repository.realizedOutflows(eq(FROM), eq(TO), isNull())).thenReturn(List.of());
        when(repository.dashboardExpensesRegistered(FROM, TO)).thenReturn(bd("50.00"));
        when(inventoryCost.partsCostForWorkOrders(List.of())).thenReturn(bd("0.00"));

        FinanceDashboardService.Dashboard dashboard = service.dashboard(FROM, TO);

        assertThat(dashboard.revenue()).isEqualByComparingTo("0.00");
        assertThat(dashboard.grossProfit()).isEqualByComparingTo("0.00");
        // resultadoOperacional = 0 - 50 = -50 (despesa sem faturamento no período é um resultado negativo real, não um erro)
        assertThat(dashboard.operatingResult()).isEqualByComparingTo("-50.00");
        assertThat(dashboard.grossMargin()).isEqualByComparingTo("0.00");
        assertThat(dashboard.operatingMargin()).isEqualByComparingTo("0.00");
    }

    @Test
    void rejectsMissingOrInvertedOrTooLongPeriod() {
        assertThatThrownBy(() -> service.dashboard(null, TO)).extracting("code").isEqualTo("INVALID_PERIOD");
        assertThatThrownBy(() -> service.dashboard(FROM, null)).extracting("code").isEqualTo("INVALID_PERIOD");
        assertThatThrownBy(() -> service.dashboard(TO, FROM)).extracting("code").isEqualTo("INVALID_PERIOD");
        assertThatThrownBy(() -> service.dashboard(FROM, FROM.plusDays(367)))
                .isInstanceOf(FinanceException.class).extracting("code").isEqualTo("INVALID_PERIOD");
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }
}
