package br.com.uberhidraulica.erp.iam.application;

import br.com.uberhidraulica.erp.iam.IamAuthorization;
import br.com.uberhidraulica.erp.iam.domain.*;
import br.com.uberhidraulica.erp.iam.infrastructure.security.IamPrincipal;
import br.com.uberhidraulica.erp.iam.port.AuthorizationRepositoryPort;
import br.com.uberhidraulica.erp.iam.port.UserRepositoryPort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import java.util.*;

@Service("iamAuthorization")
public class AuthorizationService implements IamAuthorization {
    private final UserRepositoryPort users;
    private final AuthorizationRepositoryPort authorizations;

    public AuthorizationService(UserRepositoryPort users, AuthorizationRepositoryPort authorizations) {
        this.users = users; this.authorizations = authorizations;
    }

    @Override public boolean hasPermission(UUID userId, String code) { return effectivePermissions(userId).contains(code); }
    public boolean hasPermission(Authentication authentication, String code) {
        return authentication != null && authentication.getPrincipal() instanceof IamPrincipal principal && hasPermission(principal.id(), code);
    }

    @Override public Set<String> effectivePermissions(UUID userId) {
        IamUser user = users.findById(userId).orElseThrow(() -> new IamException("USER_NOT_FOUND", "Usuário não encontrado"));
        Set<String> profile = authorizations.profilePermissions(user.profileCode());
        Map<String, PermissionResolution> exceptions = authorizations.userExceptions(userId);
        Set<String> effective = new HashSet<>();
        for (String code : authorizations.permissionCatalog()) {
            if (PermissionDecision.resolve(profile.contains(code), exceptions.getOrDefault(code, PermissionResolution.INHERIT))) effective.add(code);
        }
        return Set.copyOf(effective);
    }
}
