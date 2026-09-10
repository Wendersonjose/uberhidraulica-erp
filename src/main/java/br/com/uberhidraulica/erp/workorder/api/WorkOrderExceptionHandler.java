package br.com.uberhidraulica.erp.workorder.api;
import br.com.uberhidraulica.erp.workorder.domain.WorkOrderException;
import org.springframework.http.*;import org.springframework.web.bind.annotation.*;import java.util.List;
@RestControllerAdvice(basePackages="br.com.uberhidraulica.erp.workorder")
public class WorkOrderExceptionHandler {
 @ExceptionHandler(WorkOrderException.class) ResponseEntity<ErrorResponse> handle(WorkOrderException e){HttpStatus s=e.code().endsWith("NOT_FOUND")?HttpStatus.NOT_FOUND:e.code().equals("VEHICLE_CUSTOMER_MISMATCH")?HttpStatus.CONFLICT:HttpStatus.BAD_REQUEST;return ResponseEntity.status(s).body(new ErrorResponse(e.code(),e.getMessage(),List.of()));}
 record ErrorResponse(String code,String message,List<String> details){}
}
