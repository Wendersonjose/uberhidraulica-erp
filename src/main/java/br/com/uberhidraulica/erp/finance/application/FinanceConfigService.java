package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.finance.domain.ExpenseCategory;
import br.com.uberhidraulica.erp.finance.domain.FinanceException;
import br.com.uberhidraulica.erp.finance.domain.LateCharge;
import br.com.uberhidraulica.erp.finance.domain.PaymentMethodFee;
import br.com.uberhidraulica.erp.finance.domain.Money;
import br.com.uberhidraulica.erp.finance.domain.PaymentMethod;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort;
import br.com.uberhidraulica.erp.iam.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Formas de pagamento, categorias de despesa e prazo padrão do recebível (DR-0015, F-04, F-09, F-12).
 * Nada é excluído: forma e categoria são inativadas, e o histórico guarda o nome usado no lançamento.
 */
@Service
public class FinanceConfigService {
    public static final int MAX_DUE_DAYS = 365;

    private final FinanceRepositoryPort repository;
    private final CurrentUser currentUser;
    private final Clock clock = Clock.systemUTC();

    public FinanceConfigService(FinanceRepositoryPort repository, CurrentUser currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<PaymentMethod> paymentMethods() { return repository.paymentMethods(); }

    /**
     * Nova forma: a classificação "movimenta dinheiro físico" é obrigatória e definitiva (revisão TASK-0015, F2).
     * Nunca é inferida do nome. Com {@code cashSessionRequired = true}, a forma fica indisponível para liquidação
     * até existir sessão de caixa (TASK-0016).
     */
    @Transactional
    public PaymentMethod createPaymentMethod(String name, Boolean cashSessionRequired) {
        String normalized = Money.requiredText(name, "Nome da forma de pagamento", 80);
        if (cashSessionRequired == null)
            throw new FinanceException("INVALID_PAYMENT_METHOD", "Informe se a forma movimenta dinheiro físico e exige sessão de caixa");
        Instant now = clock.instant();
        PaymentMethod method = new PaymentMethod(UUID.randomUUID(), code(normalized), normalized, true, cashSessionRequired, now, now);
        return save(() -> repository.savePaymentMethod(method, true), method, "PAYMENT_METHOD_ALREADY_EXISTS");
    }

    @Transactional
    public PaymentMethod updatePaymentMethod(UUID id, String name, boolean active) {
        PaymentMethod current = repository.paymentMethod(id)
                .orElseThrow(() -> new FinanceException("PAYMENT_METHOD_NOT_FOUND", "Forma de pagamento não encontrada"));
        PaymentMethod updated = current.withNameAndActive(name, active, clock.instant());
        return save(() -> repository.savePaymentMethod(updated, false), updated, "PAYMENT_METHOD_ALREADY_EXISTS");
    }

    @Transactional(readOnly = true)
    public List<ExpenseCategory> expenseCategories() { return repository.expenseCategories(); }

    @Transactional
    public ExpenseCategory createExpenseCategory(String name) {
        Instant now = clock.instant();
        ExpenseCategory category = new ExpenseCategory(UUID.randomUUID(), name, true, now, now);
        return save(() -> repository.saveExpenseCategory(category, true), category, "EXPENSE_CATEGORY_ALREADY_EXISTS");
    }

    @Transactional
    public ExpenseCategory updateExpenseCategory(UUID id, String name, boolean active) {
        ExpenseCategory current = repository.expenseCategory(id)
                .orElseThrow(() -> new FinanceException("EXPENSE_CATEGORY_NOT_FOUND", "Categoria não encontrada"));
        ExpenseCategory updated = new ExpenseCategory(id, name, active, current.createdAt(), clock.instant());
        return save(() -> repository.saveExpenseCategory(updated, false), updated, "EXPENSE_CATEGORY_ALREADY_EXISTS");
    }

    @Transactional(readOnly = true)
    public List<ExpenseCategory> revenueCategories() { return repository.revenueCategories(); }

    @Transactional
    public ExpenseCategory createRevenueCategory(String name) {
        Instant now = clock.instant();
        ExpenseCategory category = new ExpenseCategory(UUID.randomUUID(), name, true, now, now);
        return save(() -> repository.saveRevenueCategory(category, true), category, "REVENUE_CATEGORY_ALREADY_EXISTS");
    }

    @Transactional
    public ExpenseCategory updateRevenueCategory(UUID id, String name, boolean active) {
        ExpenseCategory current = repository.revenueCategory(id)
                .orElseThrow(() -> new FinanceException("REVENUE_CATEGORY_NOT_FOUND", "Categoria de receita não encontrada"));
        ExpenseCategory updated = new ExpenseCategory(id, name, active, current.createdAt(), clock.instant());
        return save(() -> repository.saveRevenueCategory(updated, false), updated, "REVENUE_CATEGORY_ALREADY_EXISTS");
    }

    @Transactional(readOnly = true)
    public int defaultReceivableDueDays() { return repository.defaultReceivableDueDays(); }

    @Transactional
    public int changeDefaultReceivableDueDays(Integer days) {
        if (days == null || days < 0 || days > MAX_DUE_DAYS)
            throw new FinanceException("INVALID_FINANCE_SETTING", "Prazo padrão deve estar entre 0 e " + MAX_DUE_DAYS + " dias");
        repository.saveDefaultReceivableDueDays(days, clock.instant(), currentUser.requireId());
        return days;
    }

    // ------------------------------------------------------------------ configurações da etapa B (DR-0024)

    /** Prazo padrão, juros e multa por atraso (Q3) e limite de aprovação de contas a pagar (Q5; zero desliga). */
    public record Settings(int defaultReceivableDueDays, java.math.BigDecimal lateFeePercent, java.math.BigDecimal lateInterestMonthlyPercent,
                           java.math.BigDecimal payableApprovalThreshold) {}

    @Transactional(readOnly = true)
    public Settings settings() {
        return new Settings(repository.defaultReceivableDueDays(), new java.math.BigDecimal(repository.setting(LateChargeService.FEE_KEY)),
                new java.math.BigDecimal(repository.setting(LateChargeService.INTEREST_KEY)),
                new java.math.BigDecimal(repository.setting(PayableService.APPROVAL_THRESHOLD_KEY)));
    }

    /** Altera só o que vier preenchido; cada valor é validado antes de qualquer gravação. */
    @Transactional
    public Settings changeSettings(Integer dueDays, java.math.BigDecimal lateFee, java.math.BigDecimal lateInterest,
                                   java.math.BigDecimal approvalThreshold) {
        if (dueDays != null && (dueDays < 0 || dueDays > MAX_DUE_DAYS))
            throw new FinanceException("INVALID_FINANCE_SETTING", "Prazo padrão deve estar entre 0 e " + MAX_DUE_DAYS + " dias");
        java.math.BigDecimal fee = lateFee == null ? null : LateCharge.percent(lateFee, LateCharge.MAX_FEE_PERCENT, "Multa");
        java.math.BigDecimal interest = lateInterest == null ? null
                : LateCharge.percent(lateInterest, LateCharge.MAX_INTEREST_PERCENT, "Juros mensais");
        if (approvalThreshold != null && (approvalThreshold.signum() < 0 || approvalThreshold.stripTrailingZeros().scale() > 2
                || approvalThreshold.compareTo(new java.math.BigDecimal("999999999.99")) > 0))
            throw new FinanceException("INVALID_FINANCE_SETTING", "Limite de aprovação deve ser zero ou positivo, com até duas casas");
        Instant now = clock.instant();
        UUID author = currentUser.requireId();
        if (dueDays != null) repository.saveDefaultReceivableDueDays(dueDays, now, author);
        if (fee != null) repository.saveSetting(LateChargeService.FEE_KEY, fee.toPlainString(), now, author);
        if (interest != null) repository.saveSetting(LateChargeService.INTEREST_KEY, interest.toPlainString(), now, author);
        if (approvalThreshold != null)
            repository.saveSetting(PayableService.APPROVAL_THRESHOLD_KEY, approvalThreshold.setScale(2).toPlainString(), now, author);
        return settings();
    }

    @Transactional(readOnly = true)
    public List<PaymentMethodFee> fees(UUID paymentMethodId) {
        repository.paymentMethod(paymentMethodId)
                .orElseThrow(() -> new FinanceException("PAYMENT_METHOD_NOT_FOUND", "Forma de pagamento não encontrada"));
        return repository.currentFees(paymentMethodId);
    }

    /**
     * Nova tabela de taxas da forma (Q4); a anterior fica como histórico e os recebimentos já gravados mantêm a taxa
     * congelada. Forma de dinheiro físico não tem taxa nem parcelas. Lista vazia remove a taxa.
     */
    @Transactional
    public List<PaymentMethodFee> replaceFees(UUID paymentMethodId, List<PaymentMethodFee> fees) {
        PaymentMethod method = repository.paymentMethod(paymentMethodId)
                .orElseThrow(() -> new FinanceException("PAYMENT_METHOD_NOT_FOUND", "Forma de pagamento não encontrada"));
        if (method.cashSessionRequired() && fees != null && !fees.isEmpty())
            throw new FinanceException("INVALID_PAYMENT_METHOD_FEE", "Forma de dinheiro físico não tem taxa nem parcelamento");
        List<PaymentMethodFee> checked = PaymentMethodFee.checkSchedule(fees);
        repository.insertFeeSchedule(paymentMethodId, checked, clock.instant(), currentUser.requireId());
        return repository.currentFees(paymentMethodId);
    }

    private static <T> T save(Runnable write, T value, String duplicateCode) {
        try {
            write.run();
        } catch (DataIntegrityViolationException duplicate) {
            throw new FinanceException(duplicateCode, "Já existe um cadastro com este nome");
        }
        return value;
    }

    private static String code(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String code = ascii.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (code.isEmpty() || !Character.isLetter(code.charAt(0))) code = "FORMA_" + code;
        return code.length() > 40 ? code.substring(0, 40) : code;
    }
}
