package br.com.uberhidraulica.erp.servicecatalog.api;

import br.com.uberhidraulica.erp.servicecatalog.domain.ServiceCatalogException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestControllerAdvice(basePackages = "br.com.uberhidraulica.erp.servicecatalog")
public class ServiceCatalogExceptionHandler {
    @ExceptionHandler(ServiceCatalogException.class)
    ResponseEntity<ErrorResponse> domain(ServiceCatalogException exception) {
        HttpStatus status = "SERVICE_NOT_FOUND".equals(exception.code()) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new ErrorResponse(exception.code(), exception.getMessage(), List.of()));
    }

    record ErrorResponse(String code, String message, List<String> details) {}
}
