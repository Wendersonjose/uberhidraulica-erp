package br.com.uberhidraulica.erp.iam.infrastructure.security;

import br.com.uberhidraulica.erp.iam.CurrentUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class CurrentUserAdapter implements CurrentUser {
    @Override
    public Optional<UUID> id() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof IamPrincipal principal
                ? Optional.of(principal.id())
                : Optional.empty();
    }

    @Override
    public UUID requireId() {
        return id().orElseThrow(() -> new IllegalStateException("Operação exige usuário autenticado"));
    }
}
