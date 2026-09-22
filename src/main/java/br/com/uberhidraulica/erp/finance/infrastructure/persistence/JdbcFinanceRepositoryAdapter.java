package br.com.uberhidraulica.erp.finance.infrastructure.persistence;

import br.com.uberhidraulica.erp.finance.domain.ExpenseCategory;
import br.com.uberhidraulica.erp.finance.domain.FinancialStatus;
import br.com.uberhidraulica.erp.finance.domain.Payable;
import br.com.uberhidraulica.erp.finance.domain.PaymentMethod;
import br.com.uberhidraulica.erp.finance.domain.Receivable;
import br.com.uberhidraulica.erp.finance.domain.Settlement;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistência do Financeiro em SQL explícito: lançamentos só por INSERT, derivados calculados na consulta. */
@Component
class JdbcFinanceRepositoryAdapter implements FinanceRepositoryPort {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcFinanceRepositoryAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ================================================================ recebível

    @Override
    public Optional<Receivable> findReceivable(UUID id) {
        return loadReceivable("select * from finance.receivable where id = :id", Map.of("id", id));
    }

    @Override
    public Optional<Receivable> findReceivableByWorkOrder(UUID workOrderId) {
        return loadReceivable("select * from finance.receivable where work_order_id = :id", Map.of("id", workOrderId));
    }

    @Override
    public Optional<Receivable> lockReceivable(UUID id) {
        return loadReceivable("select * from finance.receivable where id = :id for update", Map.of("id", id));
    }

    @Override
    public Optional<Receivable> lockReceivableByWorkOrder(UUID workOrderId) {
        return loadReceivable("select * from finance.receivable where work_order_id = :id for update", Map.of("id", workOrderId));
    }

    private Optional<Receivable> loadReceivable(String sql, Map<String, ?> params) {
        return jdbc.query(sql, params, (rs, i) -> receivableRow(rs)).stream().findFirst().map(this::complete);
    }

    private record ReceivableRow(UUID id, UUID workOrderId, long number, UUID customerId, UUID quoteId, java.math.BigDecimal original,
                                 LocalDate issuedOn, LocalDate dueDate, Instant createdAt, UUID createdBy,
                                 Instant cancelledAt, UUID cancelledBy, String cancellationReason) {}

    private static ReceivableRow receivableRow(ResultSet rs) throws SQLException {
        return new ReceivableRow(uuid(rs, "id"), uuid(rs, "work_order_id"), rs.getLong("work_order_number"), uuid(rs, "customer_id"),
                uuid(rs, "billing_quote_id"), rs.getBigDecimal("original_amount"), date(rs, "issued_on"), date(rs, "due_date"),
                instant(rs, "created_at"), uuid(rs, "created_by"), instant(rs, "cancelled_at"), uuid(rs, "cancelled_by"),
                rs.getString("cancellation_reason"));
    }

    private Receivable complete(ReceivableRow row) {
        Map<String, UUID> id = Map.of("id", row.id());
        List<Receivable.Line> lines = jdbc.query("select * from finance.receivable_line where receivable_id = :id order by display_order", id,
                (rs, i) -> new Receivable.Line(uuid(rs, "id"), uuid(rs, "quote_item_revision_id"), rs.getString("description"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("discount_amount"),
                        rs.getBigDecimal("total_amount"), rs.getInt("display_order")));
        List<Receivable.Adjustment> adjustments = jdbc.query(
                "select a.*, v.id as reversal_id, v.reason as reversal_reason, v.reversed_at, v.reversed_by, v.idempotency_key as reversal_key"
                        + " from finance.receivable_adjustment a left join finance.receivable_adjustment_reversal v on v.adjustment_id = a.id"
                        + " where a.receivable_id = :id order by a.recorded_at, a.id", id,
                (rs, i) -> new Receivable.Adjustment(uuid(rs, "id"), Receivable.AdjustmentType.valueOf(rs.getString("adjustment_type")),
                        rs.getBigDecimal("amount"), rs.getString("reason"), instant(rs, "recorded_at"), uuid(rs, "recorded_by"),
                        rs.getString("idempotency_key"), uuid(rs, "reversal_id") == null ? null : new Settlement.Reversal(uuid(rs, "reversal_id"),
                        rs.getString("reversal_reason"), instant(rs, "reversed_at"), uuid(rs, "reversed_by"), rs.getString("reversal_key"))));
        List<Receivable.DueDateChange> changes = jdbc.query(
                "select * from finance.receivable_due_date_change where receivable_id = :id order by changed_at, id", id,
                (rs, i) -> new Receivable.DueDateChange(uuid(rs, "id"), date(rs, "previous_due_date"), date(rs, "new_due_date"),
                        rs.getString("reason"), instant(rs, "changed_at"), uuid(rs, "changed_by"), rs.getString("idempotency_key")));
        List<Settlement> receipts = jdbc.query(RECEIPTS + " where p.receivable_id = :id order by p.recorded_at, p.id", id, RECEIPT);
        return new Receivable(row.id(), row.workOrderId(), row.number(), row.customerId(), row.quoteId(), row.original(), row.issuedOn(),
                row.dueDate(), row.createdAt(), row.createdBy(), row.cancelledAt(), row.cancelledBy(), row.cancellationReason(),
                lines, adjustments, changes, receipts);
    }

    @Override
    public void insertReceivable(Receivable r) {
        jdbc.update("insert into finance.receivable (id, work_order_id, work_order_number, customer_id, billing_quote_id, original_amount,"
                + " issued_on, due_date, created_at, created_by) values (:id, :workOrder, :number, :customer, :quote, :original,"
                + " :issuedOn, :dueDate, :createdAt, :createdBy)", new MapSqlParameterSource()
                .addValue("id", r.id()).addValue("workOrder", r.workOrderId()).addValue("number", r.workOrderNumber())
                .addValue("customer", r.customerId()).addValue("quote", r.billingQuoteId()).addValue("original", r.originalAmount())
                .addValue("issuedOn", Date.valueOf(r.issuedOn())).addValue("dueDate", Date.valueOf(r.dueDate()))
                .addValue("createdAt", Timestamp.from(r.createdAt())).addValue("createdBy", r.createdBy()));
        for (Receivable.Line line : r.lines())
            jdbc.update("insert into finance.receivable_line (id, receivable_id, quote_id, quote_item_revision_id, decision_type, description,"
                    + " quantity, unit_price, discount_amount, total_amount, display_order) values (:id, :receivable, :quote, :revision,"
                    + " 'APPROVE', :description, :quantity, :unitPrice, :discount, :total, :order)", new MapSqlParameterSource()
                    .addValue("id", line.id()).addValue("receivable", r.id()).addValue("quote", r.billingQuoteId())
                    .addValue("revision", line.quoteItemRevisionId()).addValue("description", line.description())
                    .addValue("quantity", line.quantity()).addValue("unitPrice", line.unitPrice()).addValue("discount", line.discountAmount())
                    .addValue("total", line.totalAmount()).addValue("order", line.displayOrder()));
    }

    @Override
    public void cancelReceivable(UUID id, Instant at, UUID by, String reason) {
        jdbc.update("update finance.receivable set cancelled_at = :at, cancelled_by = :by, cancellation_reason = :reason"
                + " where id = :id and cancelled_at is null", new MapSqlParameterSource().addValue("id", id)
                .addValue("at", Timestamp.from(at)).addValue("by", by).addValue("reason", reason));
    }

    @Override
    public void insertAdjustment(UUID receivableId, Receivable.Adjustment a) {
        jdbc.update("insert into finance.receivable_adjustment (id, receivable_id, adjustment_type, amount, reason, recorded_at, recorded_by,"
                + " idempotency_key) values (:id, :receivable, :type, :amount, :reason, :at, :by, :key)", new MapSqlParameterSource()
                .addValue("id", a.id()).addValue("key", a.idempotencyKey())
                .addValue("receivable", receivableId).addValue("type", a.type().name()).addValue("amount", a.amount())
                .addValue("reason", a.reason()).addValue("at", Timestamp.from(a.recordedAt())).addValue("by", a.recordedBy()));
    }

    @Override
    public void changeDueDate(UUID receivableId, Receivable.DueDateChange c) {
        jdbc.update("insert into finance.receivable_due_date_change (id, receivable_id, previous_due_date, new_due_date, reason, changed_at,"
                + " changed_by, idempotency_key) values (:id, :receivable, :previous, :next, :reason, :at, :by, :key)", new MapSqlParameterSource()
                .addValue("id", c.id()).addValue("key", c.idempotencyKey())
                .addValue("receivable", receivableId).addValue("previous", Date.valueOf(c.previousDueDate()))
                .addValue("next", Date.valueOf(c.newDueDate())).addValue("reason", c.reason())
                .addValue("at", Timestamp.from(c.changedAt())).addValue("by", c.changedBy()));
        jdbc.update("update finance.receivable set due_date = :next where id = :id",
                new MapSqlParameterSource().addValue("next", Date.valueOf(c.newDueDate())).addValue("id", receivableId));
    }

    private static final String RECEIPTS = "select p.id, p.receivable_id as owner_id, p.amount, p.payment_method_id, p.payment_method_name,"
            + " p.received_on as effective_on, p.notes, p.recorded_at, p.recorded_by, p.idempotency_key,"
            + " v.id as reversal_id, v.reason as reversal_reason, v.reversed_at, v.reversed_by, v.idempotency_key as reversal_key"
            + " from finance.receipt p left join finance.receipt_reversal v on v.receipt_id = p.id";

    private static final String PAYMENTS = "select p.id, p.payable_id as owner_id, p.amount, p.payment_method_id, p.payment_method_name,"
            + " p.paid_on as effective_on, p.notes, p.recorded_at, p.recorded_by, p.idempotency_key,"
            + " v.id as reversal_id, v.reason as reversal_reason, v.reversed_at, v.reversed_by, v.idempotency_key as reversal_key"
            + " from finance.payable_payment p left join finance.payable_payment_reversal v on v.payment_id = p.id";

    private static final RowMapper<Settlement> RECEIPT = (rs, i) -> settlement(rs);

    private static Settlement settlement(ResultSet rs) throws SQLException {
        UUID reversalId = uuid(rs, "reversal_id");
        Settlement.Reversal reversal = reversalId == null ? null : new Settlement.Reversal(reversalId, rs.getString("reversal_reason"),
                instant(rs, "reversed_at"), uuid(rs, "reversed_by"), rs.getString("reversal_key"));
        return new Settlement(uuid(rs, "id"), uuid(rs, "owner_id"), rs.getBigDecimal("amount"), uuid(rs, "payment_method_id"),
                rs.getString("payment_method_name"), date(rs, "effective_on"), rs.getString("notes"), instant(rs, "recorded_at"),
                uuid(rs, "recorded_by"), rs.getString("idempotency_key"), reversal);
    }

    @Override
    public void insertReceipt(Settlement s) {
        jdbc.update("insert into finance.receipt (id, receivable_id, amount, payment_method_id, payment_method_name, received_on, notes,"
                + " recorded_at, recorded_by, idempotency_key) values (:id, :owner, :amount, :method, :methodName, :on, :notes, :at, :by, :key)",
                settlementParams(s));
    }

    @Override
    public Optional<Settlement> findReceipt(UUID id) {
        return jdbc.query(RECEIPTS + " where p.id = :id", Map.of("id", id), RECEIPT).stream().findFirst();
    }

    @Override
    public Optional<Settlement> findReceiptByKey(String key) {
        return jdbc.query(RECEIPTS + " where p.idempotency_key = :key", Map.of("key", key), RECEIPT).stream().findFirst();
    }

    @Override
    public void insertReceiptReversal(UUID receiptId, Settlement.Reversal r) {
        jdbc.update("insert into finance.receipt_reversal (id, receipt_id, reason, reversed_at, reversed_by, idempotency_key)"
                + " values (:id, :target, :reason, :at, :by, :key)", reversalParams(receiptId, r));
    }

    @Override
    public Optional<UUID> receiptReversedWithKey(String key) {
        return jdbc.query("select receipt_id from finance.receipt_reversal where idempotency_key = :key", Map.of("key", key),
                (rs, i) -> uuid(rs, "receipt_id")).stream().findFirst();
    }

    /** Totais derivados por recebível, calculados a partir dos lançamentos a cada consulta. */
    private static final String RECEIVABLE_TOTALS = "with totals as (select r.*,"
            + " coalesce((select sum(a.amount) from finance.receivable_adjustment a where a.receivable_id = r.id and a.adjustment_type = 'DISCOUNT'"
            + "   and not exists (select 1 from finance.receivable_adjustment_reversal x where x.adjustment_id = a.id)), 0) as discount_amount,"
            + " coalesce((select sum(a.amount) from finance.receivable_adjustment a where a.receivable_id = r.id and a.adjustment_type = 'SURCHARGE'"
            + "   and not exists (select 1 from finance.receivable_adjustment_reversal x where x.adjustment_id = a.id)), 0) as surcharge_amount,"
            + " coalesce((select sum(p.amount) from finance.receipt p where p.receivable_id = r.id"
            + "   and not exists (select 1 from finance.receipt_reversal v where v.receipt_id = p.id)), 0) as settled_amount"
            + " from finance.receivable r),"
            + " balances as (select t.*, t.original_amount - t.discount_amount + t.surcharge_amount - t.settled_amount as outstanding from totals t),"
            + " classified as (select b.*, case when b.cancelled_at is not null then 'CANCELADO' when b.outstanding = 0 then 'QUITADO'"
            + "   when b.due_date < :today then 'VENCIDO' when b.settled_amount > 0 then 'PARCIAL' else 'ABERTO' end as status from balances b)";

    @Override
    public PageResult<ReceivableSummary> listReceivables(ReceivableFilter filter, LocalDate today, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("today", Date.valueOf(today));
        List<String> where = new ArrayList<>();
        if (filter.status() != null) { where.add("status = :status"); params.addValue("status", filter.status().name()); }
        if (filter.customerId() != null) { where.add("customer_id = :customer"); params.addValue("customer", filter.customerId()); }
        if (filter.workOrderNumber() != null) { where.add("work_order_number = :number"); params.addValue("number", filter.workOrderNumber()); }
        if (filter.dueFrom() != null) { where.add("due_date >= :dueFrom"); params.addValue("dueFrom", Date.valueOf(filter.dueFrom())); }
        if (filter.dueTo() != null) { where.add("due_date <= :dueTo"); params.addValue("dueTo", Date.valueOf(filter.dueTo())); }
        String condition = where.isEmpty() ? "" : " where " + String.join(" and ", where);
        long total = jdbc.queryForObject(RECEIVABLE_TOTALS + " select count(*) from classified" + condition, params, Long.class);
        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<ReceivableSummary> items = jdbc.query(RECEIVABLE_TOTALS + " select * from classified" + condition
                + " order by due_date, work_order_number limit :limit offset :offset", params, (rs, i) -> new ReceivableSummary(
                uuid(rs, "id"), uuid(rs, "work_order_id"), rs.getLong("work_order_number"), uuid(rs, "customer_id"),
                rs.getBigDecimal("original_amount"), rs.getBigDecimal("discount_amount"), rs.getBigDecimal("surcharge_amount"),
                rs.getBigDecimal("settled_amount"), rs.getBigDecimal("outstanding"), date(rs, "issued_on"), date(rs, "due_date"),
                FinancialStatus.valueOf(rs.getString("status"))));
        return new PageResult<>(items, total, page, size);
    }

    @Override
    public Optional<UUID> receivableAdjustedWithKey(String key) {
        return jdbc.query("select receivable_id from finance.receivable_adjustment where idempotency_key = :key", Map.of("key", key),
                (rs, i) -> uuid(rs, "receivable_id")).stream().findFirst();
    }

    @Override
    public Optional<UUID> receivableOfAdjustment(UUID adjustmentId) {
        return jdbc.query("select receivable_id from finance.receivable_adjustment where id = :id", Map.of("id", adjustmentId),
                (rs, i) -> uuid(rs, "receivable_id")).stream().findFirst();
    }

    @Override
    public void insertAdjustmentReversal(UUID adjustmentId, Settlement.Reversal r) {
        jdbc.update("insert into finance.receivable_adjustment_reversal (id, adjustment_id, reason, reversed_at, reversed_by, idempotency_key)"
                + " values (:id, :target, :reason, :at, :by, :key)", reversalParams(adjustmentId, r));
    }

    @Override
    public Optional<UUID> adjustmentReversedWithKey(String key) {
        return jdbc.query("select adjustment_id from finance.receivable_adjustment_reversal where idempotency_key = :key", Map.of("key", key),
                (rs, i) -> uuid(rs, "adjustment_id")).stream().findFirst();
    }

    @Override
    public Optional<DueDateChangeRef> dueDateChangedWithKey(String key) {
        return jdbc.query("select receivable_id, new_due_date from finance.receivable_due_date_change where idempotency_key = :key",
                Map.of("key", key), (rs, i) -> new DueDateChangeRef(uuid(rs, "receivable_id"), date(rs, "new_due_date"))).stream().findFirst();
    }

    @Override
    public void lockIdempotencyKey(String key) {
        // Serializa requisições concorrentes com a mesma chave; a segunda passa a enxergar o que a primeira gravou.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(:key, 0))", Map.of("key", key), rs -> null);
    }

    // ================================================================ contas a pagar

    private static final String PAYABLE = "select p.*, c.name as category_name from finance.payable p"
            + " join finance.expense_category c on c.id = p.category_id";

    @Override
    public Optional<Payable> findPayable(UUID id) { return loadPayable(PAYABLE + " where p.id = :id", Map.of("id", id)); }

    @Override
    public Optional<Payable> lockPayable(UUID id) { return loadPayable(PAYABLE + " where p.id = :id for update of p", Map.of("id", id)); }

    @Override
    public Optional<Payable> findPayableByKey(String key) {
        return loadPayable(PAYABLE + " where p.idempotency_key = :key", Map.of("key", key));
    }

    private Optional<Payable> loadPayable(String sql, Map<String, ?> params) {
        return jdbc.query(sql, params, (rs, i) -> new Payable(uuid(rs, "id"), rs.getString("description"), rs.getString("supplier"),
                        uuid(rs, "category_id"), rs.getString("category_name"), rs.getBigDecimal("amount"), date(rs, "due_date"),
                        rs.getString("notes"), instant(rs, "created_at"), uuid(rs, "created_by"), rs.getString("idempotency_key"),
                        instant(rs, "cancelled_at"), uuid(rs, "cancelled_by"), rs.getString("cancellation_reason"), List.of()))
                .stream().findFirst()
                .map(payable -> new Payable(payable.id(), payable.description(), payable.supplier(), payable.categoryId(),
                        payable.categoryName(), payable.amount(), payable.dueDate(), payable.notes(), payable.createdAt(), payable.createdBy(),
                        payable.idempotencyKey(), payable.cancelledAt(), payable.cancelledBy(), payable.cancellationReason(),
                        jdbc.query(PAYMENTS + " where p.payable_id = :id order by p.recorded_at, p.id", Map.of("id", payable.id()), RECEIPT)));
    }

    @Override
    public void insertPayable(Payable p) {
        jdbc.update("insert into finance.payable (id, description, supplier, category_id, amount, due_date, notes, created_at, created_by,"
                + " idempotency_key) values (:id, :description, :supplier, :category, :amount, :dueDate, :notes, :at, :by, :key)",
                new MapSqlParameterSource().addValue("id", p.id()).addValue("description", p.description()).addValue("supplier", p.supplier())
                        .addValue("category", p.categoryId()).addValue("amount", p.amount()).addValue("dueDate", Date.valueOf(p.dueDate()))
                        .addValue("notes", p.notes()).addValue("at", Timestamp.from(p.createdAt())).addValue("by", p.createdBy())
                        .addValue("key", p.idempotencyKey()));
    }

    @Override
    public void cancelPayable(UUID id, Instant at, UUID by, String reason) {
        jdbc.update("update finance.payable set cancelled_at = :at, cancelled_by = :by, cancellation_reason = :reason"
                + " where id = :id and cancelled_at is null", new MapSqlParameterSource().addValue("id", id)
                .addValue("at", Timestamp.from(at)).addValue("by", by).addValue("reason", reason));
    }

    @Override
    public void insertPayment(Settlement s) {
        jdbc.update("insert into finance.payable_payment (id, payable_id, amount, payment_method_id, payment_method_name, paid_on, notes,"
                + " recorded_at, recorded_by, idempotency_key) values (:id, :owner, :amount, :method, :methodName, :on, :notes, :at, :by, :key)",
                settlementParams(s));
    }

    @Override
    public Optional<Settlement> findPayment(UUID id) {
        return jdbc.query(PAYMENTS + " where p.id = :id", Map.of("id", id), RECEIPT).stream().findFirst();
    }

    @Override
    public Optional<Settlement> findPaymentByKey(String key) {
        return jdbc.query(PAYMENTS + " where p.idempotency_key = :key", Map.of("key", key), RECEIPT).stream().findFirst();
    }

    @Override
    public void insertPaymentReversal(UUID paymentId, Settlement.Reversal r) {
        jdbc.update("insert into finance.payable_payment_reversal (id, payment_id, reason, reversed_at, reversed_by, idempotency_key)"
                + " values (:id, :target, :reason, :at, :by, :key)", reversalParams(paymentId, r));
    }

    @Override
    public Optional<UUID> paymentReversedWithKey(String key) {
        return jdbc.query("select payment_id from finance.payable_payment_reversal where idempotency_key = :key", Map.of("key", key),
                (rs, i) -> uuid(rs, "payment_id")).stream().findFirst();
    }

    private static final String PAYABLE_TOTALS = "with totals as (select p.*, c.name as category_name,"
            + " coalesce((select sum(x.amount) from finance.payable_payment x where x.payable_id = p.id"
            + "   and not exists (select 1 from finance.payable_payment_reversal v where v.payment_id = x.id)), 0) as settled_amount"
            + " from finance.payable p join finance.expense_category c on c.id = p.category_id),"
            + " balances as (select t.*, t.amount - t.settled_amount as outstanding from totals t),"
            + " classified as (select b.*, case when b.cancelled_at is not null then 'CANCELADO' when b.outstanding = 0 then 'QUITADO'"
            + "   when b.due_date < :today then 'VENCIDO' when b.settled_amount > 0 then 'PARCIAL' else 'ABERTO' end as status from balances b)";

    @Override
    public PageResult<PayableSummary> listPayables(PayableFilter filter, LocalDate today, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("today", Date.valueOf(today));
        List<String> where = new ArrayList<>();
        if (filter.status() != null) { where.add("status = :status"); params.addValue("status", filter.status().name()); }
        if (filter.categoryId() != null) { where.add("category_id = :category"); params.addValue("category", filter.categoryId()); }
        if (filter.dueFrom() != null) { where.add("due_date >= :dueFrom"); params.addValue("dueFrom", Date.valueOf(filter.dueFrom())); }
        if (filter.dueTo() != null) { where.add("due_date <= :dueTo"); params.addValue("dueTo", Date.valueOf(filter.dueTo())); }
        String condition = where.isEmpty() ? "" : " where " + String.join(" and ", where);
        long total = jdbc.queryForObject(PAYABLE_TOTALS + " select count(*) from classified" + condition, params, Long.class);
        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<PayableSummary> items = jdbc.query(PAYABLE_TOTALS + " select * from classified" + condition
                + " order by due_date, created_at limit :limit offset :offset", params, (rs, i) -> new PayableSummary(uuid(rs, "id"),
                rs.getString("description"), rs.getString("supplier"), uuid(rs, "category_id"), rs.getString("category_name"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("settled_amount"), rs.getBigDecimal("outstanding"), date(rs, "due_date"),
                FinancialStatus.valueOf(rs.getString("status"))));
        return new PageResult<>(items, total, page, size);
    }

    // ================================================================ configuração

    private static final RowMapper<PaymentMethod> METHOD = (rs, i) -> new PaymentMethod(uuid(rs, "id"), rs.getString("code"),
            rs.getString("name"), rs.getBoolean("active"), rs.getBoolean("cash_session_required"), instant(rs, "created_at"),
            instant(rs, "updated_at"));

    @Override
    public List<PaymentMethod> paymentMethods() {
        return jdbc.query("select * from finance.payment_method order by name", Map.of(), METHOD);
    }

    @Override
    public Optional<PaymentMethod> paymentMethod(UUID id) {
        return jdbc.query("select * from finance.payment_method where id = :id", Map.of("id", id), METHOD).stream().findFirst();
    }

    @Override
    public void savePaymentMethod(PaymentMethod m, boolean isNew) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", m.id()).addValue("code", m.code())
                .addValue("name", m.name()).addValue("active", m.active()).addValue("cash", m.cashSessionRequired())
                .addValue("created", Timestamp.from(m.createdAt())).addValue("updated", Timestamp.from(m.updatedAt()));
        if (isNew)
            jdbc.update("insert into finance.payment_method (id, code, name, active, cash_session_required, created_at, updated_at)"
                    + " values (:id, :code, :name, :active, :cash, :created, :updated)", params);
        else
            jdbc.update("update finance.payment_method set name = :name, active = :active, updated_at = :updated where id = :id", params);
    }

    private static final RowMapper<ExpenseCategory> CATEGORY = (rs, i) -> new ExpenseCategory(uuid(rs, "id"), rs.getString("name"),
            rs.getBoolean("active"), instant(rs, "created_at"), instant(rs, "updated_at"));

    @Override
    public List<ExpenseCategory> expenseCategories() {
        return jdbc.query("select * from finance.expense_category order by name", Map.of(), CATEGORY);
    }

    @Override
    public Optional<ExpenseCategory> expenseCategory(UUID id) {
        return jdbc.query("select * from finance.expense_category where id = :id", Map.of("id", id), CATEGORY).stream().findFirst();
    }

    @Override
    public void saveExpenseCategory(ExpenseCategory c, boolean isNew) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", c.id()).addValue("name", c.name())
                .addValue("active", c.active()).addValue("created", Timestamp.from(c.createdAt())).addValue("updated", Timestamp.from(c.updatedAt()));
        if (isNew)
            jdbc.update("insert into finance.expense_category (id, name, active, created_at, updated_at)"
                    + " values (:id, :name, :active, :created, :updated)", params);
        else
            jdbc.update("update finance.expense_category set name = :name, active = :active, updated_at = :updated where id = :id", params);
    }

    @Override
    public int defaultReceivableDueDays() {
        return Integer.parseInt(jdbc.queryForObject("select value from finance.settings where key = 'DEFAULT_RECEIVABLE_DUE_DAYS'",
                Map.of(), String.class));
    }

    @Override
    public void saveDefaultReceivableDueDays(int days, Instant at, UUID by) {
        jdbc.update("update finance.settings set value = :value, updated_at = :at, updated_by = :by where key = 'DEFAULT_RECEIVABLE_DUE_DAYS'",
                new MapSqlParameterSource().addValue("value", String.valueOf(days)).addValue("at", Timestamp.from(at)).addValue("by", by));
    }

    // ================================================================ fluxo de caixa

    @Override
    public List<DailyAmount> realizedInflows(LocalDate from, LocalDate to) {
        return daily("select p.received_on as day, sum(p.amount) as amount from finance.receipt p"
                + " where p.received_on between :from and :to"
                + " and not exists (select 1 from finance.receipt_reversal v where v.receipt_id = p.id)"
                + " group by p.received_on order by p.received_on", period(from, to));
    }

    @Override
    public List<DailyAmount> realizedOutflows(LocalDate from, LocalDate to, UUID categoryId) {
        MapSqlParameterSource params = period(from, to).addValue("category", categoryId);
        return daily("select x.paid_on as day, sum(x.amount) as amount from finance.payable_payment x"
                + " join finance.payable p on p.id = x.payable_id"
                + " where x.paid_on between :from and :to"
                + " and not exists (select 1 from finance.payable_payment_reversal v where v.payment_id = x.id)"
                + (categoryId == null ? "" : " and p.category_id = :category")
                + " group by x.paid_on order by x.paid_on", params);
    }

    @Override
    public List<DailyAmount> forecastInflows(LocalDate from, LocalDate to) {
        return daily(RECEIVABLE_TOTALS + " select due_date as day, sum(outstanding) as amount from classified"
                + " where cancelled_at is null and outstanding > 0 and due_date between :from and :to"
                + " group by due_date order by due_date", period(from, to).addValue("today", Date.valueOf(from)));
    }

    @Override
    public List<DailyAmount> forecastOutflows(LocalDate from, LocalDate to, UUID categoryId) {
        MapSqlParameterSource params = period(from, to).addValue("today", Date.valueOf(from)).addValue("category", categoryId);
        return daily(PAYABLE_TOTALS + " select due_date as day, sum(outstanding) as amount from classified"
                + " where cancelled_at is null and outstanding > 0 and due_date between :from and :to"
                + (categoryId == null ? "" : " and category_id = :category")
                + " group by due_date order by due_date", params);
    }

    private List<DailyAmount> daily(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql, params, (rs, i) -> new DailyAmount(date(rs, "day"), rs.getBigDecimal("amount")));
    }

    private static MapSqlParameterSource period(LocalDate from, LocalDate to) {
        return new MapSqlParameterSource().addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to));
    }

    // ================================================================ apoio

    private static MapSqlParameterSource settlementParams(Settlement s) {
        return new MapSqlParameterSource().addValue("id", s.id()).addValue("owner", s.ownerId()).addValue("amount", s.amount())
                .addValue("method", s.paymentMethodId()).addValue("methodName", s.paymentMethodName())
                .addValue("on", Date.valueOf(s.effectiveOn())).addValue("notes", s.notes())
                .addValue("at", Timestamp.from(s.recordedAt())).addValue("by", s.recordedBy()).addValue("key", s.idempotencyKey());
    }

    private static MapSqlParameterSource reversalParams(UUID target, Settlement.Reversal r) {
        return new MapSqlParameterSource().addValue("id", r.id()).addValue("target", target).addValue("reason", r.reason())
                .addValue("at", Timestamp.from(r.reversedAt())).addValue("by", r.reversedBy()).addValue("key", r.idempotencyKey());
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException { return rs.getObject(column, UUID.class); }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
