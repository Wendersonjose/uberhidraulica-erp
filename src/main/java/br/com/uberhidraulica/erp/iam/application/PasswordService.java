package br.com.uberhidraulica.erp.iam.application;

import br.com.uberhidraulica.erp.iam.domain.*;
import br.com.uberhidraulica.erp.iam.port.AuditPort;
import br.com.uberhidraulica.erp.iam.port.UserRepositoryPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class PasswordService {
    private final UserRepositoryPort users;
    private final AuditPort audit;
    private final PasswordEncoder encoder;

    public PasswordService(UserRepositoryPort users, AuditPort audit, PasswordEncoder encoder) {
        this.users = users; this.audit = audit; this.encoder = encoder;
    }

    @Transactional
    public void change(UUID userId, String currentPassword, String newPassword) {
        IamUser user = users.findById(userId).orElseThrow(() -> new IamException("USER_NOT_FOUND", "Usuário não encontrado"));
        if (!encoder.matches(currentPassword, user.passwordHash())) {
            audit.recordIndependent(userId, "PASSWORD_CHANGED", "USER", userId.toString(), "FAILURE", null, null);
            throw new IamException("CURRENT_PASSWORD_INVALID", "Senha atual inválida");
        }
        users.updateCredential(userId, encoder.encode(newPassword), false);
        audit.record(userId, "PASSWORD_CHANGED", "USER", userId.toString(), "SUCCESS", null, "mustChangePassword=false");
    }
}
