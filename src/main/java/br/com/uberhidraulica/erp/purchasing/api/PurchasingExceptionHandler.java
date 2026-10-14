package br.com.uberhidraulica.erp.purchasing.api;

import br.com.uberhidraulica.erp.purchasing.domain.PurchasingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Set;

/** Erros de Compras com código estável. Validação de corpo e JSON ilegível seguem o tratamento global do IAM. */
@RestControllerAdvice(basePackages = "br.com.uberhidraulica.erp.purchasing")
public class PurchasingExceptionHandler {
    private static final Set<String> CONFLICTS = Set.of(
            "SUPPLIER_DOCUMENT_DUPLICATE", "SUPPLIER_VERSION_CONFLICT", "SUPPLIER_ALREADY_INACTIVE", "SUPPLIER_ALREADY_ACTIVE",
            "SUPPLIER_CONTACT_PRIMARY_CONFLICT",
            // I3: estado já atingido ou recurso já usado. 409 e não 400, porque o pedido é válido — o que
            // mudou foi o estado do lado do servidor, e repetir não vai passar a dar certo.
            "PURCHASE_DUPLICATE_NFE", "PURCHASE_IDEMPOTENCY_KEY_REUSED", "PURCHASE_ALREADY_CANCELLED",
            "PURCHASE_DOCUMENT_ALREADY_VOIDED", "PURCHASE_NFE_CONTENT_DIVERGES",
            // I4: o pedido é válido, mas o estado do recebimento ou do estoque do lado do servidor impede.
            "PURCHASE_NOT_RECEIVABLE", "PURCHASE_RECEIPT_ALREADY_REVERSED", "PURCHASE_RECEIPT_STOCK_CONSUMED",
            "PURCHASE_HAS_ACTIVE_RECEIPT", "PURCHASE_STOCK_REFUSED");
    /** Sem a permissão de aceitar divergência a operação é negada, e não apenas inválida. */
    private static final Set<String> FORBIDDEN = Set.of("PURCHASE_DIVERGENCE_NOT_AUTHORIZED");

    @ExceptionHandler(PurchasingException.class)
    ResponseEntity<ErrorResponse> domain(PurchasingException exception) {
        HttpStatus status = exception.code().endsWith("NOT_FOUND") ? HttpStatus.NOT_FOUND
                : FORBIDDEN.contains(exception.code()) ? HttpStatus.FORBIDDEN
                : CONFLICTS.contains(exception.code()) ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new ErrorResponse(exception.code(), exception.getMessage(), List.of()));
    }

    record ErrorResponse(String code, String message, List<String> details) {}
}
