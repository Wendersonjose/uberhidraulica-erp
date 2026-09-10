package br.com.uberhidraulica.erp.iam.infrastructure.persistence;

import br.com.uberhidraulica.erp.iam.domain.ProfileCode;
import br.com.uberhidraulica.erp.iam.domain.UserState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.*;

interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {
    @EntityGraph(attributePaths = "profile")
    Optional<UserEntity> findById(UUID id);
    @EntityGraph(attributePaths = "profile")
    Optional<UserEntity> findByNormalizedEmail(String normalizedEmail);
    @EntityGraph(attributePaths = "profile")
    List<UserEntity> findAll();
    @EntityGraph(attributePaths = "profile")
    Page<UserEntity> findAll(Pageable pageable);
    long countByProfileCodeAndState(ProfileCode code, UserState state);
}
interface ProfileJpaRepository extends JpaRepository<ProfileEntity, UUID> { Optional<ProfileEntity> findByCode(ProfileCode code); }
interface PermissionJpaRepository extends JpaRepository<PermissionEntity, UUID> { Optional<PermissionEntity> findByCode(String code); }
interface CredentialJpaRepository extends JpaRepository<CredentialEntity, UUID> {}
interface ProfilePermissionJpaRepository extends JpaRepository<ProfilePermissionEntity, ProfilePermissionId> { List<ProfilePermissionEntity> findByProfileId(UUID profileId); }
interface UserPermissionExceptionJpaRepository extends JpaRepository<UserPermissionExceptionEntity, UUID> {
    List<UserPermissionExceptionEntity> findByUserId(UUID userId);
    Optional<UserPermissionExceptionEntity> findByUserIdAndPermissionId(UUID userId, UUID permissionId);
}
interface AuditEventJpaRepository extends JpaRepository<AuditEventEntity, UUID> {}
