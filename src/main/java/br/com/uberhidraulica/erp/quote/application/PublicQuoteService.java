package br.com.uberhidraulica.erp.quote.application;

import br.com.uberhidraulica.erp.iam.CurrentUser;
import br.com.uberhidraulica.erp.quote.domain.*;
import br.com.uberhidraulica.erp.quote.port.PublicQuoteRepositoryPort;
import br.com.uberhidraulica.erp.quote.port.QuoteRepositoryPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class PublicQuoteService {
    private final QuoteRepositoryPort quotes;
    private final PublicQuoteRepositoryPort publicAccess;
    private final CurrentUser currentUser;
    private final Clock clock;

    public PublicQuoteService(QuoteRepositoryPort quotes, PublicQuoteRepositoryPort publicAccess,
                              CurrentUser currentUser, Clock clock) {
        this.quotes = quotes;
        this.publicAccess = publicAccess;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** Token bruto devolvido uma única vez, no instante da emissão. */
    public record IssuedAccess(PublicQuoteAccess access, String rawToken) {}

    public record PublicItemView(UUID itemReference, String description, BigDecimal quantity,
                                 BigDecimal unitPrice, BigDecimal totalPrice, DecisionStatus decisionStatus,
                                 PublicDecisionAvailability decisionAvailability) {}

    public record PublicQuoteView(UUID revisionReference, int revisionNumber, Instant presentedAt,
                                  Instant validUntil, BigDecimal total, List<PublicItemView> items) {}

    public record DecisionRequest(UUID revisionReference, String requestId, String customerName,
                                  DocumentType documentType, String documentNumber, boolean explicitAcceptance,
                                  List<ItemDecision> decisions, String ipAddress, String userAgent) {}

    public record ItemDecision(UUID itemReference, DecisionType decision) {}

    public record DecisionResult(PublicQuoteView view, boolean replayed) {}

    // ----- emissão e revogação, do lado interno -----

    @Transactional
    public IssuedAccess issue(Quote quote, UUID revisionId, Instant requestedValidUntil) {
        QuoteRevision revision = quote.revision(revisionId)
                .orElseThrow(() -> new QuoteException("QUOTE_REVISION_NOT_FOUND", "Revisão não encontrada"));
        String rawToken = PublicAccessToken.generate();
        PublicQuoteAccess access = PublicQuoteAccess.issue(revision, PublicAccessToken.digest(rawToken),
                Instant.now(clock), requestedValidUntil, currentUser.requireId());
        return new IssuedAccess(publicAccess.create(access), rawToken);
    }

    @Transactional
    public void revoke(Quote quote, UUID accessId) {
        PublicQuoteAccess access = publicAccess.findByQuoteId(quote.id()).stream()
                .filter(candidate -> candidate.id().equals(accessId)).findFirst()
                .orElseThrow(() -> new QuoteException("PUBLIC_QUOTE_ACCESS_NOT_FOUND", "Link público não encontrado"));
        if (!publicAccess.revoke(access.id(), Instant.now(clock)))
            throw new QuoteException("PUBLIC_QUOTE_ACCESS_ALREADY_REVOKED", "Link público já estava revogado");
    }

    @Transactional(readOnly = true)
    public List<PublicQuoteAccess> accesses(Quote quote) { return publicAccess.findByQuoteId(quote.id()); }

    // ----- lado público -----

    @Transactional(readOnly = true)
    public PublicQuoteView view(String rawToken) {
        Instant now = Instant.now(clock);
        PublicQuoteAccess access = authorize(rawToken, now);
        Quote quote = loadQuote(access);
        return render(quote, access, now);
    }

    /**
     * Consolida as decisões do cliente.
     *
     * <p>Tudo acontece em uma transação só, e a obsolescência é reconferida <b>dentro</b> dela, logo
     * antes de gravar. Validar antes de abrir a transação não bastaria: entre a validação e o commit,
     * uma apresentação concorrente poderia tornar a versão obsoleta e a decisão seria aceita sobre
     * uma condição que o cliente já não deveria poder aceitar.</p>
     *
     * <p>O ponto de serialização é a linha do orçamento: apresentar e decidir avançam a mesma versão,
     * de modo que a segunda operação encontra a versão mudada em vez de gravar sobre leitura vencida.</p>
     */
    @Transactional
    public DecisionResult decide(String rawToken, DecisionRequest request) {
        Instant now = Instant.now(clock);
        PublicQuoteAccess access = authorize(rawToken, now);

        if (request.revisionReference() == null || !request.revisionReference().equals(access.quoteRevisionId()))
            throw new QuoteException("QUOTE_REVISION_NOT_AUTHORIZED",
                    "A revisão informada não corresponde a este link");
        if (request.decisions() == null || request.decisions().isEmpty())
            throw new QuoteException("VALIDATION_ERROR", "Informe ao menos uma decisão");

        byte[] payloadDigest = canonicalDigest(request);
        Optional<DecisionSubmission> previous = publicAccess.findSubmission(access.id(), request.requestId());
        if (previous.isPresent()) return replay(previous.get(), payloadDigest, access, now);

        Quote quote = loadQuote(access);
        QuoteRevision revision = quote.revision(access.quoteRevisionId())
                .orElseThrow(() -> new QuoteException("PUBLIC_QUOTE_NOT_AVAILABLE", "Orçamento indisponível"));
        if (revision.expiredAt(now))
            throw new QuoteException("PUBLIC_QUOTE_EXPIRED", "A validade desta proposta terminou");

        Map<UUID, DecisionType> alreadyDecided = publicAccess.decisionsByQuote(quote.id());
        List<DecisionSubmission.Decision> decisions = validateAll(quote, revision, request, alreadyDecided, now);

        // Reconfirmação dentro da transação: se uma apresentação concorrente entrou entre a leitura e
        // este ponto, a versão do orçamento já mudou e nada é gravado.
        if (!quotes.touch(quote.id(), quote.version()))
            throw new QuoteException("CONCURRENT_MODIFICATION",
                    "O orçamento foi alterado durante o envio; recarregue antes de decidir");

        DecisionSubmission submission = new DecisionSubmission(UUID.randomUUID(), access.id(),
                revision.id(), quote.id(), request.requestId(), payloadDigest, request.customerName(),
                request.documentType(), request.documentNumber(), request.explicitAcceptance(), now,
                request.ipAddress(), request.userAgent(), decisions);
        try {
            publicAccess.save(submission);
        } catch (DataIntegrityViolationException collision) {
            // A constraint única da decisão por versão comercial é a última linha de defesa.
            throw new QuoteException("QUOTE_ITEM_ALREADY_DECIDED",
                    "Um dos itens recebeu decisão durante o envio; recarregue o orçamento");
        }
        return new DecisionResult(render(loadQuote(access), access, now), false);
    }

    // ----- apoio -----

    private PublicQuoteAccess authorize(String rawToken, Instant now) {
        PublicQuoteAccess access = publicAccess.findByTokenDigest(PublicAccessToken.digest(rawToken))
                .orElseThrow(PublicQuoteService::notAvailable);
        // Revogado responde como inexistente: confirmar que o link existiu entrega informação de graça.
        if (access.revoked()) throw notAvailable();
        if (access.expiredAt(now))
            throw new QuoteException("PUBLIC_QUOTE_EXPIRED", "Este link de orçamento expirou");
        return access;
    }

    private Quote loadQuote(PublicQuoteAccess access) {
        return quotes.findById(access.quoteId()).orElseThrow(PublicQuoteService::notAvailable);
    }

    private DecisionResult replay(DecisionSubmission previous, byte[] payloadDigest,
                                  PublicQuoteAccess access, Instant now) {
        if (!MessageDigest.isEqual(previous.payloadDigest(), payloadDigest))
            throw new QuoteException("IDEMPOTENCY_KEY_REUSED",
                    "Este identificador de envio já foi usado com outro conteúdo");
        return new DecisionResult(render(loadQuote(access), access, now), true);
    }

    /**
     * Valida todos os itens antes de gravar qualquer um.
     *
     * <p>A submissão é atômica: se um item não pode ser consolidado, nenhum outro do mesmo envio é.</p>
     */
    private List<DecisionSubmission.Decision> validateAll(Quote quote, QuoteRevision revision,
                                                          DecisionRequest request,
                                                          Map<UUID, DecisionType> alreadyDecided, Instant now) {
        Set<UUID> seen = new LinkedHashSet<>();
        List<DecisionSubmission.Decision> decisions = new ArrayList<>();
        for (ItemDecision item : request.decisions()) {
            if (item.itemReference() == null || item.decision() == null)
                throw new QuoteException("VALIDATION_ERROR", "Decisão incompleta");
            if (!seen.add(item.itemReference()))
                throw new QuoteException("VALIDATION_ERROR", "O mesmo item foi informado mais de uma vez");
            if (!revision.contains(item.itemReference()))
                throw new QuoteException("QUOTE_ITEM_NOT_IN_REVISION",
                        "O item informado não faz parte desta apresentação");
            QuoteItemRevision itemRevision = quote.itemRevision(item.itemReference())
                    .orElseThrow(() -> new QuoteException("QUOTE_ITEM_NOT_IN_REVISION",
                            "O item informado não faz parte desta apresentação"));
            if (alreadyDecided.containsKey(itemRevision.id()))
                throw new QuoteException("QUOTE_ITEM_ALREADY_DECIDED", "Este item já recebeu decisão");
            // Rascunho posterior não torna obsoleto; apresentação posterior do mesmo item torna.
            if (quote.superseded(itemRevision))
                throw new QuoteException("QUOTE_ITEM_REVISION_STALE",
                        "Este item foi atualizado. Recarregue o orçamento antes de continuar");
            decisions.add(new DecisionSubmission.Decision(UUID.randomUUID(), itemRevision.id(),
                    item.decision(), now));
        }
        return decisions;
    }

    /** Projeção pública: só o que a decisão exige, sem custo, margem, fornecedor ou dado interno. */
    private PublicQuoteView render(Quote quote, PublicQuoteAccess access, Instant now) {
        QuoteRevision revision = quote.revision(access.quoteRevisionId()).orElseThrow(PublicQuoteService::notAvailable);
        Map<UUID, DecisionType> decided = publicAccess.decisionsByQuote(quote.id());
        List<PublicItemView> items = revision.entries().stream()
                .map(entry -> quote.itemRevision(entry.quoteItemRevisionId()))
                .flatMap(Optional::stream)
                .map(itemRevision -> new PublicItemView(itemRevision.id(), itemRevision.description(),
                        itemRevision.quantity(), itemRevision.unitPrice(), itemRevision.totalPrice(),
                        status(decided.get(itemRevision.id())),
                        availability(quote, itemRevision, decided)))
                .toList();
        // O prazo que vale para o cliente é o menor entre a proposta e a credencial.
        Instant validUntil = access.validUntil().isBefore(revision.validUntil())
                ? access.validUntil() : revision.validUntil();
        return new PublicQuoteView(revision.id(), revision.revisionNumber(), revision.presentedAt(),
                validUntil, quote.revisionTotal(revision), items);
    }

    private static DecisionStatus status(DecisionType decision) {
        if (decision == null) return DecisionStatus.PENDING_APPROVAL;
        return decision == DecisionType.APPROVE ? DecisionStatus.APPROVED : DecisionStatus.REJECTED;
    }

    private static PublicDecisionAvailability availability(Quote quote, QuoteItemRevision itemRevision,
                                                           Map<UUID, DecisionType> decided) {
        if (decided.containsKey(itemRevision.id())) return PublicDecisionAvailability.ALREADY_DECIDED;
        if (quote.superseded(itemRevision)) return PublicDecisionAvailability.SUPERSEDED;
        return PublicDecisionAvailability.DECIDABLE;
    }

    /**
     * Forma canônica do conteúdo, base da idempotência.
     *
     * <p>Ordena as decisões para que a mesma intenção enviada em ordem diferente continue sendo o
     * mesmo envio, e não um conflito de reuso.</p>
     */
    private static byte[] canonicalDigest(DecisionRequest request) {
        String canonical = request.revisionReference() + "|"
                + String.valueOf(request.customerName()).trim() + "|"
                + request.documentType() + "|"
                + String.valueOf(request.documentNumber()).replaceAll("[^0-9]", "") + "|"
                + request.explicitAcceptance() + "|"
                + request.decisions().stream()
                .map(item -> item.itemReference() + "=" + item.decision())
                .sorted()
                .reduce("", (left, right) -> left + right + ";");
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponível na plataforma", impossible);
        }
    }

    private static QuoteException notAvailable() {
        return new QuoteException("PUBLIC_QUOTE_NOT_AVAILABLE", "Orçamento indisponível");
    }
}
