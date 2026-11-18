package br.com.uberhidraulica.erp.finance.infrastructure.persistence;

import br.com.uberhidraulica.erp.finance.domain.FinancialAccount;
import br.com.uberhidraulica.erp.finance.domain.Reconciliation;
import br.com.uberhidraulica.erp.finance.port.ReconciliationRepositoryPort;
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

/** Contas financeiras e conciliação manual em SQL explícito (DR-0024, Q7). */
@Component
class JdbcReconciliationAdapter implements ReconciliationRepositoryPort {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcReconciliationAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ================================================================ contas

    private static final RowMapper<FinancialAccount> ACCOUNT = (rs, i) -> new FinancialAccount(uuid(rs, "id"), rs.getString("name"),
            FinancialAccount.Type.valueOf(rs.getString("account_type")), rs.getString("bank_name"), rs.getString("branch"),
            rs.getString("account_number"), rs.getBoolean("active"), instant(rs, "created_at"), instant(rs, "updated_at"));

    @Override
    public List<FinancialAccount> accounts() {
        return jdbc.query("select * from finance.financial_account order by active desc, name", Map.of(), ACCOUNT);
    }

    @Override
    public Optional<FinancialAccount> account(UUID id) {
        return jdbc.query("select * from finance.financial_account where id = :id", Map.of("id", id), ACCOUNT).stream().findFirst();
    }

    @Override
    public void saveAccount(FinancialAccount a, boolean isNew) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", a.id()).addValue("name", a.name())
                .addValue("type", a.type().name()).addValue("bank", a.bankName()).addValue("branch", a.branch())
                .addValue("number", a.accountNumber()).addValue("active", a.active())
                .addValue("created", Timestamp.from(a.createdAt())).addValue("updated", Timestamp.from(a.updatedAt()));
        if (isNew)
            jdbc.update("insert into finance.financial_account (id, name, account_type, bank_name, branch, account_number, active, created_at,"
                    + " updated_at) values (:id, :name, :type, :bank, :branch, :number, :active, :created, :updated)", params);
        else
            jdbc.update("update finance.financial_account set name = :name, bank_name = :bank, branch = :branch, account_number = :number,"
                    + " active = :active, updated_at = :updated where id = :id", params);
    }

    // ================================================================ lançamentos

    @Override
    public Optional<Entry> lockEntry(Reconciliation.EntryType type, UUID entryId) {
        String sql = type == Reconciliation.EntryType.RECEIPT
                ? "select p.id, p.received_on as effective_on, p.amount, p.amount - p.fee_amount as net_amount,"
                + " exists (select 1 from finance.receipt_reversal v where v.receipt_id = p.id) as reversed"
                + " from finance.receipt p where p.id = :id for update"
                : "select p.id, p.paid_on as effective_on, p.amount, p.amount as net_amount,"
                + " exists (select 1 from finance.payable_payment_reversal v where v.payment_id = p.id) as reversed"
                + " from finance.payable_payment p where p.id = :id for update";
        return jdbc.query(sql, Map.of("id", entryId), (rs, i) -> new Entry(type, uuid(rs, "id"), date(rs, "effective_on"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("net_amount"), rs.getBoolean("reversed"))).stream().findFirst();
    }

    private static final String SELECT = "select c.*, case when c.receipt_id is not null then c.receipt_id else c.payable_payment_id end"
            + " as entry_id from finance.reconciliation c";

    private static final RowMapper<Reconciliation> RECONCILIATION = (rs, i) -> reconciliation(rs, "");

    private static Reconciliation reconciliation(ResultSet rs, String prefix) throws SQLException {
        UUID id = uuid(rs, prefix + "id");
        if (id == null) return null;
        return new Reconciliation(id, uuid(rs, prefix + "account_id"), Reconciliation.EntryType.valueOf(rs.getString(prefix + "entry_type")),
                uuid(rs, prefix + "entry_id"), date(rs, prefix + "statement_date"), rs.getBigDecimal(prefix + "statement_amount"),
                rs.getString(prefix + "statement_reference"), rs.getBigDecimal(prefix + "expected_amount"), rs.getString(prefix + "difference_reason"),
                instant(rs, prefix + "recorded_at"), uuid(rs, prefix + "recorded_by"), instant(rs, prefix + "reversed_at"),
                uuid(rs, prefix + "reversed_by"), rs.getString(prefix + "reversal_reason"));
    }

    private static String entryColumn(Reconciliation.EntryType type) {
        return type == Reconciliation.EntryType.RECEIPT ? "receipt_id" : "payable_payment_id";
    }

    @Override
    public Optional<Reconciliation> activeFor(Reconciliation.EntryType type, UUID entryId) {
        return jdbc.query(SELECT + " where c." + entryColumn(type) + " = :id and c.reversed_at is null", Map.of("id", entryId), RECONCILIATION)
                .stream().findFirst();
    }

    @Override
    public Optional<Reconciliation> find(UUID id) {
        return jdbc.query(SELECT + " where c.id = :id", Map.of("id", id), RECONCILIATION).stream().findFirst();
    }

    @Override
    public Optional<Reconciliation> lock(UUID id) {
        return jdbc.query(SELECT + " where c.id = :id for update of c", Map.of("id", id), RECONCILIATION).stream().findFirst();
    }

    @Override
    public Optional<Reconciliation> findByKey(String key) {
        return jdbc.query(SELECT + " where c.idempotency_key = :key", Map.of("key", key), RECONCILIATION).stream().findFirst();
    }

    @Override
    public Optional<UUID> reversedWithKey(String key) {
        return jdbc.query("select id from finance.reconciliation where reversal_idempotency_key = :key", Map.of("key", key),
                (rs, i) -> uuid(rs, "id")).stream().findFirst();
    }

    @Override
    public void insert(Reconciliation r, String key) {
        jdbc.update("insert into finance.reconciliation (id, account_id, entry_type, receipt_id, payable_payment_id, statement_date,"
                + " statement_amount, statement_reference, expected_amount, difference_reason, recorded_at, recorded_by, idempotency_key)"
                + " values (:id, :account, :type, :receipt, :payment, :date, :amount, :reference, :expected, :reason, :at, :by, :key)",
                new MapSqlParameterSource().addValue("id", r.id()).addValue("account", r.accountId()).addValue("type", r.entryType().name())
                        .addValue("receipt", r.entryType() == Reconciliation.EntryType.RECEIPT ? r.entryId() : null)
                        .addValue("payment", r.entryType() == Reconciliation.EntryType.PAYMENT ? r.entryId() : null)
                        .addValue("date", Date.valueOf(r.statementDate())).addValue("amount", r.statementAmount())
                        .addValue("reference", r.statementReference()).addValue("expected", r.expectedAmount())
                        .addValue("reason", r.differenceReason()).addValue("at", Timestamp.from(r.recordedAt()))
                        .addValue("by", r.recordedBy()).addValue("key", key));
    }

    @Override
    public void reverse(UUID id, Reconciliation r, String key) {
        jdbc.update("update finance.reconciliation set reversed_at = :at, reversed_by = :by, reversal_reason = :reason,"
                + " reversal_idempotency_key = :key where id = :id and reversed_at is null", new MapSqlParameterSource().addValue("id", id)
                .addValue("at", Timestamp.from(r.reversedAt())).addValue("by", r.reversedBy()).addValue("reason", r.reversalReason())
                .addValue("key", key));
    }

    @Override
    public List<EntryRow> entries(EntryFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("from", Date.valueOf(filter.from()))
                .addValue("to", Date.valueOf(filter.to()));
        List<String> where = new ArrayList<>();
        if (filter.type() != null) { where.add("e.entry_type = :type"); params.addValue("type", filter.type().name()); }
        if (filter.reconciled() != null) where.add(filter.reconciled() ? "c.id is not null" : "c.id is null");
        if (filter.accountId() != null) { where.add("c.account_id = :account"); params.addValue("account", filter.accountId()); }
        String condition = where.isEmpty() ? "" : " where " + String.join(" and ", where);
        return jdbc.query("""
                with entries as (
                  select 'RECEIPT' as entry_type, p.id, p.receivable_id as owner_id,
                         coalesce('OS #' || r.work_order_number, r.description) as owner_label, p.received_on as effective_on,
                         p.payment_method_name, p.amount, p.amount - p.fee_amount as net_amount, p.expected_settlement_on
                    from finance.receipt p join finance.receivable r on r.id = p.receivable_id
                   where p.received_on between :from and :to
                     and not exists (select 1 from finance.receipt_reversal v where v.receipt_id = p.id)
                  union all
                  select 'PAYMENT', x.id, x.payable_id, pa.description, x.paid_on, x.payment_method_name, x.amount, x.amount, null::date
                    from finance.payable_payment x join finance.payable pa on pa.id = x.payable_id
                   where x.paid_on between :from and :to
                     and not exists (select 1 from finance.payable_payment_reversal v where v.payment_id = x.id))
                select e.*, a.name as account_name, c.id as c_id, c.account_id as c_account_id, c.entry_type as c_entry_type,
                       e.id as c_entry_id, c.statement_date as c_statement_date, c.statement_amount as c_statement_amount,
                       c.statement_reference as c_statement_reference, c.expected_amount as c_expected_amount,
                       c.difference_reason as c_difference_reason, c.recorded_at as c_recorded_at, c.recorded_by as c_recorded_by,
                       c.reversed_at as c_reversed_at, c.reversed_by as c_reversed_by, c.reversal_reason as c_reversal_reason
                  from entries e
                  left join finance.reconciliation c on c.reversed_at is null
                       and ((e.entry_type = 'RECEIPT' and c.receipt_id = e.id) or (e.entry_type = 'PAYMENT' and c.payable_payment_id = e.id))
                  left join finance.financial_account a on a.id = c.account_id
                """ + condition + " order by e.effective_on, e.entry_type, e.id", params,
                (rs, i) -> new EntryRow(Reconciliation.EntryType.valueOf(rs.getString("entry_type")), uuid(rs, "id"), uuid(rs, "owner_id"),
                        rs.getString("owner_label"), date(rs, "effective_on"), rs.getString("payment_method_name"), rs.getBigDecimal("amount"),
                        rs.getBigDecimal("net_amount"), date(rs, "expected_settlement_on"), reconciliation(rs, "c_"), rs.getString("account_name")));
    }

    // ================================================================ apoio

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
