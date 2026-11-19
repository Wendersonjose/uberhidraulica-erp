package br.com.uberhidraulica.erp.finance.infrastructure.persistence;

import br.com.uberhidraulica.erp.finance.port.FinanceReportPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.uberhidraulica.erp.finance.infrastructure.persistence.JdbcFinanceRepositoryAdapter.PAYABLE_TOTALS;
import static br.com.uberhidraulica.erp.finance.infrastructure.persistence.JdbcFinanceRepositoryAdapter.RECEIVABLE_OPEN_ITEMS;
import static br.com.uberhidraulica.erp.finance.infrastructure.persistence.JdbcFinanceRepositoryAdapter.RECEIVABLE_TOTALS;

/** Relatórios financeiros: só SELECT sobre as tabelas do próprio módulo {@code finance}. */
@Component
class JdbcFinanceReportAdapter implements FinanceReportPort {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcFinanceReportAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String AGING_ORDER = "case bucket when 'A_VENCER' then 1 when 'ATE_30' then 2 when 'DE_31_A_60' then 3"
            + " when 'DE_61_A_90' then 4 else 5 end";

    @Override
    public List<AgingBucket> receivableAging(LocalDate today) {
        // Cada parcela cai na faixa do seu próprio vencimento; o acréscimo não pago, na do vencimento corrente.
        return jdbc.query(RECEIVABLE_OPEN_ITEMS + ", open as (select receivable_id, amount as outstanding,"
                        + "   (cast(:today as date) - due_date) as late from open_items),"
                        + " bucketed as (select receivable_id, outstanding, case when late <= 0 then 'A_VENCER' when late <= 30 then 'ATE_30'"
                        + "   when late <= 60 then 'DE_31_A_60' when late <= 90 then 'DE_61_A_90' else 'ACIMA_90' end as bucket from open)"
                        + " select b.bucket, count(distinct o.receivable_id) as n, coalesce(sum(o.outstanding), 0) as amount"
                        + " from (values ('A_VENCER'), ('ATE_30'), ('DE_31_A_60'), ('DE_61_A_90'), ('ACIMA_90')) as b(bucket)"
                        + " left join bucketed o on o.bucket = b.bucket group by b.bucket order by " + AGING_ORDER.replace("bucket", "b.bucket"),
                today(today), (rs, i) -> new AgingBucket(rs.getString("bucket"), rs.getLong("n"), money(rs, "amount")));
    }

    @Override
    public List<CustomerDebt> receivableDebtors(LocalDate today, int limit) {
        return jdbc.query(RECEIVABLE_TOTALS + " select customer_id, count(*) as n, sum(outstanding) as outstanding,"
                        + " coalesce(sum(case when status = 'VENCIDO' then outstanding end), 0) as overdue, min(due_date) as oldest"
                        + " from classified where cancelled_at is null and outstanding > 0"
                        + " group by customer_id order by overdue desc, outstanding desc limit :limit",
                today(today).addValue("limit", limit),
                (rs, i) -> new CustomerDebt(rs.getObject("customer_id", UUID.class), rs.getLong("n"), money(rs, "outstanding"),
                        money(rs, "overdue"), rs.getDate("oldest").toLocalDate()));
    }

    private static final String DUE_SUMMARY = " select"
            + " count(*) filter (where due_date < :today) as overdue_n, coalesce(sum(outstanding) filter (where due_date < :today), 0) as overdue,"
            + " count(*) filter (where due_date between :today and cast(:today as date) + 7) as d7_n,"
            + " coalesce(sum(outstanding) filter (where due_date between :today and cast(:today as date) + 7), 0) as d7,"
            + " count(*) filter (where due_date between :today and cast(:today as date) + 30) as d30_n,"
            + " coalesce(sum(outstanding) filter (where due_date between :today and cast(:today as date) + 30), 0) as d30,"
            + " count(*) as open_n, coalesce(sum(outstanding), 0) as open"
            + " from classified where cancelled_at is null and outstanding > 0";

    /** Recebíveis por parcela: a conta é contada uma vez por faixa, e o valor é o das parcelas daquela faixa. */
    private static final String RECEIVABLE_DUE_SUMMARY = " select"
            + " count(distinct receivable_id) filter (where due_date < :today) as overdue_n,"
            + " coalesce(sum(amount) filter (where due_date < :today), 0) as overdue,"
            + " count(distinct receivable_id) filter (where due_date between :today and cast(:today as date) + 7) as d7_n,"
            + " coalesce(sum(amount) filter (where due_date between :today and cast(:today as date) + 7), 0) as d7,"
            + " count(distinct receivable_id) filter (where due_date between :today and cast(:today as date) + 30) as d30_n,"
            + " coalesce(sum(amount) filter (where due_date between :today and cast(:today as date) + 30), 0) as d30,"
            + " count(distinct receivable_id) as open_n, coalesce(sum(amount), 0) as open from open_items";

    @Override
    public DueSummary receivableDue(LocalDate today) {
        return jdbc.queryForObject(RECEIVABLE_OPEN_ITEMS + RECEIVABLE_DUE_SUMMARY, today(today), JdbcFinanceReportAdapter::dueSummary);
    }

    @Override
    public DueSummary payableDue(LocalDate today) {
        return jdbc.queryForObject(PAYABLE_TOTALS + DUE_SUMMARY, today(today), JdbcFinanceReportAdapter::dueSummary);
    }

    @Override
    public List<CategoryExpense> expensesByCategory(LocalDate from, LocalDate to) {
        return jdbc.query("""
                select c.id, c.name,
                  (select count(*) from finance.payable p where p.category_id = c.id and p.cancelled_at is null
                     and (p.created_at at time zone 'America/Sao_Paulo')::date between :from and :to) as registered_n,
                  (select coalesce(sum(p.amount), 0) from finance.payable p where p.category_id = c.id and p.cancelled_at is null
                     and (p.created_at at time zone 'America/Sao_Paulo')::date between :from and :to) as registered,
                  (select coalesce(sum(x.amount), 0) from finance.payable_payment x join finance.payable p on p.id = x.payable_id
                     where p.category_id = c.id and x.paid_on between :from and :to
                     and not exists (select 1 from finance.payable_payment_reversal v where v.payment_id = x.id)) as paid
                from finance.expense_category c
                order by registered desc, paid desc, c.name""", period(from, to),
                (rs, i) -> new CategoryExpense(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("registered_n"),
                        money(rs, "registered"), money(rs, "paid")))
                .stream().filter(c -> c.registeredCount() > 0 || c.paid().signum() != 0).toList();
    }

    @Override
    public List<WorkOrderResult> workOrderResults(LocalDate from, LocalDate to, LocalDate today) {
        return jdbc.query(RECEIVABLE_TOTALS + " select * from classified where cancelled_at is null and origin = 'WORK_ORDER'"
                        + " and issued_on between :from and :to order by issued_on, work_order_number",
                period(from, to).addValue("today", Date.valueOf(today)),
                (rs, i) -> new WorkOrderResult(rs.getObject("id", UUID.class), rs.getObject("work_order_id", UUID.class),
                        rs.getLong("work_order_number"), rs.getObject("customer_id", UUID.class), rs.getDate("issued_on").toLocalDate(),
                        rs.getDate("due_date").toLocalDate(), rs.getString("status"), money(rs, "original_amount"),
                        money(rs, "discount_amount"), money(rs, "surcharge_amount"), money(rs, "settled_amount"), money(rs, "outstanding")));
    }

    @Override
    public List<ReceivableRow> receivablesDueBetween(LocalDate from, LocalDate to, LocalDate today) {
        return jdbc.query(RECEIVABLE_TOTALS + " select * from classified where due_date between :from and :to"
                        + " order by due_date, work_order_number nulls last, created_at", period(from, to).addValue("today", Date.valueOf(today)),
                (rs, i) -> new ReceivableRow(rs.getObject("id", UUID.class), (Long) rs.getObject("work_order_number"),
                        rs.getString("description"),
                        rs.getObject("customer_id", UUID.class), rs.getDate("issued_on").toLocalDate(), rs.getDate("due_date").toLocalDate(),
                        rs.getString("status"), money(rs, "original_amount"), money(rs, "discount_amount"), money(rs, "surcharge_amount"),
                        money(rs, "settled_amount"), money(rs, "outstanding")));
    }

    @Override
    public List<RevenueTotals> revenueByOrigin(LocalDate from, LocalDate to) {
        return jdbc.query(RECEIVABLE_TOTALS + " select origin, count(*) as n, coalesce(sum(original_amount), 0) as original,"
                        + " coalesce(sum(discount_amount), 0) as discount, coalesce(sum(surcharge_amount), 0) as surcharge"
                        + " from classified where cancelled_at is null and issued_on between :from and :to group by origin order by origin",
                period(from, to).addValue("today", Date.valueOf(from)),
                (rs, i) -> new RevenueTotals(rs.getString("origin"), rs.getLong("n"), money(rs, "original"), money(rs, "discount"),
                        money(rs, "surcharge")));
    }

    @Override
    public List<PayableRow> payablesDueBetween(LocalDate from, LocalDate to, LocalDate today) {
        return jdbc.query(PAYABLE_TOTALS + " select *, (created_at at time zone 'America/Sao_Paulo')::date as registered_on from classified"
                        + " where due_date between :from and :to order by due_date, created_at",
                period(from, to).addValue("today", Date.valueOf(today)),
                (rs, i) -> new PayableRow(rs.getObject("id", UUID.class), rs.getString("description"), rs.getString("supplier"),
                        rs.getString("category_name"), rs.getDate("registered_on").toLocalDate(), rs.getDate("due_date").toLocalDate(),
                        rs.getString("status"), money(rs, "amount"), money(rs, "settled_amount"), money(rs, "outstanding")));
    }

    private static DueSummary dueSummary(ResultSet rs, int i) throws SQLException {
        return new DueSummary(rs.getLong("overdue_n"), money(rs, "overdue"), rs.getLong("d7_n"), money(rs, "d7"),
                rs.getLong("d30_n"), money(rs, "d30"), rs.getLong("open_n"), money(rs, "open"));
    }

    private static BigDecimal money(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static MapSqlParameterSource today(LocalDate today) {
        return new MapSqlParameterSource(Map.of("today", Date.valueOf(today)));
    }

    private static MapSqlParameterSource period(LocalDate from, LocalDate to) {
        return new MapSqlParameterSource().addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to));
    }
}
