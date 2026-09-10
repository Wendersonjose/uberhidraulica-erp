package br.com.uberhidraulica.erp.iam.application;

import br.com.uberhidraulica.erp.iam.port.AuditPort;
import br.com.uberhidraulica.erp.iam.port.ConcurrencyPort;
import br.com.uberhidraulica.erp.iam.port.SessionPort;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuthenticationLifecycleService {
    private final SessionPort sessions;
    private final AuditPort audit;
    private final ConcurrencyPort concurrency;

    public AuthenticationLifecycleService(SessionPort sessions, AuditPort audit, ConcurrencyPort concurrency) {
        this.sessions = sessions;
        this.audit = audit;
        this.concurrency = concurrency;
    }

    public AutoCloseable lockLoginSession(String normalizedEmail) {
        return concurrency.lockLoginSession(normalizedEmail);
    }

    public void loginSucceeded(UUID userId, String normalizedEmail, String currentSessionId) {
        sessions.invalidateOthers(normalizedEmail, currentSessionId);
        audit.record(userId, "LOGIN_SUCCEEDED", "USER", userId.toString(), "SUCCESS", null, null);
    }

    public void loginFailed() {
        audit.recordIndependent(null, "LOGIN_FAILED", "USER", null, "FAILURE", null, null);
    }

    public void logout(UUID userId) {
        audit.record(userId, "LOGOUT", "USER", userId.toString(), "SUCCESS", null, null);
    }
}
