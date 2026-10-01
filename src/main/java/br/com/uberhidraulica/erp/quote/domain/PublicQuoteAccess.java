package br.com.uberhidraulica.erp.quote.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Credencial pública limitada a uma apresentação específica.
 *
 * <p>Não é autorização global: o token só alcança a revisão para a qual foi emitido, e conhecer um
 * identificador interno não substitui possuí-lo.</p>
 */
public record PublicQuoteAccess(UUID id, UUID quoteId, UUID quoteRevisionId, byte[] tokenDigest,
                                Instant createdAt, Instant validUntil, Instant revokedAt, UUID createdBy) {

    public PublicQuoteAccess {
        if (id == null || quoteId == null || quoteRevisionId == null || createdAt == null
                || validUntil == null || createdBy == null)
            throw invalid("Dados obrigatórios ausentes");
        if (tokenDigest == null || tokenDigest.length != PublicAccessToken.digestLength())
            throw invalid("Digest do token inválido");
        if (validUntil.isBefore(createdAt)) throw invalid("Validade anterior à emissão");
        if (revokedAt != null && revokedAt.isBefore(createdAt)) throw invalid("Revogação anterior à emissão");
        tokenDigest = tokenDigest.clone();
    }

    @Override
    public byte[] tokenDigest() { return tokenDigest.clone(); }

    /**
     * Emite o acesso para uma apresentação.
     *
     * <p>A validade da credencial nunca ultrapassa a validade comercial: um link que sobrevivesse à
     * proposta permitiria decidir sobre algo que já não está mais de pé. A verificação vive na
     * aplicação porque um {@code CHECK} do PostgreSQL não consulta outra tabela.</p>
     */
    public static PublicQuoteAccess issue(QuoteRevision revision, byte[] tokenDigest, Instant now,
                                          Instant requestedValidUntil, UUID author) {
        if (!revision.presented())
            throw new QuoteException("QUOTE_REVISION_NOT_PRESENTED",
                    "Somente uma revisão apresentada pode gerar link público");
        Instant validUntil = requestedValidUntil == null || requestedValidUntil.isAfter(revision.validUntil())
                ? revision.validUntil()
                : requestedValidUntil;
        if (validUntil.isBefore(now))
            throw new QuoteException("QUOTE_REVISION_EXPIRED",
                    "A validade comercial desta apresentação já terminou");
        return new PublicQuoteAccess(UUID.randomUUID(), revision.quoteId(), revision.id(), tokenDigest,
                now, validUntil, null, author);
    }

    public boolean revoked() { return revokedAt != null; }

    public boolean expiredAt(Instant now) { return validUntil.isBefore(now); }

    private static QuoteException invalid(String message) {
        return new QuoteException("INVALID_PUBLIC_QUOTE_ACCESS", message);
    }
}
