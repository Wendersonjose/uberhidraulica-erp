package br.com.uberhidraulica.erp.iam.application;

import br.com.uberhidraulica.erp.iam.IamAuthorization;
import br.com.uberhidraulica.erp.iam.domain.IamUser;
import br.com.uberhidraulica.erp.iam.port.UserRepositoryPort;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

@Service
public class AuthenticationIdentityService {
    private final UserRepositoryPort users;
    private final IamAuthorization authorization;

    public AuthenticationIdentityService(UserRepositoryPort users, IamAuthorization authorization) {
        this.users = users;
        this.authorization = authorization;
    }

    public Optional<AuthenticationIdentity> activeByEmail(String email) {
        return users.findByNormalizedEmail(email).filter(IamUser::canAuthenticate).map(user ->
                new AuthenticationIdentity(user, authorization.effectivePermissions(user.id())));
    }

    public boolean passwordChangeRequired(java.util.UUID userId) {
        return users.findById(userId).map(IamUser::mustChangePassword).orElse(true);
    }

    public record AuthenticationIdentity(IamUser user, Set<String> permissions) {}
}
