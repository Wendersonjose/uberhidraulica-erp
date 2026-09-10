package br.com.uberhidraulica.erp.iam.application;

import br.com.uberhidraulica.erp.iam.infrastructure.security.IamPrincipal;
import br.com.uberhidraulica.erp.iam.port.AuditPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DeniedOperationAuditService {
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
        return new DeniedOperation("IAM_ACCESS", "HTTP_ENDPOINT", method + " " + path);
    }

    private record DeniedOperation(String action, String targetType, String targetId) {}
}
