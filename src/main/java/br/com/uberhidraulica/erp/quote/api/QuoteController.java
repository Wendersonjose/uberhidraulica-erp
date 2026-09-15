package br.com.uberhidraulica.erp.quote.api;

import br.com.uberhidraulica.erp.quote.application.QuoteApplicationService;
import br.com.uberhidraulica.erp.quote.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    public QuoteController(QuoteApplicationService application) { this.application = application; }

    @PostMapping
    ResponseEntity<Response> open(@PathVariable UUID workOrderId) {
        Response response = Response.from(application.open(workOrderId), application.now());
        return ResponseEntity
                .created(URI.create("/api/work-orders/" + workOrderId + "/quotes/" + response.id()))
                .body(response);
    }

    @GetMapping
    List<Response> list(@PathVariable UUID workOrderId) {
        Instant now = application.now();
        return application.listByWorkOrder(workOrderId).stream().map(quote -> Response.from(quote, now)).toList();
    }

    @GetMapping("/{quoteId}")
    Response get(@PathVariable UUID workOrderId, @PathVariable UUID quoteId) {
        return Response.from(application.get(workOrderId, quoteId), application.now());
    }

    @GetMapping("/{quoteId}/history")
    List<HistoryEntry> history(@PathVariable UUID workOrderId, @PathVariable UUID quoteId) {
        return HistoryEntry.of(application.get(workOrderId, quoteId));
    }

    @PostMapping("/{quoteId}/revisions")
    ResponseEntity<Response> createRevision(@PathVariable UUID workOrderId, @PathVariable UUID quoteId,
                                            @Valid @RequestBody RevisionRequest request) {
        Quote quote = application.createRevision(workOrderId, quoteId, request.items().stream()
                .map(item -> new QuoteApplicationService.ItemSpec(item.quoteItemId(), item.workOrderServiceId(),
                        item.description(), item.quantity(), item.unitPrice(), item.revisionReason()))
                .toList());
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.from(quote, application.now()));
    }

    @PostMapping("/{quoteId}/revisions/{revisionId}/present")
    Response present(@PathVariable UUID workOrderId, @PathVariable UUID quoteId, @PathVariable UUID revisionId) {
        return Response.from(application.present(workOrderId, quoteId, revisionId), application.now());
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
                              @NotBlank @Size(max = 1000) String description,
                              @NotNull @DecimalMin(value = "0.000", inclusive = false) @Digits(integer = 15, fraction = 3) BigDecimal quantity,
                              @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal unitPrice,
                              @Size(max = 50) String revisionReason) {}

    public record Response(UUID id, UUID workOrderId, Instant createdAt, UUID createdBy,
                           List<RevisionResponse> revisions, List<ItemResponse> items) {
        static Response from(Quote quote, Instant now) {
            return new Response(quote.id(), quote.workOrderId(), quote.createdAt(), quote.createdBy(),
                    quote.revisions().stream().map(revision -> RevisionResponse.from(revision, now)).toList(),
                    quote.items().stream().map(item -> ItemResponse.from(quote, item, now)).toList());
        }
    }

    public record RevisionResponse(UUID id, int revisionNumber, RevisionStatus status, Instant presentedAt,
                                   Instant validUntil, boolean expired, Instant createdAt, UUID createdBy,
                                   List<EntryResponse> items) {
        static RevisionResponse from(QuoteRevision revision, Instant now) {
            return new RevisionResponse(revision.id(), revision.revisionNumber(), revision.status(),
                    revision.presentedAt(), revision.validUntil(), revision.expiredAt(now),
                    revision.createdAt(), revision.createdBy(),
                    revision.entries().stream()
                            .map(entry -> new EntryResponse(entry.quoteItemRevisionId(), entry.displayOrder()))
                            .toList());
        }
    }

    public record EntryResponse(UUID quoteItemRevisionId, int displayOrder) {}

    public record ItemResponse(UUID id, UUID workOrderServiceId, Instant createdAt,
                               List<ItemRevisionResponse> revisions) {
        static ItemResponse from(Quote quote, QuoteItem item, Instant now) {
            return new ItemResponse(item.id(), item.workOrderServiceId(), item.createdAt(),
                    item.revisions().stream()
                            .map(revision -> ItemRevisionResponse.from(quote, revision, now)).toList());
        }
    }

    /** {@code availability} é derivado a cada leitura: nenhuma coluna guarda obsolescência. */
    public record ItemRevisionResponse(UUID id, int revisionSequence, String description, BigDecimal quantity,
                                       BigDecimal unitPrice, BigDecimal totalPrice, String revisionReason,
                                       boolean presented, DecisionAvailability availability, Instant createdAt,
                                       UUID createdBy) {
        static ItemRevisionResponse from(Quote quote, QuoteItemRevision revision, Instant now) {
            return new ItemRevisionResponse(revision.id(), revision.revisionSequence(), revision.description(),
                    revision.quantity(), revision.unitPrice(), revision.totalPrice(), revision.revisionReason(),
                    quote.presentedSomewhere(revision.id()), quote.availability(revision, now),
                    revision.createdAt(), revision.createdBy());
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
