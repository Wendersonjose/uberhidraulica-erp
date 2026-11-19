package br.com.uberhidraulica.erp.finance.port;

import br.com.uberhidraulica.erp.finance.domain.FinancialAccount;
import br.com.uberhidraulica.erp.finance.domain.Reconciliation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Contas financeiras e conciliação manual (DR-0024, Q7). */
public interface ReconciliationRepositoryPort {

    List<FinancialAccount> accounts();

    Optional<FinancialAccount> account(UUID id);

    void saveAccount(FinancialAccount account, boolean isNew);

    /** Lançamento conciliável: recebimento ou pagamento ainda não estornado. Bloqueia a linha do lançamento. */
    Optional<Entry> lockEntry(Reconciliation.EntryType type, UUID entryId);

    /** Conciliação ativa (não estornada) do lançamento, se houver. */
    Optional<Reconciliation> activeFor(Reconciliation.EntryType type, UUID entryId);

    Optional<Reconciliation> find(UUID id);

    Optional<Reconciliation> lock(UUID id);

    Optional<Reconciliation> findByKey(String idempotencyKey);

    Optional<UUID> reversedWithKey(String idempotencyKey);

    void insert(Reconciliation reconciliation, String idempotencyKey);

    void reverse(UUID id, Reconciliation reversed, String idempotencyKey);

    List<EntryRow> entries(EntryFilter filter);

    /**
     * @param amount    valor bruto do lançamento
     * @param netAmount o que entra ou sai da conta: bruto menos taxa de cartão no recebimento
     */
    record Entry(Reconciliation.EntryType type, UUID id, LocalDate effectiveOn, BigDecimal amount, BigDecimal netAmount, boolean reversed) {}

    record EntryFilter(LocalDate from, LocalDate to, Reconciliation.EntryType type, Boolean reconciled, UUID accountId) {}

    /** Lançamento do período e a sua conciliação ativa, se houver. */
    record EntryRow(Reconciliation.EntryType type, UUID id, UUID ownerId, String ownerLabel, LocalDate effectiveOn, String paymentMethodName,
                    BigDecimal amount, BigDecimal netAmount, LocalDate expectedSettlementOn, Reconciliation reconciliation, String accountName) {}
}
