package br.com.uberhidraulica.erp.workorder.api;
import br.com.uberhidraulica.erp.workorder.domain.WorkOrderException;
import org.springframework.http.*;import org.springframework.web.bind.annotation.*;import java.util.List;import java.util.Set;
@RestControllerAdvice(basePackages="br.com.uberhidraulica.erp.workorder")
public class WorkOrderExceptionHandler {
 /** Estados do catálogo, do cadastro ou do fluxo que impedem a operação sem que o pedido esteja malformado. */
 private static final Set<String> CONFLICTS=Set.of("VEHICLE_CUSTOMER_MISMATCH","PRODUCT_INACTIVE","PRODUCT_WITHOUT_SALE_PRICE","SERVICE_INACTIVE",
   "CUSTOMER_INACTIVE","VEHICLE_INACTIVE","WORK_ORDER_CLOSED","WORK_ORDER_STATUS_TRANSITION_NOT_ALLOWED","WORK_ORDER_STATUS_INACTIVE",
   "STATUS_DEFAULT_CANNOT_BE_INACTIVATED");
 @ExceptionHandler(WorkOrderException.class) ResponseEntity<ErrorResponse> handle(WorkOrderException e){
  HttpStatus s=e.code().endsWith("NOT_FOUND")?HttpStatus.NOT_FOUND:CONFLICTS.contains(e.code())||e.code().contains("ALREADY_")?HttpStatus.CONFLICT:HttpStatus.BAD_REQUEST;
  return ResponseEntity.status(s).body(new ErrorResponse(e.code(),e.getMessage(),List.of()));
 }
 record ErrorResponse(String code,String message,List<String> details){}
}
