package br.com.uberhidraulica.erp.quote;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TASK-0013: diagnóstico, desconto por item, registro interno da decisão e status automático da OS. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class Task0013DiagnosisDiscountDecisionIntegrationTest {
    private static final String OWNER_EMAIL = "owner-decision@example.test";
    private static final String FINANCE_EMAIL = "finance-decision@example.test";
    private static final String BOOTSTRAP_PASSWORD = "decision-bootstrap-password";
    private static final String PASSWORD = "decision-operational-password";

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Decision");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private Session owner;
    private int sequence;

    @BeforeEach
    void clean() throws Exception {
        for (String table : new String[]{"workshop.quote_decision", "workshop.quote_decision_submission", "workshop.public_quote_access",
                "workshop.quote_revision_item", "workshop.quote_item_revision", "workshop.quote_item", "workshop.quote_revision", "workshop.quote",
                "workorder.work_order_product", "workorder.work_order_service", "workorder.work_order_status_history", "workorder.work_order",
                "crm.vehicle_ownership", "crm.vehicle", "crm.customer"})
            jdbc.update("delete from " + table);
        jdbc.update("update workorder.status_automation set enabled = true");
        owner = openOwnerSession();
    }

    @Test
    void diagnosisMovesOpenOrderToDiagnosisOnlyOnceAndRespectsTheRule() throws Exception {
        String order = openWorkOrder();
        send(owner, put("/api/work-orders/" + order + "/diagnosis"), "{\"diagnosis\":\"Folga na caixa de direção\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnosis").value("Folga na caixa de direção"))
                .andExpect(jsonPath("$.status").value("EM_DIAGNOSTICO"))
                .andExpect(jsonPath("$.lifecycle.diagnosedAt").exists());
        String firstDiagnosedAt = JsonPath.read(get(owner, "/api/work-orders/" + order), "$.lifecycle.diagnosedAt");
        send(owner, put("/api/work-orders/" + order + "/diagnosis"), "{\"diagnosis\":\"Folga e vazamento\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle.diagnosedAt").value(firstDiagnosedAt));
        String history = get(owner, "/api/work-orders/" + order + "/status-history");
        assertThat((Integer) JsonPath.read(history, "$.length()")).isEqualTo(2);
        assertThat((String) JsonPath.read(history, "$[1].reason")).isEqualTo("Diagnóstico registrado");

        send(owner, put("/api/work-order-statuses/automations/DIAGNOSIS_REGISTERED"), "{\"enabled\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.DIAGNOSIS_REGISTERED").value(false));
        String other = openWorkOrder();
        send(owner, put("/api/work-orders/" + other + "/diagnosis"), "{\"diagnosis\":\"Sem defeito\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABERTA"));
        send(owner, put("/api/work-order-statuses/automations/INEXISTENTE"), "{\"enabled\":true}").andExpect(status().isNotFound());
    }

    @Test
    void discountIsVersionedCommercialTermLimitedToGrossAndRequiresPermission() throws Exception {
        String order = openWorkOrder();
        String quote = openQuote(order);
        String created = revision(owner, order, quote, item(null, "Reparo", "2", "50.00", "10.00")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].revisions[0].grossTotal").value(100.00))
                .andExpect(jsonPath("$.items[0].revisions[0].discountAmount").value(10.00))
                .andExpect(jsonPath("$.items[0].revisions[0].totalPrice").value(90.00))
                .andExpect(jsonPath("$.revisions[0].total").value(90.00))
                .andReturn().getResponse().getContentAsString();
        String itemId = JsonPath.read(created, "$.items[0].id");

        revision(owner, order, quote, item(itemId, "Reparo", "2", "50.00", "10.00")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].revisions.length()").value(1));
        revision(owner, order, quote, item(itemId, "Reparo", "2", "50.00", "15.00")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].revisions.length()").value(2))
                .andExpect(jsonPath("$.items[0].revisions[1].totalPrice").value(85.00));
        revision(owner, order, quote, item(null, "Peça", "1", "30.00", "30.01")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUOTE_DISCOUNT_EXCEEDS_ITEM_TOTAL"));

        Session finance = createUserSession(FINANCE_EMAIL, "GERENTE_FINANCEIRO");
        revision(finance, order, quote, item(null, "Peça", "1", "30.00", "5.00")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUOTE_DISCOUNT_NOT_ALLOWED"));
        revision(finance, order, quote, item(null, "Peça", "1", "30.00", "0")).andExpect(status().isCreated());
        assertThatThrownBy(() -> jdbc.update("update workshop.quote_item_revision set discount_amount = 1.005"))
                .hasMessageContaining("ck_quote_item_revision_discount");
    }

    @Test
    void presentationAndInternalApprovalMoveTheOrderAutomatically() throws Exception {
        String order = openWorkOrder();
        String quote = openQuote(order);
        String draft = revision(owner, order, quote, item(null, "Reparo", "1", "300.00", null) + "," + item(null, "Alinhamento", "1", "80.00", null).substring(0))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String revisionId = JsonPath.read(draft, "$.revisions[0].id");
        String repair = JsonPath.read(draft, "$.items[0].revisions[0].id");
        String alignment = JsonPath.read(draft, "$.items[1].revisions[0].id");

        decide(owner, order, quote, revisionId, "PRESENCIAL", repair, "APPROVE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_REVISION_NOT_PRESENTED"));

        send(owner, post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions/" + revisionId + "/present"), "").andExpect(status().isOk());
        assertThat((String) JsonPath.read(get(owner, "/api/work-orders/" + order), "$.status")).isEqualTo("AGUARDANDO_APROVACAO");

        decide(owner, order, quote, revisionId, "FAX", repair, "APPROVE").andExpect(status().isBadRequest());
        Session finance = createUserSession(FINANCE_EMAIL, "GERENTE_FINANCEIRO");
        decide(finance, order, quote, revisionId, "TELEFONE", repair, "APPROVE").andExpect(status().isForbidden());

        decide(owner, order, quote, revisionId, "TELEFONE", repair, "APPROVE").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].revisions[0].decision").value("APPROVE"))
                .andExpect(jsonPath("$.items[1].revisions[0].decision").doesNotExist());
        assertThat((String) JsonPath.read(get(owner, "/api/work-orders/" + order), "$.status")).isEqualTo("APROVADA");
        decide(owner, order, quote, revisionId, "TELEFONE", repair, "REJECT").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_ITEM_ALREADY_DECIDED"));
        decide(owner, order, quote, revisionId, "WHATSAPP", alignment, "REJECT").andExpect(status().isOk());
        assertThat((String) JsonPath.read(get(owner, "/api/work-orders/" + order), "$.status")).isEqualTo("APROVADA");

        assertThat(jdbc.queryForObject("select count(*) from workshop.quote_decision_submission where channel = 'INTERNAL' and recorded_by is not null and contact_channel is not null", Long.class))
                .isEqualTo(2);
        String history = get(owner, "/api/work-orders/" + order + "/status-history");
        assertThat((String) JsonPath.read(history, "$[2].reason")).isEqualTo("Orçamento aprovado pelo cliente");
    }

    @Test
    void rejectingEveryItemMovesToRejectedAndExecutionIsNeverDisturbed() throws Exception {
        String order = openWorkOrder();
        String quote = openQuote(order);
        String draft = revision(owner, order, quote, item(null, "Reparo", "1", "300.00", null)).andReturn().getResponse().getContentAsString();
        String revisionId = JsonPath.read(draft, "$.revisions[0].id");
        String repair = JsonPath.read(draft, "$.items[0].revisions[0].id");
        send(owner, post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions/" + revisionId + "/present"), "").andExpect(status().isOk());
        decide(owner, order, quote, revisionId, "PRESENCIAL", repair, "REJECT").andExpect(status().isOk());
        assertThat((String) JsonPath.read(get(owner, "/api/work-orders/" + order), "$.status")).isEqualTo("REPROVADA");

        String second = openWorkOrder();
        String secondQuote = openQuote(second);
        String secondDraft = revision(owner, second, secondQuote, item(null, "Reparo", "1", "100.00", null)).andReturn().getResponse().getContentAsString();
        send(owner, post("/api/work-orders/" + second + "/start-execution"), "").andExpect(status().isOk());
        send(owner, post("/api/work-orders/" + second + "/quotes/" + secondQuote + "/revisions/" + JsonPath.read(secondDraft, "$.revisions[0].id") + "/present"), "")
                .andExpect(status().isOk());
        assertThat((String) JsonPath.read(get(owner, "/api/work-orders/" + second), "$.status")).isEqualTo("EM_EXECUCAO");
    }

    @Test
    void postgresRequiresEvidenceForEachChannel() throws Exception {
        String order = openWorkOrder();
        String quote = openQuote(order);
        String draft = revision(owner, order, quote, item(null, "Reparo", "1", "10.00", null)).andReturn().getResponse().getContentAsString();
        UUID revisionId = UUID.fromString(JsonPath.read(draft, "$.revisions[0].id"));
        assertThatThrownBy(() -> jdbc.update("insert into workshop.quote_decision_submission(id, channel, quote_revision_id, quote_id, request_id, explicit_acceptance, occurred_at, created_at, contact_channel) "
                + "values (?, 'INTERNAL', ?, ?, 'x', true, now(), now(), 'TELEFONE')", UUID.randomUUID(), revisionId, UUID.fromString(quote)))
                .hasMessageContaining("ck_quote_submission_internal_evidence");
        assertThatThrownBy(() -> jdbc.update("insert into workshop.quote_decision_submission(id, channel, quote_revision_id, quote_id, request_id, explicit_acceptance, occurred_at, created_at) "
                + "values (?, 'PUBLIC_LINK', ?, ?, 'y', true, now(), now())", UUID.randomUUID(), revisionId, UUID.fromString(quote)))
                .hasMessageContaining("ck_quote_submission_public_evidence");
    }

    // ------------------------------------------------------------------ apoio

    private static String item(String quoteItemId, String description, String quantity, String unitPrice, String discount) {
        return "{" + (quoteItemId == null ? "" : "\"quoteItemId\":\"" + quoteItemId + "\",") + "\"description\":\"" + description + "\",\"quantity\":\""
                + quantity + "\",\"unitPrice\":\"" + unitPrice + "\"" + (discount == null ? "" : ",\"discount\":\"" + discount + "\"") + "}";
    }

    private ResultActions revision(Session session, String order, String quote, String items) throws Exception {
        return send(session, post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions"), "{\"items\":[" + items + "]}");
    }

    private ResultActions decide(Session session, String order, String quote, String revision, String channel, String itemRevision, String decision) throws Exception {
        return send(session, post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions/" + revision + "/decisions"),
                "{\"contactChannel\":\"" + channel + "\",\"authorizedBy\":\"Maria\",\"decisions\":[{\"itemReference\":\"" + itemRevision + "\",\"decision\":\"" + decision + "\"}]}");
    }

    private String openQuote(String order) throws Exception {
        return JsonPath.read(send(owner, post("/api/work-orders/" + order + "/quotes"), "").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String openWorkOrder() throws Exception {
        sequence++;
        String customer = JsonPath.read(send(owner, post("/api/customers"), "{\"personType\":\"PF\",\"name\":\"Cliente Decisão\",\"phone\":\"34999990000\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String vehicle = JsonPath.read(send(owner, post("/api/vehicles"), "{\"customerId\":\"" + customer + "\",\"plate\":\"DEC" + (1000 + sequence)
                + "\",\"manufacturer\":\"Ford\",\"model\":\"Cargo\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        return JsonPath.read(send(owner, post("/api/work-orders"), "{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle
                + "\",\"complaint\":\"Direção pesada\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String get(Session session, String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).cookie(session.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private ResultActions send(Session session, MockHttpServletRequestBuilder builder, String body) throws Exception {
        builder.cookie(session.cookie()).header(session.csrfHeader(), session.csrfToken());
        if (!body.isEmpty()) builder.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(builder);
    }

    private Session createUserSession(String email, String profileCode) throws Exception {
        Session existing = login(email, PASSWORD, true);
        if (existing != null) return existing;
        String created = send(owner, post("/api/iam/users"), "{\"name\":\"Usuario Financeiro\",\"email\":\"" + email + "\",\"profileCode\":\"" + profileCode + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String temporary = JsonPath.read(created, "$.temporaryPassword");
        Session first = login(email, temporary, false);
        send(first, post("/api/iam/password/change"), "{\"currentPassword\":\"" + temporary + "\",\"newPassword\":\"" + PASSWORD + "\"}")
                .andExpect(status().isNoContent());
        return login(email, PASSWORD, false);
    }

    private Session openOwnerSession() throws Exception {
        Session bootstrap = login(OWNER_EMAIL, BOOTSTRAP_PASSWORD, true);
        if (bootstrap != null)
            send(bootstrap, post("/api/iam/password/change"), "{\"currentPassword\":\"" + BOOTSTRAP_PASSWORD + "\",\"newPassword\":\"" + PASSWORD + "\"}")
                    .andExpect(status().isNoContent());
        return login(OWNER_EMAIL, PASSWORD, false);
    }

    private Session login(String email, String password, boolean optional) throws Exception {
        MvcResult csrf = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/iam/csrf")).andExpect(status().isOk()).andReturn();
        Cookie anonymous = csrf.getResponse().getCookie("SESSION");
        String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        String header = JsonPath.read(csrf.getResponse().getContentAsString(), "$.headerName");
        MvcResult result = mvc.perform(post("/api/iam/auth/login").cookie(anonymous).header(header, token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
        if (result.getResponse().getStatus() != 200) {
            if (optional) return null;
            throw new IllegalStateException("Login falhou: " + result.getResponse().getStatus());
        }
        return new Session(result.getResponse().getCookie("SESSION"), header, token);
    }

    private record Session(Cookie cookie, String csrfHeader, String csrfToken) {}
}
