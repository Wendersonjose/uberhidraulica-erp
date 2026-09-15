package br.com.uberhidraulica.erp.quote.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Credencial pública opaca do orçamento.
 *
 * <p>O token bruto é segredo e só existe uma vez, no instante em que é entregue a quem vai enviá-lo
 * ao cliente. O banco guarda apenas o digest SHA-256, de modo que ler a tabela inteira não permite
 * abrir nenhum link.</p>
 *
 * <p>Não deriva de nada do negócio — nem OS, nem placa, nem documento —, porque um segredo previsível
 * a partir de dado conhecido não é segredo.</p>
 */
public final class PublicAccessToken {
    /** 256 bits de material aleatório antes da codificação, conforme a revisão de segurança aprovada. */
    private static final int ENTROPY_BYTES = 32;
    private static final int DIGEST_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private PublicAccessToken() {}

    /** Token bruto em Base64 URL-safe sem padding, adequado a um caminho de URL. */
    public static String generate() {
        byte[] material = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(material);
        return ENCODER.encodeToString(material);
    }

    /**
     * Digest de busca do token.
     *
     * <p>SHA-256 e não hash de senha: o token tem 256 bits de entropia aleatória, então não há o que
     * um ataque de dicionário explore, e a busca precisa ser determinística.</p>
     */
    public static byte[] digest(String rawToken) {
        if (rawToken == null || rawToken.isBlank())
            throw new QuoteException("PUBLIC_QUOTE_NOT_AVAILABLE", "Orçamento indisponível");
        try {
            return MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponível na plataforma", impossible);
        }
    }

    public static int digestLength() { return DIGEST_BYTES; }
}
