package br.com.uberhidraulica.erp.iam.application;

import br.com.uberhidraulica.erp.iam.domain.*;
import br.com.uberhidraulica.erp.iam.port.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class UserAdminService {
    private final UserRepositoryPort users;
    private final AuthorizationRepositoryPort authorizations;
    private final ConcurrencyPort concurrency;
    private final SessionPort sessions;
    private final AuditPort audit;
    private final PasswordEncoder encoder;
    private final TemporaryCredentialGenerator generator;

    public UserAdminService(UserRepositoryPort users, AuthorizationRepositoryPort authorizations,
                            ConcurrencyPort concurrency, SessionPort sessions, AuditPort audit,
                            PasswordEncoder encoder, TemporaryCredentialGenerator generator) {
        this.users = users; this.authorizations = authorizations; this.concurrency = concurrency;
        this.sessions = sessions; this.audit = audit; this.encoder = encoder; this.generator = generator;
    }

    public List<IamUser> list() { return users.findAll(); }
    public UserPage list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IamException("INVALID_PAGINATION", "Paginação inválida");
        }
        return users.findPage(page, size);
    }
    public IamUser get(UUID id) { return users.findById(id).orElseThrow(UserAdminService::notFound); }

    @Transactional
    public CreatedUser create(UUID actor, String name, String email, ProfileCode profile) {
        if (profile == ProfileCode.DONO) requireOwner(actor, "USER_CREATED", "USER", email, "profile=DONO");
        String temporary = generator.generate();
        IamUser created = users.create(name, email, profile, UserState.ACTIVE, encoder.encode(temporary), true);
        audit.record(actor, "USER_CREATED", "USER", created.id().toString(), "SUCCESS", null, "state=ACTIVE;profile=" + profile);
        return new CreatedUser(created, temporary);
    }

    @Transactional
    public IamUser changeState(UUID actor, UUID userId, UserState next) {
        IamUser current = get(userId);
        if (current.profileCode() == ProfileCode.DONO)
            requireOwner(actor, "USER_STATE_CHANGED", "USER", userId.toString(), next.name());
        if (current.state() == next) return current;
        if (current.profileCode() == ProfileCode.DONO && next == UserState.INACTIVE) {
            concurrency.lockActiveOwnerTransition();
            if (users.countActiveOwners() <= 1) {
                audit.recordIndependent(actor, "USER_STATE_CHANGED", "USER", userId.toString(), "DENIED", current.state().name(), next.name());
                throw new IamException("LAST_ACTIVE_OWNER_REQUIRED", "Ao menos um Dono deve permanecer ativo");
            }
        }
        users.updateState(userId, next);
        if (next == UserState.INACTIVE) sessions.invalidateAll(current.normalizedEmail());
        audit.record(actor, "USER_STATE_CHANGED", "USER", userId.toString(), "SUCCESS", current.state().name(), next.name());
        return get(userId);
    }

    @Transactional
    public TemporaryPassword resetPassword(UUID actor, ProfileCode actorProfile, UUID targetId) {
        if (actorProfile != ProfileCode.DONO || actor.equals(targetId)) {
            audit.recordIndependent(actor, "PASSWORD_RESET", "USER", targetId.toString(), "DENIED", null, null);
            throw new IamException("ACCESS_DENIED", "Acesso negado");
        }
        IamUser target = get(targetId);
        String temporary = generator.generate();
        users.updateCredential(targetId, encoder.encode(temporary), true);
        sessions.invalidateAll(target.normalizedEmail());
        audit.record(actor, "PASSWORD_RESET", "USER", targetId.toString(), "SUCCESS", null, "mustChangePassword=true");
        audit.record(actor, "SESSIONS_INVALIDATED", "USER", targetId.toString(), "SUCCESS", null, "reason=PASSWORD_RESET");
        return new TemporaryPassword(temporary);
    }

    public Set<String> permissions() { return authorizations.permissionCatalog(); }
    public Set<String> profilePermissions(ProfileCode profile) { return authorizations.profilePermissions(profile); }
    public Map<String, PermissionResolution> exceptions(UUID userId) { get(userId); return authorizations.userExceptions(userId); }

    @Transactional public void addProfilePermission(UUID actor, ProfileCode profile, String code) {
        if (profile == ProfileCode.DONO || iam(code)) requireOwner(actor, "PROFILE_PERMISSION_ADDED", "PROFILE", profile.name(), code);
        authorizations.addProfilePermission(profile, code); audit.record(actor, "PROFILE_PERMISSION_ADDED", "PROFILE", profile.name(), "SUCCESS", null, code);
    }
    @Transactional public void removeProfilePermission(UUID actor, ProfileCode profile, String code) {
        if (profile == ProfileCode.DONO || iam(code)) requireOwner(actor, "PROFILE_PERMISSION_REMOVED", "PROFILE", profile.name(), code);
        authorizations.removeProfilePermission(profile, code); audit.record(actor, "PROFILE_PERMISSION_REMOVED", "PROFILE", profile.name(), "SUCCESS", code, null);
    }
    @Transactional public void setException(UUID actor, UUID userId, String code, PermissionResolution resolution) {
        if (iam(code) || get(userId).profileCode() == ProfileCode.DONO)
            requireOwner(actor, "USER_PERMISSION_EXCEPTION_CHANGED", "USER", userId.toString(), code + ":" + resolution);
        authorizations.setUserException(userId, code, resolution); audit.record(actor, "USER_PERMISSION_EXCEPTION_CHANGED", "USER", userId.toString(), "SUCCESS", null, code + ":" + resolution);
    }

    private static boolean iam(String permissionCode) {
        return permissionCode != null && permissionCode.toUpperCase(Locale.ROOT).startsWith("IAM_");
    }

    /**
     * DR-0020 item 3, opção A (decidida em 2026-11-12): criar ou inativar/reativar um usuário Dono, alterar as
     * permissões do perfil Dono e conceder, retirar ou excepcionar qualquer permissão {@code IAM_*} são atos
     * exclusivos de quem É Dono. As permissões {@code IAM_*} continuam delegáveis para o resto (criar gerente, ler
     * usuários), mas não servem mais de escada: quem as recebe não consegue se promover a Dono nem ampliar o próprio
     * alcance no IAM. A recusa é auditada em transação própria, para sobreviver ao rollback.
     */
    private void requireOwner(UUID actor, String action, String targetType, String targetId, String attempted) {
        boolean owner = actor != null && users.findById(actor)
                .map(user -> user.profileCode() == ProfileCode.DONO && user.state() == UserState.ACTIVE).orElse(false);
        if (owner) return;
        audit.recordIndependent(actor, action, targetType, targetId, "DENIED", null, attempted);
        throw new IamException("OWNER_ONLY_OPERATION",
                "Somente um usuário com perfil Dono pode alterar um Dono, o perfil Dono ou permissões de administração (IAM)");
    }

    private static IamException notFound() { return new IamException("USER_NOT_FOUND", "Usuário não encontrado"); }
    public record CreatedUser(IamUser user, String temporaryPassword) {}
    public record TemporaryPassword(String temporaryPassword) {}
}
