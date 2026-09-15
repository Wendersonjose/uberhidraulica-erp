package br.com.uberhidraulica.erp.quote;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import br.com.uberhidraulica.erp.quote.application.PublicQuoteService;
import br.com.uberhidraulica.erp.quote.domain.DecisionType;
import br.com.uberhidraulica.erp.quote.domain.DocumentType;
import br.com.uberhidraulica.erp.quote.domain.Quote;
import br.com.uberhidraulica.erp.quote.port.QuoteRepositoryPort;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vertical pública do orçamento em PostgreSQL real: link seguro, decisão por item, evidências,
 * idempotência, atomicidade e a corrida DECIDE × PRESENT.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class Task0008PublicQuoteDecisionIntegrationTest {
    private static final String OWNER_EMAIL = "owner-public@example.test";
    private static final String BOOTSTRAP_PASSWORD = "public-bootstrap-password";
    private static final String PASSWORD = "public-operational-password";

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Public");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired QuoteRepositoryPort repository;
    @Autowired PublicQuoteService publicQuotes;
    @Autowired TransactionTemplate transactions;

    private Session session;

    @BeforeEach
    void clean() throws Exception {
        jdbc.update("delete from workshop.quote_decision");
        jdbc.update("delete from workshop.quote_decision_submission");
        jdbc.update("delete from workshop.public_quote_access");
        jdbc.update("delete from workshop.quote_revision_item");
        jdbc.update("delete from workshop.quote_item_revision");
        jdbc.update("delete from workshop.quote_item");
        jdbc.update("delete from workshop.quote_revision");
        jdbc.update("delete from workshop.quote");
        jdbc.update("delete from workorder.work_order_product");
        jdbc.update("delete from workorder.work_order_service");
        jdbc.update("delete from workorder.work_order");
        jdbc.update("delete from crm.vehicle");
        jdbc.update("delete from crm.customer");
        session = openSession();
    }

    @Test
    void issuesTheLinkOnceAndKeepsOnlyTheDigest() throws Exception {
        Scenario scenario = presentedQuote("11100022233", "PBA1A11");

        assertThat(scenario.token()).isNotBlank().hasSizeGreaterThanOrEqualTo(43);
        // O banco guarda digest de 32 bytes e em nenhum lugar o token bruto.
        assertThat(jdbc.queryForObject("select octet_length(token_digest) from workshop.public_quote_access",
                Integer.class)).isEqualTo(32);
        assertThat(jdbc.queryForObject("select count(*) from workshop.public_quote_access"
                        + " where encode(token_digest, 'escape') like ?", Long.class, "%" + scenario.token() + "%"))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from iam.audit_event where after_state like ?",
                Long.class, "%" + scenario.token() + "%")).isZero();
    }

    @Test
    void issuingAndRevokingRequireTheQuotePresentPermission() throws Exception {
        Scenario scenario = presentedQuote("11100022244", "PBB1A11");
        Session financial = createUserSession("financeiro-public@example.test", "GERENTE_FINANCEIRO");

        mvc.perform(post("/api/work-orders/{w}/quotes/{q}/revisions/{r}/public-access",
                        scenario.workOrder(), scenario.quote(), scenario.revision())
                        .cookie(financial.cookie()).header(financial.csrfHeader(), financial.csrfToken()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/work-orders/{w}/quotes/{q}/public-access/{a}/revoke",
                        scenario.workOrder(), scenario.quote(), UUID.randomUUID())
                        .cookie(financial.cookie()).header(financial.csrfHeader(), financial.csrfToken()))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from workshop.public_quote_access", Long.class)).isEqualTo(1);
    }

    @Test
    void publicViewExposesOnlyWhatTheDecisionNeeds() throws Exception {
        Scenario scenario = presentedQuote("11100022255", "PBC1A11");

        String body = mvc.perform(get("/api/public/quotes/{t}", scenario.token()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.revisionNumber").value(1))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].decisionStatus").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.items[0].decisionAvailability").value("DECIDABLE"))
                .andExpect(jsonPath("$.total").value(700.0))
                .andReturn().getResponse().getContentAsString();

        // Nada de interno atravessa: sem custo, fornecedor, OS, cliente ou identificador de usuário.
        assertThat(body).doesNotContain("workOrder").doesNotContain("createdBy").doesNotContain("cost")
                .doesNotContain("customerId").doesNotContain("quoteId").doesNotContain(scenario.quote());
    }

    @Test
    void unknownAndRevokedTokensAnswerExactlyTheSame() throws Exception {
        Scenario scenario = presentedQuote("11100022266", "PBD1A11");
        String unknownBody = mvc.perform(get("/api/public/quotes/{t}", "token-inexistente"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUBLIC_QUOTE_NOT_AVAILABLE"))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(authorized(post("/api/work-orders/{w}/quotes/{q}/public-access/{a}/revoke",
                scenario.workOrder(), scenario.quote(), scenario.accessId()))).andExpect(status().isNoContent());

        String revokedBody = mvc.perform(get("/api/public/quotes/{t}", scenario.token()))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(revokedBody).isEqualTo(unknownBody);
    }

    @Test
    void expiredAccessAnswersGone() throws Exception {
        Scenario scenario = presentedQuote("11100022277", "PBE1A11");
        jdbc.update("update workshop.public_quote_access set created_at = now() - interval '10 days',"
                + " valid_until = now() - interval '1 day'");

        mvc.perform(get("/api/public/quotes/{t}", scenario.token()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("PUBLIC_QUOTE_EXPIRED"));
        mvc.perform(decision(scenario, "req-expirado", approve(scenario.firstItem())))
                .andExpect(status().isGone());
        assertThat(jdbc.queryForObject("select count(*) from workshop.quote_decision", Long.class)).isZero();
    }

    @Test
    void partialApprovalLeavesUntouchedItemsPending() throws Exception {
        Scenario scenario = presentedQuote("11100022288", "PBF1A11");

        mvc.perform(decision(scenario, "req-parcial",
                        approve(scenario.firstItem()) + "," + reject(scenario.secondItem())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.quote.items[0].decisionStatus").value("APPROVED"))
                .andExpect(jsonPath("$.quote.items[0].decisionAvailability").value("ALREADY_DECIDED"))
                .andExpect(jsonPath("$.quote.items[1].decisionStatus").value("REJECTED"));

        // Um terceiro item omitido continuaria pendente: ausência de decisão não é rejeição.
        Scenario other = presentedQuote("11100022299", "PBG1A11");
        mvc.perform(decision(other, "req-um-item", approve(other.firstItem())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quote.items[0].decisionStatus").value("APPROVED"))
                .andExpect(jsonPath("$.quote.items[1].decisionStatus").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.quote.items[1].decisionAvailability").value("DECIDABLE"));
    }

    @Test
    void decisionRequiresExplicitAcceptanceAndAtLeastOneItem() throws Exception {
        Scenario scenario = presentedQuote("11100022300", "PBH1A11");

        mvc.perform(publicPost(scenario.token(), payload(scenario, "req-sem-aceite",
                        approve(scenario.firstItem()), false, "Jose da Silva", "CPF", "12345678901")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPLICIT_ACCEPTANCE_REQUIRED"));
        mvc.perform(decision(scenario, "req-vazio", "")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from workshop.quote_decision_submission", Long.class))
                .isZero();
    }

    @Test
    void replayOfTheSameSubmissionDoesNotDuplicateAnything() throws Exception {
        Scenario scenario = presentedQuote("11100022311", "PBI1A11");
        String body = payload(scenario, "req-replay", approve(scenario.firstItem()), true,
                "Jose da Silva", "CPF", "123.456.789-01");

        mvc.perform(publicPost(scenario.token(), body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(publicPost(scenario.token(), body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.quote.items[0].decisionStatus").value("APPROVED"));

        assertThat(jdbc.queryForObject("select count(*) from workshop.quote_decision_submission", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from workshop.quote_decision", Long.class)).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentContentIsRefused() throws Exception {
        Scenario scenario = presentedQuote("11100022322", "PBJ1A11");

        mvc.perform(publicPost(scenario.token(), payload(scenario, "req-conflito",
                approve(scenario.firstItem()), true, "Jose", "CPF", "12345678901")))
                .andExpect(status().isCreated());
        mvc.perform(publicPost(scenario.token(), payload(scenario, "req-conflito",
                        reject(scenario.firstItem()), true, "Jose", "CPF", "12345678901")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("select decision_type from workshop.quote_decision", String.class))
                .isEqualTo("APPROVE");
    }

    @Test
    void oneInvalidItemRollsBackTheWholeSubmission() throws Exception {
        Scenario scenario = presentedQuote("11100022333", "PBK1A11");
        Scenario other = presentedQuote("11100022344", "PBL1A11");

        // Item válido junto de um item que não pertence à apresentação: nada pode ser consolidado.
        mvc.perform(decision(scenario, "req-atomico",
                        approve(scenario.firstItem()) + "," + approve(other.firstItem())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUOTE_ITEM_NOT_IN_REVISION"));
        assertThat(jdbc.queryForObject("select count(*) from workshop.quote_decision", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from workshop.quote_decision_submission", Long.class))
                .isZero();
    }

    @Test
    void aPresentedNewerVersionBlocksTheDecisionOnTheOldOne() throws Exception {
        Scenario scenario = presentedQuote("11100022355", "PBM1A11");
        String itemId = JsonPath.read(getQuote(scenario), "$.items[0].id");

        String second = JsonPath.read(createRevision(scenario, itemId, "700.00"), "$.revisions[1].id");
        present(scenario, second);

        mvc.perform(decision(scenario, "req-stale", approve(scenario.firstItem())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_ITEM_REVISION_STALE"));
        assertThat(jdbc.queryForObject("select count(*) from workshop.quote_decision", Long.class)).isZero();
    }

    @Test
    void aDraftNewerVersionDoesNotBlockTheDecision() throws Exception {
        Scenario scenario = presentedQuote("11100022366", "PBN1A11");
        String itemId = JsonPath.read(getQuote(scenario), "$.items[0].id");

        // A nova versão existe apenas em rascunho: DR-0001, opção B.
        createRevision(scenario, itemId, "700.00");

        mvc.perform(decision(scenario, "req-draft", approve(scenario.firstItem())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quote.items[0].decisionStatus").value("APPROVED"));
    }

    @Test
    void aComplementReusingTheSameVersionDoesNotBlockTheDecision() throws Exception {
        Scenario scenario = presentedQuote("11100022377", "PBO1A11");
        String itemId = JsonPath.read(getQuote(scenario), "$.items[0].id");

        // Mesma condição comercial reaproveitada em uma nova apresentação: RN-22.
        String second = JsonPath.read(createRevision(scenario, itemId, "500.00"), "$.revisions[1].id");
        present(scenario, second);

        mvc.perform(decision(scenario, "req-complemento", approve(scenario.firstItem())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quote.items[0].decisionStatus").value("APPROVED"));
    }

    @Test
    void anItemThatAlreadyHasADecisionCannotBeDecidedAgain() throws Exception {
        Scenario scenario = presentedQuote("11100022388", "PBP1A11");

        mvc.perform(decision(scenario, "req-primeira", approve(scenario.firstItem())))
                .andExpect(status().isCreated());
        mvc.perform(decision(scenario, "req-segunda", reject(scenario.firstItem())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_ITEM_ALREADY_DECIDED"));
        assertThat(jdbc.queryForObject("select count(*) from workshop.quote_decision", Long.class)).isEqualTo(1);
    }

    @Test
    void theSubmissionPreservesTheEvidenceOfTheDecision() throws Exception {
        Scenario scenario = presentedQuote("11100022399", "PBQ1A11");

        mvc.perform(publicPost(scenario.token(), payload(scenario, "req-evidencia",
                        approve(scenario.firstItem()), true, "  José da Silva  ", "CPF", "123.456.789-01"))
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 Teste"))
                .andExpect(status().isCreated());

        var evidence = jdbc.queryForMap("select customer_name, document_type, document_number,"
                + " explicit_acceptance, host(ip_address) as ip, user_agent, occurred_at"
                + " from workshop.quote_decision_submission");
        assertThat(evidence.get("customer_name")).isEqualTo("José da Silva");
        assertThat(evidence.get("document_type")).isEqualTo("CPF");
        // Documento normalizado para dígitos, como manda a revisão de segurança.
        assertThat(evidence.get("document_number")).isEqualTo("12345678901");
        assertThat(evidence.get("explicit_acceptance")).isEqualTo(true);
        assertThat(evidence.get("ip")).isNotNull();
        assertThat(evidence.get("user_agent")).isEqualTo("Mozilla/5.0 Teste");
        assertThat(evidence.get("occurred_at")).isNotNull();
    }

    /**
     * Prova do finding `F-07-01`.
     *
     * <p>Uma apresentação concorrente abre a transação e trava a linha do orçamento antes que a
     * decisão grave. A decisão já leu a versão antiga, e é exatamente essa leitura vencida que não
     * pode virar uma decisão aceita.</p>
     */
    @Test
    void aConcurrentPresentationStopsADecisionBasedOnAStaleRead() throws Exception {
        Scenario scenario = presentedQuote("11100022400", "PBR1A11");
        Quote quote = repository.findById(UUID.fromString(scenario.quote())).orElseThrow();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> presenter = executor.submit(() -> transactions.execute(status -> {
                repository.touch(quote.id(), quote.version());
                locked.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> decider = executor.submit(() -> publicQuotes.decide(scenario.token(), request(scenario)));
            // Espera curta apenas para garantir que a decisão já leu o orçamento e ficou bloqueada na
            // gravação; o desfecho assertado não depende do tempo exato.
            Thread.sleep(500);
            release.countDown();
            presenter.get(15, TimeUnit.SECONDS);

            assertThatThrownBy(() -> decider.get(15, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(br.com.uberhidraulica.erp.quote.domain.QuoteException.class)
                    .satisfies(failure -> assertThat(
                            ((br.com.uberhidraulica.erp.quote.domain.QuoteException) failure.getCause()).code())
                            .isEqualTo("CONCURRENT_MODIFICATION"));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from workshop.quote_decision", Long.class)).isZero();
    }

    @Test
    void postgresRejectsDecisionsOutsideTheSubmissionRevision() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '10' and success",
                Long.class)).isEqualTo(1);
        Scenario scenario = presentedQuote("11100022411", "PBS1A11");
        Scenario other = presentedQuote("11100022422", "PBT1A11");

        mvc.perform(decision(scenario, "req-banco", approve(scenario.firstItem())))
                .andExpect(status().isCreated());
        String submission = jdbc.queryForObject("select id::text from workshop.quote_decision_submission",
                String.class);

        // Item de outro orçamento, ainda que a submissão seja válida: o banco recusa sozinho.
        assertThatThrownBy(() -> jdbc.update("insert into workshop.quote_decision"
                        + " (id, submission_id, quote_revision_id, quote_item_revision_id, quote_id,"
                        + " decision_type, occurred_at) values (?, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'APPROVE', now())",
                UUID.randomUUID(), submission, scenario.revision(), other.firstItem(), scenario.quote()))
                .hasMessageContaining("fk_quote_decision_presented_item");

        // Segunda decisão para a mesma versão comercial, mesmo por SQL direto.
        assertThatThrownBy(() -> jdbc.update("insert into workshop.quote_decision"
                        + " (id, submission_id, quote_revision_id, quote_item_revision_id, quote_id,"
                        + " decision_type, occurred_at) values (?, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'REJECT', now())",
                UUID.randomUUID(), submission, scenario.revision(), scenario.firstItem(), scenario.quote()))
                .hasMessageContaining("uq_quote_decision_item_revision");

        assertThatThrownBy(() -> jdbc.update("update workshop.quote_decision_submission"
                + " set explicit_acceptance = false")).hasMessageContaining("ck_quote_submission_acceptance");
        assertThatThrownBy(() -> jdbc.update("update workshop.quote_decision_submission"
                + " set document_number = '123'")).hasMessageContaining("ck_quote_submission_document_length");
    }

    @Test
    void thePublicEndpointNeedsNeitherSessionNorCsrf() throws Exception {
        Scenario scenario = presentedQuote("11100022433", "PBU1A11");
        // Sem cookie de sessão e sem token CSRF: é exatamente o que o cliente externo tem.
        mvc.perform(publicPost(scenario.token(), payload(scenario, "req-sem-sessao",
                approve(scenario.firstItem()), true, "Cliente", "CNPJ", "12345678000199")))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select document_type from workshop.quote_decision_submission",
                String.class)).isEqualTo("CNPJ");
    }

    // ----- infraestrutura do teste -----

    private record Scenario(String workOrder, String quote, String revision, String accessId, String token,
                            String firstItem, String secondItem) {}

    private PublicQuoteService.DecisionRequest request(Scenario scenario) {
        return new PublicQuoteService.DecisionRequest(UUID.fromString(scenario.revision()), "req-concorrente",
                "Cliente Concorrente", DocumentType.CPF, "12345678901", true,
                List.of(new PublicQuoteService.ItemDecision(UUID.fromString(scenario.firstItem()),
                        DecisionType.APPROVE)), "127.0.0.1", "teste");
    }

    private static String approve(String itemReference) {
        return "{\"itemReference\":\"" + itemReference + "\",\"decision\":\"APPROVE\"}";
    }

    private static String reject(String itemReference) {
        return "{\"itemReference\":\"" + itemReference + "\",\"decision\":\"REJECT\"}";
    }

    private static String payload(Scenario scenario, String requestId, String decisions, boolean acceptance,
                                  String name, String documentType, String documentNumber) {
        return "{\"revisionReference\":\"" + scenario.revision() + "\",\"requestId\":\"" + requestId + "\","
                + "\"customer\":{\"name\":\"" + name + "\",\"documentType\":\"" + documentType + "\","
                + "\"documentNumber\":\"" + documentNumber + "\"},"
                + "\"explicitAcceptance\":" + acceptance + ",\"decisions\":[" + decisions + "]}";
    }

    private MockHttpServletRequestBuilder decision(Scenario scenario, String requestId, String decisions) {
        return publicPost(scenario.token(), payload(scenario, requestId, decisions, true,
                "Jose da Silva", "CPF", "12345678901"));
    }

    private MockHttpServletRequestBuilder publicPost(String token, String body) {
        return post("/api/public/quotes/{t}/decisions", token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String getQuote(Scenario scenario) throws Exception {
        return mvc.perform(authorized(get("/api/work-orders/{w}/quotes/{q}",
                        scenario.workOrder(), scenario.quote())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String createRevision(Scenario scenario, String itemId, String price) throws Exception {
        return mvc.perform(authorized(post("/api/work-orders/{w}/quotes/{q}/revisions",
                        scenario.workOrder(), scenario.quote()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"quoteItemId\":\"" + itemId + "\",\"description\":\"Caixa\","
                                + "\"quantity\":\"1\",\"unitPrice\":\"" + price + "\"}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private void present(Scenario scenario, String revisionId) throws Exception {
        mvc.perform(authorized(post("/api/work-orders/{w}/quotes/{q}/revisions/{r}/present",
                scenario.workOrder(), scenario.quote(), revisionId))).andExpect(status().isOk());
    }

    /** OS → orçamento → revisão com dois itens → apresentação → link público emitido. */
    private Scenario presentedQuote(String document, String plate) throws Exception {
        String customer = JsonPath.read(mvc.perform(authorized(post("/api/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personType\":\"PF\",\"name\":\"Cliente Publico\",\"document\":\"" + document + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String vehicle = JsonPath.read(mvc.perform(authorized(post("/api/vehicles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer + "\",\"plate\":\"" + plate + "\",\"manufacturer\":\"Ford\","
                                + "\"model\":\"Cargo\",\"modelYear\":2022,\"mileage\":1000}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String workOrder = JsonPath.read(mvc.perform(authorized(post("/api/work-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle
                                + "\",\"entryMileage\":1000}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String quote = JsonPath.read(mvc.perform(authorized(post("/api/work-orders/{w}/quotes", workOrder)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String created = mvc.perform(authorized(post("/api/work-orders/{w}/quotes/{q}/revisions", workOrder, quote))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":["
                                + "{\"description\":\"Caixa\",\"quantity\":\"1\",\"unitPrice\":\"500.00\"},"
                                + "{\"description\":\"Mangueira\",\"quantity\":\"2\",\"unitPrice\":\"100.00\"}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String revision = JsonPath.read(created, "$.revisions[0].id");
        mvc.perform(authorized(post("/api/work-orders/{w}/quotes/{q}/revisions/{r}/present",
                workOrder, quote, revision))).andExpect(status().isOk());

        String issued = mvc.perform(authorized(post("/api/work-orders/{w}/quotes/{q}/revisions/{r}/public-access",
                        workOrder, quote, revision)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String view = mvc.perform(get("/api/public/quotes/{t}", JsonPath.<String>read(issued, "$.token")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Scenario(workOrder, quote, revision, JsonPath.read(issued, "$.accessId"),
                JsonPath.read(issued, "$.token"), JsonPath.read(view, "$.items[0].itemReference"),
                JsonPath.read(view, "$.items[1].itemReference"));
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
        return builder.cookie(session.cookie()).header(session.csrfHeader(), session.csrfToken());
    }

    private Session createUserSession(String email, String profileCode) throws Exception {
        Session existing = login(email, PASSWORD, true);
        if (existing != null) return existing;
        String created = mvc.perform(authorized(post("/api/iam/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Usuario Teste\",\"email\":\"" + email + "\",\"profileCode\":\""
                                + profileCode + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String temporary = JsonPath.read(created, "$.temporaryPassword");
        Session first = login(email, temporary, false);
        mvc.perform(post("/api/iam/password/change").cookie(first.cookie())
                        .header(first.csrfHeader(), first.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + temporary + "\",\"newPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
        return login(email, PASSWORD, false);
    }

    private Session openSession() throws Exception {
        Session bootstrap = login(OWNER_EMAIL, BOOTSTRAP_PASSWORD, true);
        if (bootstrap != null) {
            mvc.perform(post("/api/iam/password/change").cookie(bootstrap.cookie())
                            .header(bootstrap.csrfHeader(), bootstrap.csrfToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"" + BOOTSTRAP_PASSWORD + "\",\"newPassword\":\""
                                    + PASSWORD + "\"}"))
                    .andExpect(status().isNoContent());
        }
        return login(OWNER_EMAIL, PASSWORD, false);
    }

    private Session login(String email, String password, boolean optional) throws Exception {
        MvcResult csrf = mvc.perform(get("/api/iam/csrf")).andExpect(status().isOk()).andReturn();
        Cookie anonymous = csrf.getResponse().getCookie("SESSION");
        String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        String headerName = JsonPath.read(csrf.getResponse().getContentAsString(), "$.headerName");
        MvcResult result = mvc.perform(post("/api/iam/auth/login").cookie(anonymous).header(headerName, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            if (optional) return null;
            throw new IllegalStateException("Login falhou: " + result.getResponse().getStatus());
        }
        return new Session(result.getResponse().getCookie("SESSION"), headerName, token);
    }

    private record Session(Cookie cookie, String csrfHeader, String csrfToken) {}
}
