package br.com.uberhidraulica.erp.finance.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Consultas somente leitura dos relatórios financeiros (TASK-0024, etapa A).
 *
 * <p>Saldo, status e vencimento seguem exatamente a mesma regra das listas de recebíveis e contas a pagar
 * (desconto e acréscimo não estornados, recebimentos e pagamentos não estornados; cancelado fica fora).</p>
 */
public interface FinanceReportPort {

    /** Faixa de atraso do saldo em aberto: {@code A_VENCER}, {@code ATE_30}, {@code DE_31_A_60}, {@code DE_61_A_90}, {@code ACIMA_90}. */
    record AgingBucket(String code, long count, BigDecimal amount) {}

    record CustomerDebt(UUID customerId, long count, BigDecimal outstanding, BigDecimal overdue, LocalDate oldestDueDate) {}

    /** Vencido = vencimento antes de hoje; "em N dias" = de hoje até hoje + N (inclusive). */
    record DueSummary(long overdueCount, BigDecimal overdueAmount, long dueIn7Count, BigDecimal dueIn7Amount,
                      long dueIn30Count, BigDecimal dueIn30Amount, long openCount, BigDecimal openAmount) {}

    record CategoryExpense(UUID categoryId, String categoryName, long registeredCount, BigDecimal registered, BigDecimal paid) {}

    /** Contas de compra de mercadoria para estoque ({@code origin = 'PURCHASE'}): cadastrado e pago no período. */
    record StockPurchases(long registeredCount, BigDecimal registered, BigDecimal paid) {}

    record WorkOrderResult(UUID receivableId, UUID workOrderId, long workOrderNumber, UUID customerId, LocalDate issuedOn,
                           LocalDate dueDate, String status, BigDecimal original, BigDecimal discount, BigDecimal surcharge,
                           BigDecimal received, BigDecimal outstanding) {}

    /** {@code workOrderNumber} é nulo e {@code description} preenchida no recebível avulso. */
    record ReceivableRow(UUID id, Long workOrderNumber, String description, UUID customerId, LocalDate issuedOn, LocalDate dueDate, String status,
                         BigDecimal original, BigDecimal discount, BigDecimal surcharge, BigDecimal received, BigDecimal outstanding) {}

    record PayableRow(UUID id, String description, String supplier, String categoryName, LocalDate registeredOn,
                      LocalDate dueDate, String status, BigDecimal amount, BigDecimal paid, BigDecimal outstanding) {}

    /** Receita emitida no período por origem ({@code WORK_ORDER} ou {@code MANUAL}), sem cancelados. */
    record RevenueTotals(String origin, long count, BigDecimal original, BigDecimal discount, BigDecimal surcharge) {}

    List<RevenueTotals> revenueByOrigin(LocalDate from, LocalDate to);

    List<AgingBucket> receivableAging(LocalDate today);

    List<CustomerDebt> receivableDebtors(LocalDate today, int limit);

    DueSummary receivableDue(LocalDate today);

    DueSummary payableDue(LocalDate today);

    /**
     * Despesas operacionais por categoria. Cadastrado = contas não canceladas criadas no período (data da oficina);
     * pago = pagamentos não estornados no período. Exclui as contas de compra de mercadoria para estoque
     * ({@code origin = 'PURCHASE'}): a peça entra no resultado como custo de peças quando é consumida na OS.
     */
    List<CategoryExpense> expensesByCategory(LocalDate from, LocalDate to);

    /** Compras de mercadoria para estoque no período, com o mesmo recorte de {@link #expensesByCategory}. */
    StockPurchases stockPurchases(LocalDate from, LocalDate to);

    /** Recebíveis não cancelados emitidos no período — o mesmo recorte do painel gerencial. */
    List<WorkOrderResult> workOrderResults(LocalDate from, LocalDate to, LocalDate today);

    /** Recebíveis com vencimento no período (todos os status, inclusive cancelados). */
    List<ReceivableRow> receivablesDueBetween(LocalDate from, LocalDate to, LocalDate today);

    /** Contas a pagar com vencimento no período (todos os status, inclusive canceladas). */
    List<PayableRow> payablesDueBetween(LocalDate from, LocalDate to, LocalDate today);
}
