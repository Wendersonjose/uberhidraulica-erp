package br.com.uberhidraulica.erp.iam.application;

import br.com.uberhidraulica.erp.iam.domain.*;
import br.com.uberhidraulica.erp.iam.port.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BootstrapService {
    private final UserRepositoryPort users;
    private final AuthorizationRepositoryPort authorization;
    private final ConcurrencyPort concurrency;
    private final AuditPort audit;
    private final PasswordEncoder encoder;

    public BootstrapService(UserRepositoryPort users, AuthorizationRepositoryPort authorization,
                            ConcurrencyPort concurrency, AuditPort audit, PasswordEncoder encoder) {
        this.users = users; this.authorization = authorization; this.concurrency = concurrency; this.audit = audit; this.encoder = encoder;
    }

    @Transactional
    public boolean bootstrap(String name, String email, String rawPassword) {
        concurrency.lockBootstrap();
        if (users.count() > 0) return false;
        authorization.ensureFixedCatalogAndOwnerPermissions();
        IamUser owner = users.create(name, email, ProfileCode.DONO, UserState.ACTIVE, encoder.encode(rawPassword), true);
        audit.record(null, "FIRST_OWNER_BOOTSTRAPPED", "USER", owner.id().toString(), "SUCCESS", null, "profile=DONO;state=ACTIVE;mustChangePassword=true");
        return true;
    }
}
