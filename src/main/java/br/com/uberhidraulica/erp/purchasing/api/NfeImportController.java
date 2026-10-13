package br.com.uberhidraulica.erp.purchasing.api;

import br.com.uberhidraulica.erp.purchasing.application.NfeImportService;
import br.com.uberhidraulica.erp.purchasing.application.NfePreviewService;
import br.com.uberhidraulica.erp.purchasing.domain.NfeImportPreview;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Importação de NF-e de compra (`TASK-0021` I3). Exige {@code PURCHASE_IMPORT_XML}.
 *
 * <p>A prévia é somente leitura e não grava nada, por isso é segura de chamar quantas vezes for preciso. A
 * confirmação exige {@code Idempotency-Key}: sem ela, um retry de rede duplicaria a compra e as contas a
 * pagar — exatamente o que a `D4` manda impedir.</p>
 */
@RestController
@RequestMapping("/api/purchasing/nfe")
class NfeImportController {
    private final NfePreviewService previews;
    private final NfeImportService imports;

    NfeImportController(NfePreviewService previews, NfeImportService imports) {
        this.previews = previews;
        this.imports = imports;
    }

    /** Prévia por upload de arquivo. */
    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_IMPORT_XML')")
    NfeImportPreview previewUpload(@RequestParam("file") MultipartFile file) {
        return previews.preview(bytes(file));
    }

    /** Prévia por corpo XML, para integração e teste sem multipart. */
    @PostMapping(path = "/preview", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_IMPORT_XML')")
    NfeImportPreview previewBody(@RequestBody byte[] xml) {
        return previews.preview(xml);
    }

    /**
     * Confirma a importação e grava compra, documento e contas a pagar em uma transação.
     *
     * <p>O XML vai no corpo como texto em vez de multipart porque a confirmação carrega também as decisões do
     * operador (produto e fator de cada item, parcelas); misturar multipart e JSON tornaria o contrato mais
     * frágil sem ganho — e o parser já limita o XML a 2 MB.</p>
     */
    @PostMapping(path = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'PURCHASE_IMPORT_XML')")
    ConfirmResponse confirm(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                            @Valid @RequestBody ConfirmRequest request) {
        NfeImportService.Result result = imports.confirm(new NfeImportService.Command(
                request.xml().getBytes(StandardCharsets.UTF_8), idempotencyKey, request.createSupplier(),
                request.expenseCategoryId(), request.decisions(), request.installments() == null ? null
                : new NfeImportService.Installments(request.installments().count(),
                        request.installments().firstDueDate(), request.installments().intervalDays()),
                request.workOrderId(), request.notes()));
        return ConfirmResponse.from(result);
    }

    private static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new PurchasingException("PURCHASE_XML_REQUIRED", "Envie o arquivo XML da NF-e");
        }
        try {
            return file.getBytes();
        } catch (IOException failure) {
            throw new PurchasingException("PURCHASE_XML_UNREADABLE", "Não foi possível ler o arquivo enviado");
        }
    }

    // ------------------------------------------------------------------ contratos

    /** {@code createSupplier} e {@code saveLink} são opcionais: ausente significa {@code false}. */
    record ConfirmRequest(@NotBlank String xml, Boolean createSupplier, UUID expenseCategoryId,
                          List<NfeImportService.ItemDecision> decisions, InstallmentsPayload installments,
                          UUID workOrderId, @Size(max = 1000) String notes) {}

    record InstallmentsPayload(Integer count, LocalDate firstDueDate, Integer intervalDays) {}

    record ConfirmResponse(UUID purchaseId, long number, UUID supplierId, String status, BigDecimal payableTotal,
                           int itemCount, List<InstallmentResponse> installments, boolean replayed) {
        static ConfirmResponse from(NfeImportService.Result result) {
            return new ConfirmResponse(result.purchase().id(), result.purchase().number(),
                    result.purchase().supplierId(), result.purchase().status().name(),
                    result.purchase().payableTotal(), result.purchase().items().size(),
                    result.payables().stream().map(InstallmentResponse::new).toList(), result.replayed());
        }
    }

    record InstallmentResponse(UUID payableId, int installmentNo, LocalDate dueDate, BigDecimal amount,
                               String invoiceReference) {
        InstallmentResponse(br.com.uberhidraulica.erp.purchasing.domain.PurchasePayableLink link) {
            this(link.payableId(), link.installmentNo(), link.dueDate(), link.amount(), link.invoiceReference());
        }
    }
}
