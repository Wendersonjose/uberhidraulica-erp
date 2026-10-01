package br.com.uberhidraulica.erp.purchasing.api;

import br.com.uberhidraulica.erp.purchasing.application.SupplierService.DuplicateSupplierDocumentException;
import br.com.uberhidraulica.erp.purchasing.application.SupplierService.SupplierNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(basePackages = "br.com.uberhidraulica.erp.purchasing")
class PurchasingExceptionHandler {
    @ExceptionHandler(SupplierNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(SupplierNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "SUPPLIER_NOT_FOUND", "message", e.getMessage()));
    }

    @ExceptionHandler(DuplicateSupplierDocumentException.class)
    ResponseEntity<Map<String, String>> duplicateDocument(DuplicateSupplierDocumentException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "SUPPLIER_DOCUMENT_DUPLICATE", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "PURCHASING_VALIDATION", "message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream().findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage()).orElse("Dados inválidos");
        return ResponseEntity.badRequest().body(Map.of("code", "PURCHASING_VALIDATION", "message", message));
    }
}
