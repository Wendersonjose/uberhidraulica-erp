package br.com.uberhidraulica.erp.iam.infrastructure.security;

import br.com.uberhidraulica.erp.iam.application.AuthenticationIdentityService;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
public class IamUserDetailsService implements UserDetailsService {
    private final AuthenticationIdentityService identities;

    public IamUserDetailsService(AuthenticationIdentityService identities) { this.identities = identities; }

    @Override public UserDetails loadUserByUsername(String username) {
        var identity = identities.activeByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("Credenciais inválidas"));
        var user = identity.user();
        return new IamPrincipal(user.id(), user.name(), user.email(), user.normalizedEmail(), user.profileCode(),
                user.passwordHash(), user.mustChangePassword(), identity.permissions());
    }
}
