package br.com.uberhidraulica.erp.quote.port;

import br.com.uberhidraulica.erp.quote.domain.DecisionSubmission;
import br.com.uberhidraulica.erp.quote.domain.DecisionType;
import br.com.uberhidraulica.erp.quote.domain.PublicQuoteAccess;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface PublicQuoteRepositoryPort {
    PublicQuoteAccess create(PublicQuoteAccess access);

    /** Busca pelo digest: o token bruto nunca chega à persistência. */
    Optional<PublicQuoteAccess> findByTokenDigest(byte[] tokenDigest);

    List<PublicQuoteAccess> findByQuoteId(UUID quoteId);

    /** @return {@code false} quando o acesso não existe ou já estava revogado */
    boolean revoke(UUID accessId, Instant revokedAt);

    /** Decisões efetivas por versão comercial; ausência na coleção significa pendente. */
    Map<UUID, DecisionType> decisionsByQuote(UUID quoteId);

    /** Submissão anterior com o mesmo par acesso/requestId, base da idempotência. */
    Optional<DecisionSubmission> findSubmission(UUID publicQuoteAccessId, String requestId);

    void save(DecisionSubmission submission);
}
