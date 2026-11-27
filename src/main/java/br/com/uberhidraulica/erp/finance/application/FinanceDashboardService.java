package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.finance.domain.FinanceException;
import br.com.uberhidraulica.erp.finance.domain.Money;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort;
import br.com.uberhidraulica.erp.inventory.InventoryCostQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Resumo financeiro gerencial do piloto (MVP): faturamento, recebido, a receber, vencido, custo de peças,
 * despesas, lucro bruto, resultado operacional e margens de um período.
 *
 * <p>DR-0024, Q8: lucro, resultado e margens são calculados sobre a receita líquida (faturamento menos descontos
 * mais acréscimos), a mesma base do demonstrativo gerencial.</p>
 *
 * <p>Não é DRE contábil. O único custo direto confiavelmente disponível é o de peças (custo histórico da
 * movimentação de estoque vinculada à OS, nunca o custo atual do catálogo); mão de obra não é custeada
 * neste MVP. Faturamento, a receber e vencido são todos restritos aos recebíveis emitidos no período, para
 * que o painel inteiro fale do mesmo recorte de Ordens de Serviço.</p>
 *
 * <p>Compra de mercadoria para estoque (contas geradas pela importação da NF-e) <b>não</b> é despesa
 * operacional: a peça entra no resultado como custo de peças quando é consumida na OS. Somá-la também às
 * despesas contava a mesma peça duas vezes. Ela aparece à parte em {@code stockPurchasesRegistered}, e o
 * pagamento continua em {@code expensesPaid} (saídas de caixa), como qualquer outra obrigação.</p>
 */
@Service
public class FinanceDashboardService {
    public static final long MAX_DAYS = 366;

    private final FinanceRepositoryPort repository;
    private final InventoryCostQuery inventoryCost;
    private final Clock clock = Clock.systemUTC();

    public FinanceDashboardService(FinanceRepositoryPort repository, InventoryCostQuery inventoryCost) {
        this.repository = repository;
        this.inventoryCost = inventoryCost;
    }

    public record Dashboard(LocalDate from, LocalDate to, BigDecimal revenue, BigDecimal discounts, BigDecimal surcharges,
                            BigDecimal netRevenue, BigDecimal received, BigDecimal receivableOpen,
                            BigDecimal overdue, BigDecimal partsCost, BigDecimal expensesRegistered, BigDecimal expensesPaid,
                            BigDecimal cardFees,
                            BigDecimal grossProfit, BigDecimal operatingResult, BigDecimal grossMargin, BigDecimal operatingMargin,
                            BigDecimal stockPurchasesRegistered) {}

    @Transactional(readOnly = true)
    public Dashboard dashboard(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw new FinanceException("INVALID_PERIOD", "Período inicial e final são obrigatórios");
        if (to.isBefore(from)) throw new FinanceException("INVALID_PERIOD", "Período final anterior ao inicial");
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) throw new FinanceException("INVALID_PERIOD", "Período máximo de " + MAX_DAYS + " dias");

        LocalDate today = LocalDate.now(clock.withZone(Money.WORKSHOP_ZONE));
        var receivableTotals = repository.dashboardReceivableTotals(from, to, today);
        BigDecimal received = Money.sum(repository.realizedInflows(from, to).stream().map(FinanceRepositoryPort.DailyAmount::amount).toList());
        BigDecimal expensesPaid = Money.sum(repository.realizedOutflows(from, to, null).stream().map(FinanceRepositoryPort.DailyAmount::amount).toList());
        BigDecimal expensesRegistered = repository.dashboardExpensesRegistered(from, to).setScale(Money.SCALE, RoundingMode.UNNECESSARY);
        BigDecimal stockPurchases = repository.dashboardStockPurchasesRegistered(from, to).setScale(Money.SCALE, RoundingMode.UNNECESSARY);
        BigDecimal partsCost = inventoryCost.partsCostForWorkOrders(receivableTotals.workOrderIds());

        BigDecimal revenue = receivableTotals.revenue();
        BigDecimal netRevenue = revenue.subtract(receivableTotals.discounts()).add(receivableTotals.surcharges());
        BigDecimal grossProfit = netRevenue.subtract(partsCost);
        // DR-0024, Q4: a taxa de cartão dos recebimentos do período é despesa financeira.
        BigDecimal cardFees = repository.cardFees(from, to);
        BigDecimal operatingResult = grossProfit.subtract(expensesRegistered).subtract(cardFees);
        BigDecimal grossMargin = percentage(grossProfit, netRevenue);
        BigDecimal operatingMargin = percentage(operatingResult, netRevenue);

        return new Dashboard(from, to, revenue, receivableTotals.discounts(), receivableTotals.surcharges(), netRevenue, received, receivableTotals.receivableOpen(), receivableTotals.overdue(),
                partsCost, expensesRegistered, expensesPaid, cardFees, grossProfit, operatingResult, grossMargin, operatingMargin, stockPurchases);
    }

    /** Sem faturamento no período, a margem é zero — nunca `NaN` nem divisão por zero. */
    private static BigDecimal percentage(BigDecimal part, BigDecimal total) {
        if (total.signum() == 0) return Money.zero();
        return part.multiply(BigDecimal.valueOf(100)).divide(total, Money.SCALE, RoundingMode.HALF_UP);
    }
}
