package br.com.uberhidraulica.erp.iam.infrastructure.security;

import br.com.uberhidraulica.erp.iam.application.AuthenticationIdentityService;
import br.com.uberhidraulica.erp.iam.application.DeniedOperationAuditService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;

@Component
public class MustChangePasswordFilter extends OncePerRequestFilter {
    private final AuthenticationIdentityService identities;
    private final DeniedOperationAuditService deniedAudit;
    public MustChangePasswordFilter(AuthenticationIdentityService identities, DeniedOperationAuditService deniedAudit) {
        this.identities = identities;
        this.deniedAudit = deniedAudit;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof IamPrincipal iam && identities.passwordChangeRequired(iam.id()) && !allowed(request)) {
            deniedAudit.record(request, SecurityContextHolder.getContext().getAuthentication());
            response.setStatus(403); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":\"Troca de senha obrigatória\",\"details\":[]}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean allowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/iam/session") || path.equals("/api/iam/password/change")
                || path.equals("/api/iam/auth/logout") || path.equals("/api/iam/csrf");
    }
}
