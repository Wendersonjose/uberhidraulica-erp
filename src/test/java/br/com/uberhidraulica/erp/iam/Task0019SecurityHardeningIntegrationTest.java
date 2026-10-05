package br.com.uberhidraulica.erp.iam;

import br.com.uberhidraulica.erp.iam.domain.PasswordPolicy;
import br.com.uberhidraulica.erp.support.ApiSessions;
import br.com.uberhidraulica.erp.support.ApiSessions.Session;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endurecimento encontrado na auditoria de fechamento do MVP, contra PostgreSQL real.
 *
 * <ul>
 *   <li>respostas 401/403 escritas no filtro de segurança eram ISO-8859-1 (corpo JSON com byte inválido em UTF-8);</li>
 *   <li>a troca de senha aceitava uma senha de 1 caractere e, acima de 72 bytes (limite do bcrypt), terminava em 500.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class Task0019SecurityHardeningIntegrationTest {
    private static final String OWNER_EMAIL = "owner-hardening@example.test";
    private static final String BOOTSTRAP_PASSWORD = "hardening-bootstrap-password";
    private static final String PASSWORD = "hardening-operational-password";

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Hardening");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired MockMvc mvc;

    private ApiSessions api;
    private Session owner;

    @BeforeEach
    void sessions() throws Exception {
        api = new ApiSessions(mvc, PASSWORD);
        owner = api.owner(OWNER_EMAIL, BOOTSTRAP_PASSWORD);
    }

    @Test
    void unauthenticatedResponseIsUtf8Json() throws Exception {
        MvcResult result = mvc.perform(get("/api/customers")).andExpect(status().isUnauthorized()).andReturn();

        assertThat(result.getResponse().getContentType()).containsIgnoringCase("application/json").containsIgnoringCase("charset=UTF-8");
        assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .contains("\"code\":\"AUTHENTICATION_REQUIRED\"").contains("Autenticação necessária");
    }

    @Test
    void passwordChangeRequiredResponseIsUtf8Json() throws Exception {
        String created = api.send(owner, post("/api/iam/users"), "{\"name\":\"Gerente Troca\",\"email\":\"troca-pendente@example.test\","
                        + "\"profileCode\":\"GERENTE_ADMINISTRATIVO\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String temporary = JsonPath.read(created, "$.temporaryPassword");
        Session pending = api.login("troca-pendente@example.test", temporary, false);

        MvcResult blocked = api.read(pending, "/api/customers").andExpect(status().isForbidden()).andReturn();

        assertThat(blocked.getResponse().getContentType()).containsIgnoringCase("charset=UTF-8");
        assertThat(new String(blocked.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .contains("\"code\":\"PASSWORD_CHANGE_REQUIRED\"").contains("Troca de senha obrigatória");
    }

    @Test
    void passwordChangeRejectsShortAndOverlongPasswordsWithoutServerError() throws Exception {
        String tooShort = "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"a\"}";
        api.send(owner, post("/api/iam/password/change"), tooShort)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"));

        // 73 bytes: o bcrypt recusa acima de 72 e a requisição virava 500.
        String ascii73 = "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + "a".repeat(73) + "\"}";
        api.send(owner, post("/api/iam/password/change"), ascii73)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"));

        // 40 caracteres, 80 bytes em UTF-8: o limite é em bytes, não em caracteres.
        String accented = "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + "ç".repeat(40) + "\"}";
        api.send(owner, post("/api/iam/password/change"), accented)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"));

        // A senha atual continua valendo depois de todas as recusas.
        assertThat(api.login(OWNER_EMAIL, PASSWORD, true)).isNotNull();
    }

    @Test
    void passwordChangeAcceptsTheBoundaryLengthsAndTheOldSecretStopsWorking() throws Exception {
        String seventyTwo = "b".repeat(72);
        api.send(owner, post("/api/iam/password/change"), "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + seventyTwo + "\"}")
                .andExpect(status().isNoContent());
        assertThat(api.login(OWNER_EMAIL, PASSWORD, true)).as("a senha antiga deixou de valer").isNull();
        Session renewed = api.login(OWNER_EMAIL, seventyTwo, false);

        String eight = "c".repeat(8);
        api.send(renewed, post("/api/iam/password/change"), "{\"currentPassword\":\"" + seventyTwo + "\",\"newPassword\":\"" + eight + "\"}")
                .andExpect(status().isNoContent());
        assertThat(api.login(OWNER_EMAIL, eight, true)).isNotNull();

        // Restaura a senha operacional para os demais testes da classe.
        Session current = api.login(OWNER_EMAIL, eight, false);
        api.send(current, post("/api/iam/password/change"), "{\"currentPassword\":\"" + eight + "\",\"newPassword\":\"" + PASSWORD + "\"}")
                .andExpect(status().isNoContent());
    }

    @Test
    void generatedTemporaryPasswordsAlwaysSatisfyThePolicy() throws Exception {
        for (int i = 0; i < 20; i++) {
            String created = api.send(owner, post("/api/iam/users"), "{\"name\":\"Gerador " + i + "\",\"email\":\"gerador-" + i
                            + "@example.test\",\"profileCode\":\"GERENTE_FINANCEIRO\"}")
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            String temporary = JsonPath.read(created, "$.temporaryPassword");
            assertThat(PasswordPolicy.violation(temporary)).isEmpty();
        }
    }
}
