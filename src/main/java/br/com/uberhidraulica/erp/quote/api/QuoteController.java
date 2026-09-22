package br.com.uberhidraulica.erp.quote.api;

import br.com.uberhidraulica.erp.quote.application.QuoteApplicationService;
import br.com.uberhidraulica.erp.quote.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/work-orders/{workOrderId}/quotes")
public class QuoteController {
    private final QuoteApplicationService application;
    private final br.com.uberhidraulica.erp.quote.application.PublicQuoteService publicQuotes;

    public QuoteController(QuoteApplicationService application,
                           br.com.uberhidraulica.erp.quote.application.PublicQuoteService publicQuotes) {
        this.application = application;
        this.publicQuotes = publicQuotes;
    }

    @PostMapping
    ResponseEntity<Response> open(@PathVariable UUID workOrderId) {
        Response response = respond(application.open(workOrderId));
        return ResponseEntity
                .created(URI.create("/api/work-orders/" + workOrderId + "/quotes/" + response.id()))
                .body(response);
    }

    @GetMapping
    List<Response> list(@PathVariable UUID workOrderId) {
        return application.listByWorkOrder(workOrderId).stream().map(this::respond).toList();
    }

    @GetMapping("/{quoteId}")
    Response get(@PathVariable UUID workOrderId, @PathVariable UUID quoteId) {
        return respond(application.get(workOrderId, quoteId));
    }

    @GetMapping("/{quoteId}/history")
    List<HistoryEntry> history(@PathVariable UUID workOrderId, @PathVariable UUID quoteId) {
        return HistoryEntry.of(application.get(workOrderId, quoteId));
    }

    @PostMapping("/{quoteId}/revisions")
    ResponseEntity<Response> createRevision(@PathVariable UUID workOrderId, @PathVariable UUID quoteId,
                                            @Valid @RequestBody RevisionRequest request) {
        Quote quote = application.createRevision(workOrderId, quoteId, request.items().stream()
                .map(item -> new QuoteApplicationService.ItemSpec(item.quoteItemId(), item.workOrderServiceId(), item.workOrderProductId(),
                        item.description(), item.quantity(), item.unitPrice(), item.discount(), item.revisionReason()))
                .toList());
        return ResponseEntity.status(HttpStatus.CREATED).body(respond(quote));
    }

    @PostMapping("/{quoteId}/revisions/{revisionId}/present")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'QUOTE_PRESENT')")
    Response present(@PathVariable UUID workOrderId, @PathVariable UUID quoteId, @PathVariable UUID revisionId) {
        return respond(application.present(workOrderId, quoteId, revisionId));
    }

    /**
     * Registra a decisão que o cliente deu fora do link (pessoalmente, telefone, mensagem). Exige
     * {@code QUOTE_PRESENT}: quem pode fazer a proposta chegar ao cliente é quem registra a resposta dele.
     */
    @PostMapping("/{quoteId}/revisions/{revisionId}/decisions")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'QUOTE_PRESENT')")
    Response registerDecision(@PathVariable UUID workOrderId, @PathVariable UUID quoteId, @PathVariable UUID revisionId,
                              @Valid @RequestBody InternalDecisionRequest request) {
        var quote = publicQuotes.registerInternal(application.get(workOrderId, quoteId), revisionId, request.contactChannel(),
                request.authorizedBy(), request.notes(), request.decisions().stream()
                        .map(d -> new br.com.uberhidraulica.erp.quote.application.PublicQuoteService.ItemDecision(d.itemReference(), d.decision()))
                        .toList());
        return respond(quote);
    }

    public record InternalDecisionRequest(@NotBlank @Size(max = 16) String contactChannel, @Size(max = 200) String authorizedBy,
                                          @Size(max = 500) String notes, @NotEmpty @Valid List<InternalItemDecision> decisions) {}
    public record InternalItemDecision(@NotNull UUID itemReference, @NotNull DecisionType decision) {}

    /**
     * Emite o link público de uma apresentação. O token bruto aparece <b>uma única vez</b>, aqui.
     *
     * <p>Exige {@code QUOTE_PRESENT}: o link é o meio pelo qual a proposta chega ao cliente, então
     * emiti-lo tem a mesma autoridade de apresentá-la.</p>
     */
    @PostMapping("/{quoteId}/revisions/{revisionId}/public-access")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'QUOTE_PRESENT')")
    ResponseEntity<IssuedAccessResponse> issuePublicAccess(@PathVariable UUID workOrderId,
                                                           @PathVariable UUID quoteId,
                                                           @PathVariable UUID revisionId) {
        var issued = publicQuotes.issue(application.get(workOrderId, quoteId), revisionId, null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store")
                .body(IssuedAccessResponse.from(issued));
    }

    @GetMapping("/{quoteId}/public-access")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'QUOTE_PRESENT')")
    List<AccessResponse> listPublicAccess(@PathVariable UUID workOrderId, @PathVariable UUID quoteId) {
        Instant now = application.now();
        return publicQuotes.accesses(application.get(workOrderId, quoteId)).stream()
                .map(access -> AccessResponse.from(access, now)).toList();
    }

    @PostMapping("/{quoteId}/public-access/{accessId}/revoke")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'QUOTE_PRESENT')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokePublicAccess(@PathVariable UUID workOrderId, @PathVariable UUID quoteId,
                            @PathVariable UUID accessId) {
        publicQuotes.revoke(application.get(workOrderId, quoteId), accessId);
    }

    /** {@code token} só existe nesta resposta; o banco guarda apenas o digest. */
    public record IssuedAccessResponse(UUID accessId, UUID quoteRevisionId, String token,
                                       Instant validUntil) {
        static IssuedAccessResponse from(br.com.uberhidraulica.erp.quote.application.PublicQuoteService.IssuedAccess issued) {
            return new IssuedAccessResponse(issued.access().id(), issued.access().quoteRevisionId(),
                    issued.rawToken(), issued.access().validUntil());
        }
    }

    /** Nunca expõe o digest: o identificador do acesso é o que serve para correlacionar. */
    public record AccessResponse(UUID id, UUID quoteRevisionId, Instant createdAt, Instant validUntil,
                                 Instant revokedAt, boolean active) {
        static AccessResponse from(br.com.uberhidraulica.erp.quote.domain.PublicQuoteAccess access, Instant now) {
            return new AccessResponse(access.id(), access.quoteRevisionId(), access.createdAt(),
                    access.validUntil(), access.revokedAt(), !access.revoked() && !access.expiredAt(now));
        }
    }

    private Response respond(Quote quote) {
        return Response.from(quote, application.now(), publicQuotes.decisions(quote));
    }

    public record RevisionRequest(@NotEmpty @Valid List<ItemRequest> items) {}

    /**
     * Item pedido para a revisão.
     *
     * <p>{@code quoteItemId} ausente cria um novo item comercial; informado, produz nova versão do
     * item existente, ou reaproveita a atual quando os três campos comerciais coincidem.</p>
     */
    public record ItemRequest(UUID quoteItemId,
                              UUID workOrderServiceId,
                              UUID workOrderProductId,
                              @NotBlank @Size(max = 1000) String description,
                              @NotNull @DecimalMin(value = "0.0000", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal quantity,
                              @NotNull @DecimalMin("0.0000") @Digits(integer = 15, fraction = 4) BigDecimal unitPrice,
                              @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal discount,
                              @Size(max = 50) String revisionReason) {}

    /** {@code availableTotal} é a soma dos totais de item já arredondados que o cliente ainda pode aceitar. */
    public record Response(UUID id, UUID workOrderId, Instant createdAt, UUID createdBy,
                           BigDecimal availableTotal, List<RevisionResponse> revisions, List<ItemResponse> items) {
        static Response from(Quote quote, Instant now, java.util.Map<UUID, DecisionType> decisions) {
            return new Response(quote.id(), quote.workOrderId(), quote.createdAt(), quote.createdBy(),
                    quote.availableTotal(now),
                    quote.revisions().stream().map(revision -> RevisionResponse.from(quote, revision, now)).toList(),
                    quote.items().stream().map(item -> ItemResponse.from(quote, item, now, decisions)).toList());
        }
    }

    public record RevisionResponse(UUID id, int revisionNumber, RevisionStatus status, Instant presentedAt,
                                   Instant validUntil, boolean expired, BigDecimal total, Instant createdAt,
                                   UUID createdBy, List<EntryResponse> items) {
        static RevisionResponse from(Quote quote, QuoteRevision revision, Instant now) {
            return new RevisionResponse(revision.id(), revision.revisionNumber(), revision.status(),
                    revision.presentedAt(), revision.validUntil(), revision.expiredAt(now),
                    quote.revisionTotal(revision), revision.createdAt(), revision.createdBy(),
                    revision.entries().stream()
                            .map(entry -> new EntryResponse(entry.quoteItemRevisionId(), entry.displayOrder()))
                            .toList());
        }
    }

    public record EntryResponse(UUID quoteItemRevisionId, int displayOrder) {}

    public record ItemResponse(UUID id, UUID workOrderServiceId, UUID workOrderProductId, Instant createdAt,
                               List<ItemRevisionResponse> revisions) {
        static ItemResponse from(Quote quote, QuoteItem item, Instant now, java.util.Map<UUID, DecisionType> decisions) {
            return new ItemResponse(item.id(), item.workOrderServiceId(), item.workOrderProductId(), item.createdAt(),
                    item.revisions().stream()
                            .map(revision -> ItemRevisionResponse.from(quote, revision, now, decisions.get(revision.id()))).toList());
        }
    }

    /** {@code availability} é derivado a cada leitura: nenhuma coluna guarda obsolescência. */
    public record ItemRevisionResponse(UUID id, int revisionSequence, String description, BigDecimal quantity,
                                       BigDecimal unitPrice, BigDecimal discountAmount, BigDecimal grossTotal, BigDecimal totalPrice,
                                       String revisionReason, boolean presented, DecisionAvailability availability,
                                       DecisionType decision, Instant createdAt, UUID createdBy) {
        static ItemRevisionResponse from(Quote quote, QuoteItemRevision revision, Instant now, DecisionType decision) {
            return new ItemRevisionResponse(revision.id(), revision.revisionSequence(), revision.description(),
                    revision.quantity(), revision.unitPrice(), revision.discountAmount(), revision.grossTotal(), revision.totalPrice(),
                    revision.revisionReason(), quote.presentedSomewhere(revision.id()), quote.availability(revision, now),
                    decision, revision.createdAt(), revision.createdBy());
        }
    }

    /** Histórico derivado do próprio dado: não existe tabela de eventos nesta Task. */
    public record HistoryEntry(Instant occurredAt, String type, Integer revisionNumber, UUID quoteItemId,
                               Integer revisionSequence, String description) {
        static List<HistoryEntry> of(Quote quote) {
            List<HistoryEntry> entries = new ArrayList<>();
            for (QuoteRevision revision : quote.revisions()) {
                entries.add(new HistoryEntry(revision.createdAt(), "REVISION_CREATED",
                        revision.revisionNumber(), null, null, null));
                if (revision.presented()) entries.add(new HistoryEntry(revision.presentedAt(), "REVISION_PRESENTED",
                        revision.revisionNumber(), null, null, null));
            }
            for (QuoteItem item : quote.items())
                for (QuoteItemRevision revision : item.revisions())
                    entries.add(new HistoryEntry(revision.createdAt(), "ITEM_REVISION_CREATED", null,
                            item.id(), revision.revisionSequence(), revision.description()));
            return entries.stream()
                    .sorted(Comparator.comparing(HistoryEntry::occurredAt).thenComparing(HistoryEntry::type))
                    .toList();
        }
    }
}
