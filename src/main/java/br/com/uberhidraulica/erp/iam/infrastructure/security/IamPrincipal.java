package br.com.uberhidraulica.erp.iam.infrastructure.security;

import br.com.uberhidraulica.erp.iam.domain.ProfileCode;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public final class IamPrincipal implements UserDetails, CredentialsContainer, Serializable {
    private final UUID id;
    private final String name;
    private final String email;
    private final String normalizedEmail;
    private final ProfileCode profileCode;
    private transient String password;
    private final boolean mustChangePassword;
    private final Set<String> permissions;

    public IamPrincipal(UUID id, String name, String email, String normalizedEmail,
                        ProfileCode profileCode, String password, boolean mustChangePassword,
                        Set<String> permissions) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.normalizedEmail = normalizedEmail;
        this.profileCode = profileCode;
        this.password = password;
        this.mustChangePassword = mustChangePassword;
        this.permissions = Set.copyOf(permissions);
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public String email() { return email; }
    public String normalizedEmail() { return normalizedEmail; }
    public ProfileCode profileCode() { return profileCode; }
    public boolean mustChangePassword() { return mustChangePassword; }
    public Set<String> permissions() { return permissions; }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return permissions.stream().map(SimpleGrantedAuthority::new).toList();
    }
    @Override public String getUsername() { return normalizedEmail; }
    @Override public String getPassword() { return password; }
    @Override public boolean isEnabled() { return true; }
    @Override public void eraseCredentials() { password = null; }
}
