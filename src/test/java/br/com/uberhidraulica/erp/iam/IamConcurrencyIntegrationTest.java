package br.com.uberhidraulica.erp.iam;

import br.com.uberhidraulica.erp.iam.application.BootstrapService;
import br.com.uberhidraulica.erp.iam.application.BootstrapConfigurationValidator;
import br.com.uberhidraulica.erp.iam.application.UserAdminService;
import br.com.uberhidraulica.erp.iam.config.IamBootstrapRunner;
import br.com.uberhidraulica.erp.iam.domain.IamException;
import br.com.uberhidraulica.erp.iam.domain.ProfileCode;
import br.com.uberhidraulica.erp.iam.domain.UserState;
import br.com.uberhidraulica.erp.iam.port.UserRepositoryPort;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamConcurrencyIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired BootstrapService bootstrap;
    @Autowired UserAdminService administration;
    @Autowired UserRepositoryPort users;
    @Autowired JdbcTemplate jdbc;
    @Autowired BootstrapConfigurationValidator bootstrapValidator;

    @Test
    @Order(1)
    void absentBootstrapConfigurationLeavesDatabaseEmpty() {
        assertThat(users.count()).isZero();
    }

    @Test
    @Order(1)
    void semanticallyInvalidBootstrapLeavesNoPartialPersistence() {
        List<String[]> invalid = List.of(
                new String[]{"Owner", "invalid-email", "secret"},
                new String[]{" ", "owner@example.test", "secret"},
                new String[]{"x".repeat(161), "owner@example.test", "secret"},
                new String[]{"Owner", "a".repeat(310) + "@example.test", "secret"},
                new String[]{"Owner", "owner@example.test", "x".repeat(1025)});

        for (String[] values : invalid) {
            MockEnvironment environment = new MockEnvironment()
                    .withProperty(IamBootstrapRunner.OWNER_NAME, values[0])
                    .withProperty(IamBootstrapRunner.OWNER_EMAIL, values[1])
                    .withProperty(IamBootstrapRunner.OWNER_PASSWORD, values[2]);
            IamBootstrapRunner runner = new IamBootstrapRunner(environment, bootstrap, bootstrapValidator);
            assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                    .isInstanceOfSatisfying(IamException.class,
                            error -> assertThat(error.code()).isEqualTo("BOOTSTRAP_CONFIGURATION_INVALID"));
        }

        assertThat(users.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from iam.credential", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from iam.audit_event", Long.class)).isZero();
    }

    @Test
    @Order(2)
    void concurrentBootstrapCreatesAtMostOneOwner() throws Exception {
        List<Boolean> results = concurrently(
                () -> bootstrap.bootstrap("Owner A", "a@example.test", "secret-a"),
                () -> bootstrap.bootstrap("Owner B", "b@example.test", "secret-b"));

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(users.count()).isEqualTo(1);
        assertThat(users.countActiveOwners()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from iam.credential", Long.class)).isEqualTo(1);
    }

    @Test
    @Order(3)
    void repeatedBootstrapDoesNotAlterExistingUser() {
        UUID original = users.findAll().get(0).id();
        assertThat(bootstrap.bootstrap("Other", "other@example.test", "other-secret")).isFalse();
        assertThat(users.findAll()).singleElement().extracting(user -> user.id()).isEqualTo(original);
    }

    @Test
    @Order(4)
    void concurrentOwnerDeactivationKeepsOneActiveOwner() throws Exception {
        UUID first = users.findAll().get(0).id();
        UUID second = administration.create(first, "Second Owner", "second-owner@example.test", ProfileCode.DONO).user().id();

        List<Object> outcomes = concurrentlyOutcomes(
                () -> administration.changeState(first, first, UserState.INACTIVE),
                () -> administration.changeState(first, second, UserState.INACTIVE));

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.stream().filter(value -> value instanceof IamException).count()).isEqualTo(1);
        assertThat(users.countActiveOwners()).isEqualTo(1);
    }

    @Test
    @Order(5)
    void lastActiveOwnerCannotBeDeactivatedAndStateIsPreserved() {
        var active = users.findAll().stream()
                .filter(user -> user.profileCode() == ProfileCode.DONO && user.state() == UserState.ACTIVE)
                .findFirst().orElseThrow();

        assertThatThrownBy(() -> administration.changeState(active.id(), active.id(), UserState.INACTIVE))
                .isInstanceOfSatisfying(IamException.class,
                        error -> assertThat(error.code()).isEqualTo("LAST_ACTIVE_OWNER_REQUIRED"));
        assertThat(users.findById(active.id()).orElseThrow().state()).isEqualTo(UserState.ACTIVE);
        assertThat(users.countActiveOwners()).isEqualTo(1);
    }

    @Test
    @Order(6)
    void postgresConstraintsRejectInvalidCatalogUsersAssociationsAndCredentials() {
        UUID profileId = jdbc.queryForObject("select id from iam.profile where code='DONO'", UUID.class);
        UUID permissionId = jdbc.queryForObject("select id from iam.permission where code='IAM_USERS_READ'", UUID.class);
        UUID userId = users.findAll().get(0).id();

        rejected("insert into iam.profile(id,code) values (?,?)", UUID.randomUUID(), "CUSTOM");
        rejected("insert into iam.permission(id,code,description) values (?,?,?)", UUID.randomUUID(), "IAM_USERS_READ", "duplicate");
        rejected("insert into iam.app_user(id,name,email,normalized_email,profile_id,state) values (?,?,?,?,?,?)",
                UUID.randomUUID(), "Duplicate", "duplicate@example.test", users.findAll().get(0).normalizedEmail(), profileId, "ACTIVE");
        rejected("insert into iam.app_user(id,name,email,normalized_email,profile_id,state) values (?,?,?,?,?,?)",
                UUID.randomUUID(), "Invalid", "invalid@example.test", "invalid@example.test", profileId, "BLOCKED");
        rejected("insert into iam.app_user(id,name,email,normalized_email,profile_id,state) values (?,?,?,?,?,?)",
                UUID.randomUUID(), "FK", "fk@example.test", "fk@example.test", UUID.randomUUID(), "ACTIVE");
        rejected("insert into iam.profile_permission(profile_id,permission_id) values (?,?)", profileId, permissionId);
        rejected("insert into iam.credential(user_id,password_hash,must_change_password) values (?,?,?)", userId, "{noop}duplicate", true);

        UUID exceptionId = UUID.randomUUID();
        jdbc.update("insert into iam.user_permission_exception(id,user_id,permission_id,resolution) values (?,?,?,?)",
                exceptionId, userId, permissionId, "INHERIT");
        rejected("insert into iam.user_permission_exception(id,user_id,permission_id,resolution) values (?,?,?,?)",
                UUID.randomUUID(), userId, permissionId, "ALLOW");
        UUID otherPermission = jdbc.queryForObject("select id from iam.permission where code='IAM_USERS_MANAGE'", UUID.class);
        rejected("insert into iam.user_permission_exception(id,user_id,permission_id,resolution) values (?,?,?,?)",
                UUID.randomUUID(), userId, otherPermission, "INVALID");
    }

    private void rejected(String sql, Object... arguments) {
        assertThatThrownBy(() -> jdbc.update(sql, arguments)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<T> a = executor.submit(awaitStart(first, ready, start));
            Future<T> b = executor.submit(awaitStart(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<Object> concurrentlyOutcomes(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> a = executor.submit(outcome(awaitStart(first, ready, start)));
            Future<Object> b = executor.submit(outcome(awaitStart(second, ready, start)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> Callable<T> awaitStart(Callable<T> action, CountDownLatch ready, CountDownLatch start) {
        return () -> { ready.countDown(); start.await(); return action.call(); };
    }

    private static Callable<Object> outcome(Callable<?> action) {
        return () -> { try { return action.call(); } catch (Exception exception) { return exception; } };
    }
}
