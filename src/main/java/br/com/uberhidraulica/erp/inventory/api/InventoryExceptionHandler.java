package br.com.uberhidraulica.erp.inventory.api;

import br.com.uberhidraulica.erp.inventory.domain.InventoryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Set;

/**
 * Cobre também os controladores da OS: a baixa de estoque acontece dentro da operação da OS, e a
 * recusa por saldo precisa chegar ao usuário com o código do estoque.
 */
@RestControllerAdvice(basePackages = {"br.com.uberhidraulica.erp.inventory", "br.com.uberhidraulica.erp.workorder"})
public class InventoryExceptionHandler {
    private static final Set<String> CONFLICTS = Set.of("INSUFFICIENT_STOCK", "PRODUCT_INACTIVE",
            "MOVEMENT_ALREADY_REVERSAL", "MOVEMENT_ALREADY_REVERSED", "STOCK_WRITE_OFF_CONFLICT");

    @ExceptionHandler(InventoryException.class)
    ResponseEntity<ErrorResponse> handle(InventoryException exception) {
        HttpStatus status = exception.code().endsWith("NOT_FOUND") ? HttpStatus.NOT_FOUND
                : CONFLICTS.contains(exception.code()) ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new ErrorResponse(exception.code(), exception.getMessage(), List.of()));
    }

    record ErrorResponse(String code, String message, List<String> details) {}
}
