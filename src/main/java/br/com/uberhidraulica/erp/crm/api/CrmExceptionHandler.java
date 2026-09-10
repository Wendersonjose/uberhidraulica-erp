package br.com.uberhidraulica.erp.crm.api;
import br.com.uberhidraulica.erp.crm.domain.CrmException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestControllerAdvice(basePackages="br.com.uberhidraulica.erp.crm")
public class CrmExceptionHandler {
    @ExceptionHandler(CrmException.class) ResponseEntity<ErrorResponse> handle(CrmException e){ HttpStatus s=e.code().endsWith("NOT_FOUND")?HttpStatus.NOT_FOUND:e.code().endsWith("ALREADY_EXISTS")?HttpStatus.CONFLICT:HttpStatus.BAD_REQUEST;return ResponseEntity.status(s).body(new ErrorResponse(e.code(),e.getMessage(),List.of())); }
    record ErrorResponse(String code,String message,List<String> details){}
}
