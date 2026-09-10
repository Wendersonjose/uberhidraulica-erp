package br.com.uberhidraulica.erp.iam.domain;

public final class PermissionDecision {
    private PermissionDecision() {
    }

    public static boolean resolve(boolean profileAllows, PermissionResolution resolution) {
        if (resolution == PermissionResolution.ALLOW) {
            return true;
        }
        if (resolution == PermissionResolution.DENY) {
            return false;
        }
        return profileAllows;
    }
}
