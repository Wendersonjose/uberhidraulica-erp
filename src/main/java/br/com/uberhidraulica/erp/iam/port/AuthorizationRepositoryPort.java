package br.com.uberhidraulica.erp.iam.port;

import br.com.uberhidraulica.erp.iam.domain.PermissionResolution;
import br.com.uberhidraulica.erp.iam.domain.ProfileCode;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface AuthorizationRepositoryPort {
    Set<String> profilePermissions(ProfileCode profile);
    Map<String, PermissionResolution> userExceptions(UUID userId);
    void addProfilePermission(ProfileCode profile, String permissionCode);
    void removeProfilePermission(ProfileCode profile, String permissionCode);
    void setUserException(UUID userId, String permissionCode, PermissionResolution resolution);
    Set<String> permissionCatalog();
    void ensureFixedCatalogAndOwnerPermissions();
}
