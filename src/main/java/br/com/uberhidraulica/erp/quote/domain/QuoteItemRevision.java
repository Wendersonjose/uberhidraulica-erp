package br.com.uberhidraulica.erp.quote.domain;

import java.math.BigDecimal;
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

    /** Escala de persistência dos valores comerciais, conforme `NUMERIC(19,4)` do modelo aprovado. */
    public static final int MONEY_SCALE = 4;
    /** Casas aceitas na entrada de quantidade, alinhadas ao item físico da OS (DR-0006). */
    public static final int QUANTITY_INPUT_SCALE = 3;
    /** Casas aceitas na entrada de preço unitário, alinhadas ao restante do sistema. */
    public static final int PRICE_INPUT_SCALE = 2;

    public QuoteItemRevision {
        if (id == null || quoteId == null || quoteItemId == null || createdAt == null || createdBy == null)
            throw invalid("Dados obrigatórios ausentes");
        if (revisionSequence <= 0) throw invalid("Sequência da versão comercial inválida");
        description = requiredDescription(description);
        quantity = validQuantity(quantity);
        unitPrice = validUnitPrice(unitPrice);
        totalPrice = exactTotal(quantity, unitPrice);
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

    private static String requiredDescription(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 1000) throw invalid("Descrição inválida");
        return value.trim();
    }

    private static BigDecimal validQuantity(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > QUANTITY_INPUT_SCALE)
            throw invalid("Quantidade deve ser positiva e possuir no máximo três casas decimais");
        return value.setScale(MONEY_SCALE, java.math.RoundingMode.UNNECESSARY);
    }

    private static BigDecimal validUnitPrice(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > PRICE_INPUT_SCALE)
            throw invalid("Preço unitário deve ser não negativo e possuir no máximo duas casas decimais");
        return value.setScale(MONEY_SCALE, java.math.RoundingMode.UNNECESSARY);
    }

    /**
     * Total exato do item, sem arredondar.
     *
     * <p>A regra de arredondamento comercial ainda não foi decidida (DR-0006/DR-0007). Enquanto isso,
     * o único caminho honesto é recusar o caso que exigiria escolher uma: arredondar por conta própria
     * mudaria o valor cobrado do cliente por decisão de implementação.</p>
     */
    private static BigDecimal exactTotal(BigDecimal quantity, BigDecimal unitPrice) {
        BigDecimal exact = quantity.multiply(unitPrice).stripTrailingZeros();
        if (exact.scale() > MONEY_SCALE)
            throw new QuoteException("QUOTE_TOTAL_REQUIRES_ROUNDING_DECISION",
                    "Quantidade e preço geram total com mais de quatro casas decimais; a regra de arredondamento comercial ainda não foi decidida");
        return exact.setScale(MONEY_SCALE, java.math.RoundingMode.UNNECESSARY);
    }

    private static QuoteException invalid(String message) {
        return new QuoteException("INVALID_QUOTE_ITEM_REVISION", message);
    }
}
