package br.com.uberhidraulica.erp.finance.api;

import br.com.uberhidraulica.erp.finance.application.CashFlowService;
import br.com.uberhidraulica.erp.finance.application.FinanceConfigService;
import br.com.uberhidraulica.erp.finance.domain.ExpenseCategory;
import br.com.uberhidraulica.erp.finance.domain.PaymentMethod;
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

    FinanceConfigController(FinanceConfigService config, CashFlowService cashFlow) {
        this.config = config;
        this.cashFlow = cashFlow;
    }

    @GetMapping("/cash-flow")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    CashFlowService.CashFlow cashFlow(@RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam(required = false) UUID categoryId) {
        return cashFlow.cashFlow(from, to, categoryId);
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

    @GetMapping("/settings")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    Map<String, Integer> settings() { return Map.of("defaultReceivableDueDays", config.defaultReceivableDueDays()); }

    @PutMapping("/settings")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_CONFIG')")
    Map<String, Integer> changeSettings(@Valid @RequestBody SettingsRequest request) {
        return Map.of("defaultReceivableDueDays", config.changeDefaultReceivableDueDays(request.defaultReceivableDueDays()));
    }

    record NameRequest(@NotBlank @Size(max = 80) String name) {}

    /** {@code cashSessionRequired} obrigatório: movimenta dinheiro físico? Definido na criação, nunca alterado. */
    record PaymentMethodRequest(@NotBlank @Size(max = 80) String name, @NotNull Boolean cashSessionRequired) {}

    record UpdateRequest(@NotBlank @Size(max = 80) String name, @NotNull Boolean active) {}

    record SettingsRequest(@NotNull Integer defaultReceivableDueDays) {}
}
