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
            "SUPPLIER_CONTACT_PRIMARY_CONFLICT");

    @ExceptionHandler(PurchasingException.class)
    ResponseEntity<ErrorResponse> domain(PurchasingException exception) {
        HttpStatus status = exception.code().endsWith("NOT_FOUND") ? HttpStatus.NOT_FOUND
                : CONFLICTS.contains(exception.code()) ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new ErrorResponse(exception.code(), exception.getMessage(), List.of()));
    }

    record ErrorResponse(String code, String message, List<String> details) {}
}
