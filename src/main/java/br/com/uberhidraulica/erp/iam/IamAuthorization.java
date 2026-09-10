package br.com.uberhidraulica.erp.iam;

import java.util.Set;
import java.util.UUID;

public interface IamAuthorization {
    boolean hasPermission(UUID userId, String permissionCode);
    Set<String> effectivePermissions(UUID userId);
}
