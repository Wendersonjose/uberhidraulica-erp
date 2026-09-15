package br.com.uberhidraulica.erp.iam;

import java.util.Optional;
import java.util.UUID;

/**
 * Identidade do usuário autenticado na requisição em curso.
 *
 * <p>Existe para que outros módulos registrem autoria sem depender do principal interno do IAM.
 * Não concede nem verifica permissão: autorização continua sendo {@link IamAuthorization}.</p>
 */
public interface CurrentUser {
    Optional<UUID> id();

    /** Identidade do autor da operação; falha quando não há sessão autenticada. */
    UUID requireId();
}
