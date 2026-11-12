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
 * <p>Recusa também as <b>senhas comuns</b> ({@code DR-0020} item 2, opção B, decidida em 2026-11-12): as que
 * encabeçam qualquer lista de vazamento, as de um caractere só e as sequências de teclado. É a recomendação da
 * NIST SP 800-63B — barrar o que o atacante tenta primeiro — e por isso continua sem impor composição
 * (maiúsculas, símbolos...) nem expiração periódica. As senhas temporárias geradas pelo sistema têm 32
 * caracteres aleatórios e sempre cumprem esta regra.</p>
 */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;

    /**
     * Senhas que não entram, comparadas sem diferenciar maiúsculas. A lista é curta de propósito: cobre o que
     * aparece no topo dos vazamentos em português e em inglês com oito caracteres ou mais; as variações mecânicas
     * (um caractere repetido, sequências) são pegas por regra, não por lista.
     */
    private static final java.util.Set<String> COMMON = java.util.Set.of(
            "password", "password1", "password12", "password123", "passw0rd", "p@ssw0rd", "p@ssword",
            "senha123", "senha1234", "senha12345", "senha@123", "minhasenha", "mudar123", "mudar@123", "trocar123",
            "admin123", "admin1234", "admin@123", "administrador", "administrator", "root1234",
            "qwerty123", "qwertyuiop", "1q2w3e4r", "1q2w3e4r5t", "q1w2e3r4", "zaq12wsx", "1qaz2wsx",
            "iloveyou", "teamo123", "euteamo123", "brasil123", "brasil2026", "flamengo", "corinthians", "palmeiras",
            "saopaulo", "cruzeiro", "atletico", "gremio123", "futebol123", "jesus123", "deus1234", "felicidade",
            "oficina123", "oficina1234", "uberhidraulica", "hidraulica", "hidraulica123", "uberlandia", "mecanica123",
            "welcome1", "welcome123", "bemvindo", "bemvindo123", "letmein123", "changeme", "changeme123", "trustno1",
            "monkey123", "dragon123", "master123", "superman", "princesa", "estrela123", "familia123", "sunshine",
            "football", "baseball", "abc12345", "abcd1234", "a1b2c3d4", "123456789a", "1234qwer", "qwer1234",
            "102030405", "10203040", "11223344", "12341234", "12121212", "123123123", "123321123", "987654321a");

    private static final String[] RUNS = {
            "0123456789", "9876543210", "abcdefghijklmnopqrstuvwxyz", "zyxwvutsrqponmlkjihgfedcba",
            "qwertyuiop", "poiuytrewq", "asdfghjkl", "lkjhgfdsa", "zxcvbnm", "mnbvcxz"};

    /** Senha comum, de um caractere só ou feita de uma única sequência (numérica, alfabética ou de teclado). */
    static boolean common(String password) {
        String normalized = password.toLowerCase(java.util.Locale.ROOT);
        if (COMMON.contains(normalized)) return true;
        if (normalized.chars().distinct().count() == 1) return true;
        for (String run : RUNS) {
            // "0123456789012" e afins: a sequência pode dar a volta.
            if ((run + run).contains(normalized)) return true;
        }
        return false;
    }

    private PasswordPolicy() {}

    /** Motivo da recusa, sem nunca ecoar a senha; vazio quando a senha cumpre a regra. */
    public static Optional<String> violation(String password) {
        if (password == null || password.isBlank())
            return Optional.of("A senha é obrigatória");
        if (password.codePointCount(0, password.length()) < MIN_LENGTH)
            return Optional.of("A senha deve ter pelo menos " + MIN_LENGTH + " caracteres");
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            return Optional.of("A senha deve ter no máximo " + MAX_BYTES + " bytes (cerca de 72 caracteres sem acentos)");
        if (common(password))
            return Optional.of("Esta senha é muito comum ou previsível. Escolha outra: uma frase com três ou quatro palavras funciona bem");
        return Optional.empty();
    }

    public static void require(String password) {
        violation(password).ifPresent(message -> {
            throw new IamException("PASSWORD_POLICY_VIOLATION", message);
        });
    }
}
