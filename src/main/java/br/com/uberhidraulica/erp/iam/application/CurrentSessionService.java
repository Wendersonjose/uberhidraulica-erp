package br.com.uberhidraulica.erp.iam.application;

import br.com.uberhidraulica.erp.iam.IamAuthorization;
import br.com.uberhidraulica.erp.iam.domain.ProfileCode;
import br.com.uberhidraulica.erp.iam.domain.UserState;
import br.com.uberhidraulica.erp.iam.port.UserRepositoryPort;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Service
public class CurrentSessionService {
    private final UserRepositoryPort users;
    private final IamAuthorization authorization;

    public CurrentSessionService(UserRepositoryPort users, IamAuthorization authorization) {
        this.users = users;
        this.authorization = authorization;
    }

    public CurrentSession find(UUID userId) {
        var user = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Usuário autenticado não encontrado"));
        return new CurrentSession(
                user.id(), user.name(), user.email(), user.profileCode(), user.state(),
                user.mustChangePassword(), authorization.effectivePermissions(user.id()));
    }

    public record CurrentSession(
            UUID id,
            String name,
            String email,
            ProfileCode profileCode,
            UserState state,
            boolean mustChangePassword,
            Set<String> permissions) {
    }
}
