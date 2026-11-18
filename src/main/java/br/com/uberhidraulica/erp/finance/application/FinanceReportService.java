package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.finance.domain.FinanceException;
import br.com.uberhidraulica.erp.finance.domain.Money;
import br.com.uberhidraulica.erp.finance.port.CashSessionRepositoryPort;
import br.com.uberhidraulica.erp.finance.port.FinanceReportPort;
import br.com.uberhidraulica.erp.finance.port.FinanceReportPort.*;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort;
import br.com.uberhidraulica.erp.inventory.InventoryCostQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Relatórios gerenciais do Financeiro (TASK-0024, etapa A). Somente leitura: nenhum método grava.
 *
 * <p>Os números fecham com o painel ({@link FinanceDashboardService}) e com o fluxo de caixa ({@link CashFlowService}):
 * mesmo recorte de recebíveis (emitidos no período, não cancelados), mesmo custo histórico de peças e as mesmas
 * despesas (cadastradas no período, não canceladas). Não é DRE contábil; mão de obra não é custeada.</p>
 */
@Service
public class FinanceReportService {
    public static final long MAX_DAYS = 366;
    public static final int MAX_DEBTORS = 50;

    private final FinanceReportPort reports;
    private final FinanceRepositoryPort finance;
    private final CashSessionRepositoryPort cash;
    private final InventoryCostQuery inventoryCost;
    private final FinanceDashboardService dashboard;
    private final Clock clock = Clock.systemUTC();

    public FinanceReportService(FinanceReportPort reports, FinanceRepositoryPort finance, CashSessionRepositoryPort cash,
                                InventoryCostQuery inventoryCost, FinanceDashboardService dashboard) {
        this.reports = reports;
        this.finance = finance;
        this.cash = cash;
        this.inventoryCost = inventoryCost;
        this.dashboard = dashboard;
    }

    public LocalDate today() { return LocalDate.now(clock.withZone(Money.WORKSHOP_ZONE)); }

    // ------------------------------------------------------------------ painel ampliado (A1)

    public record CashPosition(boolean open, UUID sessionId, Instant openedAt, BigDecimal expectedBalance) {}

    public record Overview(LocalDate today, FinanceDashboardService.Dashboard period, CashPosition cash,
                           DueSummary receivables, DueSummary payables) {}

    @Transactional(readOnly = true)
    public Overview overview(LocalDate from, LocalDate to) {
        var periodTotals = dashboard.dashboard(from, to);
        CashPosition position = cash.findOpenSession()
                .map(s -> new CashPosition(true, s.id(), s.openedAt(), cash.expectedBalance(s.id()).setScale(Money.SCALE, RoundingMode.UNNECESSARY)))
                .orElse(new CashPosition(false, null, null, null));
        LocalDate today = today();
        return new Overview(today, periodTotals, position, reports.receivableDue(today), reports.payableDue(today));
    }

    // ------------------------------------------------------------------ inadimplência (A2)

    public record Aging(LocalDate asOf, BigDecimal totalOutstanding, List<AgingBucket> buckets, List<CustomerDebt> debtors) {}

    @Transactional(readOnly = true)
    public Aging aging() {
        LocalDate today = today();
        List<AgingBucket> buckets = reports.receivableAging(today);
        BigDecimal total = Money.sum(buckets.stream().map(AgingBucket::amount).toList());
        return new Aging(today, total, buckets, reports.receivableDebtors(today, MAX_DEBTORS));
    }

    // ------------------------------------------------------------------ despesas por categoria (A3)

    public record ExpensesByCategory(LocalDate from, LocalDate to, BigDecimal registered, BigDecimal paid, List<CategoryExpense> categories) {}

    @Transactional(readOnly = true)
    public ExpensesByCategory expensesByCategory(LocalDate from, LocalDate to) {
        validate(from, to);
        List<CategoryExpense> rows = reports.expensesByCategory(from, to);
        return new ExpensesByCategory(from, to, Money.sum(rows.stream().map(CategoryExpense::registered).toList()),
                Money.sum(rows.stream().map(CategoryExpense::paid).toList()), rows);
    }

    // ------------------------------------------------------------------ resultado por OS (A4)

    public record WorkOrderLine(WorkOrderResult receivable, BigDecimal netRevenue, BigDecimal partsCost, BigDecimal result, BigDecimal margin) {}

    public record WorkOrderResults(LocalDate from, LocalDate to, BigDecimal netRevenue, BigDecimal partsCost, BigDecimal result,
                                   List<WorkOrderLine> workOrders) {}

    @Transactional(readOnly = true)
    public WorkOrderResults workOrderResults(LocalDate from, LocalDate to) {
        validate(from, to);
        List<WorkOrderResult> rows = reports.workOrderResults(from, to, today());
        Map<UUID, BigDecimal> costs = inventoryCost.partsCostByWorkOrder(rows.stream().map(WorkOrderResult::workOrderId).toList());
        List<WorkOrderLine> lines = rows.stream().map(r -> {
            BigDecimal net = r.original().subtract(r.discount()).add(r.surcharge());
            BigDecimal parts = costs.getOrDefault(r.workOrderId(), Money.zero()).setScale(Money.SCALE, RoundingMode.HALF_UP);
            BigDecimal result = net.subtract(parts);
            return new WorkOrderLine(r, net, parts, result, percentage(result, net));
        }).toList();
        return new WorkOrderResults(from, to, Money.sum(lines.stream().map(WorkOrderLine::netRevenue).toList()),
                Money.sum(lines.stream().map(WorkOrderLine::partsCost).toList()),
                Money.sum(lines.stream().map(WorkOrderLine::result).toList()), lines);
    }

    // ------------------------------------------------------------------ demonstrativo gerencial (A5)

    public record IncomeStatement(LocalDate from, LocalDate to,
                                  BigDecimal grossRevenue, BigDecimal discounts, BigDecimal surcharges, BigDecimal netRevenue,
                                  BigDecimal partsCost, BigDecimal grossProfit, List<CategoryExpense> expenses, BigDecimal totalExpenses,
                                  BigDecimal operatingResult, BigDecimal operatingMargin,
                                  BigDecimal cashReceived, BigDecimal cashPaid, BigDecimal cashResult) {}

    @Transactional(readOnly = true)
    public IncomeStatement incomeStatement(LocalDate from, LocalDate to) {
        validate(from, to);
        List<WorkOrderResult> rows = reports.workOrderResults(from, to, today());
        BigDecimal gross = Money.sum(rows.stream().map(WorkOrderResult::original).toList());
        BigDecimal discounts = Money.sum(rows.stream().map(WorkOrderResult::discount).toList());
        BigDecimal surcharges = Money.sum(rows.stream().map(WorkOrderResult::surcharge).toList());
        BigDecimal net = gross.subtract(discounts).add(surcharges);
        BigDecimal parts = inventoryCost.partsCostForWorkOrders(rows.stream().map(WorkOrderResult::workOrderId).toList())
                .setScale(Money.SCALE, RoundingMode.HALF_UP);
        BigDecimal grossProfit = net.subtract(parts);
        List<CategoryExpense> expenses = reports.expensesByCategory(from, to).stream().filter(c -> c.registeredCount() > 0).toList();
        BigDecimal totalExpenses = Money.sum(expenses.stream().map(CategoryExpense::registered).toList());
        BigDecimal operating = grossProfit.subtract(totalExpenses);
        BigDecimal received = Money.sum(finance.realizedInflows(from, to).stream().map(FinanceRepositoryPort.DailyAmount::amount).toList());
        BigDecimal paid = Money.sum(finance.realizedOutflows(from, to, null).stream().map(FinanceRepositoryPort.DailyAmount::amount).toList());
        return new IncomeStatement(from, to, gross, discounts, surcharges, net, parts, grossProfit, expenses, totalExpenses,
                operating, percentage(operating, net), received, paid, received.subtract(paid));
    }

    // ------------------------------------------------------------------ listas do relatório (A6)

    @Transactional(readOnly = true)
    public List<ReceivableRow> receivablesDue(LocalDate from, LocalDate to) {
        validate(from, to);
        return reports.receivablesDueBetween(from, to, today());
    }

    @Transactional(readOnly = true)
    public List<PayableRow> payablesDue(LocalDate from, LocalDate to) {
        validate(from, to);
        return reports.payablesDueBetween(from, to, today());
    }

    // ------------------------------------------------------------------ apoio

    static void validate(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw new FinanceException("INVALID_PERIOD", "Período inicial e final são obrigatórios");
        if (to.isBefore(from)) throw new FinanceException("INVALID_PERIOD", "Período final anterior ao inicial");
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) throw new FinanceException("INVALID_PERIOD", "Período máximo de " + MAX_DAYS + " dias");
    }

    private static BigDecimal percentage(BigDecimal part, BigDecimal total) {
        if (total.signum() == 0) return Money.zero();
        return part.multiply(BigDecimal.valueOf(100)).divide(total, Money.SCALE, RoundingMode.HALF_UP);
    }
}
