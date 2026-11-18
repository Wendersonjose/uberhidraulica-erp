package br.com.uberhidraulica.erp.finance.api;

import br.com.uberhidraulica.erp.finance.application.CashFlowService;
import br.com.uberhidraulica.erp.finance.application.FinanceConfigService;
import br.com.uberhidraulica.erp.finance.application.FinanceDashboardService;
import br.com.uberhidraulica.erp.finance.domain.ExpenseCategory;
import br.com.uberhidraulica.erp.finance.domain.PaymentMethod;
import br.com.uberhidraulica.erp.finance.domain.PaymentMethodFee;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Configuração do Financeiro e fluxo de caixa. */
@RestController
@RequestMapping("/api/finance")
class FinanceConfigController {
    private final FinanceConfigService config;
    private final CashFlowService cashFlow;
    private final FinanceDashboardService dashboard;

    FinanceConfigController(FinanceConfigService config, CashFlowService cashFlow, FinanceDashboardService dashboard) {
        this.config = config;
        this.cashFlow = cashFlow;
        this.dashboard = dashboard;
    }

    @GetMapping("/cash-flow")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    CashFlowService.CashFlow cashFlow(@RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam(required = false) UUID categoryId) {
        return cashFlow.cashFlow(from, to, categoryId);
    }

    @GetMapping("/dashboard")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    FinanceDashboardService.Dashboard dashboard(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return dashboard.dashboard(from, to);
    }

    @GetMapping("/payment-methods")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    List<PaymentMethod> paymentMethods() { return config.paymentMethods(); }

    @PostMapping("/payment-methods")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_CONFIG')")
    PaymentMethod createPaymentMethod(@Valid @RequestBody PaymentMethodRequest request) {
        return config.createPaymentMethod(request.name(), request.cashSessionRequired());
    }

    @PutMapping("/payment-methods/{id}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_CONFIG')")
    PaymentMethod updatePaymentMethod(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return config.updatePaymentMethod(id, request.name(), request.active());
    }

    @GetMapping("/expense-categories")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    List<ExpenseCategory> expenseCategories() { return config.expenseCategories(); }

    @PostMapping("/expense-categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_CONFIG')")
    ExpenseCategory createExpenseCategory(@Valid @RequestBody NameRequest request) { return config.createExpenseCategory(request.name()); }

    @PutMapping("/expense-categories/{id}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_CONFIG')")
    ExpenseCategory updateExpenseCategory(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return config.updateExpenseCategory(id, request.name(), request.active());
    }

    @GetMapping("/revenue-categories")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    List<ExpenseCategory> revenueCategories() { return config.revenueCategories(); }

    @PostMapping("/revenue-categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_CONFIG')")
    ExpenseCategory createRevenueCategory(@Valid @RequestBody NameRequest request) { return config.createRevenueCategory(request.name()); }

    @PutMapping("/revenue-categories/{id}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_CONFIG')")
    ExpenseCategory updateRevenueCategory(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return config.updateRevenueCategory(id, request.name(), request.active());
    }

    @GetMapping("/settings")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    FinanceConfigService.Settings settings() { return config.settings(); }

    @PutMapping("/settings")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_CONFIG')")
    FinanceConfigService.Settings changeSettings(@Valid @RequestBody SettingsRequest request) {
        return config.changeSettings(request.defaultReceivableDueDays(), request.lateFeePercent(), request.lateInterestMonthlyPercent(),
                request.payableApprovalThreshold());
    }

    @GetMapping("/payment-methods/{id}/fees")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    List<PaymentMethodFee> fees(@PathVariable UUID id) { return config.fees(id); }

    @PutMapping("/payment-methods/{id}/fees")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_CONFIG')")
    List<PaymentMethodFee> replaceFees(@PathVariable UUID id, @Valid @RequestBody FeesRequest request) {
        return config.replaceFees(id, request.fees().stream().map(f -> {
            if (f == null || f.installments() == null || f.feePercent() == null || f.settlementDays() == null)
                throw new br.com.uberhidraulica.erp.finance.domain.FinanceException("INVALID_PAYMENT_METHOD_FEE",
                        "Informe parcelas, taxa e prazo de repasse de cada linha");
            return new PaymentMethodFee(f.installments(), f.feePercent(), f.settlementDays());
        }).toList());
    }

    record NameRequest(@NotBlank @Size(max = 80) String name) {}

    /** {@code cashSessionRequired} obrigatório: movimenta dinheiro físico? Definido na criação, nunca alterado. */
    record PaymentMethodRequest(@NotBlank @Size(max = 80) String name, @NotNull Boolean cashSessionRequired) {}

    record UpdateRequest(@NotBlank @Size(max = 80) String name, @NotNull Boolean active) {}

    /** Só os campos preenchidos são alterados. */
    record SettingsRequest(Integer defaultReceivableDueDays, java.math.BigDecimal lateFeePercent,
                           java.math.BigDecimal lateInterestMonthlyPercent, java.math.BigDecimal payableApprovalThreshold) {}

    record FeeRequest(Integer installments, java.math.BigDecimal feePercent, Integer settlementDays) {}

    record FeesRequest(@NotNull @Size(max = PaymentMethodFee.MAX_INSTALLMENTS) List<FeeRequest> fees) {}
}
