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
        assertThat(PasswordPolicy.violation("k7#Wp2mq")).as("oito caracteres").isEmpty();
        assertThat(PasswordPolicy.violation("ab".repeat(36))).isEmpty();
        assertThat(PasswordPolicy.violation("çã".repeat(18))).as("36 caracteres = 72 bytes").isEmpty();
    }

    /** DR-0020 item 2, opção B: o que o atacante tenta primeiro não entra; o resto continua livre de composição. */
    @Test
    void rejectsCommonRepeatedAndSequentialPasswordsButNotOrdinaryOnes() {
        for (String common : new String[] {"12345678", "123456789", "87654321", "password", "Password123", "SENHA123",
                "senha@123", "qwertyuiop", "asdfghjkl", "abcdefgh", "aaaaaaaa", "11111111", "admin123", "uberhidraulica",
                "oficina123", "90123456"}) {
            assertThat(PasswordPolicy.violation(common)).as(common).isPresent();
            assertThat(PasswordPolicy.violation(common).orElseThrow()).doesNotContain(common);
        }
        for (String fine : new String[] {"cavalo bateria grampo", "uma-senha-valida", "Oficina da Vila 1987", "tr0ca-de-oleo",
                "12345678x", "minha senha longa"}) {
            assertThat(PasswordPolicy.violation(fine)).as(fine).isEmpty();
        }
    }

    @Test
    void rejectsMissingBlankShortAndOverlongPasswords() {
        assertThat(PasswordPolicy.violation(null)).isPresent();
        assertThat(PasswordPolicy.violation("")).isPresent();
        assertThat(PasswordPolicy.violation("        ")).as("só espaços").isPresent();
        assertThat(PasswordPolicy.violation("1234567")).isPresent();
        assertThat(PasswordPolicy.violation("ab".repeat(36) + "c")).isPresent();
        assertThat(PasswordPolicy.violation("çã".repeat(18) + "ç")).as("37 caracteres = 74 bytes").isPresent();
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
