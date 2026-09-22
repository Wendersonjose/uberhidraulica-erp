package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.finance.domain.ExpenseCategory;
import br.com.uberhidraulica.erp.finance.domain.FinanceException;
import br.com.uberhidraulica.erp.finance.domain.Money;
import br.com.uberhidraulica.erp.finance.domain.Payable;
import br.com.uberhidraulica.erp.finance.domain.PaymentMethod;
import br.com.uberhidraulica.erp.finance.domain.Settlement;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort;
import br.com.uberhidraulica.erp.iam.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Contas a pagar (DR-0015, F-12): mesmas regras de liquidação, estorno e idempotência dos recebimentos. */
@Service
public class PayableService {
    private final FinanceRepositoryPort repository;
    private final ReceivableService receivables;
    private final CurrentUser currentUser;
    private final Clock clock = Clock.systemUTC();

    public PayableService(FinanceRepositoryPort repository, ReceivableService receivables, CurrentUser currentUser) {
        this.repository = repository;
        this.receivables = receivables;
        this.currentUser = currentUser;
    }

    @Transactional
    public Recorded<Payable> create(String description, String supplier, UUID categoryId, BigDecimal amount, LocalDate dueDate,
                                    String notes, String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        BigDecimal value = Money.positive(amount, "Valor");
        if (dueDate == null) throw new FinanceException("INVALID_FINANCE_ENTRY", "Vencimento é obrigatório");
        repository.lockIdempotencyKey(key);
        var previous = repository.findPayableByKey(key);
        if (previous.isPresent()) {
            if (!previous.get().sameRequest(description, supplier, categoryId, value, dueDate, notes)) throw Idempotency.reused();
            return new Recorded<>(previous.get(), true);
        }
        ExpenseCategory category = categoryId == null ? null : repository.expenseCategory(categoryId).orElse(null);
        if (category == null) throw new FinanceException("EXPENSE_CATEGORY_NOT_FOUND", "Categoria não encontrada");
        if (!category.active()) throw new FinanceException("EXPENSE_CATEGORY_INACTIVE", "Categoria inativa não recebe lançamento");
        Payable payable = new Payable(UUID.randomUUID(), description, supplier, category.id(), category.name(), value, dueDate, notes,
                clock.instant(), currentUser.requireId(), key, null, null, null, List.of());
        try {
            repository.insertPayable(payable);
        } catch (DataIntegrityViolationException collision) {
            throw Idempotency.reused();
        }
        return new Recorded<>(payable, false);
    }

    @Transactional
    public Recorded<Settlement> pay(UUID payableId, BigDecimal amount, UUID paymentMethodId, LocalDate paidOn, String notes,
                                    String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        BigDecimal value = Money.positive(amount, "Valor pago");
        LocalDate effectiveOn = Money.effectiveDate(paidOn, receivables.today());
        repository.lockIdempotencyKey(key);
        Payable payable = lock(payableId);
        var previous = repository.findPaymentByKey(key);
        if (previous.isPresent()) {
            if (!previous.get().sameRequest(payableId, value, paymentMethodId, paidOn, notes)) throw Idempotency.reused();
            return new Recorded<>(previous.get(), true);
        }
        PaymentMethod method = receivables.usableMethod(paymentMethodId);
        payable.checkPayment(value);
        Settlement payment = new Settlement(UUID.randomUUID(), payableId, value, method.id(), method.name(), effectiveOn, notes,
                clock.instant(), currentUser.requireId(), key, null);
        try {
            repository.insertPayment(payment);
        } catch (DataIntegrityViolationException collision) {
            throw Idempotency.reused();
        }
        return new Recorded<>(payment, false);
    }

    @Transactional
    public Recorded<Settlement> reversePayment(UUID paymentId, String reason, String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        String normalized = Money.requiredText(reason, "Motivo do estorno", 500);
        Settlement found = repository.findPayment(paymentId)
                .orElseThrow(() -> new FinanceException("PAYMENT_NOT_FOUND", "Pagamento não encontrado"));
        repository.lockIdempotencyKey(key);
        lock(found.ownerId());
        var previous = repository.paymentReversedWithKey(key);
        if (previous.isPresent()) {
            if (!previous.get().targetId().equals(paymentId) || !previous.get().reason().equals(normalized)) throw Idempotency.reused();
            return new Recorded<>(repository.findPayment(paymentId).orElseThrow(), true);
        }
        Settlement payment = repository.findPayment(paymentId).orElseThrow();
        if (payment.reversed()) throw new FinanceException("PAYMENT_ALREADY_REVERSED", "Este pagamento já foi estornado");
        try {
            repository.insertPaymentReversal(paymentId, new Settlement.Reversal(UUID.randomUUID(), normalized, clock.instant(),
                    currentUser.requireId(), key));
        } catch (DataIntegrityViolationException alreadyReversed) {
            throw new FinanceException("PAYMENT_ALREADY_REVERSED", "Este pagamento já foi estornado");
        }
        return new Recorded<>(repository.findPayment(paymentId).orElseThrow(), false);
    }

    @Transactional
    public Payable cancel(UUID payableId, String reason) {
        String normalized = Money.requiredText(reason, "Motivo do cancelamento", 500);
        Payable payable = lock(payableId);
        payable.checkCancellation();
        repository.cancelPayable(payableId, clock.instant(), currentUser.requireId(), normalized);
        return get(payableId);
    }

    @Transactional(readOnly = true)
    public Payable get(UUID id) {
        return repository.findPayable(id).orElseThrow(() -> new FinanceException("PAYABLE_NOT_FOUND", "Conta a pagar não encontrada"));
    }

    @Transactional(readOnly = true)
    public FinanceRepositoryPort.PageResult<FinanceRepositoryPort.PayableSummary> list(FinanceRepositoryPort.PayableFilter filter, int page, int size) {
        ReceivableService.requirePage(page, size);
        return repository.listPayables(filter, receivables.today(), page, size);
    }

    private Payable lock(UUID payableId) {
        return repository.lockPayable(payableId).orElseThrow(() -> new FinanceException("PAYABLE_NOT_FOUND", "Conta a pagar não encontrada"));
    }
}
