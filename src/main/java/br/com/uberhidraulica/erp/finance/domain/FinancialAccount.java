package br.com.uberhidraulica.erp.finance.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Conta financeira (DR-0024, Q7): caixa físico, conta bancária ou conta de cartão. Cadastro gerencial para conciliar
 * lançamentos contra extratos; inativada, nunca excluída. Não guarda senha, token nem credencial de banco.
 */
public record FinancialAccount(UUID id, String name, Type type, String bankName, String branch, String accountNumber, boolean active,
                               Instant createdAt, Instant updatedAt) {

    public enum Type { CASH, BANK, CARD }

    public FinancialAccount {
        if (id == null || type == null || createdAt == null || updatedAt == null)
            throw new FinanceException("INVALID_FINANCIAL_ACCOUNT", "Dados obrigatórios da conta financeira ausentes");
        name = Money.requiredText(name, "Nome da conta", 80);
        bankName = Money.optionalText(bankName, "Banco", 80);
        branch = Money.optionalText(branch, "Agência", 20);
        accountNumber = Money.optionalText(accountNumber, "Número da conta", 30);
    }
}
