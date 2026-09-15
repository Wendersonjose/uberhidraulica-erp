package br.com.uberhidraulica.erp.quote.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Condição comercial específica e historicamente imutável de um {@link QuoteItem}.
 *
 * <p>É o que o cliente efetivamente recebeu. Depois de apresentada, descrição, quantidade e preço
 * nunca são atualizados: alteração comercial cria uma nova versão (RN-04 a RN-06, RN-08).</p>
 */
public record QuoteItemRevision(UUID id, UUID quoteId, UUID quoteItemId, int revisionSequence,
                                String description, BigDecimal quantity, BigDecimal unitPrice,
                                BigDecimal totalPrice, String revisionReason,
                                Instant createdAt, UUID createdBy) {

    /** Escala de valores unitários e de quantidade, conforme a DR-0007. */
    public static final int UNIT_SCALE = 4;
    /** Escala do valor efetivamente cobrado do cliente, conforme a DR-0007. */
    public static final int CHARGED_SCALE = 2;
    /** Arredondamento oficial do valor cobrado, conforme a DR-0007. */
    public static final RoundingMode CHARGED_ROUNDING = RoundingMode.HALF_UP;

    public QuoteItemRevision {
        if (id == null || quoteId == null || quoteItemId == null || createdAt == null || createdBy == null)
            throw invalid("Dados obrigatórios ausentes");
        if (revisionSequence <= 0) throw invalid("Sequência da versão comercial inválida");
        description = requiredDescription(description);
        quantity = validQuantity(quantity);
        unitPrice = validUnitPrice(unitPrice);
        totalPrice = checkedTotal(quantity, unitPrice, totalPrice);
        revisionReason = revisionReason == null || revisionReason.isBlank() ? null : revisionReason.trim();
        if (revisionReason != null && revisionReason.length() > 50) throw invalid("Motivo da versão inválido");
    }

    public static QuoteItemRevision first(UUID quoteId, UUID quoteItemId, String description,
                                          BigDecimal quantity, BigDecimal unitPrice, String reason,
                                          Instant now, UUID author) {
        return new QuoteItemRevision(UUID.randomUUID(), quoteId, quoteItemId, 1, description,
                quantity, unitPrice, null, reason, now, author);
    }

    /** Nova condição comercial do mesmo item; a anterior permanece intacta como histórico. */
    public QuoteItemRevision next(String description, BigDecimal quantity, BigDecimal unitPrice,
                                  String reason, Instant now, UUID author) {
        return new QuoteItemRevision(UUID.randomUUID(), quoteId, quoteItemId, revisionSequence + 1,
                description, quantity, unitPrice, null, reason, now, author);
    }

    /** Verdadeiro quando os três campos comerciais coincidem, permitindo reaproveitar esta versão. */
    public boolean sameCommercialTerms(String otherDescription, BigDecimal otherQuantity, BigDecimal otherUnitPrice) {
        return description.equals(requiredDescription(otherDescription))
                && quantity.compareTo(validQuantity(otherQuantity)) == 0
                && unitPrice.compareTo(validUnitPrice(otherUnitPrice)) == 0;
    }

    /**
     * Total do item conforme a política monetária aprovada (DR-0007).
     *
     * <p>A multiplicação usa a precisão integral do {@code BigDecimal} — os operandos nunca são
     * arredondados antes — e somente o resultado é levado a duas casas com {@code HALF_UP}. Arredondar
     * os operandos primeiro produziria um total diferente do que a conta real dá.</p>
     */
    public static BigDecimal total(BigDecimal quantity, BigDecimal unitPrice) {
        return quantity.multiply(unitPrice).setScale(CHARGED_SCALE, CHARGED_ROUNDING);
    }

    private static String requiredDescription(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 1000) throw invalid("Descrição inválida");
        return value.trim();
    }

    private static BigDecimal validQuantity(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > UNIT_SCALE)
            throw invalid("Quantidade deve ser positiva e possuir no máximo quatro casas decimais");
        return value.setScale(UNIT_SCALE, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal validUnitPrice(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > UNIT_SCALE)
            throw invalid("Preço unitário deve ser não negativo e possuir no máximo quatro casas decimais");
        return value.setScale(UNIT_SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * O total é sempre derivado da fórmula; quando vem persistido, é conferido em vez de aceito.
     *
     * <p>Recalcular e ignorar o valor gravado esconderia uma linha adulterada; recusar deixa a
     * divergência visível.</p>
     */
    private static BigDecimal checkedTotal(BigDecimal quantity, BigDecimal unitPrice, BigDecimal persisted) {
        BigDecimal expected = total(quantity, unitPrice);
        if (persisted != null && persisted.compareTo(expected) != 0)
            throw new QuoteException("QUOTE_ITEM_REVISION_TOTAL_MISMATCH",
                    "Total gravado diverge de quantidade × preço unitário");
        return expected;
    }

    private static QuoteException invalid(String message) {
        return new QuoteException("INVALID_QUOTE_ITEM_REVISION", message);
    }
}
