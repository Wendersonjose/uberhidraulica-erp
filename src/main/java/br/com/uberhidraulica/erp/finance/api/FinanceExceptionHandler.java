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
    private static final Set<String> FORBIDDEN = Set.of("FINANCE_BILL_REQUIRED", "CASH_REVERSAL_REQUIRED");
    private static final Set<String> CONFLICTS = Set.of(
            "WORK_ORDER_WITHOUT_BILLING_BASIS", "BILLING_QUOTE_SELECTION_REQUIRED", "BILLING_QUOTE_NOT_ELIGIBLE",
            "RECEIVABLE_HAS_RECEIPTS", "RECEIVABLE_CANCELLED", "RECEIVABLE_NOT_OPEN", "AMOUNT_EXCEEDS_BALANCE",
            "CASH_SESSION_REQUIRED", "CASH_SESSION_ALREADY_OPEN", "CASH_SESSION_NOT_OPEN", "CASH_SESSION_NOT_PENDING_CHECK",
            "CASH_AMOUNT_EXCEEDS_BALANCE", "CASH_MOVEMENT_ALREADY_REVERSED",
            "PAYMENT_METHOD_INACTIVE", "RECEIPT_ALREADY_REVERSED", "PAYMENT_ALREADY_REVERSED",
            "PAYABLE_HAS_PAYMENTS", "PAYABLE_CANCELLED", "EXPENSE_CATEGORY_INACTIVE", "IDEMPOTENCY_KEY_REUSED",
            "PAYMENT_METHOD_ALREADY_EXISTS", "EXPENSE_CATEGORY_ALREADY_EXISTS",
            "ADJUSTMENT_ALREADY_REVERSED", "ADJUSTMENT_REVERSAL_EXCEEDS_RECEIVED",
            // TASK-0024 etapa B (DR-0024)
            "INSTALLMENT_NOT_OPEN", "RECEIVABLE_FROM_WORK_ORDER", "REVENUE_CATEGORY_INACTIVE", "REVENUE_CATEGORY_ALREADY_EXISTS",
            "CUSTOMER_INACTIVE", "LATE_CHARGE_NOT_DUE", "LATE_CHARGE_CHANGED", "INSTALLMENTS_NOT_ALLOWED",
            "PAYABLE_APPROVAL_REQUIRED", "PAYABLE_ALREADY_APPROVED", "PAYABLE_APPROVAL_NOT_REQUIRED", "SUPPLIER_INACTIVE",
            "FINANCIAL_ACCOUNT_INACTIVE", "FINANCIAL_ACCOUNT_ALREADY_EXISTS", "RECONCILIATION_ENTRY_REVERSED", "ENTRY_ALREADY_RECONCILED",
            "STATEMENT_REFERENCE_ALREADY_USED", "RECONCILIATION_ALREADY_REVERSED", "ENTRY_RECONCILED");
    private static final Set<String> FORBIDDEN_STAGE_B = Set.of("PAYABLE_APPROVAL_SEGREGATION");

    @ExceptionHandler(FinanceException.class)
    ResponseEntity<ErrorResponse> domain(FinanceException exception) {
        HttpStatus status = FORBIDDEN.contains(exception.code()) || FORBIDDEN_STAGE_B.contains(exception.code()) ? HttpStatus.FORBIDDEN
                : exception.code().endsWith("NOT_FOUND") ? HttpStatus.NOT_FOUND
                : CONFLICTS.contains(exception.code()) ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new ErrorResponse(exception.code(), exception.getMessage(), List.of()));
    }

    /**
     * Disputa de bloqueio (por exemplo, deadlock detectado pelo PostgreSQL) entre finalização e alteração
     * comercial: nada foi gravado, e a operação pode ser repetida depois de recarregar.
     */
    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> lockConflict(org.springframework.dao.PessimisticLockingFailureException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("CONCURRENT_MODIFICATION",
                "Outra operação alterou esta OS ao mesmo tempo; recarregue e tente novamente", List.of()));
    }

    record ErrorResponse(String code, String message, List<String> details) {}
}
