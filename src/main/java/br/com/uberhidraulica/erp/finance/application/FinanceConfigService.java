package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.finance.domain.ExpenseCategory;
import br.com.uberhidraulica.erp.finance.domain.FinanceException;
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

    /** Nova forma: o código deriva do nome; a marca de sessão de caixa nunca é concedida pela API. */
    @Transactional
    public PaymentMethod createPaymentMethod(String name) {
        String normalized = Money.requiredText(name, "Nome da forma de pagamento", 80);
        Instant now = clock.instant();
        PaymentMethod method = new PaymentMethod(UUID.randomUUID(), code(normalized), normalized, true, false, now, now);
        return save(() -> repository.savePaymentMethod(method, true), method, "PAYMENT_METHOD_ALREADY_EXISTS");
    }

    @Transactional
    public PaymentMethod updatePaymentMethod(UUID id, String name, boolean active) {
        PaymentMethod current = repository.paymentMethod(id)
                .orElseThrow(() -> new FinanceException("PAYMENT_METHOD_NOT_FOUND", "Forma de pagamento não encontrada"));
        PaymentMethod updated = new PaymentMethod(id, current.code(), name, active, current.cashSessionRequired(), current.createdAt(), clock.instant());
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
    public int defaultReceivableDueDays() { return repository.defaultReceivableDueDays(); }

    @Transactional
    public int changeDefaultReceivableDueDays(Integer days) {
        if (days == null || days < 0 || days > MAX_DUE_DAYS)
            throw new FinanceException("INVALID_FINANCE_SETTING", "Prazo padrão deve estar entre 0 e " + MAX_DUE_DAYS + " dias");
        repository.saveDefaultReceivableDueDays(days, clock.instant(), currentUser.requireId());
        return days;
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
