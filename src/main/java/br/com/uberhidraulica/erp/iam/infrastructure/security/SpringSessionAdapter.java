package br.com.uberhidraulica.erp.iam.infrastructure.security;

import br.com.uberhidraulica.erp.iam.port.SessionPort;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SpringSessionAdapter implements SessionPort {
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public SpringSessionAdapter(FindByIndexNameSessionRepository<? extends Session> sessions) { this.sessions = sessions; }

    @Override public void invalidateAll(String principal) { sessions.findByPrincipalName(principal).keySet().forEach(sessions::deleteById); }
    @Override public void invalidateOthers(String principal, String currentSessionId) {
        Map<String, ? extends Session> found = sessions.findByPrincipalName(principal);
        found.keySet().stream().filter(id -> !id.equals(currentSessionId)).forEach(sessions::deleteById);
    }
}
