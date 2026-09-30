package br.com.uberhidraulica.erp.finance.infrastructure.persistence;

import br.com.uberhidraulica.erp.finance.domain.CashMovement;
import br.com.uberhidraulica.erp.finance.domain.CashSession;
import br.com.uberhidraulica.erp.finance.port.CashSessionRepositoryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
class JdbcCashSessionRepositoryAdapter implements CashSessionRepositoryPort {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcCashSessionRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CashSession> findSession(UUID id) {
        return jdbc.query("select * from finance.cash_session where id = :id", Map.of("id", id),
                (rs, row) -> session(rs)).stream().findFirst();
    }

    @Override
    public Optional<CashSession> findOpenSession() {
        return jdbc.query("select * from finance.cash_session where status = 'OPEN'", Map.of(),
                (rs, row) -> session(rs)).stream().findFirst();
    }

    @Override
    public Optional<CashSession> lockOpenSession() {
        return jdbc.query("select * from finance.cash_session where status = 'OPEN' for update", Map.of(),
                (rs, row) -> session(rs)).stream().findFirst();
    }

    @Override
    public Optional<CashSession> findLatestPendingCheck() {
        return jdbc.query("select * from finance.cash_session where status='AUTO_CLOSED' and conference_status='NOT_CHECKED' " +
                        "order by closed_at desc, id desc limit 1", Map.of(),
                (rs, row) -> session(rs)).stream().findFirst();
    }

    @Override
    public BigDecimal suggestedOpeningBalance() {
        return jdbc.query("select closing_expected_balance from finance.cash_session where status <> 'OPEN' " +
                        "order by closed_at desc nulls last, opened_at desc limit 1", Map.of(),
                (rs, row) -> rs.getBigDecimal(1)).stream().findFirst().orElse(BigDecimal.ZERO);
    }

    @Override
    public BigDecimal expectedBalance(UUID sessionId) {
        String sql = "select s.opening_counted_balance + coalesce(sum(case m.direction when 'IN' then m.amount else -m.amount end), 0) " +
                "from finance.cash_session s left join finance.cash_movement m on m.cash_session_id = s.id " +
                "where s.id = :id group by s.opening_counted_balance";
        return jdbc.query(sql, Map.of("id", sessionId), (rs, row) -> rs.getBigDecimal(1)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Sessão de caixa inexistente: " + sessionId));
    }

    @Override
    public void insertSession(CashSession session) {
        String sql = "insert into finance.cash_session " +
                "(id,status,conference_status,opened_at,opened_by,opening_expected_balance,opening_counted_balance,opening_difference_reason) " +
                "values (:id,:status,:conference,:openedAt,:openedBy,:expected,:counted,:reason)";
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", session.id()).addValue("status", session.status().name())
                .addValue("conference", session.conferenceStatus().name()).addValue("openedAt", Timestamp.from(session.openedAt()))
                .addValue("openedBy", session.openedBy()).addValue("expected", session.openingExpectedBalance())
                .addValue("counted", session.openingCountedBalance()).addValue("reason", session.openingDifferenceReason()));
    }

    @Override
    public void closeManual(UUID sessionId, Instant closedAt, UUID closedBy, BigDecimal expectedBalance,
                            BigDecimal countedBalance, String differenceReason) {
        String sql = "update finance.cash_session set status='CLOSED', conference_status='CHECKED', closed_at=:at, " +
                "closed_by=:by, closing_expected_balance=:expected, closing_counted_balance=:counted, " +
                "closing_difference_reason=:reason where id=:id and status='OPEN'";
        requireOne(jdbc.update(sql, new MapSqlParameterSource().addValue("id", sessionId)
                .addValue("at", Timestamp.from(closedAt)).addValue("by", closedBy).addValue("expected", expectedBalance)
                .addValue("counted", countedBalance).addValue("reason", differenceReason)));
    }

    @Override
    public void closeAutomatically(UUID sessionId, Instant closedAt, BigDecimal expectedBalance) {
        String sql = "update finance.cash_session set status='AUTO_CLOSED', conference_status='NOT_CHECKED', " +
                "closed_at=:at, closing_expected_balance=:expected where id=:id and status='OPEN'";
        requireOne(jdbc.update(sql, new MapSqlParameterSource().addValue("id", sessionId)
                .addValue("at", Timestamp.from(closedAt)).addValue("expected", expectedBalance)));
    }

    @Override
    public void checkAutomaticallyClosed(UUID sessionId, Instant checkedAt, UUID checkedBy, BigDecimal countedBalance,
                                         String differenceReason) {
        String sql = "update finance.cash_session set conference_status='CHECKED', checked_at=:at, checked_by=:by, " +
                "checked_counted_balance=:counted, checked_difference_reason=:reason " +
                "where id=:id and status='AUTO_CLOSED' and conference_status='NOT_CHECKED'";
        requireOne(jdbc.update(sql, new MapSqlParameterSource().addValue("id", sessionId)
                .addValue("at", Timestamp.from(checkedAt)).addValue("by", checkedBy).addValue("counted", countedBalance)
                .addValue("reason", differenceReason)));
    }

    @Override
    public List<CashMovement> listMovements(UUID sessionId) {
        return jdbc.query("select * from finance.cash_movement where cash_session_id=:id order by recorded_at,id",
                Map.of("id", sessionId), (rs, row) -> movement(rs));
    }

    @Override
    public Optional<CashMovement> findMovement(UUID movementId) {
        return oneMovement("select * from finance.cash_movement where id=:id", Map.of("id", movementId));
    }

    @Override
    public Optional<CashMovement> findMovementByIdempotencyKey(String key) {
        return oneMovement("select * from finance.cash_movement where idempotency_key=:key", Map.of("key", key));
    }

    @Override
    public Optional<CashMovement> findReceiptMovement(UUID receiptId) {
        return oneMovement("select * from finance.cash_movement where movement_type='RECEIPT' and receipt_id=:id",
                Map.of("id", receiptId));
    }

    @Override
    public Optional<CashMovement> findChangeMovement(UUID receiptId) {
        return oneMovement("select * from finance.cash_movement where movement_type='CHANGE' and receipt_id=:id",
                Map.of("id", receiptId));
    }

    @Override
    public Optional<CashMovement> findPayablePaymentMovement(UUID paymentId) {
        return oneMovement("select * from finance.cash_movement where movement_type='PAYABLE_PAYMENT' and payable_payment_id=:id",
                Map.of("id", paymentId));
    }

    private Optional<CashMovement> oneMovement(String sql, Map<String, ?> params) {
        return jdbc.query(sql, params, (rs, row) -> movement(rs)).stream().findFirst();
    }

    @Override
    public boolean isReversed(UUID movementId) {
        Integer count = jdbc.queryForObject("select count(*) from finance.cash_movement where movement_type='REVERSAL' and reversed_movement_id=:id",
                Map.of("id", movementId), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public void insertMovement(CashMovement movement) {
        String sql = "insert into finance.cash_movement " +
                "(id,cash_session_id,movement_type,direction,amount,reason,receipt_id,payable_payment_id,reversed_movement_id,recorded_at,recorded_by,idempotency_key) " +
                "values (:id,:session,:type,:direction,:amount,:reason,:receipt,:payment,:reversed,:at,:by,:key)";
        jdbc.update(sql, new MapSqlParameterSource().addValue("id", movement.id()).addValue("session", movement.cashSessionId())
                .addValue("type", movement.type().name()).addValue("direction", movement.direction().name())
                .addValue("amount", movement.amount()).addValue("reason", movement.reason()).addValue("receipt", movement.receiptId())
                .addValue("payment", movement.payablePaymentId()).addValue("reversed", movement.reversedMovementId())
                .addValue("at", Timestamp.from(movement.recordedAt())).addValue("by", movement.recordedBy())
                .addValue("key", movement.idempotencyKey()));
    }

    private static CashSession session(ResultSet rs) throws SQLException {
        return new CashSession(uuid(rs, "id"), CashSession.Status.valueOf(rs.getString("status")),
                CashSession.ConferenceStatus.valueOf(rs.getString("conference_status")), instant(rs, "opened_at"),
                uuid(rs, "opened_by"), rs.getBigDecimal("opening_expected_balance"), rs.getBigDecimal("opening_counted_balance"),
                rs.getString("opening_difference_reason"), instant(rs, "closed_at"), uuid(rs, "closed_by"),
                rs.getBigDecimal("closing_expected_balance"), rs.getBigDecimal("closing_counted_balance"),
                rs.getString("closing_difference_reason"), instant(rs, "checked_at"), uuid(rs, "checked_by"),
                rs.getBigDecimal("checked_counted_balance"), rs.getString("checked_difference_reason"));
    }

    private static CashMovement movement(ResultSet rs) throws SQLException {
        return new CashMovement(uuid(rs, "id"), uuid(rs, "cash_session_id"),
                CashMovement.Type.valueOf(rs.getString("movement_type")), CashMovement.Direction.valueOf(rs.getString("direction")),
                rs.getBigDecimal("amount"), rs.getString("reason"), uuid(rs, "receipt_id"), uuid(rs, "payable_payment_id"),
                uuid(rs, "reversed_movement_id"), instant(rs, "recorded_at"), uuid(rs, "recorded_by"), rs.getString("idempotency_key"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : (UUID) value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void requireOne(int rows) {
        if (rows != 1) throw new IllegalStateException("Estado concorrente da sessão de caixa");
    }
}
