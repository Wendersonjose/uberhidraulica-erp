package br.com.uberhidraulica.erp.iam;

import br.com.uberhidraulica.erp.iam.application.AuthorizationService;
import br.com.uberhidraulica.erp.iam.domain.*;
import br.com.uberhidraulica.erp.iam.port.AuthorizationRepositoryPort;
import br.com.uberhidraulica.erp.iam.port.UserRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuthorizationServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String PERMISSION = IamPermissions.USERS_READ;
    private AuthorizationRepositoryPort repository;
    private AuthorizationService service;

    @BeforeEach
    void setUp() {
        UserRepositoryPort users = mock(UserRepositoryPort.class);
        repository = mock(AuthorizationRepositoryPort.class);
        when(users.findById(USER_ID)).thenReturn(Optional.of(new IamUser(
                USER_ID, "User", "user@example.test", "user@example.test",
                ProfileCode.GERENTE_ADMINISTRATIVO, UserState.ACTIVE, "{noop}hash", false)));
        when(repository.permissionCatalog()).thenReturn(Set.of(PERMISSION));
        service = new AuthorizationService(users, repository);
    }

    @Test
    void profileAllowsAndInheritAllows() {
        decide(Set.of(PERMISSION), PermissionResolution.INHERIT, true);
    }

    @Test
    void profileDeniesAndInheritDenies() {
        decide(Set.of(), PermissionResolution.INHERIT, false);
    }

    @Test
    void individualAllowOverridesProfileDenial() {
        decide(Set.of(), PermissionResolution.ALLOW, true);
    }

    @Test
    void individualDenyOverridesProfilePermission() {
        decide(Set.of(PERMISSION), PermissionResolution.DENY, false);
    }

    @Test
    void absentExceptionInheritsProfile() {
        when(repository.profilePermissions(ProfileCode.GERENTE_ADMINISTRATIVO)).thenReturn(Set.of(PERMISSION));
        when(repository.userExceptions(USER_ID)).thenReturn(Map.of());
        assertThat(service.hasPermission(USER_ID, PERMISSION)).isTrue();
    }

    private void decide(Set<String> profile, PermissionResolution resolution, boolean expected) {
        when(repository.profilePermissions(ProfileCode.GERENTE_ADMINISTRATIVO)).thenReturn(profile);
        when(repository.userExceptions(USER_ID)).thenReturn(Map.of(PERMISSION, resolution));
        assertThat(service.hasPermission(USER_ID, PERMISSION)).isEqualTo(expected);
    }
}
