package br.com.uberhidraulica.erp.finance.api;

import br.com.uberhidraulica.erp.crm.CustomerVehicleQuery;
import br.com.uberhidraulica.erp.finance.application.FinanceReportService;
import br.com.uberhidraulica.erp.finance.port.FinanceReportPort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Relatórios gerenciais (TASK-0024, etapa A). Todos somente leitura e restritos a quem tem {@code FINANCE_VIEW}. */
@RestController
@RequestMapping("/api/finance/reports")
class FinanceReportController {
    private final FinanceReportService reports;
    private final CustomerVehicleQuery customers;

    FinanceReportController(FinanceReportService reports, CustomerVehicleQuery customers) {
        this.reports = reports;
        this.customers = customers;
    }

    @GetMapping("/overview")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    FinanceReportService.Overview overview(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.overview(from, to);
    }

    record DebtorResponse(UUID customerId, String customerName, long count, BigDecimal outstanding, BigDecimal overdue, LocalDate oldestDueDate) {}

    record AgingResponse(LocalDate asOf, BigDecimal totalOutstanding, List<FinanceReportPort.AgingBucket> buckets, List<DebtorResponse> debtors) {}

    @GetMapping("/aging")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    AgingResponse aging() {
        var aging = reports.aging();
        Map<UUID, String> names = new HashMap<>();
        return new AgingResponse(aging.asOf(), aging.totalOutstanding(), aging.buckets(), aging.debtors().stream()
                .map(d -> new DebtorResponse(d.customerId(), name(names, d.customerId()), d.count(), d.outstanding(), d.overdue(),
                        d.oldestDueDate())).toList());
    }

    @GetMapping("/expenses-by-category")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    FinanceReportService.ExpensesByCategory expensesByCategory(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.expensesByCategory(from, to);
    }

    record WorkOrderLineResponse(UUID receivableId, UUID workOrderId, long workOrderNumber, UUID customerId, String customerName,
                                 LocalDate issuedOn, LocalDate dueDate, String status, BigDecimal original, BigDecimal discount,
                                 BigDecimal surcharge, BigDecimal netRevenue, BigDecimal received, BigDecimal outstanding,
                                 BigDecimal partsCost, BigDecimal result, BigDecimal margin) {}

    record WorkOrderResultsResponse(LocalDate from, LocalDate to, BigDecimal netRevenue, BigDecimal partsCost, BigDecimal result,
                                    List<WorkOrderLineResponse> workOrders) {}

    @GetMapping("/work-orders")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    WorkOrderResultsResponse workOrders(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        var results = reports.workOrderResults(from, to);
        Map<UUID, String> names = new HashMap<>();
        return new WorkOrderResultsResponse(results.from(), results.to(), results.netRevenue(), results.partsCost(), results.result(),
                results.workOrders().stream().map(l -> {
                    var r = l.receivable();
                    return new WorkOrderLineResponse(r.receivableId(), r.workOrderId(), r.workOrderNumber(), r.customerId(),
                            name(names, r.customerId()), r.issuedOn(), r.dueDate(), r.status(), r.original(), r.discount(), r.surcharge(),
                            l.netRevenue(), r.received(), r.outstanding(), l.partsCost(), l.result(), l.margin());
                }).toList());
    }

    @GetMapping("/income-statement")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    FinanceReportService.IncomeStatement incomeStatement(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.incomeStatement(from, to);
    }

    record ReceivableRowResponse(UUID id, long workOrderNumber, UUID customerId, String customerName, LocalDate issuedOn, LocalDate dueDate,
                                 String status, BigDecimal original, BigDecimal discount, BigDecimal surcharge, BigDecimal received,
                                 BigDecimal outstanding) {}

    @GetMapping("/receivables")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    List<ReceivableRowResponse> receivables(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        Map<UUID, String> names = new HashMap<>();
        return reports.receivablesDue(from, to).stream().map(r -> new ReceivableRowResponse(r.id(), r.workOrderNumber(), r.customerId(),
                name(names, r.customerId()), r.issuedOn(), r.dueDate(), r.status(), r.original(), r.discount(), r.surcharge(),
                r.received(), r.outstanding())).toList();
    }

    @GetMapping("/payables")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    List<FinanceReportPort.PayableRow> payables(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.payablesDue(from, to);
    }

    private String name(Map<UUID, String> cache, UUID customerId) {
        return cache.computeIfAbsent(customerId,
                id -> customers.customer(id).map(CustomerVehicleQuery.CustomerReference::name).orElse(null));
    }
}
