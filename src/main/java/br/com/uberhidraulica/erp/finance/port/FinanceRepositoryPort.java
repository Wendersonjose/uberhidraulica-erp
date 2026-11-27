package br.com.uberhidraulica.erp.finance.port;

import br.com.uberhidraulica.erp.finance.domain.ExpenseCategory;
import br.com.uberhidraulica.erp.finance.domain.FinancialStatus;
import br.com.uberhidraulica.erp.finance.domain.LateCharge;
import br.com.uberhidraulica.erp.finance.domain.PaymentMethodFee;
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

    /** Recebível avulso gravado com esta chave de idempotência, se houver. */
    Optional<Receivable> findReceivableByKey(String idempotencyKey);

    void insertReceivable(Receivable receivable);

    void cancelReceivable(UUID id, Instant at, UUID by, String reason);

    void insertAdjustment(UUID receivableId, Receivable.Adjustment adjustment);

    /**
     * Grava o histórico e o novo vencimento da parcela. No plano implícito (versão 0) o vencimento é o do próprio
     * recebível; no plano gravado é o da parcela, e a parcela 1 também atualiza o vencimento do recebível.
     */
    void changeDueDate(UUID receivableId, Receivable.DueDateChange change, int planVersion);

    /** Grava um plano de parcelas novo; o anterior continua gravado como histórico. */
    void insertInstallmentPlan(UUID receivableId, Receivable.InstallmentPlan plan, String idempotencyKey);

    /** Plano gravado com esta chave, se houver. */
    Optional<KeyedPlan> installmentPlanWithKey(String idempotencyKey);

    void insertReceipt(Settlement receipt);

    Optional<Settlement> findReceipt(UUID id);

    Optional<Settlement> findReceiptByKey(String idempotencyKey);

    void insertReceiptReversal(UUID receiptId, Settlement.Reversal reversal);

    /** Estorno de recebimento gravado com esta chave, se houver. */
    Optional<KeyedReversal> receiptReversedWithKey(String idempotencyKey);

    /** Ajuste gravado com esta chave, se houver. */
    Optional<KeyedAdjustment> receivableAdjustedWithKey(String idempotencyKey);

    /** Recebível dono do ajuste, se o ajuste existir. */
    Optional<UUID> receivableOfAdjustment(UUID adjustmentId);

    void insertAdjustmentReversal(UUID adjustmentId, Settlement.Reversal reversal);

    /** Estorno de ajuste gravado com esta chave, se houver. */
    Optional<KeyedReversal> adjustmentReversedWithKey(String idempotencyKey);

    /** Alteração de vencimento gravada com esta chave, se houver. */
    Optional<DueDateChangeRef> dueDateChangedWithKey(String idempotencyKey);

    /**
     * Bloqueio transacional sobre a chave de idempotência: duas requisições com a mesma chave se serializam,
     * e a segunda encontra o lançamento da primeira em vez de disputar a constraint.
     */
    void lockIdempotencyKey(String idempotencyKey);

    PageResult<ReceivableSummary> listReceivables(ReceivableFilter filter, LocalDate today, int page, int size);

    // ---------------------------------------------------------------- juros e multa (DR-0024, Q3)

    /** Cálculos decididos do recebível, com as parcelas de cada um. */
    List<LateCharge.Recorded> lateCharges(UUID receivableId);

    void insertLateCharge(UUID receivableId, LateCharge.Recorded charge, LateCharge.Suggestion suggestion, String idempotencyKey);

    Optional<KeyedLateCharge> lateChargeWithKey(String idempotencyKey);

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

    Optional<KeyedReversal> paymentReversedWithKey(String idempotencyKey);

    PageResult<PayableSummary> listPayables(PayableFilter filter, LocalDate today, int page, int size);

    /** Grupo de parcelas (DR-0024, Q5): o cabeçalho; as contas são gravadas por {@link #insertPayable}. */
    void insertPayableGroup(PayableGroup group);

    Optional<PayableGroup> findPayableGroup(UUID id);

    Optional<PayableGroup> findPayableGroupByKey(String idempotencyKey);

    List<Payable> payablesOfGroup(UUID groupId);

    void insertPayableApproval(UUID payableId, Payable.Approval approval);

    // ---------------------------------------------------------------- configuração

    List<PaymentMethod> paymentMethods();

    Optional<PaymentMethod> paymentMethod(UUID id);

    void savePaymentMethod(PaymentMethod method, boolean isNew);

    List<ExpenseCategory> expenseCategories();

    Optional<ExpenseCategory> expenseCategory(UUID id);

    void saveExpenseCategory(ExpenseCategory category, boolean isNew);

    List<ExpenseCategory> revenueCategories();

    Optional<ExpenseCategory> revenueCategory(UUID id);

    void saveRevenueCategory(ExpenseCategory category, boolean isNew);

    int defaultReceivableDueDays();

    /** Valor de configuração de {@code finance.settings}; a chave é validada pelo banco. */
    String setting(String key);

    void saveSetting(String key, String value, Instant at, UUID by);

    // ---------------------------------------------------------------- taxas de cartão (DR-0024, Q4)

    /** Tabela de taxas vigente da forma; vazia quando a forma não tem taxa. */
    List<PaymentMethodFee> currentFees(UUID paymentMethodId);

    /** Grava uma tabela nova; a anterior continua gravada como histórico. */
    void insertFeeSchedule(UUID paymentMethodId, List<PaymentMethodFee> fees, Instant at, UUID by);

    /** Taxas de cartão dos recebimentos não estornados no período, pela data do recebimento. */
    BigDecimal cardFees(LocalDate from, LocalDate to);

    void saveDefaultReceivableDueDays(int days, Instant at, UUID by);

    // ---------------------------------------------------------------- fluxo de caixa

    List<DailyAmount> realizedInflows(LocalDate from, LocalDate to);

    List<DailyAmount> realizedOutflows(LocalDate from, LocalDate to, UUID categoryId);

    List<DailyAmount> forecastInflows(LocalDate from, LocalDate to);

    List<DailyAmount> forecastOutflows(LocalDate from, LocalDate to, UUID categoryId);

    // ---------------------------------------------------------------- resumo gerencial

    /** Totais de recebíveis não cancelados emitidos no período, para o resumo financeiro gerencial. */
    DashboardReceivableTotals dashboardReceivableTotals(LocalDate from, LocalDate to, LocalDate today);

    /**
     * Despesas operacionais registradas (não canceladas) no período, pela data de criação da conta a pagar.
     * Exclui as contas de compra de mercadoria para estoque ({@code origin = 'PURCHASE'}): a mercadoria vira custo
     * quando é consumida na OS, e contá-la aqui também seria contar a mesma peça duas vezes.
     */
    BigDecimal dashboardExpensesRegistered(LocalDate from, LocalDate to);

    /** Contas a pagar de compra de mercadoria para estoque ({@code origin = 'PURCHASE'}) registradas no período. */
    BigDecimal dashboardStockPurchasesRegistered(LocalDate from, LocalDate to);

    // ---------------------------------------------------------------- tipos de consulta

    record PageResult<T>(List<T> items, long totalItems, int page, int size) {
        public int totalPages() { return (int) ((totalItems + size - 1) / size); }
    }

    record ReceivableFilter(FinancialStatus status, UUID customerId, Long workOrderNumber, LocalDate dueFrom, LocalDate dueTo,
                            Receivable.Origin origin) {
        public ReceivableFilter(FinancialStatus status, UUID customerId, Long workOrderNumber, LocalDate dueFrom, LocalDate dueTo) {
            this(status, customerId, workOrderNumber, dueFrom, dueTo, null);
        }
    }

    /** {@code dueDate} é o vencimento corrente: o da primeira parcela com saldo. */
    record ReceivableSummary(UUID id, Receivable.Origin origin, UUID workOrderId, Long workOrderNumber, String description,
                             UUID customerId, BigDecimal originalAmount,
                             BigDecimal discountAmount, BigDecimal surchargeAmount, BigDecimal receivedAmount,
                             BigDecimal outstandingBalance, LocalDate issuedOn, LocalDate dueDate, FinancialStatus status,
                             int installmentCount) {}

    record PayableFilter(FinancialStatus status, UUID categoryId, LocalDate dueFrom, LocalDate dueTo, Payable.ApprovalStatus approval,
                         UUID groupId) {
        public PayableFilter(FinancialStatus status, UUID categoryId, LocalDate dueFrom, LocalDate dueTo) {
            this(status, categoryId, dueFrom, dueTo, null, null);
        }
    }

    record PayableSummary(UUID id, String description, String supplier, UUID categoryId, String categoryName, BigDecimal amount,
                          BigDecimal paidAmount, BigDecimal outstandingBalance, LocalDate dueDate, FinancialStatus status,
                          UUID supplierId, UUID groupId, Integer installmentNumber, Payable.ApprovalStatus approvalStatus) {}

    record PayableGroup(UUID id, String description, String supplier, UUID supplierId, UUID categoryId, BigDecimal totalAmount,
                        int installmentCount, String notes, java.time.Instant createdAt, UUID createdBy, String idempotencyKey) {}

    record KeyedLateCharge(UUID receivableId, LateCharge.Decision decision, LocalDate calculatedOn, BigDecimal total) {}

    record DailyAmount(LocalDate date, BigDecimal amount) {}

    /** Identidade persistida dos pedidos idempotentes (revisão TASK-0015, F4). */
    record DueDateChangeRef(UUID receivableId, LocalDate newDueDate, String reason, Integer installmentNumber) {}

    record KeyedPlan(UUID receivableId, int version) {}

    /** Base do faturamento, a receber e vencido, todos restritos aos recebíveis emitidos no período. */
    record DashboardReceivableTotals(BigDecimal revenue, BigDecimal discounts, BigDecimal surcharges, BigDecimal receivableOpen,
                                     BigDecimal overdue, List<UUID> workOrderIds) {}

    record KeyedReversal(UUID targetId, String reason) {}

    record KeyedAdjustment(UUID receivableId, Receivable.AdjustmentType type, BigDecimal amount, String reason) {}
}
