package br.com.uberhidraulica.erp.iam.infrastructure.persistence;

import br.com.uberhidraulica.erp.iam.domain.PermissionResolution;
import br.com.uberhidraulica.erp.iam.domain.ProfileCode;
import br.com.uberhidraulica.erp.iam.domain.UserState;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "profile", schema = "iam")
class ProfileEntity {
    @Id UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, unique = true) ProfileCode code;
    String description;
    @Column(name = "created_at", nullable = false) Instant createdAt;

    ProfileCode getCode() { return code; }
}

@Entity @Table(name = "permission", schema = "iam")
class PermissionEntity {
    @Id UUID id;
    @Column(nullable = false, unique = true) String code;
    @Column(nullable = false) String description;
    @Column(name = "created_at", nullable = false) Instant createdAt;
}

@Entity @Table(name = "app_user", schema = "iam")
class UserEntity {
    @Id UUID id;
    @Column(nullable = false) String name;
    @Column(nullable = false) String email;
    @Column(name = "normalized_email", nullable = false, unique = true) String normalizedEmail;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "profile_id") ProfileEntity profile;
    @Enumerated(EnumType.STRING) @Column(nullable = false) UserState state;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
}

@Entity @Table(name = "credential", schema = "iam")
class CredentialEntity {
    @Id @Column(name = "user_id") UUID userId;
    @Column(name = "password_hash", nullable = false) String passwordHash;
    @Column(name = "must_change_password", nullable = false) boolean mustChangePassword;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
}

@Entity @Table(name = "profile_permission", schema = "iam") @IdClass(ProfilePermissionId.class)
class ProfilePermissionEntity {
    @Id @Column(name = "profile_id") UUID profileId;
    @Id @Column(name = "permission_id") UUID permissionId;
    @Column(name = "created_at", nullable = false) Instant createdAt;
}

class ProfilePermissionId implements java.io.Serializable {
    UUID profileId;
    UUID permissionId;
    ProfilePermissionId() {}
    ProfilePermissionId(UUID profileId, UUID permissionId) { this.profileId = profileId; this.permissionId = permissionId; }
    @Override public boolean equals(Object other) { return other instanceof ProfilePermissionId id && java.util.Objects.equals(profileId, id.profileId) && java.util.Objects.equals(permissionId, id.permissionId); }
    @Override public int hashCode() { return java.util.Objects.hash(profileId, permissionId); }
}

@Entity @Table(name = "user_permission_exception", schema = "iam")
class UserPermissionExceptionEntity {
    @Id UUID id;
    @Column(name = "user_id", nullable = false) UUID userId;
    @Column(name = "permission_id", nullable = false) UUID permissionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) PermissionResolution resolution;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
}

@Entity @Table(name = "audit_event", schema = "iam")
class AuditEventEntity {
    @Id UUID id;
    @Column(name = "occurred_at", nullable = false) Instant occurredAt;
    @Column(name = "actor_user_id") UUID actorUserId;
    @Column(nullable = false) String action;
    @Column(name = "target_type") String targetType;
    @Column(name = "target_id") String targetId;
    @Column(nullable = false) String result;
    @Column(name = "correlation_id") String correlationId;
    @Column(name = "before_state") String beforeState;
    @Column(name = "after_state") String afterState;
}
