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
    private final Clock clock = Clock.systemUTC();

    public ReceivableService(FinanceRepositoryPort repository, QuoteBillingQuery billing, WorkOrderQuery workOrders, CurrentUser currentUser) {
        this.repository = repository;
        this.billing = billing;
        this.workOrders = workOrders;
        this.currentUser = currentUser;
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
        var existing = repository.findReceivableByWorkOrder(workOrderId);
        if (existing.isPresent()) return existing.get();
        var order = workOrders.workOrder(workOrderId)
                .orElseThrow(() -> new FinanceException("WORK_ORDER_NOT_FOUND", "Ordem de Serviço não encontrada"));
        QuoteBillingQuery.BillingCandidate source = select(billing.billingCandidates(workOrderId), billingQuoteId);
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

    /** Seleção do orçamento de faturamento: nunca o último, nunca a soma, nunca o total bruto da OS. */
    static QuoteBillingQuery.BillingCandidate select(List<QuoteBillingQuery.BillingCandidate> candidates, UUID chosen) {
        if (chosen != null)
            return candidates.stream().filter(candidate -> candidate.quoteId().equals(chosen)).findFirst()
                    .orElseThrow(() -> new FinanceException("BILLING_QUOTE_NOT_ELIGIBLE",
                            "O orçamento escolhido não pertence a esta OS ou não tem item aprovado"));
        if (candidates.isEmpty())
            throw new FinanceException("WORK_ORDER_WITHOUT_BILLING_BASIS",
                    "A OS não tem orçamento com item aprovado; regularize a aprovação comercial antes de finalizar");
        if (candidates.size() > 1)
            throw new FinanceException("BILLING_QUOTE_SELECTION_REQUIRED",
                    "A OS tem mais de um orçamento aprovado; escolha qual será cobrado");
        return candidates.get(0);
    }

    /** F-08: com recebimento não estornado, recusa; sem recebimentos, cancela preservando o histórico. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void cancelForWorkOrder(UUID workOrderId, String reason) {
        repository.lockReceivableByWorkOrder(workOrderId).ifPresent(receivable -> {
            receivable.checkCancellation();
            repository.cancelReceivable(receivable.id(), clock.instant(), currentUser.id().orElse(null),
                    reason == null || reason.isBlank() ? "Cancelamento da OS" : reason.trim());
        });
    }

    // ------------------------------------------------------------------ recebimento e estorno

    @Transactional
    public Recorded<Settlement> receive(UUID receivableId, BigDecimal amount, UUID paymentMethodId, LocalDate receivedOn,
                                        String notes, String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        BigDecimal value = Money.positive(amount, "Valor recebido");
        LocalDate effectiveOn = Money.effectiveDate(receivedOn, today());
        repository.lockIdempotencyKey(key);
        Receivable receivable = lock(receivableId);
        var previous = repository.findReceiptByKey(key);
        if (previous.isPresent()) {
            if (!previous.get().sameRequest(receivableId, value, paymentMethodId, effectiveOn)) throw Idempotency.reused();
            return new Recorded<>(previous.get(), true);
        }
        PaymentMethod method = usableMethod(paymentMethodId);
        receivable.checkReceipt(value);
        Settlement receipt = new Settlement(UUID.randomUUID(), receivableId, value, method.id(), method.name(), effectiveOn, notes,
                clock.instant(), currentUser.requireId(), key, null);
        try {
            repository.insertReceipt(receipt);
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
            if (!previous.get().equals(receiptId)) throw Idempotency.reused();
            return new Recorded<>(repository.findReceipt(receiptId).orElseThrow(), true);
        }
        Settlement receipt = repository.findReceipt(receiptId).orElseThrow();
        if (receipt.reversed()) throw new FinanceException("RECEIPT_ALREADY_REVERSED", "Este recebimento já foi estornado");
        Settlement.Reversal reversal = new Settlement.Reversal(UUID.randomUUID(), normalized, clock.instant(), currentUser.requireId(), key);
        try {
            repository.insertReceiptReversal(receiptId, reversal);
        } catch (DataIntegrityViolationException alreadyReversed) {
            throw new FinanceException("RECEIPT_ALREADY_REVERSED", "Este recebimento já foi estornado");
        }
        return new Recorded<>(repository.findReceipt(receiptId).orElseThrow(), false);
    }

    // ------------------------------------------------------------------ ajustes e vencimento

    @Transactional
    public Recorded<Receivable> adjust(UUID receivableId, Receivable.AdjustmentType type, BigDecimal amount, String reason, String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        if (type == null) throw new FinanceException("INVALID_FINANCE_ENTRY", "Tipo de ajuste é obrigatório");
        repository.lockIdempotencyKey(key);
        Receivable receivable = lock(receivableId);
        var previous = repository.receivableAdjustedWithKey(key);
        if (previous.isPresent()) {
            if (!previous.get().equals(receivableId)) throw Idempotency.reused();
            return new Recorded<>(receivable, true);
        }
        Receivable.Adjustment adjustment = new Receivable.Adjustment(UUID.randomUUID(), type, amount, reason, clock.instant(),
                currentUser.requireId(), key);
        receivable.checkAdjustment(type, adjustment.amount());
        repository.insertAdjustment(receivableId, adjustment);
        return new Recorded<>(repository.findReceivable(receivableId).orElseThrow(), false);
    }

    @Transactional
    public Receivable changeDueDate(UUID receivableId, LocalDate newDueDate, String reason) {
        String normalized = Money.requiredText(reason, "Motivo da alteração", 500);
        Receivable receivable = lock(receivableId);
        receivable.checkDueDateChange(newDueDate);
        repository.changeDueDate(receivableId, new Receivable.DueDateChange(UUID.randomUUID(), receivable.dueDate(), newDueDate,
                normalized, clock.instant(), currentUser.requireId()));
        return repository.findReceivable(receivableId).orElseThrow();
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
}
