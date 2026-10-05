package br.com.uberhidraulica.erp.iam.domain;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Regra mínima de uma senha definida por pessoa: bootstrap do Dono e troca de senha.
 *
 * <p>O mínimo de oito caracteres é o mesmo que o formulário de troca de senha já exige; o backend é a
 * autoridade e não pode aceitar do cliente o que a própria tela recusaria. O máximo é o limite do
 * bcrypt, 72 <b>bytes</b> em UTF-8: acima dele o codificador recusa a senha e a requisição terminaria em
 * erro 500, ou, pior, em versões que truncam em silêncio, duas senhas diferentes valeriam o mesmo.</p>
 *
 * <p>Não impõe composição (maiúsculas, símbolos...) nem expiração: política mais rígida é decisão de
 * produto e está registrada na {@code DR-0020}. As senhas temporárias geradas pelo sistema têm 32
 * caracteres aleatórios e sempre cumprem esta regra.</p>
 */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {}

    /** Motivo da recusa, sem nunca ecoar a senha; vazio quando a senha cumpre a regra. */
    public static Optional<String> violation(String password) {
        if (password == null || password.isBlank())
            return Optional.of("A senha é obrigatória");
        if (password.codePointCount(0, password.length()) < MIN_LENGTH)
            return Optional.of("A senha deve ter pelo menos " + MIN_LENGTH + " caracteres");
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            return Optional.of("A senha deve ter no máximo " + MAX_BYTES + " bytes (cerca de 72 caracteres sem acentos)");
        return Optional.empty();
    }

    public static void require(String password) {
        violation(password).ifPresent(message -> {
            throw new IamException("PASSWORD_POLICY_VIOLATION", message);
        });
    }
}
