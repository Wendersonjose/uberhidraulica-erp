package br.com.uberhidraulica.erp.iam.application;

import br.com.uberhidraulica.erp.iam.infrastructure.security.IamPrincipal;
import br.com.uberhidraulica.erp.iam.port.AuditPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DeniedOperationAuditService {
    private static final int TARGET_ID_MAX = 120;

    private final AuditPort audit;

    public DeniedOperationAuditService(AuditPort audit) { this.audit = audit; }

    public void record(HttpServletRequest request, Authentication authentication) {
        UUID actor = authentication != null && authentication.getPrincipal() instanceof IamPrincipal principal
                ? principal.id() : null;
        DeniedOperation operation = identify(request);
        audit.recordIndependent(actor, operation.action(), operation.targetType(), operation.targetId(),
                "DENIED", null, null);
    }

    private static DeniedOperation identify(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        String[] parts = path.split("/");
        if (path.matches("/api/iam/users/[^/]+/password-reset"))
            return new DeniedOperation("PASSWORD_RESET", "USER", parts[4]);
        if (path.matches("/api/iam/users/[^/]+/permission-exceptions/[^/]+"))
            return new DeniedOperation("USER_PERMISSION_EXCEPTION_CHANGED", "USER", parts[4]);
        if (path.matches("/api/iam/users/[^/]+") && "PATCH".equals(method))
            return new DeniedOperation("USER_STATE_CHANGED", "USER", parts[4]);
        if (path.equals("/api/iam/users") && "POST".equals(method))
            return new DeniedOperation("USER_CREATED", "USER", null);
        if (path.matches("/api/iam/profiles/[^/]+/permissions/[^/]+"))
            return new DeniedOperation("PUT".equals(method) ? "PROFILE_PERMISSION_ADDED" : "PROFILE_PERMISSION_REMOVED", "PROFILE", parts[4]);
        return new DeniedOperation("IAM_ACCESS", "HTTP_ENDPOINT", truncated(method + " " + path));
    }

    /**
     * O caminho de uma requisição não tem limite; {@code audit_event.target_id} tem 120 caracteres.
     *
     * <p>Sem este corte, negar acesso a uma rota longa fazia a própria gravação da auditoria falhar,
     * e a negação chegava ao cliente como erro de servidor em vez de 403.</p>
     */
    private static String truncated(String value) {
        return value.length() <= TARGET_ID_MAX ? value : value.substring(0, TARGET_ID_MAX - 1) + "…";
    }

    private record DeniedOperation(String action, String targetType, String targetId) {}
}
