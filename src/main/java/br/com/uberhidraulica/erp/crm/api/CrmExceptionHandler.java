package br.com.uberhidraulica.erp.crm.api;
import br.com.uberhidraulica.erp.crm.domain.CrmException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Set;
@RestControllerAdvice(basePackages="br.com.uberhidraulica.erp.crm")
public class CrmExceptionHandler {
    /** Estado atual do cadastro impede a operação, sem que o pedido esteja malformado. */
    private static final Set<String> STATE_CONFLICTS = Set.of("CUSTOMER_INACTIVE");
    @ExceptionHandler(CrmException.class) ResponseEntity<ErrorResponse> handle(CrmException e){
        HttpStatus s=e.code().endsWith("NOT_FOUND")?HttpStatus.NOT_FOUND
                :e.code().contains("ALREADY_")||STATE_CONFLICTS.contains(e.code())?HttpStatus.CONFLICT:HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(s).body(new ErrorResponse(e.code(),e.getMessage(),List.of()));
    }
    record ErrorResponse(String code,String message,List<String> details){}
}
