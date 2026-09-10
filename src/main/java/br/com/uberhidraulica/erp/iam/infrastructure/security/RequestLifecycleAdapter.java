package br.com.uberhidraulica.erp.iam.infrastructure.security;

import br.com.uberhidraulica.erp.iam.port.CorrelationIdPort;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLifecycleAdapter extends OncePerRequestFilter implements CorrelationIdPort {
    public static final String LOGIN_LOCK_ATTRIBUTE = RequestLifecycleAdapter.class.getName() + ".LOGIN_LOCK";
    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CORRELATION_ID.set(UUID.randomUUID().toString());
        try {
            chain.doFilter(request, response);
        } finally {
            closeLoginLock(request.getAttribute(LOGIN_LOCK_ATTRIBUTE));
            CORRELATION_ID.remove();
        }
    }

    @Override public String currentOrCreate() {
        String current = CORRELATION_ID.get();
        return current == null ? UUID.randomUUID().toString() : current;
    }

    private static void closeLoginLock(Object value) {
        if (value instanceof AutoCloseable lock) {
            try { lock.close(); }
            catch (Exception exception) { throw new IllegalStateException("Não foi possível liberar o lock de login", exception); }
        }
    }
}
