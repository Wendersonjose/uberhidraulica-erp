package br.com.uberhidraulica.erp.iam.api;

import br.com.uberhidraulica.erp.iam.domain.IamException;
import org.springframework.http.*;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestControllerAdvice
public class IamExceptionHandler {
    @ExceptionHandler(IamException.class)
    ResponseEntity<ErrorResponse> iam(IamException exception) {
        HttpStatus status = switch (exception.code()) {
            case "USER_NOT_FOUND", "PROFILE_NOT_FOUND", "PERMISSION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "DUPLICATE_USER_EMAIL" -> HttpStatus.CONFLICT;
            case "ACCESS_DENIED" -> HttpStatus.FORBIDDEN;
            case "CURRENT_PASSWORD_INVALID" -> HttpStatus.BAD_REQUEST;
            case "LAST_ACTIVE_OWNER_REQUIRED" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(exception.code(), exception.getMessage(), List.of()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ErrorResponse> authentication() { return ResponseEntity.status(401).body(new ErrorResponse("AUTHENTICATION_FAILED", "Credenciais inválidas", List.of())); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult().getFieldErrors().stream().map(error -> error.getField() + ": " + error.getDefaultMessage()).toList();
        return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_FAILED", "Dados inválidos", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException exception) {
        String description = exception.getMessage() == null ? "" : exception.getMessage();
        String code = description.contains("PermissionResolution")
                ? "INVALID_PERMISSION_RESOLUTION"
                : description.contains("UserState") ? "INVALID_USER_STATE" : "INVALID_REQUEST";
        return ResponseEntity.badRequest().body(new ErrorResponse(code, "Dados inválidos", List.of()));
    }

    public record ErrorResponse(String code, String message, List<String> details) {}
}
