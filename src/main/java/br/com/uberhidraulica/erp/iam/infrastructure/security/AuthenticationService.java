package br.com.uberhidraulica.erp.iam.infrastructure.security;

import br.com.uberhidraulica.erp.iam.application.AuthenticationLifecycleService;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {
    private final AuthenticationManager manager;
    private final SecurityContextRepository contexts;
    private final AuthenticationLifecycleService lifecycle;

    public AuthenticationService(AuthenticationManager manager, SecurityContextRepository contexts,
                                 AuthenticationLifecycleService lifecycle) {
        this.manager = manager; this.contexts = contexts; this.lifecycle = lifecycle;
    }

    public IamPrincipal login(String email, String password, HttpServletRequest request, HttpServletResponse response) {
        try {
            Authentication authentication = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
            IamPrincipal principal = (IamPrincipal) authentication.getPrincipal();
            AutoCloseable loginLock = lifecycle.lockLoginSession(principal.normalizedEmail());
            request.setAttribute(RequestLifecycleAdapter.LOGIN_LOCK_ATTRIBUTE, loginLock);
            HttpSession session = request.getSession(false);
            String sessionId;
            if (session == null) {
                sessionId = request.getSession(true).getId();
            } else {
                sessionId = request.changeSessionId();
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication); SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            lifecycle.loginSucceeded(principal.id(), principal.normalizedEmail(), sessionId);
            return principal;
        } catch (AuthenticationException exception) {
            lifecycle.loginFailed();
            throw new BadCredentialsException("Credenciais inválidas");
        }
    }

    public void logout(IamPrincipal principal, HttpServletRequest request) {
        lifecycle.logout(principal.id());
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }
}
