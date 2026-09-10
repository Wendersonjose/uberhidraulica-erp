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
        assertThat(authorizations.profilePermissions(ProfileCode.DONO)).containsExactlyInAnyOrderElementsOf(
                br.com.uberhidraulica.erp.iam.domain.IamPermissions.ALL);
        assertThat(authorizations.profilePermissions(ProfileCode.GERENTE_ADMINISTRATIVO)).isEmpty();
        assertThat(authorizations.profilePermissions(ProfileCode.GERENTE_FINANCEIRO)).isEmpty();
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
        assertAuditHasNoSensitiveData(missingAudit.correlationId(), "missing@example.test");
    }

    @Test
    @Order(4)
    void mandatoryPasswordChangeIsEnforcedByBackend() throws Exception {
        LoginSession owner = login(OWNER_EMAIL, OWNER_PASSWORD);

        mvc.perform(get("/api/iam/users").cookie(owner.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        String originalHash = users.findByNormalizedEmail(OWNER_EMAIL).orElseThrow().passwordHash();
        String ownerId = users.findByNormalizedEmail(OWNER_EMAIL).orElseThrow().id().toString();
        long failedChangeBefore = auditCount("PASSWORD_CHANGED", "FAILURE", ownerId, ownerId);
        mvc.perform(post("/api/iam/password/change")
                        .cookie(owner.cookie()).header(owner.csrfHeader(), owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"ignored-new-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"));
        assertThat(users.findByNormalizedEmail(OWNER_EMAIL).orElseThrow().passwordHash()).isEqualTo(originalHash);
        AuditEvidence failedChange = assertExactlyOneAudit(failedChangeBefore, "PASSWORD_CHANGED", "FAILURE", ownerId, ownerId);
        assertAuditHasNoSensitiveData(failedChange.correlationId(), "wrong", "ignored-new-password", originalHash,
                owner.cookie().getValue(), owner.csrfToken());

        mvc.perform(post("/api/iam/password/change")
                        .cookie(owner.cookie()).header(owner.csrfHeader(), owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + OWNER_PASSWORD + "\",\"newPassword\":\"new-owner-password\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select must_change_password from iam.credential", Boolean.class)).isFalse();
        assertThat(failedLoginCode(OWNER_EMAIL, OWNER_PASSWORD)).isEqualTo("AUTHENTICATION_FAILED");
        LoginSession changed = login(OWNER_EMAIL, "new-owner-password");
        mvc.perform(get("/api/iam/users").cookie(changed.cookie())).andExpect(status().isOk());
    }

    @Test
    @Order(5)
    void userLifecycleTemporaryCredentialSingleSessionResetAndLogout(CapturedOutput output) throws Exception {
        LoginSession owner = login(OWNER_EMAIL, "new-owner-password");
        MvcResult creation = mvc.perform(post("/api/iam/users")
                        .cookie(owner.cookie()).header(owner.csrfHeader(), owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Manager\",\"email\":\"manager@example.test\",\"profileCode\":\"GERENTE_ADMINISTRATIVO\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.state").value("ACTIVE"))
                .andExpect(jsonPath("$.user.mustChangePassword").value(true))
                .andReturn();
        String body = creation.getResponse().getContentAsString();
        String userId = JsonPath.read(body, "$.user.id");
        String temporary = JsonPath.read(body, "$.temporaryPassword");
        ISSUED_SECRETS.add(temporary);
        assertThat(temporary).matches("[A-Za-z0-9_-]{32}");
        assertThat(jdbc.queryForObject("select password_hash from iam.credential where user_id=?::uuid", String.class, userId))
                .doesNotContain(temporary);
        assertThat(jdbc.queryForObject("select concat_ws(' ', before_state, after_state) from iam.audit_event where action='USER_CREATED' and target_id=?", String.class, userId))
                .doesNotContain(temporary);
        String createdHash = jdbc.queryForObject("select password_hash from iam.credential where user_id=?::uuid", String.class, userId);
        assertThat(output.getAll()).doesNotContain(temporary, createdHash);
        mvc.perform(get("/api/iam/users/" + userId).cookie(owner.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.temporaryPassword").doesNotExist());

        LoginSession manager = login("manager@example.test", temporary);
        mvc.perform(get("/api/iam/profiles").cookie(manager.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        LoginSession ownerAgain = login(OWNER_EMAIL, "new-owner-password");
        mvc.perform(patch("/api/iam/users/" + userId)
                        .cookie(ownerAgain.cookie()).header(ownerAgain.csrfHeader(), ownerAgain.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"INACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("INACTIVE"));
        mvc.perform(get("/api/iam/session").cookie(manager.cookie())).andExpect(status().isUnauthorized());
        assertThat(failedLoginCode("manager@example.test")).isEqualTo("AUTHENTICATION_FAILED");

        mvc.perform(patch("/api/iam/users/" + userId)
                        .cookie(ownerAgain.cookie()).header(ownerAgain.csrfHeader(), ownerAgain.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"ACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACTIVE"));
        LoginSession reactivated = login("manager@example.test", temporary);

        MvcResult reset = mvc.perform(post("/api/iam/users/" + userId + "/password-reset")
                        .cookie(ownerAgain.cookie()).header(ownerAgain.csrfHeader(), ownerAgain.csrfToken()))
                .andExpect(status().isOk()).andReturn();
        String resetTemporary = JsonPath.read(reset.getResponse().getContentAsString(), "$.temporaryPassword");
        ISSUED_SECRETS.add(resetTemporary);
        assertThat(resetTemporary).isNotEqualTo(temporary);
        String resetHash = jdbc.queryForObject("select password_hash from iam.credential where user_id=?::uuid", String.class, userId);
        assertThat(output.getAll()).doesNotContain(resetTemporary, resetHash);
        List<String> resetCorrelations = jdbc.queryForList(
                "select correlation_id from iam.audit_event where target_id=? and action in ('PASSWORD_RESET','SESSIONS_INVALIDATED') order by action",
                String.class, userId);
        assertThat(resetCorrelations).hasSize(2).containsOnly(resetCorrelations.get(0));
        assertThat(jdbc.queryForList(
                "select correlation_id from iam.audit_event where target_id=? and action='USER_STATE_CHANGED'",
                String.class, userId)).doesNotContain(resetCorrelations.get(0));
        mvc.perform(get("/api/iam/session").cookie(reactivated.cookie())).andExpect(status().isUnauthorized());

        LoginSession first = login(OWNER_EMAIL, "new-owner-password");
        LoginSession second = login(OWNER_EMAIL, "new-owner-password");
        mvc.perform(get("/api/iam/session").cookie(first.cookie())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/iam/session").cookie(second.cookie())).andExpect(status().isOk());
        mvc.perform(post("/api/iam/auth/logout")
                        .cookie(second.cookie()).header(second.csrfHeader(), second.csrfToken()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/iam/session").cookie(second.cookie())).andExpect(status().isUnauthorized());
    }

    @Test
    @Order(6)
    void csrfAndFixedProfileSurfaceAreEnforced() throws Exception {
        LoginSession owner = login(OWNER_EMAIL, "new-owner-password");
        String payload = "{\"name\":\"CSRF\",\"email\":\"csrf@example.test\",\"profileCode\":\"GERENTE_FINANCEIRO\"}";

        mvc.perform(post("/api/iam/users").cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/iam/users").cookie(owner.cookie()).header(owner.csrfHeader(), "invalid")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/iam/users")).andExpect(status().isUnauthorized());
        String ownerId = users.findByNormalizedEmail(OWNER_EMAIL).orElseThrow().id().toString();
        mvc.perform(patch("/api/iam/users/" + ownerId)
                        .cookie(owner.cookie()).header(owner.csrfHeader(), owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"BLOCKED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_USER_STATE"));
        mvc.perform(put("/api/iam/users/" + ownerId + "/permission-exceptions/IAM_USERS_READ")
                        .cookie(owner.cookie()).header(owner.csrfHeader(), owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resolution\":\"INVALID\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PERMISSION_RESOLUTION"));
        mvc.perform(post("/api/iam/profiles").cookie(owner.cookie())
                        .header(owner.csrfHeader(), owner.csrfToken()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(patch("/api/iam/profiles/DONO").cookie(owner.cookie())
                        .header(owner.csrfHeader(), owner.csrfToken()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/iam/profiles/DONO").cookie(owner.cookie())
                        .header(owner.csrfHeader(), owner.csrfToken()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @Order(7)
    void endpointAuthorizationUsesEffectivePermissionAndResetAlsoRequiresOwnerProfile() throws Exception {
        LoginSession owner = login(OWNER_EMAIL, "new-owner-password");
        MvcResult creation = mvc.perform(post("/api/iam/users")
                        .cookie(owner.cookie()).header(owner.csrfHeader(), owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Financial\",\"email\":\"financial@example.test\",\"profileCode\":\"GERENTE_FINANCEIRO\"}"))
                .andExpect(status().isCreated()).andReturn();
        String userId = JsonPath.read(creation.getResponse().getContentAsString(), "$.user.id");
        String temporary = JsonPath.read(creation.getResponse().getContentAsString(), "$.temporaryPassword");
        ISSUED_SECRETS.add(temporary);
        LoginSession financial = login("financial@example.test", temporary);
        mvc.perform(post("/api/iam/password/change")
                        .cookie(financial.cookie()).header(financial.csrfHeader(), financial.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + temporary + "\",\"newPassword\":\"financial-new-password\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/iam/users").cookie(financial.cookie()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        String ownerId = users.findByNormalizedEmail(OWNER_EMAIL).orElseThrow().id().toString();
        long deniedStateBefore = auditCount("USER_STATE_CHANGED", "DENIED", userId, ownerId);
        mvc.perform(patch("/api/iam/users/" + ownerId)
                        .cookie(financial.cookie()).header(financial.csrfHeader(), financial.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden());
        AuditEvidence deniedState = assertExactlyOneAudit(
                deniedStateBefore, "USER_STATE_CHANGED", "DENIED", userId, ownerId);
        assertAuditHasNoSensitiveData(deniedState.correlationId(), financial.cookie().getValue(), financial.csrfToken());

        for (String permission : List.of("IAM_USERS_READ", "IAM_PASSWORD_RESET")) {
            mvc.perform(put("/api/iam/users/" + userId + "/permission-exceptions/" + permission)
                            .cookie(owner.cookie()).header(owner.csrfHeader(), owner.csrfToken())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"resolution\":\"ALLOW\"}"))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(get("/api/iam/users").cookie(financial.cookie())).andExpect(status().isOk());

        String ownerHash = users.findByNormalizedEmail(OWNER_EMAIL).orElseThrow().passwordHash();
        String ownerActorId = users.findByNormalizedEmail(OWNER_EMAIL).orElseThrow().id().toString();
        long deniedSelfResetBefore = auditCount("PASSWORD_RESET", "DENIED", ownerActorId, ownerId);
        mvc.perform(post("/api/iam/users/" + ownerId + "/password-reset")
                        .cookie(owner.cookie()).header(owner.csrfHeader(), owner.csrfToken()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(users.findByNormalizedEmail(OWNER_EMAIL).orElseThrow().passwordHash()).isEqualTo(ownerHash);
        AuditEvidence deniedSelfReset = assertExactlyOneAudit(
                deniedSelfResetBefore, "PASSWORD_RESET", "DENIED", ownerActorId, ownerId);
        assertAuditHasNoSensitiveData(deniedSelfReset.correlationId(), ownerHash,
                owner.cookie().getValue(), owner.csrfToken());

        long deniedManagerResetBefore = auditCount("PASSWORD_RESET", "DENIED", userId, ownerId);
        mvc.perform(post("/api/iam/users/" + ownerId + "/password-reset")
                        .cookie(financial.cookie()).header(financial.csrfHeader(), financial.csrfToken()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(users.findByNormalizedEmail(OWNER_EMAIL).orElseThrow().passwordHash()).isEqualTo(ownerHash);
        AuditEvidence deniedManagerReset = assertExactlyOneAudit(
                deniedManagerResetBefore, "PASSWORD_RESET", "DENIED", userId, ownerId);
        assertAuditHasNoSensitiveData(deniedManagerReset.correlationId(), ownerHash,
                financial.cookie().getValue(), financial.csrfToken());
    }

    @Test
    @Order(8)
    void concurrentHttpLoginsLeaveExactlyOneEffectiveAuthenticatedSession() throws Exception {
        PreparedLogin first = prepareLogin(OWNER_EMAIL, "new-owner-password");
        PreparedLogin second = prepareLogin(OWNER_EMAIL, "new-owner-password");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (Connection blocker = dataSource.getConnection()) {
            long advisoryKey = scalarLong(blocker, "select hashtextextended(?, 8364003)", OWNER_EMAIL);
            long blockerPid = scalarLong(blocker, "select pg_backend_pid()");
            execute(blocker, "select pg_advisory_lock(?)", advisoryKey);

            Future<LoginSession> a = executor.submit(() -> executePreparedLogin(first, ready, start));
            Future<LoginSession> b = executor.submit(() -> executePreparedLogin(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            awaitDatabaseCondition(() -> jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where wait_event_type='Lock' and wait_event='advisory' " +
                            "and ? = any(pg_blocking_pids(pid))",
                    Long.class, blockerPid) == 2L);
            assertThat(a.isDone()).isFalse();
            assertThat(b.isDone()).isFalse();

            execute(blocker, "select pg_advisory_unlock(?)", advisoryKey);
            List<LoginSession> sessions = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertThat(sessions.get(0).cookie().getValue()).isNotEqualTo(sessions.get(1).cookie().getValue());

            long effective = 0;
            for (LoginSession session : sessions) {
                int status = mvc.perform(get("/api/iam/session").cookie(session.cookie()))
                        .andReturn().getResponse().getStatus();
                if (status == 200) effective++;
                else assertThat(status).isEqualTo(401);
            }
            assertThat(effective).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "select count(*) from iam.spring_session where principal_name=?",
                    Long.class, OWNER_EMAIL)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Order(8)
    void usersEndpointIsPaginatedAcrossApplicationAndPersistence() throws Exception {
        LoginSession owner = login(OWNER_EMAIL, "new-owner-password");
        createUserForPagination(owner, "Same Name", "same-a@example.test");
        createUserForPagination(owner, "Same Name", "same-b@example.test");

        List<String> expectedIds = jdbc.queryForList("select id::text from iam.app_user order by name, id", String.class);
        List<String> expectedNames = jdbc.queryForList("select name from iam.app_user order by name, id", String.class);

        MvcResult firstPage = mvc.perform(get("/api/iam/users?page=0&size=3").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andReturn();
        MvcResult secondPage = mvc.perform(get("/api/iam/users?page=1&size=3").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andReturn();

        List<String> firstIds = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.content[*].id");
        List<String> secondIds = JsonPath.read(secondPage.getResponse().getContentAsString(), "$.content[*].id");
        List<String> firstNames = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.content[*].name");
        List<String> secondNames = JsonPath.read(secondPage.getResponse().getContentAsString(), "$.content[*].name");
        assertThat(firstIds).containsExactlyElementsOf(expectedIds.subList(0, 3));
        assertThat(secondIds).containsExactlyElementsOf(expectedIds.subList(3, 5));
        assertThat(firstNames).containsExactlyElementsOf(expectedNames.subList(0, 3));
        assertThat(secondNames).containsExactlyElementsOf(expectedNames.subList(3, 5));
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        assertThat(java.util.stream.Stream.concat(firstIds.stream(), secondIds.stream()).toList())
                .containsExactlyElementsOf(expectedIds);

        assertInvalidPagination(owner, "page=-1&size=3");
        assertInvalidPagination(owner, "page=0&size=0");
        mvc.perform(get("/api/iam/users?page=0&size=101").cookie(owner.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
        mvc.perform(get("/api/iam/users?page=abc&size=3").cookie(owner.cookie()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/iam/users?page=0&size=abc").cookie(owner.cookie()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(8)
    void jdbcSessionUsesEightHourInactivityAndRenewsOnUse() throws Exception {
        LoginSession owner = login(OWNER_EMAIL, "new-owner-password");
        String sessionId = jdbc.queryForObject(
                "select session_id from iam.spring_session where principal_name=? order by creation_time desc limit 1",
                String.class, OWNER_EMAIL);
        assertThat(jdbc.queryForObject(
                "select max_inactive_interval from iam.spring_session where session_id=?", Integer.class, sessionId))
                .isEqualTo(8 * 60 * 60);

        long deliberatelyOld = System.currentTimeMillis() - 60_000;
        jdbc.update("update iam.spring_session set last_access_time=? where session_id=?", deliberatelyOld, sessionId);
        mvc.perform(get("/api/iam/session").cookie(owner.cookie())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "select last_access_time from iam.spring_session where session_id=?", Long.class, sessionId))
                .isGreaterThan(deliberatelyOld);

        jdbc.update("update iam.spring_session set last_access_time=0, expiry_time=0 where session_id=?", sessionId);
        assertThat(jdbc.queryForObject("select expiry_time from iam.spring_session where session_id=?", Long.class, sessionId)).isZero();
        mvc.perform(get("/api/iam/session").cookie(owner.cookie())).andExpect(status().isUnauthorized());
    }

    @Test
    @Order(9)
    void auditContainsRequiredEventsAndNoIssuedSecrets() throws Exception {
        LoginSession owner = login(OWNER_EMAIL, "new-owner-password");
        mvc.perform(put("/api/iam/profiles/GERENTE_ADMINISTRATIVO/permissions/IAM_USERS_READ")
                        .cookie(owner.cookie()).header(owner.csrfHeader(), owner.csrfToken()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/iam/profiles/GERENTE_ADMINISTRATIVO/permissions/IAM_USERS_READ")
                        .cookie(owner.cookie()).header(owner.csrfHeader(), owner.csrfToken()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/iam/auth/logout")
                        .cookie(owner.cookie()).header(owner.csrfHeader(), owner.csrfToken()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForList("select distinct action from iam.audit_event", String.class)).contains(
                "FIRST_OWNER_BOOTSTRAPPED", "LOGIN_SUCCEEDED", "LOGIN_FAILED", "LOGOUT",
                "PASSWORD_CHANGED", "PASSWORD_RESET", "USER_CREATED", "USER_STATE_CHANGED",
                "PROFILE_PERMISSION_ADDED", "PROFILE_PERMISSION_REMOVED", "USER_PERMISSION_EXCEPTION_CHANGED");
        String auditText = jdbc.queryForObject(
                "select string_agg(concat_ws(' ',action,target_type,target_id,result,correlation_id,before_state,after_state),' ') from iam.audit_event",
                String.class);
        assertThat(auditText).doesNotContain(OWNER_PASSWORD, "new-owner-password", "financial-new-password", "X-CSRF-TOKEN");
        ISSUED_SECRETS.forEach(secret -> assertThat(auditText).doesNotContain(secret));
        ISSUED_SESSION_COOKIES.forEach(cookie -> assertThat(auditText).doesNotContain(cookie));
        assertThat(jdbc.queryForObject(
                "select count(*) from iam.spring_session_attributes a cross join iam.credential c " +
                        "where position(convert_to(c.password_hash,'UTF8') in a.attribute_bytes) > 0",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from iam.app_user", Long.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from iam.credential", Long.class)).isEqualTo(5);
    }

    private void createUserForPagination(LoginSession owner, String name, String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/iam/users")
                        .cookie(owner.cookie()).header(owner.csrfHeader(), owner.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"email\":\"" + email +
                                "\",\"profileCode\":\"GERENTE_FINANCEIRO\"}"))
                .andExpect(status().isCreated()).andReturn();
        ISSUED_SECRETS.add(JsonPath.read(result.getResponse().getContentAsString(), "$.temporaryPassword"));
    }

    private void assertInvalidPagination(LoginSession owner, String query) throws Exception {
        mvc.perform(get("/api/iam/users?" + query).cookie(owner.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
    }

    private static long scalarLong(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object argument) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, argument);
            statement.execute();
        }
    }

    private static void awaitDatabaseCondition(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.call()) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertThat(condition.call()).as("condição observável no PostgreSQL antes do prazo").isTrue();
    }

    private String failedLoginCode(String email) throws Exception {
        return failedLoginCode(email, "invalid-password");
    }

    private String failedLoginCode(String email, String password) throws Exception {
        MvcResult csrfResult = mvc.perform(get("/api/iam/csrf")).andExpect(status().isOk()).andReturn();
        Cookie session = requiredCookie(csrfResult, "SESSION");
        String csrfToken = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");
        String csrfHeader = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.headerName");
        MvcResult result = mvc.perform(post("/api/iam/auth/login")
                        .cookie(session).header(csrfHeader, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
    }

    private LoginSession login(String email, String password) throws Exception {
        PreparedLogin prepared = prepareLogin(email, password);
        return executePreparedLogin(prepared, null, null);
    }

    private PreparedLogin prepareLogin(String email, String password) throws Exception {
        MvcResult csrfResult = mvc.perform(get("/api/iam/csrf")).andExpect(status().isOk()).andReturn();
        Cookie anonymous = requiredCookie(csrfResult, "SESSION");
        String token = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");
        String header = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.headerName");
        return new PreparedLogin(email, password, anonymous, header, token);
    }

    private LoginSession executePreparedLogin(PreparedLogin prepared, CountDownLatch ready, CountDownLatch start) throws Exception {
        if (ready != null) {
            ready.countDown();
            start.await();
        }
        MvcResult result = mvc.perform(post("/api/iam/auth/login")
                        .cookie(prepared.anonymous()).header(prepared.csrfHeader(), prepared.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + prepared.email() + "\",\"password\":\"" + prepared.password() + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie authenticated = requiredCookie(result, "SESSION");
        ISSUED_SESSION_COOKIES.add(authenticated.getValue());
        return new LoginSession(authenticated, prepared.csrfHeader(), prepared.csrfToken());
    }

    private long auditCount(String action, String result, String actorId, String targetId) {
        return jdbc.queryForObject(
                "select count(*) from iam.audit_event where action=? and result=? and actor_user_id=?::uuid and target_id=?",
                Long.class, action, result, actorId, targetId);
    }

    private long anonymousAuditCount(String action, String result) {
        return jdbc.queryForObject(
                "select count(*) from iam.audit_event where action=? and result=? and actor_user_id is null and target_id is null",
                Long.class, action, result);
    }

    private AuditEvidence assertExactlyOneAudit(long before, String action, String result,
                                                String actorId, String targetId) {
        assertThat(auditCount(action, result, actorId, targetId)).isEqualTo(before + 1);
        AuditEvidence event = jdbc.queryForObject(
                "select action,actor_user_id::text,target_id,result,correlation_id from iam.audit_event " +
                        "where action=? and result=? and actor_user_id=?::uuid and target_id=? " +
                        "order by occurred_at desc,id desc limit 1",
                (row, index) -> new AuditEvidence(row.getString(1), row.getString(2), row.getString(3),
                        row.getString(4), row.getString(5)),
                action, result, actorId, targetId);
        assertThat(event).isEqualTo(new AuditEvidence(action, actorId, targetId, result, event.correlationId()));
        assertThat(event.correlationId()).isNotBlank();
        assertThat(jdbc.queryForObject(
                "select count(*) from iam.audit_event where correlation_id=? and action=? and result=?",
                Long.class, event.correlationId(), action, result)).isEqualTo(1);
        return event;
    }

    private AuditEvidence assertExactlyOneAnonymousAudit(long before, String action, String result) {
        assertThat(anonymousAuditCount(action, result)).isEqualTo(before + 1);
        AuditEvidence event = jdbc.queryForObject(
                "select action,actor_user_id::text,target_id,result,correlation_id from iam.audit_event " +
                        "where action=? and result=? and actor_user_id is null and target_id is null " +
                        "order by occurred_at desc,id desc limit 1",
                (row, index) -> new AuditEvidence(row.getString(1), row.getString(2), row.getString(3),
                        row.getString(4), row.getString(5)), action, result);
        assertThat(event.action()).isEqualTo(action);
        assertThat(event.actorId()).isNull();
        assertThat(event.targetId()).isNull();
        assertThat(event.result()).isEqualTo(result);
        assertThat(event.correlationId()).isNotBlank();
        assertThat(jdbc.queryForObject(
                "select count(*) from iam.audit_event where correlation_id=? and action=? and result=?",
                Long.class, event.correlationId(), action, result)).isEqualTo(1);
        return event;
    }

    private void assertAuditHasNoSensitiveData(String correlationId, String... sensitiveValues) {
        String payload = jdbc.queryForObject(
                "select string_agg(concat_ws(' ',action,target_type,target_id,result,correlation_id,before_state,after_state),' ') " +
                        "from iam.audit_event where correlation_id=?",
                String.class, correlationId);
        for (String value : sensitiveValues) assertThat(payload).doesNotContain(value);
        ISSUED_SECRETS.forEach(value -> assertThat(payload).doesNotContain(value));
        ISSUED_SESSION_COOKIES.forEach(value -> assertThat(payload).doesNotContain(value));
    }

    private record PreparedLogin(String email, String password, Cookie anonymous, String csrfHeader, String csrfToken) {}
    private record LoginSession(Cookie cookie, String csrfHeader, String csrfToken) {}
    private record AuditEvidence(String action, String actorId, String targetId, String result, String correlationId) {}

    private static Cookie requiredCookie(MvcResult result, String name) {
        Cookie cookie = result.getResponse().getCookie(name);
        assertThat(cookie).as("cookie %s", name).isNotNull();
        return cookie;
    }
}
