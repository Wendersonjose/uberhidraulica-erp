package br.com.uberhidraulica.erp.finance.domain;

import java.time.Instant;
import java.util.UUID;

/** Categoria de conta a pagar em lista plana (DR-0015, F-12): cadastrada e inativada, nunca excluída. */
public record ExpenseCategory(UUID id, String name, boolean active, Instant createdAt, Instant updatedAt) {
    public ExpenseCategory {
        if (id == null || createdAt == null || updatedAt == null)
            throw new FinanceException("INVALID_EXPENSE_CATEGORY", "Dados obrigatórios da categoria ausentes");
        name = Money.requiredText(name, "Nome da categoria", 80);
    }
}
