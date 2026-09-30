package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.finance.domain.CashMovement;
import br.com.uberhidraulica.erp.finance.domain.CashSession;
import br.com.uberhidraulica.erp.finance.domain.FinanceException;
import br.com.uberhidraulica.erp.finance.domain.Money;
import br.com.uberhidraulica.erp.finance.port.CashSessionRepositoryPort;
import br.com.uberhidraulica.erp.iam.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Regras de custódia física de dinheiro aprovadas na DR-0018. */
@Service
public class CashSessionService {
    private final CashSessionRepositoryPort repository;
    private final CurrentUser currentUser;
    private final Clock clock = Clock.systemUTC();

    public CashSessionService(CashSessionRepositoryPort repository, CurrentUser currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public BigDecimal suggestedOpeningBalance() {
        return repository.suggestedOpeningBalance().setScale(2, RoundingMode.UNNECESSARY);
    }

    @Transactional
    public CashSession open(BigDecimal countedBalance, String differenceReason) {
        BigDecimal counted = nonNegativeMoney(countedBalance, "Saldo físico inicial");
        if (repository.lockOpenSession().isPresent())
            throw new FinanceException("CASH_SESSION_ALREADY_OPEN", "Já existe uma sessão de caixa aberta");

        BigDecimal expected = suggestedOpeningBalance();
        String reason = differenceReason(expected, counted, differenceReason, "Justificativa da divergência de abertura");
        CashSession session = new CashSession(UUID.randomUUID(), CashSession.Status.OPEN, CashSession.ConferenceStatus.NOT_CHECKED,
                clock.instant(), currentUser.requireId(), expected, counted, reason,
                null, null, null, null, null, null, null, null, null);
        try {
            repository.insertSession(session);
        } catch (DataIntegrityViolationException concurrentOpen) {
            throw new FinanceException("CASH_SESSION_ALREADY_OPEN", "Outra sessão de caixa foi aberta ao mesmo tempo");
        }
        return session;
    }

    @Transactional
    public CashSession close(UUID sessionId, BigDecimal countedBalance, String differenceReason) {
        CashSession session = lockOpen(sessionId);
        BigDecimal expected = expectedBalance(session.id());
        BigDecimal counted = nonNegativeMoney(countedBalance, "Saldo físico contado");
        String reason = differenceReason(expected, counted, differenceReason, "Justificativa da divergência de fechamento");
        repository.closeManual(session.id(), clock.instant(), currentUser.requireId(), expected, counted, reason);
        return get(session.id());
    }

    @Transactional
    public CashSession checkAutomaticallyClosed(UUID sessionId, BigDecimal countedBalance, String differenceReason) {
        CashSession session = repository.findSession(sessionId)
                .orElseThrow(() -> new FinanceException("CASH_SESSION_NOT_FOUND", "Sessão de caixa não encontrada"));
        if (!session.autoClosedPendingCheck())
            throw new FinanceException("CASH_SESSION_NOT_PENDING_CHECK", "A sessão não está aguardando conferência");
        BigDecimal counted = nonNegativeMoney(countedBalance, "Saldo físico contado");
        BigDecimal expected = session.closingExpectedBalance();
        String reason = differenceReason(expected, counted, differenceReason, "Justificativa da divergência de conferência");
        repository.checkAutomaticallyClosed(sessionId, clock.instant(), currentUser.requireId(), counted, reason);
        return get(sessionId);
    }

    @Transactional
    public Recorded<CashMovement> supply(BigDecimal amount, String reason, String idempotencyKey) {
        return manual(CashMovement.Type.SUPPLY, CashMovement.Direction.IN, amount, reason, idempotencyKey);
    }

    @Transactional
    public Recorded<CashMovement> withdrawal(BigDecimal amount, String reason, String idempotencyKey) {
        return manual(CashMovement.Type.WITHDRAWAL, CashMovement.Direction.OUT, amount, reason, idempotencyKey);
    }

    @Transactional
    public Recorded<CashMovement> reverse(UUID movementId, String reason, String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        String normalized = Money.requiredText(reason, "Motivo do estorno", 500);
        CashMovement original = repository.findMovement(movementId)
                .orElseThrow(() -> new FinanceException("CASH_MOVEMENT_NOT_FOUND", "Movimentação de caixa não encontrada"));
        if (original.type() == CashMovement.Type.REVERSAL)
            throw new FinanceException("CASH_REVERSAL_OF_REVERSAL", "Um estorno não pode ser estornado diretamente");

        var replay = repository.findMovementByIdempotencyKey(key);
        if (replay.isPresent()) {
            CashMovement found = replay.get();
            if (found.type() != CashMovement.Type.REVERSAL || !movementId.equals(found.reversedMovementId())
                    || !normalized.equals(found.reason())) throw Idempotency.reused();
            return new Recorded<>(found, true);
        }
        if (repository.isReversed(movementId))
            throw new FinanceException("CASH_MOVEMENT_ALREADY_REVERSED", "Esta movimentação de caixa já foi estornada");

        CashSession open = requireOpenSession();
        CashMovement.Direction direction = original.direction() == CashMovement.Direction.IN
                ? CashMovement.Direction.OUT : CashMovement.Direction.IN;
        if (direction == CashMovement.Direction.OUT) requireAvailable(open.id(), original.amount());
        CashMovement reversal = new CashMovement(UUID.randomUUID(), open.id(), CashMovement.Type.REVERSAL, direction,
                original.amount(), normalized, null, null, original.id(), clock.instant(), currentUser.requireId(), key);
        try {
            repository.insertMovement(reversal);
        } catch (DataIntegrityViolationException collision) {
            if (repository.isReversed(movementId))
                throw new FinanceException("CASH_MOVEMENT_ALREADY_REVERSED", "Esta movimentação de caixa já foi estornada");
            throw Idempotency.reused();
        }
        return new Recorded<>(reversal, false);
    }

    @Transactional
    public void autoCloseOpenSession() {
        repository.lockOpenSession().ifPresent(session -> {
            BigDecimal expected = expectedBalance(session.id());
            repository.closeAutomatically(session.id(), clock.instant(), expected);
        });
    }

    @Transactional(readOnly = true)
    public CashSession get(UUID sessionId) {
        return repository.findSession(sessionId)
                .orElseThrow(() -> new FinanceException("CASH_SESSION_NOT_FOUND", "Sessão de caixa não encontrada"));
    }

    @Transactional(readOnly = true)
    public CashSession openSession() {
        return repository.findOpenSession()
                .orElseThrow(() -> new FinanceException("CASH_SESSION_REQUIRED", "Não há sessão de caixa aberta"));
    }

    @Transactional(readOnly = true)
    public List<CashMovement> movements(UUID sessionId) {
        get(sessionId);
        return repository.listMovements(sessionId);
    }

    @Transactional(readOnly = true)
    public BigDecimal expectedBalance(UUID sessionId) {
        return repository.expectedBalance(sessionId).setScale(2, RoundingMode.UNNECESSARY);
    }

    private Recorded<CashMovement> manual(CashMovement.Type type, CashMovement.Direction direction, BigDecimal amount,
                                          String reason, String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        BigDecimal value = Money.positive(amount, "Valor");
        String normalized = Money.requiredText(reason, "Motivo", 500);
        var replay = repository.findMovementByIdempotencyKey(key);
        if (replay.isPresent()) {
            CashMovement found = replay.get();
            if (found.type() != type || found.direction() != direction || found.amount().compareTo(value) != 0
                    || !normalized.equals(found.reason())) throw Idempotency.reused();
            return new Recorded<>(found, true);
        }
        CashSession open = requireOpenSession();
        if (direction == CashMovement.Direction.OUT) requireAvailable(open.id(), value);
        CashMovement movement = new CashMovement(UUID.randomUUID(), open.id(), type, direction, value, normalized,
                null, null, null, clock.instant(), currentUser.requireId(), key);
        try {
            repository.insertMovement(movement);
        } catch (DataIntegrityViolationException collision) {
            throw Idempotency.reused();
        }
        return new Recorded<>(movement, false);
    }

    private CashSession requireOpenSession() {
        return repository.lockOpenSession()
                .orElseThrow(() -> new FinanceException("CASH_SESSION_REQUIRED", "Operação em dinheiro exige sessão de caixa aberta"));
    }

    private CashSession lockOpen(UUID sessionId) {
        CashSession open = requireOpenSession();
        if (!open.id().equals(sessionId))
            throw new FinanceException("CASH_SESSION_NOT_OPEN", "A sessão informada não está aberta");
        return open;
    }

    private void requireAvailable(UUID sessionId, BigDecimal amount) {
        if (expectedBalance(sessionId).compareTo(amount) < 0)
            throw new FinanceException("CASH_AMOUNT_EXCEEDS_BALANCE", "A saída excede o saldo físico esperado do caixa");
    }

    private static BigDecimal nonNegativeMoney(BigDecimal value, String label) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > Money.SCALE)
            throw new FinanceException("INVALID_FINANCE_ENTRY", label + " deve ser maior ou igual a zero e possuir no máximo duas casas decimais");
        return value.setScale(Money.SCALE, RoundingMode.UNNECESSARY);
    }

    private static String differenceReason(BigDecimal expected, BigDecimal counted, String reason, String label) {
        if (expected.compareTo(counted) == 0) return Money.optionalText(reason, label, 500);
        return Money.requiredText(reason, label, 500);
    }
}
