package br.com.uberhidraulica.erp.finance.port;

import br.com.uberhidraulica.erp.finance.domain.ExpenseCategory;
import br.com.uberhidraulica.erp.finance.domain.FinancialStatus;
import br.com.uberhidraulica.erp.finance.domain.Payable;
import br.com.uberhidraulica.erp.finance.domain.PaymentMethod;
import br.com.uberhidraulica.erp.finance.domain.Receivable;
import br.com.uberhidraulica.erp.finance.domain.Settlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistência do Financeiro. Só insere lançamentos; os únicos {@code UPDATE} são o vencimento corrente,
 * o cancelamento (ambos com histórico próprio) e nome/ativo de forma e categoria.
 */
public interface FinanceRepositoryPort {

    // ---------------------------------------------------------------- recebível

    Optional<Receivable> findReceivable(UUID id);

    Optional<Receivable> findReceivableByWorkOrder(UUID workOrderId);

    /** Carrega bloqueando a linha do recebível: ponto de serialização de toda mutação dele. */
    Optional<Receivable> lockReceivable(UUID id);

    Optional<Receivable> lockReceivableByWorkOrder(UUID workOrderId);

    void insertReceivable(Receivable receivable);

    void cancelReceivable(UUID id, Instant at, UUID by, String reason);

    void insertAdjustment(UUID receivableId, Receivable.Adjustment adjustment);

    void changeDueDate(UUID receivableId, Receivable.DueDateChange change);

    void insertReceipt(Settlement receipt);

    Optional<Settlement> findReceipt(UUID id);

    Optional<Settlement> findReceiptByKey(String idempotencyKey);

    void insertReceiptReversal(UUID receiptId, Settlement.Reversal reversal);

    /** Recebimento cujo estorno usou esta chave, se houver. */
    Optional<UUID> receiptReversedWithKey(String idempotencyKey);

    /** Recebível do ajuste que usou esta chave, se houver. */
    Optional<UUID> receivableAdjustedWithKey(String idempotencyKey);

    /** Recebível dono do ajuste, se o ajuste existir. */
    Optional<UUID> receivableOfAdjustment(UUID adjustmentId);

    void insertAdjustmentReversal(UUID adjustmentId, Settlement.Reversal reversal);

    /** Ajuste cujo estorno usou esta chave, se houver. */
    Optional<UUID> adjustmentReversedWithKey(String idempotencyKey);

    /** Alteração de vencimento gravada com esta chave, se houver. */
    Optional<DueDateChangeRef> dueDateChangedWithKey(String idempotencyKey);

    /**
     * Bloqueio transacional sobre a chave de idempotência: duas requisições com a mesma chave se serializam,
     * e a segunda encontra o lançamento da primeira em vez de disputar a constraint.
     */
    void lockIdempotencyKey(String idempotencyKey);

    PageResult<ReceivableSummary> listReceivables(ReceivableFilter filter, LocalDate today, int page, int size);

    // ---------------------------------------------------------------- contas a pagar

    Optional<Payable> findPayable(UUID id);

    Optional<Payable> lockPayable(UUID id);

    Optional<Payable> findPayableByKey(String idempotencyKey);

    void insertPayable(Payable payable);

    void cancelPayable(UUID id, Instant at, UUID by, String reason);

    void insertPayment(Settlement payment);

    Optional<Settlement> findPayment(UUID id);

    Optional<Settlement> findPaymentByKey(String idempotencyKey);

    void insertPaymentReversal(UUID paymentId, Settlement.Reversal reversal);

    Optional<UUID> paymentReversedWithKey(String idempotencyKey);

    PageResult<PayableSummary> listPayables(PayableFilter filter, LocalDate today, int page, int size);

    // ---------------------------------------------------------------- configuração

    List<PaymentMethod> paymentMethods();

    Optional<PaymentMethod> paymentMethod(UUID id);

    void savePaymentMethod(PaymentMethod method, boolean isNew);

    List<ExpenseCategory> expenseCategories();

    Optional<ExpenseCategory> expenseCategory(UUID id);

    void saveExpenseCategory(ExpenseCategory category, boolean isNew);

    int defaultReceivableDueDays();

    void saveDefaultReceivableDueDays(int days, Instant at, UUID by);

    // ---------------------------------------------------------------- fluxo de caixa

    List<DailyAmount> realizedInflows(LocalDate from, LocalDate to);

    List<DailyAmount> realizedOutflows(LocalDate from, LocalDate to, UUID categoryId);

    List<DailyAmount> forecastInflows(LocalDate from, LocalDate to);

    List<DailyAmount> forecastOutflows(LocalDate from, LocalDate to, UUID categoryId);

    // ---------------------------------------------------------------- tipos de consulta

    record PageResult<T>(List<T> items, long totalItems, int page, int size) {
        public int totalPages() { return (int) ((totalItems + size - 1) / size); }
    }

    record ReceivableFilter(FinancialStatus status, UUID customerId, Long workOrderNumber, LocalDate dueFrom, LocalDate dueTo) {}

    record ReceivableSummary(UUID id, UUID workOrderId, long workOrderNumber, UUID customerId, BigDecimal originalAmount,
                             BigDecimal discountAmount, BigDecimal surchargeAmount, BigDecimal receivedAmount,
                             BigDecimal outstandingBalance, LocalDate issuedOn, LocalDate dueDate, FinancialStatus status) {}

    record PayableFilter(FinancialStatus status, UUID categoryId, LocalDate dueFrom, LocalDate dueTo) {}

    record PayableSummary(UUID id, String description, String supplier, UUID categoryId, String categoryName, BigDecimal amount,
                          BigDecimal paidAmount, BigDecimal outstandingBalance, LocalDate dueDate, FinancialStatus status) {}

    record DailyAmount(LocalDate date, BigDecimal amount) {}

    record DueDateChangeRef(UUID receivableId, LocalDate newDueDate) {}
}
