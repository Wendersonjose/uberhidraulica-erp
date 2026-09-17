package br.com.uberhidraulica.erp.quote.api;

import br.com.uberhidraulica.erp.quote.application.PublicQuoteService;
import br.com.uberhidraulica.erp.quote.domain.DecisionStatus;
import br.com.uberhidraulica.erp.quote.domain.DecisionType;
import br.com.uberhidraulica.erp.quote.domain.DocumentType;
import br.com.uberhidraulica.erp.quote.domain.PublicDecisionAvailability;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Superfície pública do orçamento.
 *
 * <p>O cliente não tem conta, sessão nem perfil: a autorização é o token opaco do caminho, e ele
 * alcança exatamente uma apresentação. Nenhum dado interno da oficina atravessa daqui para fora —
 * sem custo, margem, fornecedor, outras OS ou identificadores que não sejam necessários à decisão.</p>
 */
@RestController
@RequestMapping("/api/public/quotes")
public class PublicQuoteController {
    private final PublicQuoteService service;

    public PublicQuoteController(PublicQuoteService service) { this.service = service; }

    @GetMapping("/{token}")
    ResponseEntity<PublicQuoteResponse> view(@PathVariable String token) {
        // Abrir o link é leitura: não aprova, não rejeita e não cria decisão.
        return noStore(HttpStatus.OK, PublicQuoteResponse.from(service.view(token)));
    }

    @PostMapping("/{token}/decisions")
    ResponseEntity<DecisionResponse> decide(@PathVariable String token,
                                            @Valid @RequestBody DecisionRequest body,
                                            HttpServletRequest request) {
        PublicQuoteService.DecisionResult result = service.decide(token, new PublicQuoteService.DecisionRequest(
                body.revisionReference(), body.requestId(), body.customer().name(),
                body.customer().documentType(), body.customer().documentNumber(), body.explicitAcceptance(),
                body.decisions().stream()
                        .map(item -> new PublicQuoteService.ItemDecision(item.itemReference(), item.decision()))
                        .toList(),
                // O endereço vem da conexão, nunca de um cabeçalho que o próprio cliente controla.
                request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT)));
        return noStore(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED,
                new DecisionResponse(PublicQuoteResponse.from(result.view()), result.replayed()));
    }

    /**
     * Resposta pública sem rastro em cache nem no {@code Referer}.
     *
     * <p>O token viaja no caminho da URL; um cache compartilhado guardando essa resposta, ou um
     * {@code Referer} carregando o caminho para outro site, vazaria o segredo.</p>
     */
    private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .body(body);
    }

    public record DecisionRequest(@NotNull UUID revisionReference,
                                  @NotBlank @Size(max = 100) String requestId,
                                  @NotNull @Valid Customer customer,
                                  boolean explicitAcceptance,
                                  @NotEmpty @Valid List<ItemDecisionRequest> decisions) {}

    public record Customer(@NotBlank @Size(max = 200) String name,
                           @NotNull DocumentType documentType,
                           @NotBlank @Size(max = 18) String documentNumber) {}

    public record ItemDecisionRequest(@NotNull UUID itemReference, @NotNull DecisionType decision) {}

    public record PublicQuoteResponse(UUID revisionReference, int revisionNumber, Instant presentedAt,
                                      Instant validUntil, BigDecimal total, List<PublicItemResponse> items) {
        static PublicQuoteResponse from(PublicQuoteService.PublicQuoteView view) {
            return new PublicQuoteResponse(view.revisionReference(), view.revisionNumber(), view.presentedAt(),
                    view.validUntil(), view.total(), view.items().stream().map(PublicItemResponse::from).toList());
        }
    }

    public record PublicItemResponse(UUID itemReference, String description, BigDecimal quantity,
                                     BigDecimal unitPrice, BigDecimal discountAmount, BigDecimal totalPrice,
                                     DecisionStatus decisionStatus,
                                     PublicDecisionAvailability decisionAvailability) {
        static PublicItemResponse from(PublicQuoteService.PublicItemView item) {
            return new PublicItemResponse(item.itemReference(), item.description(), item.quantity(),
                    item.unitPrice(), item.discountAmount(), item.totalPrice(), item.decisionStatus(), item.decisionAvailability());
        }
    }

    public record DecisionResponse(PublicQuoteResponse quote, boolean replayed) {}
}
