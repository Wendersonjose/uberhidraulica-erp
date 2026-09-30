package br.com.uberhidraulica.erp.iam;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import br.com.uberhidraulica.erp.iam.domain.ProfileCode;
import br.com.uberhidraulica.erp.iam.port.UserRepositoryPort;
import br.com.uberhidraulica.erp.iam.port.AuthorizationRepositoryPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Stream;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class IamAuthenticationIntegrationTest {
    private static final java.util.List<String> FINANCE = java.util.List.of("FINANCE_VIEW", "FINANCE_RECEIVE", "FINANCE_REVERSE",
            "FINANCE_ADJUST", "FINANCE_PAYABLE", "FINANCE_CONFIG", "FINANCE_BILL");
    private static final java.util.List<String> CASH = java.util.List.of("CASH_SESSION_OPEN", "CASH_SESSION_CLOSE",
            "CASH_SUPPLY", "CASH_WITHDRAWAL", "CASH_REVERSAL");
    // V20: rotina de módulo vai para DONO + GERENTE_ADMINISTRATIVO; sensível vai para DONO + GERENTE_FINANCEIRO.
    private static final java.util.List<String> MODULE_ROUTINE = java.util.List.of("PRODUCT_MANAGE", "INVENTORY_MOVE",
            "WORKORDER_MANAGE", "WORKORDER_CONFIG", "CRM_MANAGE", "SERVICE_MANAGE", "QUOTE_MANAGE");
    private static final java.util.List<String> MODULE_SENSITIVE = java.util.List.of("PRODUCT_COST_MANAGE", "INVENTORY_ADJUST",
            "SERVICE_PRICE_MANAGE");

    private static final String OWNER_EMAIL = "owner@example.test";
    private static final String OWNER_PASSWORD = "bootstrap-secret-for-test";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Test");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> OWNER_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AuthenticationManager authenticationManager;
    @Autowired UserRepositoryPort users;
    @Autowired AuthorizationRepositoryPort authorizations;
    @Autowired DataSource dataSource;
    private static final Set<String> ISSUED_SECRETS = new HashSet<>();
    private static final Set<String> ISSUED_SESSION_COOKIES = new HashSet<>();

    @Test
    @Order(1)
    void bootstrapCreatesFixedCatalogOwnerAndOnlyHashesTheSecret() {
        assertThat(jdbc.queryForObject("select count(*) from iam.app_user", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from iam.profile", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("select code from iam.profile order by code", String.class))
                .containsExactly("DONO", "GERENTE_ADMINISTRATIVO", "GERENTE_FINANCEIRO");
        assertThat(authorizations.profilePermissions(ProfileCode.DONO))
                .containsAll(br.com.uberhidraulica.erp.iam.domain.IamPermissions.ALL);
        assertThat(authorizations.profilePermissions(ProfileCode.DONO)).containsExactlyInAnyOrderElementsOf(
                Stream.of(br.com.uberhidraulica.erp.iam.domain.IamPermissions.ALL.stream(),
                        Stream.of("QUOTE_PRESENT", "QUOTE_DISCOUNT"), FINANCE.stream(), CASH.stream(),
                        MODULE_ROUTINE.stream(), MODULE_SENSITIVE.stream())
                        .flatMap(s -> s).toList());
        assertThat(authorizations.profilePermissions(ProfileCode.GERENTE_ADMINISTRATIVO)).containsExactlyInAnyOrderElementsOf(
                Stream.concat(Stream.of("QUOTE_PRESENT", "QUOTE_DISCOUNT", "FINANCE_VIEW", "FINANCE_RECEIVE", "FINANCE_BILL"),
                        MODULE_ROUTINE.stream()).toList());
        // DR-0015 + DR-0018: gerente financeiro recebe Financeiro, caixa e permissões sensíveis do V20.
        assertThat(authorizations.profilePermissions(ProfileCode.GERENTE_FINANCEIRO)).containsExactlyInAnyOrderElementsOf(
                Stream.of(FINANCE.stream(), CASH.stream(), MODULE_SENSITIVE.stream()).flatMap(s -> s).toList());
        assertThat(jdbc.queryForObject("select count(*) from iam.audit_event where action='FIRST_OWNER_BOOTSTRAPPED'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select must_change_password from iam.credential", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select password_hash from iam.credential", String.class))
                .doesNotContain(OWNER_PASSWORD).startsWith("{");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='iam' and column_name='tenant_id'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='iam' and table_name='spring_session'", Long.class)).isEqualTo(1);
    }

    @Test
    @Order(3)
    void loginRotatesExistingSessionAndOldIdentifierCannotBeReused() throws Exception {
        String storedHash = jdbc.queryForObject(
                "select c.password_hash from iam.credential c join iam.app_user u on u.id=c.user_id where u.normalized_email=?",
                String.class, OWNER_EMAIL);
        assertThat(storedHash).doesNotContain(OWNER_PASSWORD);
        assertThat(passwordEncoder.matches(OWNER_PASSWORD, storedHash)).isTrue();
        assertThat(jdbc.queryForObject(
                "select p.code from iam.app_user u join iam.profile p on p.id=u.profile_id where u.normalized_email=?",
                String.class, OWNER_EMAIL)).isEqualTo("DONO");
        assertThat(users.findByNormalizedEmail(OWNER_EMAIL).orElseThrow().profileCode()).isEqualTo(ProfileCode.DONO);
        assertThat(authorizations.profilePermissions(ProfileCode.DONO)).isNotEmpty();
        assertThat(authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(OWNER_EMAIL, OWNER_PASSWORD)).isAuthenticated()).isTrue();

        MvcResult csrfResult = mvc.perform(get("/api/iam/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie anonymousSession = requiredCookie(csrfResult, "SESSION");
        assertThat(anonymousSession.isHttpOnly()).isTrue();
        assertThat(anonymousSession.getSecure()).isFalse();
        assertThat(csrfResult.getResponse().getHeader("Set-Cookie")).contains("SameSite=Lax");
        String csrfToken = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");
        String csrfHeader = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.headerName");

        MvcResult loginResult = mvc.perform(post("/api/iam/auth/login")
                        .cookie(anonymousSession)
                        .header(csrfHeader, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + OWNER_EMAIL + "\",\"password\":\"" + OWNER_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn();

        Cookie authenticatedSession = requiredCookie(loginResult, "SESSION");
        assertThat(authenticatedSession.getValue()).isNotEqualTo(anonymousSession.getValue());

        mvc.perform(get("/api/iam/session").cookie(authenticatedSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(OWNER_EMAIL))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        mvc.perform(get("/api/iam/session").cookie(anonymousSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @Order(2)
    void invalidLoginDoesNotRevealWhetherEmailExists() throws Exception {
        long beforeExisting = anonymousAuditCount("LOGIN_FAILED", "FAILURE");
        String existing = failedLoginCode(OWNER_EMAIL);
        AuditEvidence existingAudit = assertExactlyOneAnonymousAudit(beforeExisting, "LOGIN_FAILED", "FAILURE");

        long beforeMissing = anonymousAuditCount("LOGIN_FAILED", "FAILURE");
        String missing = failedLoginCode("missing@example.test");
        AuditEvidence missingAudit = assertExactlyOneAnonymousAudit(beforeMissing, "LOGIN_FAILED", "FAILURE");

        assertThat(existing).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(missing).isEqualTo(existing);
        assertThat(existingAudit.correlationId()).isNotEqualTo(missingAudit.correlationId());
        assertAuditHasNoSensitiveData(existingAudit.correlationId(), OWNER_EMAIL);

        // remainder unchanged
    }
}
