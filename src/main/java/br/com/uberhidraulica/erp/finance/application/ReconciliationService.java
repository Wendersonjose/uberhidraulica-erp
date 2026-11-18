package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.finance.domain.FinanceException;
import br.com.uberhidraulica.erp.finance.domain.FinancialAccount;
import br.com.uberhidraulica.erp.finance.domain.Money;
import br.com.uberhidraulica.erp.finance.domain.Reconciliation;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort;
import br.com.uberhidraulica.erp.finance.port.ReconciliationRepositoryPort;
import br.com.uberhidraulica.erp.iam.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Contas financeiras e conciliação manual (DR-0024, Q7). Sem importação de extrato: o operador informa data, valor e
 * identificador da linha do extrato. O banco garante que um lançamento e uma referência de extrato só tenham uma
 * conciliação ativa cada.
 */
@Service
public class ReconciliationService {
    public static final long MAX_DAYS = 366;

    private final ReconciliationRepositoryPort repository;
    private final FinanceRepositoryPort finance;
    private final CurrentUser currentUser;
    private final Clock clock = Clock.systemUTC();

    public ReconciliationService(ReconciliationRepositoryPort repository, FinanceRepositoryPort finance, CurrentUser currentUser) {
        this.repository = repository;
        this.finance = finance;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ contas financeiras

    @Transactional(readOnly = true)
    public List<FinancialAccount> accounts() { return repository.accounts(); }

    @Transactional
    public FinancialAccount createAccount(String name, FinancialAccount.Type type, String bankName, String branch, String accountNumber) {
        Instant now = clock.instant();
        FinancialAccount account = new FinancialAccount(UUID.randomUUID(), name, type, bankName, branch, accountNumber, true, now, now);
        return save(account, true);
    }

    @Transactional
    public FinancialAccount updateAccount(UUID id, String name, String bankName, String branch, String accountNumber, boolean active) {
        FinancialAccount current = repository.account(id)
                .orElseThrow(() -> new FinanceException("FINANCIAL_ACCOUNT_NOT_FOUND", "Conta financeira não encontrada"));
        return save(new FinancialAccount(id, name, current.type(), bankName, branch, accountNumber, active, current.createdAt(),
                clock.instant()), false);
    }

    private FinancialAccount save(FinancialAccount account, boolean isNew) {
        try {
            repository.saveAccount(account, isNew);
        } catch (DataIntegrityViolationException duplicate) {
            throw new FinanceException("FINANCIAL_ACCOUNT_ALREADY_EXISTS", "Já existe uma conta com este nome");
        }
        return account;
    }

    // ------------------------------------------------------------------ conciliação

    @Transactional(readOnly = true)
    public List<ReconciliationRepositoryPort.EntryRow> entries(ReconciliationRepositoryPort.EntryFilter filter) {
        if (filter.from() == null || filter.to() == null || filter.to().isBefore(filter.from())
                || ChronoUnit.DAYS.between(filter.from(), filter.to()) >= MAX_DAYS)
            throw new FinanceException("INVALID_PERIOD", "Informe um período válido de até " + MAX_DAYS + " dias");
        return repository.entries(filter);
    }

    public record Request(UUID accountId, Reconciliation.EntryType entryType, UUID entryId, LocalDate statementDate,
                          BigDecimal statementAmount, String statementReference, String differenceReason) {}

    @Transactional
    public Recorded<Reconciliation> reconcile(Request request, String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        if (request.entryType() == null || request.entryId() == null)
            throw new FinanceException("INVALID_RECONCILIATION", "Informe o lançamento a conciliar");
        if (request.statementDate() == null) throw new FinanceException("INVALID_RECONCILIATION", "Data do extrato é obrigatória");
        finance.lockIdempotencyKey(key);
        var previous = repository.findByKey(key);
        if (previous.isPresent()) {
            Reconciliation found = previous.get();
            if (!found.entryId().equals(request.entryId()) || !found.accountId().equals(request.accountId())
                    || found.statementAmount().compareTo(Money.positive(request.statementAmount(), "Valor do extrato")) != 0
                    || !found.statementReference().equals(Money.requiredText(request.statementReference(), "Identificador do extrato", 100)))
                throw Idempotency.reused();
            return new Recorded<>(found, true);
        }
        FinancialAccount account = repository.account(request.accountId())
                .orElseThrow(() -> new FinanceException("FINANCIAL_ACCOUNT_NOT_FOUND", "Conta financeira não encontrada"));
        if (!account.active()) throw new FinanceException("FINANCIAL_ACCOUNT_INACTIVE", "Conta financeira inativa");
        ReconciliationRepositoryPort.Entry entry = repository.lockEntry(request.entryType(), request.entryId())
                .orElseThrow(() -> new FinanceException("RECONCILIATION_ENTRY_NOT_FOUND", "Lançamento não encontrado"));
        if (entry.reversed()) throw new FinanceException("RECONCILIATION_ENTRY_REVERSED", "Lançamento estornado não é conciliado");
        if (repository.activeFor(entry.type(), entry.id()).isPresent())
            throw new FinanceException("ENTRY_ALREADY_RECONCILED", "Este lançamento já está conciliado");
        Reconciliation reconciliation = new Reconciliation(UUID.randomUUID(), account.id(), entry.type(), entry.id(), request.statementDate(),
                request.statementAmount(), request.statementReference(), entry.netAmount(), request.differenceReason(), clock.instant(),
                currentUser.requireId(), null, null, null);
        try {
            repository.insert(reconciliation, key);
        } catch (DataIntegrityViolationException duplicate) {
            throw new FinanceException("STATEMENT_REFERENCE_ALREADY_USED",
                    "Esta referência de extrato já foi usada em outra conciliação desta conta");
        }
        return new Recorded<>(reconciliation, false);
    }

    @Transactional
    public Recorded<Reconciliation> reverse(UUID id, String reason, String idempotencyKey) {
        String key = Idempotency.require(idempotencyKey);
        String normalized = Money.requiredText(reason, "Motivo do estorno", 500);
        finance.lockIdempotencyKey(key);
        Reconciliation current = repository.lock(id)
                .orElseThrow(() -> new FinanceException("RECONCILIATION_NOT_FOUND", "Conciliação não encontrada"));
        var previous = repository.reversedWithKey(key);
        if (previous.isPresent()) {
            if (!previous.get().equals(id) || !normalized.equals(current.reversalReason())) throw Idempotency.reused();
            return new Recorded<>(current, true);
        }
        if (!current.active()) throw new FinanceException("RECONCILIATION_ALREADY_REVERSED", "Esta conciliação já foi desfeita");
        Reconciliation reversed = new Reconciliation(current.id(), current.accountId(), current.entryType(), current.entryId(),
                current.statementDate(), current.statementAmount(), current.statementReference(), current.expectedAmount(),
                current.differenceReason(), current.recordedAt(), current.recordedBy(), clock.instant(), currentUser.requireId(), normalized);
        repository.reverse(id, reversed, key);
        return new Recorded<>(reversed, false);
    }
}
