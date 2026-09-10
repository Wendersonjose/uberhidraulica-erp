package br.com.uberhidraulica.erp.iam.application;

import org.springframework.stereotype.Component;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class TemporaryCredentialGenerator {
    private final SecureRandom random = new SecureRandom();
    public String generate() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
