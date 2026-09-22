package br.com.uberhidraulica.erp.finance.api;

import br.com.uberhidraulica.erp.finance.domain.FinanceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Set;

/**
 * Erros do Financeiro. Cobre também os endpoints da OS, porque a finalização e o cancelamento da OS podem
 * ser recusados pelo Financeiro na mesma transação (falta de base comercial, recebimento a estornar).
 */
@RestControllerAdvice(basePackages = {"br.com.uberhidraulica.erp.finance", "br.com.uberhidraulica.erp.workorder"})
public class FinanceExceptionHandler {
    private static final Set<String> CONFLICTS = Set.of(
            "WORK_ORDER_WITHOUT_BILLING_BASIS", "BILLING_QUOTE_SELECTION_REQUIRED", "BILLING_QUOTE_NOT_ELIGIBLE",
            "RECEIVABLE_HAS_RECEIPTS", "RECEIVABLE_CANCELLED", "RECEIVABLE_NOT_OPEN", "AMOUNT_EXCEEDS_BALANCE",
            "CASH_SESSION_REQUIRED", "PAYMENT_METHOD_INACTIVE", "RECEIPT_ALREADY_REVERSED", "PAYMENT_ALREADY_REVERSED",
            "PAYABLE_HAS_PAYMENTS", "PAYABLE_CANCELLED", "EXPENSE_CATEGORY_INACTIVE", "IDEMPOTENCY_KEY_REUSED",
            "PAYMENT_METHOD_ALREADY_EXISTS", "EXPENSE_CATEGORY_ALREADY_EXISTS");

    @ExceptionHandler(FinanceException.class)
    ResponseEntity<ErrorResponse> domain(FinanceException exception) {
        HttpStatus status = exception.code().endsWith("NOT_FOUND") ? HttpStatus.NOT_FOUND
                : CONFLICTS.contains(exception.code()) ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new ErrorResponse(exception.code(), exception.getMessage(), List.of()));
    }

    record ErrorResponse(String code, String message, List<String> details) {}
}
