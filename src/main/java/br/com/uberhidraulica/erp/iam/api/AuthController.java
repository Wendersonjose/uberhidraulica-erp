package br.com.uberhidraulica.erp.iam.api;

import br.com.uberhidraulica.erp.iam.application.CurrentSessionService;
import br.com.uberhidraulica.erp.iam.infrastructure.security.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/iam")
public class AuthController {
    private final AuthenticationService authentication;
    private final CurrentSessionService currentSession;
    public AuthController(AuthenticationService authentication, CurrentSessionService currentSession) {
        this.authentication = authentication;
        this.currentSession = currentSession;
    }

    @GetMapping("/csrf") public Map<String, String> csrf(CsrfToken token) { return Map.of("headerName", token.getHeaderName(), "parameterName", token.getParameterName(), "token", token.getToken()); }

    @PostMapping("/auth/login")
    public SessionResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        IamPrincipal principal = authentication.login(body.email(), body.password(), request, response);
        return response(principal);
    }

    @PostMapping("/auth/logout") public ResponseEntity<Void> logout(@AuthenticationPrincipal IamPrincipal principal, HttpServletRequest request) {
        authentication.logout(principal, request); return ResponseEntity.noContent().build();
    }

    @GetMapping("/session") public SessionResponse current(@AuthenticationPrincipal IamPrincipal principal) { return response(principal); }

    private SessionResponse response(IamPrincipal principal) {
        var current = currentSession.find(principal.id());
        return new SessionResponse(current.id(), current.name(), current.email(), current.profileCode(), current.state(), current.mustChangePassword(), current.permissions());
    }

    public record LoginRequest(@Email @NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 1024) String password) {}
    public record SessionResponse(UUID id, String name, String email, Object profileCode, Object state, boolean mustChangePassword, Set<String> permissions) {}
}
