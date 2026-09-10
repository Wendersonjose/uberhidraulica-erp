package br.com.uberhidraulica.erp.iam.infrastructure.persistence;

import br.com.uberhidraulica.erp.iam.domain.*;
import br.com.uberhidraulica.erp.iam.port.UserRepositoryPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.time.Instant;
import java.util.*;

@Component
public class JpaUserRepositoryAdapter implements UserRepositoryPort {
    private final UserJpaRepository users;
    private final ProfileJpaRepository profiles;
    private final CredentialJpaRepository credentials;

    JpaUserRepositoryAdapter(UserJpaRepository users, ProfileJpaRepository profiles, CredentialJpaRepository credentials) {
        this.users = users; this.profiles = profiles; this.credentials = credentials;
    }

    @Override public long count() { return users.count(); }
    @Override public long countActiveOwners() { return users.countByProfileCodeAndState(ProfileCode.DONO, UserState.ACTIVE); }
    @Override public Optional<IamUser> findById(UUID id) { return users.findById(id).map(this::map); }
    @Override public Optional<IamUser> findByNormalizedEmail(String email) { return users.findByNormalizedEmail(normalize(email)).map(this::map); }
    @Override public List<IamUser> findAll() { return users.findAll().stream().map(this::map).toList(); }
    @Override public UserPage findPage(int page, int size) {
        var result = users.findAll(PageRequest.of(page, size, Sort.by("name").ascending().and(Sort.by("id"))));
        return new UserPage(result.getContent().stream().map(this::map).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Override
    public IamUser create(String name, String email, ProfileCode profileCode, UserState state, String hash, boolean mustChange) {
        UserEntity user = new UserEntity();
        user.id = UUID.randomUUID(); user.name = name.trim(); user.email = email.trim(); user.normalizedEmail = normalize(email);
        user.profile = profiles.findByCode(profileCode).orElseThrow(() -> new IamException("PROFILE_NOT_FOUND", "Perfil não encontrado"));
        user.state = state; user.createdAt = user.updatedAt = Instant.now();
        try { users.saveAndFlush(user); }
        catch (DataIntegrityViolationException exception) { throw new IamException("DUPLICATE_USER_EMAIL", "E-mail já utilizado"); }

        CredentialEntity credential = new CredentialEntity();
        credential.userId = user.id; credential.passwordHash = hash;
        credential.mustChangePassword = mustChange; credential.createdAt = credential.updatedAt = Instant.now();
        credentials.save(credential);
        return map(user, credential);
    }

    @Override public void updateCredential(UUID userId, String hash, boolean mustChange) {
        CredentialEntity value = credentials.findById(userId).orElseThrow(JpaUserRepositoryAdapter::notFound);
        value.passwordHash = hash; value.mustChangePassword = mustChange; value.updatedAt = Instant.now(); credentials.save(value);
    }

    @Override public void updateState(UUID userId, UserState state) {
        UserEntity value = users.findById(userId).orElseThrow(JpaUserRepositoryAdapter::notFound);
        value.state = state; value.updatedAt = Instant.now(); users.saveAndFlush(value);
    }

    private IamUser map(UserEntity user) {
        CredentialEntity credential = credentials.findById(user.id).orElseThrow(() -> new IamException("CREDENTIAL_NOT_FOUND", "Credencial não encontrada"));
        return map(user, credential);
    }
    private IamUser map(UserEntity user, CredentialEntity credential) {
        return new IamUser(user.id, user.name, user.email, user.normalizedEmail, user.profile.getCode(), user.state, credential.passwordHash, credential.mustChangePassword);
    }
    public static String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    private static IamException notFound() { return new IamException("USER_NOT_FOUND", "Usuário não encontrado"); }
}
