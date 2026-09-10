package br.com.uberhidraulica.erp.iam.port;

import br.com.uberhidraulica.erp.iam.domain.IamUser;
import br.com.uberhidraulica.erp.iam.domain.ProfileCode;
import br.com.uberhidraulica.erp.iam.domain.UserState;
import br.com.uberhidraulica.erp.iam.domain.UserPage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepositoryPort {
    long count();
    long countActiveOwners();
    Optional<IamUser> findById(UUID id);
    Optional<IamUser> findByNormalizedEmail(String email);
    List<IamUser> findAll();
    UserPage findPage(int page, int size);
    IamUser create(String name, String email, ProfileCode profile, UserState state, String hash, boolean mustChange);
    void updateCredential(UUID userId, String hash, boolean mustChange);
    void updateState(UUID userId, UserState state);
}
