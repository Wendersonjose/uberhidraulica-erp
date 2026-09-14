package br.com.uberhidraulica.erp.productcatalog.api;

import br.com.uberhidraulica.erp.productcatalog.domain.ProductCatalogException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.List;

@RestControllerAdvice(basePackages = "br.com.uberhidraulica.erp.productcatalog")
public class ProductCatalogExceptionHandler {
    @ExceptionHandler(ProductCatalogException.class)
    ResponseEntity<ErrorResponse> domain(ProductCatalogException exception) {
        HttpStatus status = exception.code().endsWith("NOT_FOUND") ? HttpStatus.NOT_FOUND
                : exception.code().endsWith("ALREADY_EXISTS") ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new ErrorResponse(exception.code(), exception.getMessage(), List.of()));
    }

    record ErrorResponse(String code, String message, List<String> details) {}
}
