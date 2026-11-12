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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

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
        String seventyTwo = "bq".repeat(36);
        api.send(owner, post("/api/iam/password/change"), "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + seventyTwo + "\"}")
                .andExpect(status().isNoContent());
        assertThat(api.login(OWNER_EMAIL, PASSWORD, true)).as("a senha antiga deixou de valer").isNull();
        Session renewed = api.login(OWNER_EMAIL, seventyTwo, false);

        String eight = "c7#Kp2mq";
        api.send(renewed, post("/api/iam/password/change"), "{\"currentPassword\":\"" + seventyTwo + "\",\"newPassword\":\"" + eight + "\"}")
                .andExpect(status().isNoContent());
        assertThat(api.login(OWNER_EMAIL, eight, true)).isNotNull();

        // Restaura a senha operacional para os demais testes da classe.
        Session current = api.login(OWNER_EMAIL, eight, false);
        api.send(current, post("/api/iam/password/change"), "{\"currentPassword\":\"" + eight + "\",\"newPassword\":\"" + PASSWORD + "\"}")
                .andExpect(status().isNoContent());
    }

    // ================================================================ DR-0020 (decidida em 2026-11-12)

    /** Item 2, opção B: senha comum é recusada pelo backend, sem ecoar a senha e sem trocar a credencial. */
    @Test
    void passwordChangeRefusesCommonPasswords() throws Exception {
        for (String common : new String[] {"12345678", "Password123", "senha@123", "aaaaaaaa", "qwertyuiop"}) {
            MvcResult refused = api.send(owner, post("/api/iam/password/change"),
                            "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + common + "\"}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"))
                    .andReturn();
            assertThat(refused.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(common);
        }
        assertThat(api.login(OWNER_EMAIL, PASSWORD, true)).as("a senha operacional continua valendo").isNotNull();
    }

    /**
     * Item 3, opção A: quem recebe permissões IAM por delegação administra gerentes, mas não cria nem inativa um Dono,
     * não mexe no perfil Dono e não concede IAM a ninguém — nem a si mesmo.
     */
    @Test
    void aManagerWithDelegatedIamPermissionsCannotReachTheOwnerProfileNorGrantIam() throws Exception {
        Session manager = api.user(owner, "delegado-hardening@example.test", "GERENTE_ADMINISTRATIVO");
        Session other = api.user(owner, "financeiro-hardening@example.test", "GERENTE_FINANCEIRO");
        assertThat(other).isNotNull();
        String managerId = userId("delegado-hardening@example.test");
        String otherId = userId("financeiro-hardening@example.test");
        String ownerId = userId(OWNER_EMAIL);

        // O Dono pode delegar IAM: a decisão não tirou esse poder dele.
        for (String code : new String[] {"IAM_USERS_MANAGE", "IAM_PROFILE_PERMISSIONS_MANAGE", "IAM_USER_EXCEPTIONS_MANAGE"})
            exception(owner, managerId, code, "ALLOW").andExpect(status().isNoContent());
        manager = api.login("delegado-hardening@example.test", PASSWORD, false);
        long deniedBefore = denied();

        // Usuários: gerente sim, Dono não.
        api.send(manager, post("/api/iam/users"), "{\"name\":\"Novo gerente\",\"email\":\"novo-gerente-hardening@example.test\","
                + "\"profileCode\":\"GERENTE_FINANCEIRO\"}").andExpect(status().isCreated());
        api.send(manager, post("/api/iam/users"), "{\"name\":\"Dono forjado\",\"email\":\"dono-forjado-hardening@example.test\","
                + "\"profileCode\":\"DONO\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_ONLY_OPERATION"));
        api.send(manager, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/iam/users/" + ownerId),
                "{\"state\":\"INACTIVE\"}").andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from iam.app_user where normalized_email = 'dono-forjado-hardening@example.test'",
                Long.class)).isZero();

        // Perfis: o perfil Dono e qualquer IAM_* ficam fora do alcance; o resto continua delegado.
        permission(manager, "DONO", "FINANCE_VIEW", true).andExpect(status().isForbidden());
        permission(manager, "DONO", "FINANCE_VIEW", false).andExpect(status().isForbidden());
        permission(manager, "GERENTE_ADMINISTRATIVO", "IAM_PASSWORD_RESET", true).andExpect(status().isForbidden());
        permission(manager, "GERENTE_FINANCEIRO", "WORKORDER_MANAGE", true).andExpect(status().isNoContent());
        permission(manager, "GERENTE_FINANCEIRO", "WORKORDER_MANAGE", false).andExpect(status().isNoContent());

        // Exceções: não se dá IAM a si mesmo nem a outro, e não se mexe nas exceções de um Dono.
        exception(manager, managerId, "IAM_PASSWORD_RESET", "ALLOW").andExpect(status().isForbidden());
        exception(manager, otherId, "IAM_USERS_READ", "ALLOW").andExpect(status().isForbidden());
        exception(manager, ownerId, "FINANCE_VIEW", "DENY").andExpect(status().isForbidden());
        exception(manager, otherId, "WORKORDER_MANAGE", "ALLOW").andExpect(status().isNoContent());
        exception(manager, otherId, "WORKORDER_MANAGE", "INHERIT").andExpect(status().isNoContent());

        // Toda recusa ficou na auditoria, mesmo com a transação desfeita.
        assertThat(denied() - deniedBefore).isEqualTo(8);

        for (String code : new String[] {"IAM_USERS_MANAGE", "IAM_PROFILE_PERMISSIONS_MANAGE", "IAM_USER_EXCEPTIONS_MANAGE"})
            exception(owner, managerId, code, "INHERIT").andExpect(status().isNoContent());
    }

    private String userId(String email) {
        return jdbc.queryForObject("select id::text from iam.app_user where normalized_email = ?", String.class, email);
    }

    private long denied() {
        return jdbc.queryForObject("select count(*) from iam.audit_event where result = 'DENIED'", Long.class);
    }

    private org.springframework.test.web.servlet.ResultActions exception(Session as, String userId, String code, String resolution)
            throws Exception {
        return api.send(as, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/iam/users/" + userId + "/permission-exceptions/" + code), "{\"resolution\":\"" + resolution + "\"}");
    }

    private org.springframework.test.web.servlet.ResultActions permission(Session as, String profile, String code, boolean grant)
            throws Exception {
        String path = "/api/iam/profiles/" + profile + "/permissions/" + code;
        return api.send(as, grant ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                : org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path), "");
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
