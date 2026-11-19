package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.finance.domain.FinanceException;
import br.com.uberhidraulica.erp.finance.domain.Money;
import br.com.uberhidraulica.erp.finance.domain.PaymentMethod;
import br.com.uberhidraulica.erp.finance.domain.Receivable;
import br.com.uberhidraulica.erp.finance.domain.Settlement;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort;
import br.com.uberhidraulica.erp.iam.CurrentUser;
import br.com.uberhidraulica.erp.quote.QuoteBillingQuery;
import br.com.uberhidraulica.erp.workorder.WorkOrderQuery;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Recebível da OS: geração na finalização, recebimentos, estornos, ajustes e vencimento (DR-0015).
 *
 * <p>Toda mutação começa bloqueando a linha do recebível; verificação de saldo, de chave e de estorno
 * acontece depois do bloqueio, então requisições concorrentes se serializam. As constraints do banco são a
 * autoridade final.</p>
 */
@Service
public class ReceivableService {
    public static final int MAX_PAGE_SIZE = 100;

    private final FinanceRepositoryPort repository;
    private final QuoteBillingQuery billing;
    private final WorkOrderQuery workOrders;
    private final CurrentUser currentUser;
    private final br.com.uberhidraulica.erp.iam.IamAuthorization authorization;
    private final CashSessionService cash;
    private final br.com.uberhidraulica.erp.crm.CustomerVehicleQuery customers;
    private final Clock clock = Clock.systemUTC();

    public static final String BILL_PERMISSION = "FINANCE_BILL";

    public ReceivableService(FinanceRepositoryPort repository, QuoteBillingQuery billing, WorkOrderQuery workOrders, CurrentUser currentUser,
                             br.com.uberhidraulica.erp.iam.IamAuthorization authorization, CashSessionService cash,
                             br.com.uberhidraulica.erp.crm.CustomerVehicleQuery customers) {
        this.repository = repository;
        this.billing = billing;
        this.workOrders = workOrders;
        this.currentUser = currentUser;
        this.authorization = authorization;
        this.cash = cash;
        this.customers = customers;
    }

    public LocalDate today() { return LocalDate.now(clock.withZone(Money.WORKSHOP_ZONE)); }

    // ------------------------------------------------------------------ geração e cancelamento pela OS

    @Transactional(readOnly = true)
    public List<QuoteBillingQuery.BillingCandidate> billingCandidates(UUID workOrderId) {
        workOrders.workOrder(workOrderId).orElseThrow(() -> new FinanceException("WORK_ORDER_NOT_FOUND", "Ordem de Serviço não encontrada"));
        return billing.billingCandidates(workOrderId);
    }

    /**
     * Gera o recebível principal da OS finalizada (F-01, F-02, F-03, F-04).
     *
     * <p>Roda na transação da finalização: se não houver base comercial, ou houver mais de uma sem escolha,
     * a recusa desfaz a finalização. Reprocessar o evento devolve o recebível existente; o índice único em
     * {@code work_order_id} garante que não exista um segundo.</p>
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Receivable generateForFinishedWorkOrder(UUID workOrderId, UUID billingQuoteId, Instant finishedAt, UUID finishedBy) {
        if (finishedBy == null || !authorization.hasPermission(finishedBy, BILL_PERMISSION))
            throw new FinanceException("FINANCE_BILL_REQUIRED", "Seu perfil não pode gerar o faturamento da OS");
        var existing = repository.findReceivableByWorkOrder(workOrderId);
        if (existing.isPresent()) return existing.get();
        var order = workOrders.workOrder(workOrderId)
                .orElseThrow(() -> new FinanceException("WORK_ORDER_NOT_FOUND", "Ordem de Serviço não encontrada"));
        QuoteBillingQuery.BillingCandidate source = selected(billing.billingBasisForFinalization(workOrderId, billingQuoteId));
        LocalDate issuedOn = LocalDate.ofInstant(finishedAt, Money.WORKSHOP_ZONE);
        List<Receivable.Line> lines = new ArrayList<>();
        int order0 = 1;
        for (QuoteBillingQuery.BillingLine line : source.lines())
            lines.add(new Receivable.Line(UUID.randomUUID(), line.quoteItemRevisionId(), line.description(), line.quantity(),
                    line.unitPrice(), line.discountAmount(), line.totalPrice(), order0++));
        Receivable receivable = new Receivable(UUID.randomUUID(), workOrderId, order.number(), order.customerId(), source.quoteId(),
                source.approvedTotal(), issuedOn, issuedOn.plusDays(repository.defaultReceivableDueDays()), finishedAt, finishedBy,
                null, null, null, lines, List.of(), List.of(), List.of());
        repository.insertReceivable(receivable);
        return receivable;
    }

    static QuoteBillingQuery.BillingCandidate selected(QuoteBillingQuery.BillingBasis basis) {
        return switch (basis.outcome()) {
            case SELECTED -> basis.selected();
            case NOT_ELIGIBLE -> throw new FinanceException("BILLING_QUOTE_NOT_ELIGIBLE",
                    "O orçamento escolhido não pertence a esta OS ou não tem item aprovado");
            case NO_BASIS -> throw new FinanceException("WORK_ORDER_WITHOUT_BILLING_BASIS",
                    "A OS não tem orçamento com item aprovado; regularize a aprovação comercial antes de finalizar");
            case SELECTION_REQUIRED -> throw new FinanceException("BILLING_QUOTE_SELECTION_REQUIRED",
                    "A OS tem mais de um orçamento aprovado; escolha qual será cobrado");
        };
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void cancelForWorkOrder(UUID workOrderId, String reason) {
        repository.lockReceivableByWorkOrder(workOrderId).ifPresent(receivable -> {
            receivable.checkCancellation();
            repository.cancelReceivable(receivable.id(), clock.instant(), currentUser.id().orElse(null),
                    reason == null || reason.isBlank() ? "Cancelamento da OS" : reason.trim());
        });
    }

    // ------------------------------------------------------------------ recebimento e estorno

    public Recorded<Settlement> receive(UUID receivableId, BigDecimal amount, UUID paymentMethodId, LocalDate receivedOn,
                                        String notes, BigDecimal cashTendered, String idempotencyKey) {
        return receive(receivableId, amount, paymentMethodId, receivedOn, notes, cashTendered, null, idempotencyKey);
    }

    /**
     * Recebimento. Em forma com tabela de taxas (DR-0024, Q4), {@code cardInstallments} escolhe a linha da tabela
     * (padrão 1); o lançamento congela parcelas, taxa, valor da taxa e data prevista do repasse.
     */
    @Transactional
    public Recorded<Settlement> receive(UUID receivableId, BigDecimal amount, UUID paymentMethodId, LocalDate receivedOn,
                                        String notes, BigDecimal cashTendered, Integer cardInstallments, String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        BigDecimal value = Money.positive(amount, "Valor recebido");
        LocalDate effectiveOn = Money.effectiveDate(receivedOn, today());
        repository.lockIdempotencyKey(key);
        Receivable receivable = lock(receivableId);
        var previous = repository.findReceiptByKey(key);
        if (previous.isPresent()) {
            Settlement receipt = previous.get();
            if (!receipt.sameRequest(receivableId, value, paymentMethodId, receivedOn, notes)) throw Idempotency.reused();
            int recordedInstallments = receipt.cardFee() == null ? 1 : receipt.cardFee().installments();
            if (cardInstallments != null && cardInstallments != recordedInstallments) throw Idempotency.reused();
            PaymentMethod method = repository.paymentMethod(paymentMethodId)
                    .orElseThrow(() -> new FinanceException("PAYMENT_METHOD_NOT_FOUND", "Forma de pagamento não encontrada"));
            if (method.cashSessionRequired()) cash.recordCashReceipt(receipt, cashTendered);
            else requireNoCashTendered(cashTendered);
            return new Recorded<>(receipt, true);
        }
        PaymentMethod method = usableMethod(paymentMethodId);
        if (!method.cashSessionRequired()) requireNoCashTendered(cashTendered);
        receivable.checkReceipt(value);
        Settlement receipt = new Settlement(UUID.randomUUID(), receivableId, value, method.id(), method.name(), effectiveOn, notes,
                clock.instant(), currentUser.requireId(), key, null, cardFee(method, value, effectiveOn, cardInstallments));
        try {
            repository.insertReceipt(receipt);
            if (method.cashSessionRequired()) cash.recordCashReceipt(receipt, cashTendered);
        } catch (DataIntegrityViolationException collision) {
            throw Idempotency.reused();
        }
        return new Recorded<>(receipt, false);
    }

    @Transactional
    public Recorded<Settlement> reverseReceipt(UUID receiptId, String reason, String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        String normalized = Money.requiredText(reason, "Motivo do estorno", 500);
        Settlement found = repository.findReceipt(receiptId)
                .orElseThrow(() -> new FinanceException("RECEIPT_NOT_FOUND", "Recebimento não encontrado"));
        repository.lockIdempotencyKey(key);
        lock(found.ownerId());
        var previous = repository.receiptReversedWithKey(key);
        if (previous.isPresent()) {
            if (!previous.get().targetId().equals(receiptId) || !previous.get().reason().equals(normalized)) throw Idempotency.reused();
            return new Recorded<>(repository.findReceipt(receiptId).orElseThrow(), true);
        }
        Settlement receipt = repository.findReceipt(receiptId).orElseThrow();
        if (receipt.reversed()) throw new FinanceException("RECEIPT_ALREADY_REVERSED", "Este recebimento já foi estornado");
        cash.reverseCashReceipt(receiptId, normalized);
        Settlement.Reversal reversal = new Settlement.Reversal(UUID.randomUUID(), normalized, clock.instant(), currentUser.requireId(), key);
        try {
            repository.insertReceiptReversal(receiptId, reversal);
        } catch (DataIntegrityViolationException refused) {
            throw reversalRefused(refused, "RECEIPT_ALREADY_REVERSED", "Este recebimento já foi estornado");
        }
        return new Recorded<>(repository.findReceipt(receiptId).orElseThrow(), false);
    }

    // ------------------------------------------------------------------ ajustes e vencimento

    @Transactional
    public Recorded<Receivable> adjust(UUID receivableId, Receivable.AdjustmentType type, BigDecimal amount, String reason, String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        if (type == null) throw new FinanceException("INVALID_FINANCE_ENTRY", "Tipo de ajuste é obrigatório");
        BigDecimal value = Money.positive(amount, "Valor do ajuste");
        String normalized = Money.requiredText(reason, "Motivo do ajuste", 500);
        repository.lockIdempotencyKey(key);
        Receivable receivable = lock(receivableId);
        var previous = repository.receivableAdjustedWithKey(key);
        if (previous.isPresent()) {
            var original = previous.get();
            if (!original.receivableId().equals(receivableId) || original.type() != type || original.amount().compareTo(value) != 0
                    || !original.reason().equals(normalized)) throw Idempotency.reused();
            return new Recorded<>(receivable, true);
        }
        Receivable.Adjustment adjustment = new Receivable.Adjustment(UUID.randomUUID(), type, amount, reason, clock.instant(),
                currentUser.requireId(), key, null);
        receivable.checkAdjustment(type, adjustment.amount());
        repository.insertAdjustment(receivableId, adjustment);
        return new Recorded<>(repository.findReceivable(receivableId).orElseThrow(), false);
    }

    @Transactional
    public Recorded<Receivable> reverseAdjustment(UUID adjustmentId, String reason, String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        String normalized = Money.requiredText(reason, "Motivo do estorno", 500);
        UUID receivableId = repository.receivableOfAdjustment(adjustmentId)
                .orElseThrow(() -> new FinanceException("ADJUSTMENT_NOT_FOUND", "Ajuste não encontrado"));
        repository.lockIdempotencyKey(key);
        Receivable receivable = lock(receivableId);
        var previous = repository.adjustmentReversedWithKey(key);
        if (previous.isPresent()) {
            if (!previous.get().targetId().equals(adjustmentId) || !previous.get().reason().equals(normalized)) throw Idempotency.reused();
            return new Recorded<>(receivable, true);
        }
        receivable.checkAdjustmentReversal(adjustmentId);
        try {
            repository.insertAdjustmentReversal(adjustmentId, new Settlement.Reversal(UUID.randomUUID(), normalized, clock.instant(),
                    currentUser.requireId(), key));
        } catch (DataIntegrityViolationException alreadyReversed) {
            throw new FinanceException("ADJUSTMENT_ALREADY_REVERSED", "Este ajuste já foi estornado");
        }
        return new Recorded<>(repository.findReceivable(receivableId).orElseThrow(), false);
    }

    public Recorded<Receivable> changeDueDate(UUID receivableId, LocalDate newDueDate, String reason, String idempotencyKey) {
        return changeDueDate(receivableId, null, newDueDate, reason, idempotencyKey);
    }

    /**
     * Altera o vencimento de uma parcela (DR-0015, F-04; DR-0024, Q1). Sem número, vale a primeira parcela com saldo —
     * no plano de parcela única, o comportamento anterior às parcelas.
     */
    @Transactional
    public Recorded<Receivable> changeDueDate(UUID receivableId, Integer installmentNumber, LocalDate newDueDate, String reason,
                                              String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        String normalized = Money.requiredText(reason, "Motivo da alteração", 500);
        repository.lockIdempotencyKey(key);
        Receivable receivable = lock(receivableId);
        var previous = repository.dueDateChangedWithKey(key);
        if (previous.isPresent()) {
            if (!previous.get().receivableId().equals(receivableId) || !previous.get().newDueDate().equals(newDueDate)
                    || !previous.get().reason().equals(normalized)
                    || (installmentNumber != null && !installmentNumber.equals(previous.get().installmentNumber()))) throw Idempotency.reused();
            return new Recorded<>(receivable, true);
        }
        Receivable.Installment installment = receivable.checkDueDateChange(installmentNumber, newDueDate);
        Integer recordedNumber = receivable.plan().implicit() ? null : installment.number();
        try {
            repository.changeDueDate(receivableId, new Receivable.DueDateChange(UUID.randomUUID(), installment.dueDate(), newDueDate,
                    normalized, clock.instant(), currentUser.requireId(), key, recordedNumber), receivable.plan().version());
        } catch (DataIntegrityViolationException collision) {
            throw Idempotency.reused();
        }
        return new Recorded<>(repository.findReceivable(receivableId).orElseThrow(), false);
    }

    // ------------------------------------------------------------------ parcelas (DR-0024, Q1)

    /**
     * Substitui o plano de parcelas, enquanto nenhum recebimento estiver ativo. O plano anterior continua gravado; a
     * soma é conferida pelo domínio e de novo pelo banco no fim da transação.
     */
    @Transactional
    public Recorded<Receivable> replan(UUID receivableId, List<Receivable.Installment> installments, String reason, String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        String normalized = Money.requiredText(reason, "Motivo do parcelamento", 500);
        repository.lockIdempotencyKey(key);
        Receivable receivable = lock(receivableId);
        var previous = repository.installmentPlanWithKey(key);
        if (previous.isPresent()) {
            if (!previous.get().receivableId().equals(receivableId) || !samePlan(receivable, installments, normalized)) throw Idempotency.reused();
            return new Recorded<>(receivable, true);
        }
        List<Receivable.Installment> checked = receivable.checkInstallmentPlan(installments);
        Receivable.InstallmentPlan plan = new Receivable.InstallmentPlan(receivable.plan().version() + 1, normalized, clock.instant(),
                currentUser.requireId(), checked.stream().map(i -> new Receivable.Installment(UUID.randomUUID(), i.number(), i.amount(),
                i.dueDate())).toList());
        try {
            repository.insertInstallmentPlan(receivableId, plan, key);
        } catch (DataIntegrityViolationException collision) {
            throw Idempotency.reused();
        }
        return new Recorded<>(repository.findReceivable(receivableId).orElseThrow(), false);
    }

    private static boolean samePlan(Receivable current, List<Receivable.Installment> requested, String reason) {
        List<Receivable.Installment> recorded = current.plan().installments();
        if (!reason.equals(current.plan().reason()) || requested == null || recorded.size() != requested.size()) return false;
        for (int i = 0; i < recorded.size(); i++)
            if (recorded.get(i).amount().compareTo(requested.get(i).amount()) != 0 || !recorded.get(i).dueDate().equals(requested.get(i).dueDate()))
                return false;
        return true;
    }

    // ------------------------------------------------------------------ recebível avulso (DR-0024, Q2)

    public record ManualRequest(UUID customerId, String description, UUID revenueCategoryId, BigDecimal amount, LocalDate issuedOn,
                                LocalDate dueDate, String notes, Integer installments) {}

    /**
     * Conta a receber sem OS: venda de balcão, reembolso etc. Com mais de uma parcela, o plano nasce junto, dividido em
     * partes iguais com os centavos na última e vencimentos mensais a partir do vencimento informado.
     */
    @Transactional
    public Recorded<Receivable> createManual(ManualRequest request, String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        BigDecimal value = Money.positive(request.amount(), "Valor");
        if (request.customerId() == null) throw new FinanceException("INVALID_FINANCE_ENTRY", "Cliente é obrigatório");
        if (request.dueDate() == null) throw new FinanceException("INVALID_FINANCE_ENTRY", "Vencimento é obrigatório");
        LocalDate issuedOn = request.issuedOn() == null ? today() : request.issuedOn();
        if (issuedOn.isAfter(today())) throw new FinanceException("INVALID_FINANCE_ENTRY", "Emissão não pode ser futura");
        if (request.dueDate().isBefore(issuedOn)) throw new FinanceException("INVALID_FINANCE_ENTRY", "Vencimento não pode ser antes da emissão");
        int count = request.installments() == null ? 1 : request.installments();
        repository.lockIdempotencyKey(key);
        var previous = repository.findReceivableByKey(key);
        if (previous.isPresent()) {
            Receivable found = previous.get();
            if (!found.customerId().equals(request.customerId()) || found.originalAmount().compareTo(value) != 0
                    || !found.description().equals(Money.requiredText(request.description(), "Descrição", 200))
                    || !found.revenueCategoryId().equals(request.revenueCategoryId())) throw Idempotency.reused();
            return new Recorded<>(found, true);
        }
        var customer = customers.customer(request.customerId())
                .orElseThrow(() -> new FinanceException("CUSTOMER_NOT_FOUND", "Cliente não encontrado"));
        if (!customer.active()) throw new FinanceException("CUSTOMER_INACTIVE", "Cliente inativo não recebe lançamento");
        var category = request.revenueCategoryId() == null ? null : repository.revenueCategory(request.revenueCategoryId()).orElse(null);
        if (category == null) throw new FinanceException("REVENUE_CATEGORY_NOT_FOUND", "Categoria de receita não encontrada");
        if (!category.active()) throw new FinanceException("REVENUE_CATEGORY_INACTIVE", "Categoria inativa não recebe lançamento");
        List<Receivable.Installment> split = Receivable.equalSplit(value, count, request.dueDate());
        for (Receivable.Installment installment : split) Money.positive(installment.amount(), "Valor da parcela");
        Instant now = clock.instant();
        UUID author = currentUser.requireId();
        Receivable receivable = new Receivable(UUID.randomUUID(), Receivable.Origin.MANUAL, null, null, customer.id(), null,
                request.description(), category.id(), category.name(), request.notes(), key, value, issuedOn, request.dueDate(), now, author,
                null, null, null, List.of(), List.of(), List.of(), List.of(), null);
        try {
            repository.insertReceivable(receivable);
            if (count > 1)
                repository.insertInstallmentPlan(receivable.id(), new Receivable.InstallmentPlan(1, "Parcelamento no lançamento", now, author,
                        split.stream().map(i -> new Receivable.Installment(UUID.randomUUID(), i.number(), i.amount(), i.dueDate())).toList()),
                        key + ":plano");
        } catch (DataIntegrityViolationException collision) {
            throw Idempotency.reused();
        }
        return new Recorded<>(repository.findReceivable(receivable.id()).orElseThrow(), false);
    }

    /** Cancela o recebível avulso, preservando o histórico; com recebimento ativo, é recusado (F-08). */
    @Transactional
    public Receivable cancelManual(UUID receivableId, String reason) {
        String normalized = Money.requiredText(reason, "Motivo do cancelamento", 500);
        Receivable receivable = lock(receivableId);
        if (!receivable.manual())
            throw new FinanceException("RECEIVABLE_FROM_WORK_ORDER", "O recebível da OS é cancelado pelo cancelamento da própria OS");
        receivable.checkCancellation();
        repository.cancelReceivable(receivableId, clock.instant(), currentUser.requireId(), normalized);
        return get(receivableId);
    }

    // ------------------------------------------------------------------ consulta

    @Transactional(readOnly = true)
    public Receivable get(UUID id) {
        return repository.findReceivable(id).orElseThrow(() -> new FinanceException("RECEIVABLE_NOT_FOUND", "Recebível não encontrado"));
    }

    @Transactional(readOnly = true)
    public Receivable byWorkOrder(UUID workOrderId) {
        return repository.findReceivableByWorkOrder(workOrderId)
                .orElseThrow(() -> new FinanceException("RECEIVABLE_NOT_FOUND", "A OS ainda não tem recebível"));
    }

    @Transactional(readOnly = true)
    public FinanceRepositoryPort.PageResult<FinanceRepositoryPort.ReceivableSummary> list(FinanceRepositoryPort.ReceivableFilter filter,
                                                                                         int page, int size) {
        requirePage(page, size);
        return repository.listReceivables(filter, today(), page, size);
    }

    /** O banco recusa estornar lançamento conciliado (DR-0024, Q7); o resto é estorno em duplicidade. */
    static FinanceException reversalRefused(DataIntegrityViolationException refused, String code, String message) {
        String detail = String.valueOf(refused.getMostSpecificCause().getMessage());
        if (detail.contains("entry_reconciled"))
            return new FinanceException("ENTRY_RECONCILED", "Lançamento conciliado: desfaça a conciliação antes de estornar");
        return new FinanceException(code, message);
    }

    static void requirePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE)
            throw new FinanceException("INVALID_PAGE", "Página inválida ou tamanho fora de 1 a " + MAX_PAGE_SIZE);
    }

    private Receivable lock(UUID receivableId) {
        return repository.lockReceivable(receivableId)
                .orElseThrow(() -> new FinanceException("RECEIVABLE_NOT_FOUND", "Recebível não encontrado"));
    }

    PaymentMethod usableMethod(UUID paymentMethodId) {
        if (paymentMethodId == null) throw new FinanceException("INVALID_FINANCE_ENTRY", "Forma de pagamento é obrigatória");
        PaymentMethod method = repository.paymentMethod(paymentMethodId)
                .orElseThrow(() -> new FinanceException("PAYMENT_METHOD_NOT_FOUND", "Forma de pagamento não encontrada"));
        method.requireUsable();
        return method;
    }

    /** Sem tabela de taxas, não há parcelamento: o pedido de mais de uma parcela é recusado em vez de ignorado. */
    private Settlement.CardFee cardFee(PaymentMethod method, BigDecimal amount, LocalDate receivedOn, Integer installments) {
        List<br.com.uberhidraulica.erp.finance.domain.PaymentMethodFee> fees = repository.currentFees(method.id());
        int requested = installments == null ? 1 : installments;
        if (fees.isEmpty()) {
            if (requested != 1)
                throw new FinanceException("INSTALLMENTS_NOT_ALLOWED", "A forma " + method.name() + " não tem parcelamento configurado");
            return null;
        }
        return fees.stream().filter(fee -> fee.installments() == requested).findFirst()
                .orElseThrow(() -> new FinanceException("INSTALLMENTS_NOT_ALLOWED", "A forma " + method.name() + " não aceita "
                        + requested + " parcela(s); configure a taxa antes"))
                .apply(amount, receivedOn);
    }

    private static void requireNoCashTendered(BigDecimal cashTendered) {
        if (cashTendered != null)
            throw new FinanceException("CASH_TENDERED_NOT_ALLOWED", "Valor entregue em dinheiro só pode ser informado para pagamento em dinheiro");
    }
}
