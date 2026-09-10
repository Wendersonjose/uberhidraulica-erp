package br.com.uberhidraulica.erp.iam.domain;

import java.util.Set;

public final class IamPermissions {
    public static final String USERS_READ = "IAM_USERS_READ";
    public static final String USERS_MANAGE = "IAM_USERS_MANAGE";
    public static final String CATALOG_READ = "IAM_CATALOG_READ";
    public static final String PROFILE_PERMISSIONS_MANAGE = "IAM_PROFILE_PERMISSIONS_MANAGE";
    public static final String USER_EXCEPTIONS_MANAGE = "IAM_USER_EXCEPTIONS_MANAGE";
    public static final String PASSWORD_RESET = "IAM_PASSWORD_RESET";

    public static final Set<String> ALL = Set.of(
            USERS_READ, USERS_MANAGE, CATALOG_READ,
            PROFILE_PERMISSIONS_MANAGE, USER_EXCEPTIONS_MANAGE, PASSWORD_RESET);

    private IamPermissions() {
    }
}
