package br.com.uberhidraulica.erp.purchasing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Ligação entre a parcela da compra e a conta a pagar no Financeiro (`D4`).
 *
 * <p>Existe porque {@code finance.payable} guarda o fornecedor como texto livre e não tinha como apontar para
 * a compra: sem esta tabela, as três contas da NF-e 21 ficariam órfãs e ninguém conseguiria provar que
 * pertencem à mesma nota. Não há chave estrangeira para {@code finance} de propósito — a fronteira entre
 * módulos é por id, e a existência da conta é garantida pelo caso de uso, na mesma transação.</p>
 */
public record PurchasePayableLink(UUID id, UUID purchaseId, UUID payableId, int installmentNo, LocalDate dueDate,
                                  BigDecimal amount, String invoiceReference, Instant createdAt) {

    public PurchasePayableLink {
        if (payableId == null) throw new PurchasingException("PURCHASE_INVALID", "A parcela exige a conta a pagar");
        if (installmentNo < 1) throw new PurchasingException("PURCHASE_INVALID", "Número de parcela deve ser positivo");
        if (dueDate == null) throw new PurchasingException("PURCHASE_DUE_DATE_REQUIRED", "Vencimento é obrigatório");
        PurchaseMoney.requirePositive(amount, "Valor da parcela");
        amount = PurchaseMoney.charged(amount);
    }
}
