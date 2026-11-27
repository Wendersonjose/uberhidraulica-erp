package br.com.uberhidraulica.erp.finance.infrastructure.persistence;

import br.com.uberhidraulica.erp.finance.PurchasePayables;
import br.com.uberhidraulica.erp.finance.domain.ExpenseCategory;
import br.com.uberhidraulica.erp.finance.domain.FinancialStatus;
import br.com.uberhidraulica.erp.finance.domain.LateCharge;
import br.com.uberhidraulica.erp.finance.domain.PaymentMethodFee;
import br.com.uberhidraulica.erp.finance.domain.Payable;
import br.com.uberhidraulica.erp.finance.domain.PaymentMethod;
import br.com.uberhidraulica.erp.finance.domain.Receivable;
import br.com.uberhidraulica.erp.finance.domain.Settlement;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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

    private static final String RECEIVABLE = "select r.*, c.name as revenue_category_name from finance.receivable r"
            + " left join finance.revenue_category c on c.id = r.revenue_category_id";

    @Override
    public Optional<Receivable> findReceivable(UUID id) {
        return loadReceivable(RECEIVABLE + " where r.id = :id", Map.of("id", id));
    }

    @Override
    public Optional<Receivable> findReceivableByWorkOrder(UUID workOrderId) {
        return loadReceivable(RECEIVABLE + " where r.work_order_id = :id", Map.of("id", workOrderId));
    }

    @Override
    public Optional<Receivable> lockReceivable(UUID id) {
        return loadReceivable(RECEIVABLE + " where r.id = :id for update of r", Map.of("id", id));
    }

    @Override
    public Optional<Receivable> lockReceivableByWorkOrder(UUID workOrderId) {
        return loadReceivable(RECEIVABLE + " where r.work_order_id = :id for update of r", Map.of("id", workOrderId));
    }

    @Override
    public Optional<Receivable> findReceivableByKey(String key) {
        return loadReceivable(RECEIVABLE + " where r.idempotency_key = :key", Map.of("key", key));
    }

    private Optional<Receivable> loadReceivable(String sql, Map<String, ?> params) {
        return jdbc.query(sql, params, (rs, i) -> receivableRow(rs)).stream().findFirst().map(this::complete);
    }

    private record ReceivableRow(UUID id, Receivable.Origin origin, UUID workOrderId, Long number, UUID customerId, UUID quoteId,
                                 String description, UUID revenueCategoryId, String revenueCategoryName, String notes, String key,
                                 java.math.BigDecimal original, LocalDate issuedOn, LocalDate dueDate, Instant createdAt, UUID createdBy,
                                 Instant cancelledAt, UUID cancelledBy, String cancellationReason) {}

    private static ReceivableRow receivableRow(ResultSet rs) throws SQLException {
        long number = rs.getLong("work_order_number");
        Long workOrderNumber = rs.wasNull() ? null : number;
        return new ReceivableRow(uuid(rs, "id"), Receivable.Origin.valueOf(rs.getString("origin")), uuid(rs, "work_order_id"),
                workOrderNumber, uuid(rs, "customer_id"), uuid(rs, "billing_quote_id"), rs.getString("description"),
                uuid(rs, "revenue_category_id"), rs.getString("revenue_category_name"), rs.getString("notes"), rs.getString("idempotency_key"),
                rs.getBigDecimal("original_amount"), date(rs, "issued_on"), date(rs, "due_date"),
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
                        rs.getString("reversal_reason"), instant(rs, "reversed_at"), uuid(rs, "reversed_by"), rs.getString("reversal_key")),
                        Receivable.AdjustmentOrigin.valueOf(rs.getString("origin"))));
        List<Receivable.DueDateChange> changes = jdbc.query(
                "select * from finance.receivable_due_date_change where receivable_id = :id order by changed_at, id", id,
                (rs, i) -> new Receivable.DueDateChange(uuid(rs, "id"), date(rs, "previous_due_date"), date(rs, "new_due_date"),
                        rs.getString("reason"), instant(rs, "changed_at"), uuid(rs, "changed_by"), rs.getString("idempotency_key"),
                        (Integer) rs.getObject("installment_number")));
        List<Settlement> receipts = jdbc.query(RECEIPTS + " where p.receivable_id = :id order by p.recorded_at, p.id", id, RECEIPT);
        Receivable.InstallmentPlan plan = jdbc.query("select * from finance.receivable_installment_plan where receivable_id = :id"
                        + " order by version desc limit 1", id,
                (rs, i) -> new Receivable.InstallmentPlan(rs.getInt("version"), rs.getString("reason"), instant(rs, "created_at"),
                        uuid(rs, "created_by"), installments(row.id(), rs.getInt("version"))))
                .stream().findFirst().orElse(null);
        return new Receivable(row.id(), row.origin(), row.workOrderId(), row.number(), row.customerId(), row.quoteId(), row.description(),
                row.revenueCategoryId(), row.revenueCategoryName(), row.notes(), row.key(), row.original(), row.issuedOn(),
                row.dueDate(), row.createdAt(), row.createdBy(), row.cancelledAt(), row.cancelledBy(), row.cancellationReason(),
                lines, adjustments, changes, receipts, plan);
    }

    private List<Receivable.Installment> installments(UUID receivableId, int version) {
        return jdbc.query("select * from finance.receivable_installment where receivable_id = :id and plan_version = :version order by number",
                new MapSqlParameterSource("id", receivableId).addValue("version", version),
                (rs, i) -> new Receivable.Installment(uuid(rs, "id"), rs.getInt("number"), rs.getBigDecimal("amount"), date(rs, "due_date")));
    }

    @Override
    public void insertReceivable(Receivable r) {
        jdbc.update("insert into finance.receivable (id, origin, work_order_id, work_order_number, customer_id, billing_quote_id,"
                + " description, revenue_category_id, notes, idempotency_key, original_amount,"
                + " issued_on, due_date, created_at, created_by) values (:id, :origin, :workOrder, :number, :customer, :quote,"
                + " :description, :category, :notes, :key, :original, :issuedOn, :dueDate, :createdAt, :createdBy)", new MapSqlParameterSource()
                .addValue("id", r.id()).addValue("origin", r.origin().name()).addValue("workOrder", r.workOrderId())
                .addValue("number", r.workOrderNumber()).addValue("description", r.description()).addValue("category", r.revenueCategoryId())
                .addValue("notes", r.notes()).addValue("key", r.idempotencyKey())
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
    public void insertInstallmentPlan(UUID receivableId, Receivable.InstallmentPlan plan, String key) {
        jdbc.update("insert into finance.receivable_installment_plan (receivable_id, version, reason, created_at, created_by, idempotency_key)"
                + " values (:receivable, :version, :reason, :at, :by, :key)", new MapSqlParameterSource()
                .addValue("receivable", receivableId).addValue("version", plan.version()).addValue("reason", plan.reason())
                .addValue("at", Timestamp.from(plan.createdAt())).addValue("by", plan.createdBy()).addValue("key", key));
        for (Receivable.Installment installment : plan.installments())
            jdbc.update("insert into finance.receivable_installment (id, receivable_id, plan_version, number, amount, due_date)"
                    + " values (:id, :receivable, :version, :number, :amount, :due)", new MapSqlParameterSource()
                    .addValue("id", installment.id() == null ? UUID.randomUUID() : installment.id()).addValue("receivable", receivableId)
                    .addValue("version", plan.version()).addValue("number", installment.number()).addValue("amount", installment.amount())
                    .addValue("due", Date.valueOf(installment.dueDate())));
        jdbc.update("update finance.receivable set due_date = :due where id = :id", new MapSqlParameterSource("id", receivableId)
                .addValue("due", Date.valueOf(plan.installments().get(0).dueDate())));
    }

    @Override
    public Optional<KeyedPlan> installmentPlanWithKey(String key) {
        return jdbc.query("select receivable_id, version from finance.receivable_installment_plan where idempotency_key = :key",
                Map.of("key", key), (rs, i) -> new KeyedPlan(uuid(rs, "receivable_id"), rs.getInt("version"))).stream().findFirst();
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
                + " idempotency_key, origin) values (:id, :receivable, :type, :amount, :reason, :at, :by, :key, :origin)", new MapSqlParameterSource()
                .addValue("id", a.id()).addValue("key", a.idempotencyKey()).addValue("origin", a.origin().name())
                .addValue("receivable", receivableId).addValue("type", a.type().name()).addValue("amount", a.amount())
                .addValue("reason", a.reason()).addValue("at", Timestamp.from(a.recordedAt())).addValue("by", a.recordedBy()));
    }

    @Override
    public void changeDueDate(UUID receivableId, Receivable.DueDateChange c, int planVersion) {
        jdbc.update("insert into finance.receivable_due_date_change (id, receivable_id, previous_due_date, new_due_date, reason, changed_at,"
                + " changed_by, idempotency_key, installment_number) values (:id, :receivable, :previous, :next, :reason, :at, :by, :key, :number)",
                new MapSqlParameterSource()
                .addValue("id", c.id()).addValue("key", c.idempotencyKey()).addValue("number", c.installmentNumber())
                .addValue("receivable", receivableId).addValue("previous", Date.valueOf(c.previousDueDate()))
                .addValue("next", Date.valueOf(c.newDueDate())).addValue("reason", c.reason())
                .addValue("at", Timestamp.from(c.changedAt())).addValue("by", c.changedBy()));
        int number = c.installmentNumber() == null ? 1 : c.installmentNumber();
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("next", Date.valueOf(c.newDueDate())).addValue("id", receivableId)
                .addValue("version", planVersion).addValue("number", number);
        if (planVersion > 0)
            jdbc.update("update finance.receivable_installment set due_date = :next where receivable_id = :id and plan_version = :version"
                    + " and number = :number", params);
        if (planVersion == 0 || number == 1)
            jdbc.update("update finance.receivable set due_date = :next where id = :id", params);
    }

    private static final String RECEIPTS = "select p.id, p.receivable_id as owner_id, p.amount, p.payment_method_id, p.payment_method_name,"
            + " p.received_on as effective_on, p.notes, p.recorded_at, p.recorded_by, p.idempotency_key,"
            + " p.card_installments, p.fee_percent, p.fee_amount, p.expected_settlement_on,"
            + " v.id as reversal_id, v.reason as reversal_reason, v.reversed_at, v.reversed_by, v.idempotency_key as reversal_key"
            + " from finance.receipt p left join finance.receipt_reversal v on v.receipt_id = p.id";

    private static final String PAYMENTS = "select p.id, p.payable_id as owner_id, p.amount, p.payment_method_id, p.payment_method_name,"
            + " p.paid_on as effective_on, p.notes, p.recorded_at, p.recorded_by, p.idempotency_key,"
            + " null::integer as card_installments, null::numeric as fee_percent, null::numeric as fee_amount,"
            + " null::date as expected_settlement_on,"
            + " v.id as reversal_id, v.reason as reversal_reason, v.reversed_at, v.reversed_by, v.idempotency_key as reversal_key"
            + " from finance.payable_payment p left join finance.payable_payment_reversal v on v.payment_id = p.id";

    private static final RowMapper<Settlement> RECEIPT = (rs, i) -> settlement(rs);

    private static Settlement settlement(ResultSet rs) throws SQLException {
        UUID reversalId = uuid(rs, "reversal_id");
        Settlement.Reversal reversal = reversalId == null ? null : new Settlement.Reversal(reversalId, rs.getString("reversal_reason"),
                instant(rs, "reversed_at"), uuid(rs, "reversed_by"), rs.getString("reversal_key"));
        int installments = rs.getInt("card_installments");
        Settlement.CardFee fee = rs.wasNull() ? null : new Settlement.CardFee(installments, rs.getBigDecimal("fee_percent"),
                rs.getBigDecimal("fee_amount"), date(rs, "expected_settlement_on"));
        return new Settlement(uuid(rs, "id"), uuid(rs, "owner_id"), rs.getBigDecimal("amount"), uuid(rs, "payment_method_id"),
                rs.getString("payment_method_name"), date(rs, "effective_on"), rs.getString("notes"), instant(rs, "recorded_at"),
                uuid(rs, "recorded_by"), rs.getString("idempotency_key"), reversal, fee);
    }

    @Override
    public void insertReceipt(Settlement s) {
        Settlement.CardFee fee = s.cardFee();
        jdbc.update("insert into finance.receipt (id, receivable_id, amount, payment_method_id, payment_method_name, received_on, notes,"
                + " recorded_at, recorded_by, idempotency_key, card_installments, fee_percent, fee_amount, expected_settlement_on)"
                + " values (:id, :owner, :amount, :method, :methodName, :on, :notes, :at, :by, :key, :installments, :feePercent, :feeAmount,"
                + " :settlement)", settlementParams(s).addValue("installments", fee == null ? null : fee.installments())
                .addValue("feePercent", fee == null ? null : fee.feePercent())
                .addValue("feeAmount", fee == null ? BigDecimal.ZERO : fee.feeAmount())
                .addValue("settlement", fee == null ? null : Date.valueOf(fee.expectedSettlementOn())));
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
    public Optional<KeyedReversal> receiptReversedWithKey(String key) {
        return jdbc.query("select receipt_id, reason from finance.receipt_reversal where idempotency_key = :key", Map.of("key", key),
                (rs, i) -> new KeyedReversal(uuid(rs, "receipt_id"), rs.getString("reason"))).stream().findFirst();
    }

    /**
     * Totais derivados por recebível, calculados a partir dos lançamentos a cada consulta.
     *
     * <p>{@code due_date} de {@code classified} é o vencimento corrente: o da primeira parcela do plano corrente com
     * principal em aberto (ou o da última, quando só resta acréscimo). Recebível sem plano gravado tem a parcela
     * implícita (valor original, vencimento do recebível). {@code installment_open} traz o principal em aberto de
     * cada parcela, preenchido das mais novas para as mais antigas, porque as antigas são quitadas primeiro — a mesma
     * regra de {@code Receivable.installmentPositions()}.</p>
     */
    static final String RECEIVABLE_TOTALS = "with totals as (select r.id, r.origin, r.work_order_id, r.work_order_number, r.customer_id,"
            + " r.billing_quote_id, r.description, r.revenue_category_id, r.original_amount, r.issued_on, r.due_date as base_due_date,"
            + " r.created_at, r.created_by, r.cancelled_at, r.cancelled_by, r.cancellation_reason,"
            + " coalesce((select sum(a.amount) from finance.receivable_adjustment a where a.receivable_id = r.id and a.adjustment_type = 'DISCOUNT'"
            + "   and not exists (select 1 from finance.receivable_adjustment_reversal x where x.adjustment_id = a.id)), 0) as discount_amount,"
            + " coalesce((select sum(a.amount) from finance.receivable_adjustment a where a.receivable_id = r.id and a.adjustment_type = 'SURCHARGE'"
            + "   and not exists (select 1 from finance.receivable_adjustment_reversal x where x.adjustment_id = a.id)), 0) as surcharge_amount,"
            + " coalesce((select sum(p.amount) from finance.receipt p where p.receivable_id = r.id"
            + "   and not exists (select 1 from finance.receipt_reversal v where v.receipt_id = p.id)), 0) as settled_amount"
            + " from finance.receivable r),"
            + " balances as (select t.*, t.original_amount - t.discount_amount + t.surcharge_amount - t.settled_amount as outstanding,"
            + "   greatest(t.original_amount - t.discount_amount - t.settled_amount, 0) as principal_open from totals t),"
            + " plans as (select receivable_id, max(version) as version from finance.receivable_installment_plan group by receivable_id),"
            + " installments as (select b.id as receivable_id, coalesce(i.number, 1) as number, coalesce(i.amount, b.original_amount) as amount,"
            + "   coalesce(i.due_date, b.base_due_date) as due_date, b.principal_open from balances b"
            + "   left join plans pl on pl.receivable_id = b.id"
            + "   left join finance.receivable_installment i on i.receivable_id = pl.receivable_id and i.plan_version = pl.version),"
            + " installment_open as (select x.receivable_id, x.number, x.amount, x.due_date, least(x.amount, greatest(x.principal_open"
            + "   - coalesce(sum(x.amount) over (partition by x.receivable_id order by x.number desc"
            + "     rows between unbounded preceding and 1 preceding), 0), 0)) as open_principal from installments x),"
            + " scheduled as (select b.*, coalesce((select min(o.due_date) from installment_open o where o.receivable_id = b.id"
            + "   and o.open_principal > 0), (select max(o.due_date) from installment_open o where o.receivable_id = b.id)) as due_date,"
            + "   (select count(*) from installment_open o where o.receivable_id = b.id) as installment_count from balances b),"
            + " classified as (select b.*, case when b.cancelled_at is not null then 'CANCELADO' when b.outstanding = 0 then 'QUITADO'"
            + "   when b.due_date < :today then 'VENCIDO' when b.settled_amount > 0 then 'PARCIAL' else 'ABERTO' end as status from scheduled b)";

    /**
     * Saldo em aberto por vencimento: o principal de cada parcela e, à parte, o acréscimo ainda não pago, que vence
     * com o vencimento corrente. A soma por recebível é o saldo em aberto.
     */
    static final String RECEIVABLE_OPEN_ITEMS = RECEIVABLE_TOTALS
            + ", open_items as (select c.id as receivable_id, c.customer_id, o.due_date, o.open_principal as amount"
            + "   from installment_open o join classified c on c.id = o.receivable_id"
            + "   where c.cancelled_at is null and c.outstanding > 0 and o.open_principal > 0"
            + " union all select c.id, c.customer_id, c.due_date, c.outstanding - c.principal_open from classified c"
            + "   where c.cancelled_at is null and c.outstanding > c.principal_open)";

    @Override
    public PageResult<ReceivableSummary> listReceivables(ReceivableFilter filter, LocalDate today, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("today", Date.valueOf(today));
        List<String> where = new ArrayList<>();
        if (filter.status() != null) { where.add("status = :status"); params.addValue("status", filter.status().name()); }
        if (filter.customerId() != null) { where.add("customer_id = :customer"); params.addValue("customer", filter.customerId()); }
        if (filter.workOrderNumber() != null) { where.add("work_order_number = :number"); params.addValue("number", filter.workOrderNumber()); }
        if (filter.dueFrom() != null) { where.add("due_date >= :dueFrom"); params.addValue("dueFrom", Date.valueOf(filter.dueFrom())); }
        if (filter.dueTo() != null) { where.add("due_date <= :dueTo"); params.addValue("dueTo", Date.valueOf(filter.dueTo())); }
        if (filter.origin() != null) { where.add("origin = :origin"); params.addValue("origin", filter.origin().name()); }
        String condition = where.isEmpty() ? "" : " where " + String.join(" and ", where);
        long total = jdbc.queryForObject(RECEIVABLE_TOTALS + " select count(*) from classified" + condition, params, Long.class);
        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<ReceivableSummary> items = jdbc.query(RECEIVABLE_TOTALS + " select * from classified" + condition
                + " order by due_date, work_order_number nulls last, created_at limit :limit offset :offset", params, (rs, i) -> {
            long number = rs.getLong("work_order_number");
            Long workOrderNumber = rs.wasNull() ? null : number;
            return new ReceivableSummary(uuid(rs, "id"), Receivable.Origin.valueOf(rs.getString("origin")), uuid(rs, "work_order_id"),
                    workOrderNumber, rs.getString("description"), uuid(rs, "customer_id"),
                    rs.getBigDecimal("original_amount"), rs.getBigDecimal("discount_amount"), rs.getBigDecimal("surcharge_amount"),
                    rs.getBigDecimal("settled_amount"), rs.getBigDecimal("outstanding"), date(rs, "issued_on"), date(rs, "due_date"),
                    FinancialStatus.valueOf(rs.getString("status")), rs.getInt("installment_count"));
        });
        return new PageResult<>(items, total, page, size);
    }

    @Override
    public Optional<KeyedAdjustment> receivableAdjustedWithKey(String key) {
        return jdbc.query("select receivable_id, adjustment_type, amount, reason from finance.receivable_adjustment where idempotency_key = :key",
                Map.of("key", key), (rs, i) -> new KeyedAdjustment(uuid(rs, "receivable_id"),
                        Receivable.AdjustmentType.valueOf(rs.getString("adjustment_type")), rs.getBigDecimal("amount"), rs.getString("reason")))
                .stream().findFirst();
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
    public Optional<KeyedReversal> adjustmentReversedWithKey(String key) {
        return jdbc.query("select adjustment_id, reason from finance.receivable_adjustment_reversal where idempotency_key = :key",
                Map.of("key", key), (rs, i) -> new KeyedReversal(uuid(rs, "adjustment_id"), rs.getString("reason"))).stream().findFirst();
    }

    @Override
    public Optional<DueDateChangeRef> dueDateChangedWithKey(String key) {
        return jdbc.query("select receivable_id, new_due_date, reason, installment_number from finance.receivable_due_date_change"
                        + " where idempotency_key = :key", Map.of("key", key), (rs, i) -> new DueDateChangeRef(uuid(rs, "receivable_id"),
                        date(rs, "new_due_date"), rs.getString("reason"), (Integer) rs.getObject("installment_number")))
                .stream().findFirst();
    }

    @Override
    public void lockIdempotencyKey(String key) {
        // Serializa requisições concorrentes com a mesma chave; a segunda passa a enxergar o que a primeira gravou.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(:key, 0))", Map.of("key", key), rs -> null);
    }

    // ================================================================ juros e multa (DR-0024, Q3)

    @Override
    public List<LateCharge.Recorded> lateCharges(UUID receivableId) {
        Map<UUID, List<LateCharge.RecordedLine>> lines = new java.util.HashMap<>();
        jdbc.query("select l.* from finance.receivable_late_charge_line l join finance.receivable_late_charge c on c.id = l.late_charge_id"
                + " where c.receivable_id = :id", Map.of("id", receivableId), rs -> {
            lines.computeIfAbsent(uuid(rs, "late_charge_id"), k -> new ArrayList<>()).add(new LateCharge.RecordedLine(rs.getInt("plan_version"),
                    rs.getInt("installment_number"), date(rs, "calculated_on"), rs.getBigDecimal("fee_amount")));
        });
        return jdbc.query("select c.*, (c.decision = 'WAIVED' or not exists (select 1 from finance.receivable_adjustment_reversal v"
                        + " where v.adjustment_id = c.adjustment_id)) as counts from finance.receivable_late_charge c"
                        + " where c.receivable_id = :id order by c.recorded_at, c.id", Map.of("id", receivableId),
                (rs, i) -> new LateCharge.Recorded(uuid(rs, "id"), LateCharge.Decision.valueOf(rs.getString("decision")),
                        date(rs, "calculated_on"), rs.getBigDecimal("fee_percent"), rs.getBigDecimal("interest_percent"),
                        rs.getBigDecimal("fee_amount"), rs.getBigDecimal("interest_amount"), rs.getBigDecimal("total_amount"),
                        rs.getString("reason"), uuid(rs, "adjustment_id"), instant(rs, "recorded_at"), uuid(rs, "recorded_by"),
                        rs.getBoolean("counts"), List.copyOf(lines.getOrDefault(uuid(rs, "id"), List.of()))));
    }

    @Override
    public void insertLateCharge(UUID receivableId, LateCharge.Recorded c, LateCharge.Suggestion suggestion, String key) {
        jdbc.update("insert into finance.receivable_late_charge (id, receivable_id, decision, calculated_on, fee_percent, interest_percent,"
                + " fee_amount, interest_amount, total_amount, reason, adjustment_id, recorded_at, recorded_by, idempotency_key)"
                + " values (:id, :receivable, :decision, :on, :feePercent, :interestPercent, :fee, :interest, :total, :reason, :adjustment,"
                + " :at, :by, :key)", new MapSqlParameterSource().addValue("id", c.id()).addValue("receivable", receivableId)
                .addValue("decision", c.decision().name()).addValue("on", Date.valueOf(c.calculatedOn()))
                .addValue("feePercent", c.feePercent()).addValue("interestPercent", c.interestPercent()).addValue("fee", c.feeAmount())
                .addValue("interest", c.interestAmount()).addValue("total", c.totalAmount()).addValue("reason", c.reason())
                .addValue("adjustment", c.adjustmentId()).addValue("at", Timestamp.from(c.recordedAt())).addValue("by", c.recordedBy())
                .addValue("key", key));
        for (LateCharge.Line line : suggestion.lines())
            jdbc.update("insert into finance.receivable_late_charge_line (late_charge_id, plan_version, installment_number, due_date,"
                    + " base_amount, interest_from, calculated_on, days, fee_amount, interest_amount) values (:charge, :version, :number, :due,"
                    + " :base, :from, :on, :days, :fee, :interest)", new MapSqlParameterSource().addValue("charge", c.id())
                    .addValue("version", suggestion.planVersion()).addValue("number", line.installmentNumber())
                    .addValue("due", Date.valueOf(line.dueDate())).addValue("base", line.baseAmount())
                    .addValue("from", Date.valueOf(line.interestFrom())).addValue("on", Date.valueOf(c.calculatedOn()))
                    .addValue("days", line.days()).addValue("fee", line.feeAmount()).addValue("interest", line.interestAmount()));
    }

    @Override
    public Optional<KeyedLateCharge> lateChargeWithKey(String key) {
        return jdbc.query("select receivable_id, decision, calculated_on, total_amount from finance.receivable_late_charge"
                        + " where idempotency_key = :key", Map.of("key", key),
                (rs, i) -> new KeyedLateCharge(uuid(rs, "receivable_id"), LateCharge.Decision.valueOf(rs.getString("decision")),
                        date(rs, "calculated_on"), rs.getBigDecimal("total_amount"))).stream().findFirst();
    }

    // ================================================================ contas a pagar

    private static final String PAYABLE = "select p.*, c.name as category_name, a.id as approval_id, a.approved_at, a.approved_by,"
            + " a.notes as approval_notes from finance.payable p join finance.expense_category c on c.id = p.category_id"
            + " left join finance.payable_approval a on a.payable_id = p.id";

    @Override
    public Optional<Payable> findPayable(UUID id) { return loadPayable(PAYABLE + " where p.id = :id", Map.of("id", id)); }

    @Override
    public Optional<Payable> lockPayable(UUID id) { return loadPayable(PAYABLE + " where p.id = :id for update of p", Map.of("id", id)); }

    @Override
    public Optional<Payable> findPayableByKey(String key) {
        return loadPayable(PAYABLE + " where p.idempotency_key = :key", Map.of("key", key));
    }

    private Optional<Payable> loadPayable(String sql, Map<String, ?> params) {
        return jdbc.query(sql, params, (rs, i) -> payable(rs, List.of())).stream().findFirst()
                .map(payable -> new Payable(payable.id(), payable.description(), payable.supplier(), payable.categoryId(),
                        payable.categoryName(), payable.amount(), payable.dueDate(), payable.notes(), payable.createdAt(), payable.createdBy(),
                        payable.idempotencyKey(), payable.cancelledAt(), payable.cancelledBy(), payable.cancellationReason(),
                        jdbc.query(PAYMENTS + " where p.payable_id = :id order by p.recorded_at, p.id", Map.of("id", payable.id()), RECEIPT),
                        payable.supplierId(), payable.origin(), payable.groupId(), payable.installmentNumber(), payable.approvalRequired(),
                        payable.approval()));
    }

    private static Payable payable(ResultSet rs, List<Settlement> payments) throws SQLException {
        UUID approvalId = uuid(rs, "approval_id");
        return new Payable(uuid(rs, "id"), rs.getString("description"), rs.getString("supplier"),
                uuid(rs, "category_id"), rs.getString("category_name"), rs.getBigDecimal("amount"), date(rs, "due_date"),
                rs.getString("notes"), instant(rs, "created_at"), uuid(rs, "created_by"), rs.getString("idempotency_key"),
                instant(rs, "cancelled_at"), uuid(rs, "cancelled_by"), rs.getString("cancellation_reason"), payments,
                uuid(rs, "supplier_id"), Payable.Origin.valueOf(rs.getString("origin")), uuid(rs, "group_id"),
                (Integer) rs.getObject("installment_number"), rs.getBoolean("approval_required"),
                approvalId == null ? null : new Payable.Approval(approvalId, instant(rs, "approved_at"), uuid(rs, "approved_by"),
                        rs.getString("approval_notes")));
    }

    @Override
    public void insertPayableGroup(PayableGroup g) {
        jdbc.update("insert into finance.payable_group (id, description, supplier, supplier_id, category_id, total_amount, installment_count,"
                + " notes, created_at, created_by, idempotency_key) values (:id, :description, :supplier, :supplierId, :category, :total, :count,"
                + " :notes, :at, :by, :key)", new MapSqlParameterSource().addValue("id", g.id()).addValue("description", g.description())
                .addValue("supplier", g.supplier()).addValue("supplierId", g.supplierId()).addValue("category", g.categoryId())
                .addValue("total", g.totalAmount()).addValue("count", g.installmentCount()).addValue("notes", g.notes())
                .addValue("at", Timestamp.from(g.createdAt())).addValue("by", g.createdBy()).addValue("key", g.idempotencyKey()));
    }

    private static final RowMapper<PayableGroup> GROUP = (rs, i) -> new PayableGroup(uuid(rs, "id"), rs.getString("description"),
            rs.getString("supplier"), uuid(rs, "supplier_id"), uuid(rs, "category_id"), rs.getBigDecimal("total_amount"),
            rs.getInt("installment_count"), rs.getString("notes"), instant(rs, "created_at"), uuid(rs, "created_by"),
            rs.getString("idempotency_key"));

    @Override
    public Optional<PayableGroup> findPayableGroup(UUID id) {
        return jdbc.query("select * from finance.payable_group where id = :id", Map.of("id", id), GROUP).stream().findFirst();
    }

    @Override
    public Optional<PayableGroup> findPayableGroupByKey(String key) {
        return jdbc.query("select * from finance.payable_group where idempotency_key = :key", Map.of("key", key), GROUP).stream().findFirst();
    }

    @Override
    public List<Payable> payablesOfGroup(UUID groupId) {
        List<UUID> ids = jdbc.queryForList("select id from finance.payable where group_id = :id order by installment_number",
                Map.of("id", groupId), UUID.class);
        return ids.stream().map(id -> findPayable(id).orElseThrow()).toList();
    }

    @Override
    public void insertPayableApproval(UUID payableId, Payable.Approval a) {
        jdbc.update("insert into finance.payable_approval (id, payable_id, approved_at, approved_by, notes)"
                + " values (:id, :payable, :at, :by, :notes)", new MapSqlParameterSource().addValue("id", a.id()).addValue("payable", payableId)
                .addValue("at", Timestamp.from(a.approvedAt())).addValue("by", a.approvedBy()).addValue("notes", a.notes()));
    }

    @Override
    public void insertPayable(Payable p) {
        jdbc.update("insert into finance.payable (id, description, supplier, category_id, amount, due_date, notes, created_at, created_by,"
                + " idempotency_key, supplier_id, origin, group_id, installment_number, approval_required) values (:id, :description,"
                + " :supplier, :category, :amount, :dueDate, :notes, :at, :by, :key, :supplierId, :origin, :group, :number, :approval)",
                new MapSqlParameterSource().addValue("id", p.id()).addValue("description", p.description()).addValue("supplier", p.supplier())
                        .addValue("supplierId", p.supplierId()).addValue("origin", p.origin().name()).addValue("group", p.groupId())
                        .addValue("number", p.installmentNumber()).addValue("approval", p.approvalRequired())
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
    public Optional<KeyedReversal> paymentReversedWithKey(String key) {
        return jdbc.query("select payment_id, reason from finance.payable_payment_reversal where idempotency_key = :key", Map.of("key", key),
                (rs, i) -> new KeyedReversal(uuid(rs, "payment_id"), rs.getString("reason"))).stream().findFirst();
    }

    static final String PAYABLE_TOTALS = "with totals as (select p.*, c.name as category_name,"
            + " case when not p.approval_required then 'NOT_REQUIRED' when exists (select 1 from finance.payable_approval a"
            + "   where a.payable_id = p.id) then 'APPROVED' else 'PENDING' end as approval_status,"
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
        if (filter.approval() != null) { where.add("approval_status = :approval"); params.addValue("approval", filter.approval().name()); }
        if (filter.groupId() != null) { where.add("group_id = :group"); params.addValue("group", filter.groupId()); }
        String condition = where.isEmpty() ? "" : " where " + String.join(" and ", where);
        long total = jdbc.queryForObject(PAYABLE_TOTALS + " select count(*) from classified" + condition, params, Long.class);
        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<PayableSummary> items = jdbc.query(PAYABLE_TOTALS + " select * from classified" + condition
                + " order by due_date, created_at limit :limit offset :offset", params, (rs, i) -> new PayableSummary(uuid(rs, "id"),
                rs.getString("description"), rs.getString("supplier"), uuid(rs, "category_id"), rs.getString("category_name"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("settled_amount"), rs.getBigDecimal("outstanding"), date(rs, "due_date"),
                FinancialStatus.valueOf(rs.getString("status")), uuid(rs, "supplier_id"), uuid(rs, "group_id"),
                (Integer) rs.getObject("installment_number"), Payable.ApprovalStatus.valueOf(rs.getString("approval_status"))));
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
    public List<ExpenseCategory> revenueCategories() {
        return jdbc.query("select * from finance.revenue_category order by name", Map.of(), CATEGORY);
    }

    @Override
    public Optional<ExpenseCategory> revenueCategory(UUID id) {
        return jdbc.query("select * from finance.revenue_category where id = :id", Map.of("id", id), CATEGORY).stream().findFirst();
    }

    @Override
    public void saveRevenueCategory(ExpenseCategory c, boolean isNew) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", c.id()).addValue("name", c.name())
                .addValue("active", c.active()).addValue("created", Timestamp.from(c.createdAt())).addValue("updated", Timestamp.from(c.updatedAt()));
        if (isNew)
            jdbc.update("insert into finance.revenue_category (id, name, active, created_at, updated_at)"
                    + " values (:id, :name, :active, :created, :updated)", params);
        else
            jdbc.update("update finance.revenue_category set name = :name, active = :active, updated_at = :updated where id = :id", params);
    }

    @Override
    public String setting(String key) {
        return jdbc.queryForObject("select value from finance.settings where key = :key", Map.of("key", key), String.class);
    }

    @Override
    public void saveSetting(String key, String value, Instant at, UUID by) {
        int updated = jdbc.update("update finance.settings set value = :value, updated_at = :at, updated_by = :by where key = :key",
                new MapSqlParameterSource().addValue("key", key).addValue("value", value).addValue("at", Timestamp.from(at)).addValue("by", by));
        if (updated != 1) throw new IllegalStateException("Configuração inexistente: " + key);
    }

    @Override
    public List<PaymentMethodFee> currentFees(UUID paymentMethodId) {
        return jdbc.query("select f.* from finance.payment_method_fee f where f.schedule_id = (select s.id from finance.payment_method_fee_schedule s"
                        + " where s.payment_method_id = :id order by s.created_at desc, s.id desc limit 1) order by f.installments",
                Map.of("id", paymentMethodId), (rs, i) -> new PaymentMethodFee(rs.getInt("installments"), rs.getBigDecimal("fee_percent"),
                        rs.getInt("settlement_days")));
    }

    @Override
    public void insertFeeSchedule(UUID paymentMethodId, List<PaymentMethodFee> fees, Instant at, UUID by) {
        UUID schedule = UUID.randomUUID();
        jdbc.update("insert into finance.payment_method_fee_schedule (id, payment_method_id, created_at, created_by) values (:id, :method, :at, :by)",
                new MapSqlParameterSource().addValue("id", schedule).addValue("method", paymentMethodId).addValue("at", Timestamp.from(at))
                        .addValue("by", by));
        for (PaymentMethodFee fee : fees)
            jdbc.update("insert into finance.payment_method_fee (schedule_id, installments, fee_percent, settlement_days)"
                    + " values (:schedule, :installments, :percent, :days)", new MapSqlParameterSource().addValue("schedule", schedule)
                    .addValue("installments", fee.installments()).addValue("percent", fee.feePercent()).addValue("days", fee.settlementDays()));
    }

    @Override
    public BigDecimal cardFees(LocalDate from, LocalDate to) {
        return money(jdbc.queryForObject("select coalesce(sum(p.fee_amount), 0) from finance.receipt p where p.received_on between :from and :to"
                + " and not exists (select 1 from finance.receipt_reversal v where v.receipt_id = p.id)", period(from, to), BigDecimal.class));
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
        return daily(RECEIVABLE_OPEN_ITEMS + " select due_date as day, sum(amount) as amount from open_items"
                + " where due_date between :from and :to group by due_date order by due_date",
                period(from, to).addValue("today", Date.valueOf(from)));
    }

    @Override
    public List<DailyAmount> forecastOutflows(LocalDate from, LocalDate to, UUID categoryId) {
        MapSqlParameterSource params = period(from, to).addValue("today", Date.valueOf(from)).addValue("category", categoryId);
        return daily(PAYABLE_TOTALS + " select due_date as day, sum(outstanding) as amount from classified"
                + " where cancelled_at is null and outstanding > 0 and due_date between :from and :to"
                + (categoryId == null ? "" : " and category_id = :category")
                + " group by due_date order by due_date", params);
    }

    // ================================================================ resumo gerencial

    @Override
    public DashboardReceivableTotals dashboardReceivableTotals(LocalDate from, LocalDate to, LocalDate today) {
        MapSqlParameterSource params = period(from, to).addValue("today", Date.valueOf(today));
        List<UUID> workOrderIds = jdbc.queryForList(RECEIVABLE_TOTALS
                + " select work_order_id from classified where cancelled_at is null and work_order_id is not null"
                + " and issued_on between :from and :to",
                params, UUID.class);
        Map<String, Object> totals = jdbc.queryForMap(RECEIVABLE_TOTALS
                + " select coalesce(sum(original_amount), 0) as revenue, coalesce(sum(discount_amount), 0) as discounts,"
                + " coalesce(sum(surcharge_amount), 0) as surcharges,"
                + " coalesce(sum(case when outstanding > 0 then outstanding else 0 end), 0) as receivable_open,"
                + " coalesce(sum(case when status = 'VENCIDO' then outstanding else 0 end), 0) as overdue"
                + " from classified where cancelled_at is null and issued_on between :from and :to", params);
        return new DashboardReceivableTotals(money(totals.get("revenue")), money(totals.get("discounts")), money(totals.get("surcharges")),
                money(totals.get("receivable_open")),
                money(totals.get("overdue")), workOrderIds);
    }

    @Override
    public BigDecimal dashboardExpensesRegistered(LocalDate from, LocalDate to) {
        // created_at é TIMESTAMPTZ: a data-calendário é a da oficina (America/Sao_Paulo), não a do fuso
        // da sessão do banco — mesma convenção de Money.WORKSHOP_ZONE usada no restante do Financeiro.
        return registeredPayables(from, to, false);
    }

    @Override
    public BigDecimal dashboardStockPurchasesRegistered(LocalDate from, LocalDate to) {
        return registeredPayables(from, to, true);
    }

    /**
     * Soma das contas não canceladas criadas no período, separando as de compra de mercadoria para estoque
     * ({@code origin = 'PURCHASE'}, gravada só por {@link PurchasePayables}; a V31 marcou as antigas pela chave
     * {@code PURCHASE:...}) das demais.
     */
    private BigDecimal registeredPayables(LocalDate from, LocalDate to, boolean stockPurchases) {
        // created_at é TIMESTAMPTZ: a data-calendário é a da oficina (America/Sao_Paulo), não a do fuso
        // da sessão do banco — mesma convenção de Money.WORKSHOP_ZONE usada no restante do Financeiro.
        return money(jdbc.queryForObject("select coalesce(sum(amount), 0) from finance.payable"
                + " where cancelled_at is null and (created_at at time zone 'America/Sao_Paulo')::date between :from and :to"
                + " and (origin = 'PURCHASE') = :stockPurchases",
                period(from, to).addValue("stockPurchases", stockPurchases), BigDecimal.class));
    }

    /** `coalesce(sum(numeric), 0)` some vezes volta com escala menor que a monetária (0 sem linhas). */
    private static BigDecimal money(Object value) {
        return ((BigDecimal) value).setScale(2, java.math.RoundingMode.UNNECESSARY);
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
