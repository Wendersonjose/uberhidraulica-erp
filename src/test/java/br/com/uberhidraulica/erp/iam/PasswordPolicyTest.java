package br.com.uberhidraulica.erp.iam;

import br.com.uberhidraulica.erp.iam.domain.IamException;
import br.com.uberhidraulica.erp.iam.domain.PasswordPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {
    @Test
    void acceptsTheBoundariesInCharactersAndBytes() {
        assertThat(PasswordPolicy.violation("12345678")).isEmpty();
        assertThat(PasswordPolicy.violation("a".repeat(72))).isEmpty();
        assertThat(PasswordPolicy.violation("ç".repeat(36))).as("36 caracteres = 72 bytes").isEmpty();
    }

    @Test
    void rejectsMissingBlankShortAndOverlongPasswords() {
        assertThat(PasswordPolicy.violation(null)).isPresent();
        assertThat(PasswordPolicy.violation("")).isPresent();
        assertThat(PasswordPolicy.violation("        ")).as("só espaços").isPresent();
        assertThat(PasswordPolicy.violation("1234567")).isPresent();
        assertThat(PasswordPolicy.violation("a".repeat(73))).isPresent();
        assertThat(PasswordPolicy.violation("ç".repeat(37))).as("37 caracteres = 74 bytes").isPresent();
    }

    @Test
    void neverEchoesThePasswordInTheMessage() {
        String secret = "curta";
        assertThat(PasswordPolicy.violation(secret).orElseThrow()).doesNotContain(secret);
        assertThatThrownBy(() -> PasswordPolicy.require(secret))
                .isInstanceOfSatisfying(IamException.class, error -> {
                    assertThat(error.code()).isEqualTo("PASSWORD_POLICY_VIOLATION");
                    assertThat(error.getMessage()).doesNotContain(secret);
                });
        assertThatCode(() -> PasswordPolicy.require("uma-senha-valida")).doesNotThrowAnyException();
    }
}
