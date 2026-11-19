package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.finance.FinanceSupplierDirectory;
import br.com.uberhidraulica.erp.finance.domain.ExpenseCategory;
import br.com.uberhidraulica.erp.finance.domain.FinanceException;
import br.com.uberhidraulica.erp.finance.domain.Money;
import br.com.uberhidraulica.erp.finance.domain.Payable;
import br.com.uberhidraulica.erp.finance.domain.PaymentMethod;
import br.com.uberhidraulica.erp.finance.domain.Receivable;
import br.com.uberhidraulica.erp.finance.domain.Settlement;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort;
import br.com.uberhidraulica.erp.iam.CurrentUser;
import br.com.uberhidraulica.erp.iam.IamAuthorization;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Contas a pagar (DR-0015, F-12): mesmas regras de liquidação, estorno e idempotência dos recebimentos.
 *
 * <p>DR-0024: fornecedor vinculado ao cadastro de Compras (Q6), lançamento parcelado em grupo (Q5) e aprovação
 * segregada acima do limite configurado (Q5). As contas registradas por Compras já passaram pela aprovação do pedido
 * de compra (V25) e não pedem uma segunda.</p>
 */
@Service
public class PayableService {
    public static final String APPROVAL_THRESHOLD_KEY = "PAYABLE_APPROVAL_THRESHOLD";
    public static final String APPROVE_OWN_PERMISSION = "FINANCE_PAYABLE_APPROVE_OWN";
    public static final int MAX_INSTALLMENTS = 60;

    private final FinanceRepositoryPort repository;
    private final ReceivableService receivables;
    private final CurrentUser currentUser;
    private final CashSessionService cash;
    private final FinanceSupplierDirectory suppliers;
    private final IamAuthorization authorization;
    private final Clock clock = Clock.systemUTC();

    public PayableService(FinanceRepositoryPort repository, ReceivableService receivables, CurrentUser currentUser,
                          CashSessionService cash, FinanceSupplierDirectory suppliers, IamAuthorization authorization) {
        this.repository = repository;
        this.receivables = receivables;
        this.currentUser = currentUser;
        this.cash = cash;
        this.suppliers = suppliers;
        this.authorization = authorization;
    }

    /** Dados de uma conta: {@code supplier} é texto livre; com {@code supplierId}, vazio vira o nome do cadastro. */
    public record Draft(String description, String supplier, UUID supplierId, UUID categoryId, BigDecimal amount, LocalDate dueDate,
                        String notes) {}

    public Recorded<Payable> create(String description, String supplier, UUID categoryId, BigDecimal amount, LocalDate dueDate,
                                    String notes, String idempotencyKey) {
        return create(new Draft(description, supplier, null, categoryId, amount, dueDate, notes), Payable.Origin.MANUAL, idempotencyKey);
    }

    @Transactional
    public Recorded<Payable> create(Draft draft, Payable.Origin origin, String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        BigDecimal value = Money.positive(draft.amount(), "Valor");
        if (draft.dueDate() == null) throw new FinanceException("INVALID_FINANCE_ENTRY", "Vencimento é obrigatório");
        repository.lockIdempotencyKey(key);
        var previous = repository.findPayableByKey(key);
        if (previous.isPresent()) {
            Payable found = previous.get();
            String supplier = draft.supplierId() == null ? draft.supplier() : found.supplier();
            if (!found.sameRequest(draft.description(), supplier, draft.categoryId(), value, draft.dueDate(), draft.notes())
                    || !Objects.equals(found.supplierId(), draft.supplierId())) throw Idempotency.reused();
            return new Recorded<>(found, true);
        }
        ExpenseCategory category = activeCategory(draft.categoryId());
        String supplier = supplierName(draft.supplierId(), draft.supplier());
        Payable payable = new Payable(UUID.randomUUID(), draft.description(), supplier, category.id(), category.name(), value, draft.dueDate(),
                draft.notes(), clock.instant(), currentUser.requireId(), key, null, null, null, List.of(), draft.supplierId(), origin,
                null, null, origin == Payable.Origin.MANUAL && requiresApproval(value), null);
        try {
            repository.insertPayable(payable);
        } catch (DataIntegrityViolationException collision) {
            throw Idempotency.reused();
        }
        return new Recorded<>(payable, false);
    }

    // ------------------------------------------------------------------ grupo de parcelas (DR-0024, Q5)

    public record GroupDraft(String description, String supplier, UUID supplierId, UUID categoryId, BigDecimal totalAmount, int installments,
                             LocalDate firstDueDate, String notes) {}

    public record Group(FinanceRepositoryPort.PayableGroup header, List<Payable> payables) {}

    /**
     * Lança uma despesa parcelada: N contas iguais (centavos na última), vencimentos mensais, ligadas a um grupo com
     * visão consolidada. O limite de aprovação vale para o total do grupo, não para cada parcela.
     */
    @Transactional
    public Recorded<Group> createInstallments(GroupDraft draft, String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        BigDecimal total = Money.positive(draft.totalAmount(), "Valor total");
        if (draft.firstDueDate() == null) throw new FinanceException("INVALID_FINANCE_ENTRY", "Primeiro vencimento é obrigatório");
        if (draft.installments() < 2 || draft.installments() > MAX_INSTALLMENTS)
            throw new FinanceException("INVALID_INSTALLMENT", "Informe de 2 a " + MAX_INSTALLMENTS + " parcelas");
        repository.lockIdempotencyKey(key);
        var previous = repository.findPayableGroupByKey(key);
        if (previous.isPresent()) {
            var found = previous.get();
            if (found.totalAmount().compareTo(total) != 0 || found.installmentCount() != draft.installments()
                    || !found.categoryId().equals(draft.categoryId()) || !Objects.equals(found.supplierId(), draft.supplierId())
                    || !found.description().equals(Money.requiredText(draft.description(), "Descrição", 200))) throw Idempotency.reused();
            return new Recorded<>(group(found.id()), true);
        }
        ExpenseCategory category = activeCategory(draft.categoryId());
        String supplier = supplierName(draft.supplierId(), draft.supplier());
        String description = Money.requiredText(draft.description(), "Descrição", 180);
        Instant now = clock.instant();
        UUID author = currentUser.requireId();
        boolean approval = requiresApproval(total);
        var header = new FinanceRepositoryPort.PayableGroup(UUID.randomUUID(), description, supplier, draft.supplierId(), category.id(), total,
                draft.installments(), Money.optionalText(draft.notes(), "Observação", 500), now, author, key);
        List<Receivable.Installment> split = Receivable.equalSplit(total, draft.installments(), draft.firstDueDate());
        try {
            repository.insertPayableGroup(header);
            for (Receivable.Installment installment : split)
                repository.insertPayable(new Payable(UUID.randomUUID(), description + " (" + installment.number() + "/" + split.size() + ")",
                        supplier, category.id(), category.name(), Money.positive(installment.amount(), "Valor da parcela"),
                        installment.dueDate(), header.notes(), now, author, key + ":" + installment.number(), null, null, null, List.of(),
                        draft.supplierId(), Payable.Origin.MANUAL, header.id(), installment.number(), approval, null));
        } catch (DataIntegrityViolationException collision) {
            throw Idempotency.reused();
        }
        return new Recorded<>(group(header.id()), false);
    }

    @Transactional(readOnly = true)
    public Group group(UUID groupId) {
        var header = repository.findPayableGroup(groupId)
                .orElseThrow(() -> new FinanceException("PAYABLE_GROUP_NOT_FOUND", "Grupo de parcelas não encontrado"));
        return new Group(header, repository.payablesOfGroup(groupId));
    }

    // ------------------------------------------------------------------ aprovação (DR-0024, Q5)

    @Transactional
    public Payable approve(UUID payableId, String notes) {
        Payable payable = lock(payableId);
        UUID approver = currentUser.requireId();
        payable.checkApproval(approver, authorization.hasPermission(approver, APPROVE_OWN_PERMISSION));
        try {
            repository.insertPayableApproval(payableId, new Payable.Approval(UUID.randomUUID(), clock.instant(), approver,
                    Money.optionalText(notes, "Observação", 500)));
        } catch (DataIntegrityViolationException alreadyApproved) {
            throw new FinanceException("PAYABLE_ALREADY_APPROVED", "Esta conta já foi aprovada");
        }
        return get(payableId);
    }

    /** Aprova de uma vez as parcelas pendentes do grupo; as canceladas e as já aprovadas ficam como estão. */
    @Transactional
    public Group approveGroup(UUID groupId, String notes) {
        Group current = group(groupId);
        List<Payable> pending = new ArrayList<>(current.payables().stream()
                .filter(p -> !p.cancelled() && p.approvalStatus() == Payable.ApprovalStatus.PENDING).toList());
        if (pending.isEmpty()) throw new FinanceException("PAYABLE_APPROVAL_NOT_REQUIRED", "Nenhuma parcela do grupo aguarda aprovação");
        for (Payable payable : pending) approve(payable.id(), notes);
        return group(groupId);
    }

    private boolean requiresApproval(BigDecimal amount) {
        BigDecimal threshold = new BigDecimal(repository.setting(APPROVAL_THRESHOLD_KEY));
        return threshold.signum() > 0 && amount.compareTo(threshold) > 0;
    }

    // ------------------------------------------------------------------ pagamento, estorno e cancelamento

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
            Settlement payment = previous.get();
            if (!payment.sameRequest(payableId, value, paymentMethodId, paidOn, notes)) throw Idempotency.reused();
            PaymentMethod method = repository.paymentMethod(paymentMethodId)
                    .orElseThrow(() -> new FinanceException("PAYMENT_METHOD_NOT_FOUND", "Forma de pagamento não encontrada"));
            if (method.cashSessionRequired()) cash.recordCashPayment(payment);
            return new Recorded<>(payment, true);
        }
        PaymentMethod method = receivables.usableMethod(paymentMethodId);
        payable.checkPayment(value);
        Settlement payment = new Settlement(UUID.randomUUID(), payableId, value, method.id(), method.name(), effectiveOn, notes,
                clock.instant(), currentUser.requireId(), key, null);
        try {
            repository.insertPayment(payment);
            if (method.cashSessionRequired()) cash.recordCashPayment(payment);
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
        cash.reverseCashPayment(paymentId, normalized);
        try {
            repository.insertPaymentReversal(paymentId, new Settlement.Reversal(UUID.randomUUID(), normalized, clock.instant(),
                    currentUser.requireId(), key));
        } catch (DataIntegrityViolationException refused) {
            throw ReceivableService.reversalRefused(refused, "PAYMENT_ALREADY_REVERSED", "Este pagamento já foi estornado");
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

    private ExpenseCategory activeCategory(UUID categoryId) {
        ExpenseCategory category = categoryId == null ? null : repository.expenseCategory(categoryId).orElse(null);
        if (category == null) throw new FinanceException("EXPENSE_CATEGORY_NOT_FOUND", "Categoria não encontrada");
        if (!category.active()) throw new FinanceException("EXPENSE_CATEGORY_INACTIVE", "Categoria inativa não recebe lançamento");
        return category;
    }

    /** DR-0024, Q6: o vínculo é validado pelo contrato que Compras implementa; o nome fica como snapshot. */
    private String supplierName(UUID supplierId, String informed) {
        if (supplierId == null) return informed;
        var supplier = suppliers.supplier(supplierId).orElseThrow(() -> new FinanceException("SUPPLIER_NOT_FOUND", "Fornecedor não encontrado"));
        if (!supplier.active()) throw new FinanceException("SUPPLIER_INACTIVE", "Fornecedor inativo não recebe lançamento");
        return informed == null || informed.isBlank() ? supplier.name() : informed;
    }

    private Payable lock(UUID payableId) {
        return repository.lockPayable(payableId).orElseThrow(() -> new FinanceException("PAYABLE_NOT_FOUND", "Conta a pagar não encontrada"));
    }
}
