package br.com.uberhidraulica.erp.iam.infrastructure.persistence;

import br.com.uberhidraulica.erp.iam.domain.*;
import br.com.uberhidraulica.erp.iam.port.AuthorizationRepositoryPort;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class JpaAuthorizationRepositoryAdapter implements AuthorizationRepositoryPort {
    private final ProfileJpaRepository profiles;
    private final PermissionJpaRepository permissions;
    private final ProfilePermissionJpaRepository grants;
    private final UserPermissionExceptionJpaRepository exceptions;
    private final UserJpaRepository users;

    JpaAuthorizationRepositoryAdapter(ProfileJpaRepository profiles, PermissionJpaRepository permissions,
                                      ProfilePermissionJpaRepository grants, UserPermissionExceptionJpaRepository exceptions,
                                      UserJpaRepository users) {
        this.profiles = profiles; this.permissions = permissions; this.grants = grants; this.exceptions = exceptions; this.users = users;
    }

    @Override public Set<String> profilePermissions(ProfileCode code) {
        UUID id = profile(code).id;
        Set<UUID> permissionIds = grants.findByProfileId(id).stream().map(value -> value.permissionId).collect(Collectors.toSet());
        return permissions.findAllById(permissionIds).stream().map(value -> value.code).collect(Collectors.toUnmodifiableSet());
    }

    @Override public Map<String, PermissionResolution> userExceptions(UUID userId) {
        Map<UUID, PermissionResolution> values = exceptions.findByUserId(userId).stream().collect(Collectors.toMap(value -> value.permissionId, value -> value.resolution));
        return permissions.findAllById(values.keySet()).stream().collect(Collectors.toUnmodifiableMap(value -> value.code, value -> values.get(value.id)));
    }

    @Override public void addProfilePermission(ProfileCode code, String permissionCode) {
        ProfilePermissionEntity value = new ProfilePermissionEntity();
        value.profileId = profile(code).id; value.permissionId = permission(permissionCode).id; value.createdAt = Instant.now();
        grants.save(value);
    }

    @Override public void removeProfilePermission(ProfileCode code, String permissionCode) {
        grants.deleteById(new ProfilePermissionId(profile(code).id, permission(permissionCode).id));
    }

    @Override public void setUserException(UUID userId, String permissionCode, PermissionResolution resolution) {
        if (!users.existsById(userId)) throw new IamException("USER_NOT_FOUND", "Usuário não encontrado");
        PermissionEntity permission = permission(permissionCode);
        UserPermissionExceptionEntity value = exceptions.findByUserIdAndPermissionId(userId, permission.id).orElseGet(() -> {
            UserPermissionExceptionEntity created = new UserPermissionExceptionEntity();
            created.id = UUID.randomUUID(); created.userId = userId; created.permissionId = permission.id; created.createdAt = Instant.now();
            return created;
        });
        value.resolution = resolution; value.updatedAt = Instant.now(); exceptions.save(value);
    }

    @Override public Set<String> permissionCatalog() { return permissions.findAll().stream().map(value -> value.code).collect(Collectors.toUnmodifiableSet()); }

    @Override public void ensureFixedCatalogAndOwnerPermissions() {
        Set<ProfileCode> actual = profiles.findAll().stream().map(value -> value.code).collect(Collectors.toSet());
        if (!actual.equals(Set.of(ProfileCode.values())) || !permissionCatalog().containsAll(IamPermissions.ALL)
                || !profilePermissions(ProfileCode.DONO).containsAll(IamPermissions.ALL)) {
            throw new IamException("IAM_CATALOG_INVALID", "Catálogo IAM obrigatório ausente");
        }
    }

    private ProfileEntity profile(ProfileCode code) { return profiles.findByCode(code).orElseThrow(() -> new IamException("PROFILE_NOT_FOUND", "Perfil não encontrado")); }
    private PermissionEntity permission(String code) { return permissions.findByCode(code).orElseThrow(() -> new IamException("PERMISSION_NOT_FOUND", "Permissão não encontrada")); }
}
