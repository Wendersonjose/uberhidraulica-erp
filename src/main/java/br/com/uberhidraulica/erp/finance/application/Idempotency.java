package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.finance.domain.FinanceException;

/** Chave de idempotência enviada pelo cliente no cabeçalho {@code Idempotency-Key}. */
final class Idempotency {
    private Idempotency() {}

    static String require(String key) {
        if (key == null || key.isBlank())
            throw new FinanceException("IDEMPOTENCY_KEY_REQUIRED", "Cabeçalho Idempotency-Key é obrigatório");
        String trimmed = key.trim();
        if (trimmed.length() > 100) throw new FinanceException("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key excede 100 caracteres");
        return trimmed;
    }

    static FinanceException reused() {
        return new FinanceException("IDEMPOTENCY_KEY_REUSED", "Esta chave de idempotência já foi usada para outro pedido");
    }
}
