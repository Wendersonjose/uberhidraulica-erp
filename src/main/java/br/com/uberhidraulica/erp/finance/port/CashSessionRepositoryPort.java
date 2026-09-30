package br.com.uberhidraulica.erp.finance.port;

import br.com.uberhidraulica.erp.finance.domain.CashMovement;
import br.com.uberhidraulica.erp.finance.domain.CashSession;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CashSessionRepositoryPort {
    Optional<CashSession> findSession(UUID id);
    Optional<CashSession> findOpenSession();
    Optional<CashSession> lockOpenSession();
    BigDecimal suggestedOpeningBalance();
    BigDecimal expectedBalance(UUID sessionId);

    void insertSession(CashSession session);
    void closeManual(UUID sessionId, Instant closedAt, UUID closedBy, BigDecimal expectedBalance,
                     BigDecimal countedBalance, String differenceReason);
    void closeAutomatically(UUID sessionId, Instant closedAt, BigDecimal expectedBalance);
    void checkAutomaticallyClosed(UUID sessionId, Instant checkedAt, UUID checkedBy, BigDecimal countedBalance,
                                  String differenceReason);

    List<CashMovement> listMovements(UUID sessionId);
    Optional<CashMovement> findMovement(UUID movementId);
    Optional<CashMovement> findMovementByIdempotencyKey(String key);
    boolean isReversed(UUID movementId);
    void insertMovement(CashMovement movement);
}
