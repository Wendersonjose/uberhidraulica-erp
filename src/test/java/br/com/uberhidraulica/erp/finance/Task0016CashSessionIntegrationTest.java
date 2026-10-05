package br.com.uberhidraulica.erp.finance;

import br.com.uberhidraulica.erp.finance.application.CashSessionService;
import br.com.uberhidraulica.erp.support.ApiSessions;
import br.com.uberhidraulica.erp.support.ApiSessions.Session;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TASK-0016 / DR-0018 contra PostgreSQL real: invariantes, saldo, auditoria e permissões do caixa. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class Task0016CashSessionIntegrationTest {
    private static final String OWNER_EMAIL = "owner-cash@example.test";
    private static final String BOOTSTRAP_PASSWORD = "cash-bootstrap-password";
    private static final String PASSWORD = "cash-operational-password";

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Cash");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CashSessionService cash;

    private ApiSessions api;
    private Session owner;

    @BeforeEach
    void clean() throws Exception {
        jdbc.update("delete from finance.cash_movement");
        jdbc.update("delete from finance.cash_session");
        api = new ApiSessions(mvc, PASSWORD);
        owner = api.owner(OWNER_EMAIL, BOOTSTRAP_PASSWORD);
    }

    @Test
    void sessionLifecycleMaintainsExpectedBalanceAndAuditTrail() throws Exception {
        String first = open(owner, "100.00", "Fundo físico inicial");

        api.send(owner, post("/api/finance/cash/sessions"),
                        "{\"countedBalance\":\"100.00\",\"differenceReason\":\"segunda abertura\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASH_SESSION_ALREADY_OPEN"));

        MvcResult supply = idempotent(owner, post("/api/finance/cash/movements/supply"), "supply-1",
                "{\"amount\":\"50.00\",\"reason\":\"Reforço de troco\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.type").value("SUPPLY"))
                .andReturn();
        String supplyId = JsonPath.read(supply.getResponse().getContentAsString(), "$.id");

        idempotent(owner, post("/api/finance/cash/movements/supply"), "supply-1",
                "{\"amount\":\"50.00\",\"reason\":\"Reforço de troco\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));

        idempotent(owner, post("/api/finance/cash/movements/withdrawal"), "withdraw-1",
                "{\"amount\":\"20.00\",\"reason\":\"Sangria operacional\"}")
                .andExpect(status().isCreated());

        api.read(owner, "/api/finance/cash/sessions/" + first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentExpectedBalance").value(130.0));

        idempotent(owner, post("/api/finance/cash/movements/withdrawal"), "withdraw-too-much",
                "{\"amount\":\"200.00\",\"reason\":\"Retirada inválida\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASH_AMOUNT_EXCEEDS_BALANCE"));

        idempotent(owner, post("/api/finance/cash/movements/" + supplyId + "/reversal"), "reverse-supply-1",
                "{\"reason\":\"Suprimento lançado por engano\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.direction").value("OUT"));

        api.send(owner, post("/api/finance/cash/sessions/" + first + "/close"),
                        "{\"countedBalance\":\"75.00\",\"differenceReason\":\"Diferença apurada no fechamento\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.conferenceStatus").value("CHECKED"))
                .andExpect(jsonPath("$.closingExpectedBalance").value(80.0))
                .andExpect(jsonPath("$.closingCountedBalance").value(75.0));

        api.read(owner, "/api/finance/cash/suggested-opening-balance")
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(80.0));

        assertThat(jdbc.queryForObject("select count(*) from finance.cash_movement where cash_session_id=?::uuid", Long.class, first))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from finance.cash_movement where reversed_movement_id=?::uuid", Long.class, supplyId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(distinct recorded_by) from finance.cash_movement where cash_session_id=?::uuid", Long.class, first))
                .isEqualTo(1);
    }

    @Test
    void autoClosedSessionCanBeCheckedAfterNextSessionIsOpened() throws Exception {
        String first = open(owner, "40.00", "Fundo inicial");
        cash.autoCloseOpenSession();

        api.read(owner, "/api/finance/cash/sessions/" + first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTO_CLOSED"))
                .andExpect(jsonPath("$.conferenceStatus").value("NOT_CHECKED"))
                .andExpect(jsonPath("$.closingExpectedBalance").value(40.0));

        String second = open(owner, "40.00", null);
        assertThat(second).isNotEqualTo(first);

        api.send(owner, post("/api/finance/cash/sessions/" + first + "/check"),
                        "{\"countedBalance\":\"38.00\",\"differenceReason\":\"Diferença identificada na conferência tardia\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTO_CLOSED"))
                .andExpect(jsonPath("$.conferenceStatus").value("CHECKED"))
                .andExpect(jsonPath("$.checkedCountedBalance").value(38.0));

        api.read(owner, "/api/finance/cash/sessions/open")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(second));
    }

    @Test
    void administrativeManagerCannotOperateCashWithoutCashPermissions() throws Exception {
        Session administrative = api.user(owner, "admin-cash@example.test", "GERENTE_ADMINISTRATIVO");

        api.send(administrative, post("/api/finance/cash/sessions"),
                        "{\"countedBalance\":\"0.00\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void cashMutationRequiresOpenSession() throws Exception {
        idempotent(owner, post("/api/finance/cash/movements/supply"), "without-session",
                "{\"amount\":\"10.00\",\"reason\":\"Teste sem sessão\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASH_SESSION_REQUIRED"));
    }

    /**
     * Fechamento concorrente (cenário negativo do fechamento): vários fechamentos ao mesmo tempo fecham a sessão
     * uma única vez, e um suprimento que disputa com o fechamento nunca fica de fora do saldo esperado nem
     * entra numa sessão já fechada. O ponto de serialização é a linha da sessão aberta (for update).
     */
    @Test
    void concurrentClosesCloseOnceAndRacingMovementsNeverEscapeTheClosingBalance() throws Exception {
        String sessionId = open(owner, "100.00", "Fundo inicial");
        int closers = 4;
        int supplies = 6;
        ExecutorService pool = Executors.newFixedThreadPool(closers + supplies);
        try {
            CountDownLatch ready = new CountDownLatch(closers + supplies);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> closes = new ArrayList<>();
            List<Future<Integer>> moves = new ArrayList<>();
            for (int i = 0; i < closers; i++) {
                // Contagem informada = saldo físico esperado quando só houver o fundo inicial; qualquer suprimento
                // que entre antes do fechamento muda o esperado, e a divergência sem justificativa é recusada (400).
                closes.add(pool.submit(raced(ready, go, () -> api.send(owner, post("/api/finance/cash/sessions/" + sessionId + "/close"),
                        "{\"countedBalance\":\"100.00\",\"differenceReason\":\"Conferência do caixa\"}")
                        .andReturn().getResponse().getStatus())));
            }
            for (int i = 0; i < supplies; i++) {
                String key = "race-supply-" + i;
                moves.add(pool.submit(raced(ready, go, () -> idempotent(owner, post("/api/finance/cash/movements/supply"), key,
                        "{\"amount\":\"10.00\",\"reason\":\"Suprimento concorrente\"}").andReturn().getResponse().getStatus())));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> closeStatuses = new ArrayList<>();
            for (Future<Integer> close : closes) closeStatuses.add(close.get(60, TimeUnit.SECONDS));
            List<Integer> moveStatuses = new ArrayList<>();
            for (Future<Integer> move : moves) moveStatuses.add(move.get(60, TimeUnit.SECONDS));

            assertThat(closeStatuses).as("exatamente um fechamento vence").filteredOn(code -> code == 200).hasSize(1);
            assertThat(closeStatuses).as("os demais recebem conflito, nunca erro de servidor")
                    .filteredOn(code -> code != 200).containsOnly(409);
            assertThat(moveStatuses).as("suprimento entra (201) ou encontra a sessão já fechada (409)")
                    .allMatch(code -> code == 201 || code == 409);

            long recorded = jdbc.queryForObject("select count(*) from finance.cash_movement where cash_session_id=?::uuid and movement_type='SUPPLY'",
                    Long.class, sessionId);
            assertThat(recorded).isEqualTo(moveStatuses.stream().filter(code -> code == 201).count());
            assertThat(jdbc.queryForObject("select count(*) from finance.cash_session where status='OPEN'", Long.class)).isZero();
            BigDecimal expectedAtClose = jdbc.queryForObject(
                    "select closing_expected_balance from finance.cash_session where id=?::uuid", BigDecimal.class, sessionId);
            assertThat(expectedAtClose).as("saldo esperado no fechamento = fundo + suprimentos gravados antes dele")
                    .isEqualByComparingTo(new BigDecimal("100.00").add(new BigDecimal("10.00").multiply(BigDecimal.valueOf(recorded))));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<Integer> raced(CountDownLatch ready, CountDownLatch go, Callable<Integer> action) {
        return () -> {
            ready.countDown();
            go.await();
            return action.call();
        };
    }

    private String open(Session session, String counted, String reason) throws Exception {
        String reasonJson = reason == null ? "null" : "\"" + reason + "\"";
        MvcResult result = api.send(session, post("/api/finance/cash/sessions"),
                        "{\"countedBalance\":\"" + counted + "\",\"differenceReason\":" + reasonJson + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.conferenceStatus").value("NOT_CHECKED"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private org.springframework.test.web.servlet.ResultActions idempotent(Session session,
            MockHttpServletRequestBuilder request, String key, String body) throws Exception {
        return api.send(session, request.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON), body);
    }
}
