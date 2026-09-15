package br.com.uberhidraulica.erp.quote.api;

import br.com.uberhidraulica.erp.quote.domain.QuoteException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Set;

@RestControllerAdvice(basePackages = "br.com.uberhidraulica.erp.quote")
public class QuoteExceptionHandler {
    /** Conflitos de estado: o pedido está correto, mas o orçamento não admite a operação agora. */
    private static final Set<String> CONFLICTS = Set.of(
            "QUOTE_REVISION_ALREADY_PRESENTED",
            "QUOTE_REVISION_OUT_OF_ORDER",
            "QUOTE_REVISION_CONCURRENTLY_MODIFIED");

    @ExceptionHandler(QuoteException.class)
    ResponseEntity<ErrorResponse> domain(QuoteException exception) {
        HttpStatus status = exception.code().endsWith("NOT_FOUND") ? HttpStatus.NOT_FOUND
                : CONFLICTS.contains(exception.code()) ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new ErrorResponse(exception.code(), exception.getMessage(), List.of()));
    }

    record ErrorResponse(String code, String message, List<String> details) {}
}
