package br.com.uberhidraulica.erp.purchasing.api;

import br.com.uberhidraulica.erp.purchasing.application.PurchaseService;
import br.com.uberhidraulica.erp.purchasing.application.PurchasingSettingsService;
import br.com.uberhidraulica.erp.purchasing.domain.AcquisitionCost;
import br.com.uberhidraulica.erp.purchasing.domain.Purchase;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseDocument;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseEvent;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseItem;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasePayableLink;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasingSettings;
import br.com.uberhidraulica.erp.purchasing.port.PurchaseRepositoryPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consulta de compras, cancelamento, anulação de documento e configuração do módulo (`TASK-0021` I3).
 *
 * <p>Consultar exige {@code PURCHASE_VIEW}; cancelar e anular, {@code PURCHASE_CANCEL}; configurar,
 * {@code PURCHASE_ORDER_MANAGE}. Autorização é sempre do backend — esconder botão é conforto de interface.</p>
 */
@RestController
@RequestMapping("/api/purchasing")
class PurchaseController {
    private final PurchaseService purchases;
    private final PurchasingSettingsService settings;

    PurchaseController(PurchaseService purchases, PurchasingSettingsService settings) {
        this.purchases = purchases;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ compras

    @GetMapping("/purchases")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_VIEW')")
    PageResponse<SummaryResponse> list(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) UUID supplierId,
                                       @RequestParam(required = false) Purchase.Status status,
                                       @RequestParam(required = false) Purchase.Origin origin,
                                       @RequestParam(required = false) LocalDate issuedFrom,
                                       @RequestParam(required = false) LocalDate issuedTo,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        var result = purchases.search(new PurchaseRepositoryPort.Filter(q, supplierId, status, origin, issuedFrom,
                issuedTo), page, size);
        return new PageResponse<>(result.items().stream().map(SummaryResponse::from).toList(), result.totalItems(),
                result.page(), result.size(), result.totalPages());
    }

    @GetMapping("/purchases/{id}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_VIEW')")
    DetailResponse get(@PathVariable UUID id) {
        return DetailResponse.from(purchases.detail(id));
    }

    @GetMapping("/purchases/{id}/events")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_VIEW')")
    PageResponse<EventResponse> events(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        var result = purchases.events(id, page, size);
        return new PageResponse<>(result.items().stream().map(EventResponse::from).toList(), result.totalItems(),
                result.page(), result.size(), result.totalPages());
    }

    @PostMapping("/purchases/{id}/cancel")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_CANCEL')")
    DetailResponse cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        purchases.cancel(id, request.reason());
        return DetailResponse.from(purchases.detail(id));
    }

    @PostMapping("/documents/{id}/void")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_CANCEL')")
    DocumentResponse voidDocument(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return DocumentResponse.from(purchases.voidDocument(id, request.reason()));
    }

    // ------------------------------------------------------------------ vínculos aprendidos

    @GetMapping("/suppliers/{supplierId}/product-links")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_VIEW')")
    List<LinkResponse> links(@PathVariable UUID supplierId) {
        return purchases.links(supplierId).stream().map(LinkResponse::from).toList();
    }

    /** Remover vínculo errado é a válvula de escape da `D6`: sem ela, o erro se repetiria em silêncio. */
    @DeleteMapping("/suppliers/{supplierId}/product-links/{supplierCode}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_ORDER_MANAGE')")
    ResponseEntity<Void> removeLink(@PathVariable UUID supplierId, @PathVariable String supplierCode) {
        purchases.removeLink(supplierId, supplierCode);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ configuração

    @GetMapping("/settings")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_VIEW')")
    SettingsResponse settings() {
        return SettingsResponse.from(settings.current(), settings.describe());
    }

    @PutMapping("/settings/{key}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_ORDER_MANAGE')")
    SettingsResponse changeSetting(@PathVariable String key, @Valid @RequestBody SettingRequest request) {
        return SettingsResponse.from(settings.change(key, request.value()), settings.describe());
    }

    // ------------------------------------------------------------------ contratos

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    record SettingRequest(@NotBlank @Size(max = 64) String value) {}

    record SettingsResponse(String ownTaxId, boolean ownTaxIdConfigured, boolean costIncludeIpi,
                            boolean costIncludeIcmsSt, boolean costIncludeIcmsNormal, String freightAllocation,
                            boolean costParametersValidatedByAccountant, Map<String, String> descriptions) {
        static SettingsResponse from(PurchasingSettings s, Map<String, String> descriptions) {
            return new SettingsResponse(s.ownTaxId(), s.ownTaxIdConfigured(), s.costParameters().includeIpi(),
                    s.costParameters().includeIcmsSt(), s.costParameters().includeIcmsNormal(),
                    s.freightAllocation().name(),
                    // Condição que o proprietário anexou à D1: os parâmetros seguem pendentes de validação
                    // tributária. A interface tem de dizer isso, e não pode ser um valor configurável.
                    false, descriptions);
        }
    }

    record SummaryResponse(UUID id, long number, UUID supplierId, String supplierName, String origin, String status,
                           BigDecimal payableTotal, String accessKey, Long invoiceNumber, LocalDate issuedOn,
                           Instant createdAt, int installmentCount, String receiptStatus) {
        static SummaryResponse from(PurchaseRepositoryPort.PurchaseSummary s) {
            return new SummaryResponse(s.id(), s.number(), s.supplierId(), s.supplierName(), s.origin().name(),
                    s.status().name(), s.payableTotal(), s.accessKey(), s.invoiceNumber(), s.issuedOn(),
                    s.createdAt(), s.installmentCount(), s.receiptStatus().name());
        }
    }

    record DetailResponse(UUID id, long number, UUID supplierId, String origin, String status, BigDecimal goodsTotal,
                          BigDecimal discountTotal, BigDecimal freightTotal, BigDecimal insuranceTotal,
                          BigDecimal otherTotal, BigDecimal taxAddedTotal, BigDecimal payableTotal,
                          UUID expenseCategoryId, UUID workOrderId, String notes, Instant createdAt,
                          Instant cancelledAt, String cancellationReason, List<ItemResponse> items,
                          List<DocumentResponse> documents, List<InstallmentResponse> installments,
                          String receiptStatus, long version, UUID createdBy, Instant updatedAt, Instant approvedAt,
                          UUID approvedBy) {
        static DetailResponse from(PurchaseService.Detail d) {
            Purchase p = d.purchase();
            return new DetailResponse(p.id(), p.number(), p.supplierId(), p.origin().name(), p.status().name(),
                    p.goodsTotal(), p.discountTotal(), p.freightTotal(), p.insuranceTotal(), p.otherTotal(),
                    p.taxAddedTotal(), p.payableTotal(), p.expenseCategoryId(), p.workOrderId(), p.notes(),
                    p.createdAt(), p.cancelledAt(), p.cancellationReason(),
                    p.items().stream().map(ItemResponse::from).toList(),
                    d.documents().stream().map(DocumentResponse::from).toList(),
                    d.payables().stream().map(InstallmentResponse::from).toList(), d.receiptStatus().name(),
                    p.version(), p.createdBy(), p.updatedAt(), p.approvedAt(), p.approvedBy());
        }
    }

    /** O item expõe a composição do custo parcela por parcela: é a condição de auditabilidade da `D1`. */
    record ItemResponse(UUID id, int lineNo, UUID productId, String supplierCode, String description,
                        String unitOriginal, BigDecimal quantityOriginal, BigDecimal unitConversionFactor,
                        BigDecimal quantity, BigDecimal unitPrice, BigDecimal unitCost, BigDecimal discount,
                        BigDecimal lineTotal, String ncm, String cfop, String gtin, UUID workOrderItemId,
                        CostResponse cost) {
        static ItemResponse from(PurchaseItem i) {
            return new ItemResponse(i.id(), i.lineNo(), i.productId(), i.supplierCode(), i.description(),
                    i.unitOriginal(), i.quantityOriginal(), i.unitConversionFactor(), i.quantity(), i.unitPrice(),
                    i.unitCost(), i.discount(), i.lineTotal(), i.ncm(), i.cfop(), i.gtin(), i.workOrderItemId(),
                    CostResponse.from(i.cost()));
        }
    }

    record CostResponse(BigDecimal baseAmount, BigDecimal ipiAmount, BigDecimal icmsStAmount,
                        BigDecimal icmsNormalAmount, BigDecimal freightShare, BigDecimal insuranceShare,
                        BigDecimal otherShare, BigDecimal totalAmount, BigDecimal unitCost) {
        static CostResponse from(AcquisitionCost c) {
            return c == null ? null : new CostResponse(c.baseAmount(), c.ipiAmount(), c.icmsStAmount(),
                    c.icmsNormalAmount(), c.freightShare(), c.insuranceShare(), c.otherShare(), c.totalAmount(),
                    c.unitCost());
        }
    }

    /** O XML não é devolvido aqui: só o hash, que é o que prova identidade sem carregar 2 MB em cada consulta. */
    record DocumentResponse(UUID id, String kind, String accessKey, String series, Long number, Instant issuedAt,
                            String issuerDocument, String recipientDocument, BigDecimal totalAmount,
                            String protocolNumber, String protocolStatus, String xmlSha256, Instant voidedAt,
                            String voidReason, boolean officialCheckPerformed) {
        static DocumentResponse from(PurchaseDocument d) {
            return new DocumentResponse(d.id(), d.kind().name(), d.accessKey(), d.series(), d.number(), d.issuedAt(),
                    d.issuerDocument(), d.recipientDocument(), d.totalAmount(), d.protocolNumber(),
                    d.protocolStatus(), d.xmlSha256(), d.voidedAt(), d.voidReason(),
                    // D12: a autorização é a declarada no XML. Consulta à SEFAZ exige certificado A1 e fica
                    // para Task própria; a interface não pode sugerir que a nota foi conferida na origem.
                    false);
        }
    }

    record InstallmentResponse(UUID payableId, int installmentNo, LocalDate dueDate, BigDecimal amount,
                               String invoiceReference) {
        static InstallmentResponse from(PurchasePayableLink l) {
            return new InstallmentResponse(l.payableId(), l.installmentNo(), l.dueDate(), l.amount(),
                    l.invoiceReference());
        }
    }

    record LinkResponse(UUID id, UUID supplierId, String supplierCode, String gtin, UUID productId,
                        String unitOriginal, BigDecimal unitConversionFactor, Instant confirmedAt) {
        static LinkResponse from(br.com.uberhidraulica.erp.purchasing.domain.SupplierProductLink l) {
            return new LinkResponse(l.id(), l.supplierId(), l.supplierCode(), l.gtin(), l.productId(),
                    l.unitOriginal(), l.unitConversionFactor(), l.confirmedAt());
        }
    }

    record EventResponse(UUID id, String type, UUID actorId, Instant occurredAt, String reason, String payload) {
        static EventResponse from(PurchaseEvent e) {
            return new EventResponse(e.id(), e.type().name(), e.actorId(), e.occurredAt(), e.reason(), e.payload());
        }
    }

    record PageResponse<T>(List<T> items, long totalItems, int page, int size, int totalPages) {}
}
