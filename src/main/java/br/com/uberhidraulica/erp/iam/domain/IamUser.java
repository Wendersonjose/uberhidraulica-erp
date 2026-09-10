package br.com.uberhidraulica.erp.iam.domain;

import java.util.UUID;

public record IamUser(
        UUID id,
        String name,
        String email,
        String normalizedEmail,
        ProfileCode profileCode,
        UserState state,
        String passwordHash,
        boolean mustChangePassword) {

    public boolean canAuthenticate() {
        return state == UserState.ACTIVE;
    }
}
