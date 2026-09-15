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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import br.com.uberhidraulica.erp.quote.domain.Quote;
import br.com.uberhidraulica.erp.quote.domain.QuoteRevision;
import br.com.uberhidraulica.erp.quote.port.QuoteRepositoryPort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integração do versionamento comercial do orçamento em PostgreSQL real.
 *
 * <p>Usa sessão autenticada de verdade em vez de um principal simulado: {@code created_by} vem do
 * usuário autenticado, e um principal falso não provaria que a autoria é registrada.</p>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class Task0007QuoteVersioningIntegrationTest {
    private static final String OWNER_EMAIL = "owner-quote@example.test";
    private static final String BOOTSTRAP_PASSWORD = "quote-bootstrap-password";
    private static final String PASSWORD = "quote-operational-password";

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Quote");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired QuoteRepositoryPort repository;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    private Session session;

    @BeforeEach
    void clean() throws Exception {
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
        jdbc.update("delete from servicecatalog.service");
        session = openSession();
    }

    @Test
    void endpointsRequireAuthenticationAndCsrf() throws Exception {
        UUID workOrder = UUID.randomUUID();
        // Leitura sem sessão para na autenticação.
        mvc.perform(get("/api/work-orders/{id}/quotes", workOrder)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/work-orders/{w}/quotes/{q}", workOrder, UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        // Mutação sem token CSRF para antes disso, no próprio filtro de CSRF.
        mvc.perform(post("/api/work-orders/{id}/quotes", workOrder)).andExpect(status().isForbidden());
        mvc.perform(post("/api/work-orders/{id}/quotes", workOrder).cookie(session.cookie()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/work-orders/{w}/quotes/{q}/revisions", workOrder, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void opensQuoteAndBuildsFirstDraftRevision() throws Exception {
        String workOrder = openWorkOrder("11122233344", "QTA1A11");
        String quote = openQuote(workOrder);

        MvcResult created = mvc.perform(authorized(post("/api/work-orders/{w}/quotes/{q}/revisions", workOrder, quote))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":["
                                + "{\"description\":\"Recondicionamento da caixa\",\"quantity\":1,\"unitPrice\":\"500.00\"},"
                                + "{\"description\":\"Óleo ATF\",\"quantity\":\"2.500\",\"unitPrice\":\"42.90\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisions.length()").value(1))
                .andExpect(jsonPath("$.revisions[0].revisionNumber").value(1))
                .andExpect(jsonPath("$.revisions[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.revisions[0].presentedAt").doesNotExist())
                .andExpect(jsonPath("$.revisions[0].expired").value(false))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].revisions[0].revisionSequence").value(1))
                .andExpect(jsonPath("$.items[0].revisions[0].totalPrice").value(500.0000))
                .andExpect(jsonPath("$.items[1].revisions[0].totalPrice").value(107.2500))
                // Rascunho nunca é decidível: o cliente não recebeu nada ainda.
                .andExpect(jsonPath("$.items[0].revisions[0].presented").value(false))
                .andExpect(jsonPath("$.items[0].revisions[0].availability").value("NOT_PRESENTED"))
                .andReturn();

        String body = created.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.createdBy")).isEqualTo(ownerId());
        assertThat((String) JsonPath.read(body, "$.items[0].revisions[0].createdBy")).isEqualTo(ownerId());
    }

    @Test
    void presentingStartsSevenDayCommercialValidity() throws Exception {
        String workOrder = openWorkOrder("22233344455", "QTB1A11");
        String quote = openQuote(workOrder);
        String revision = revisionId(createRevision(quote, workOrder, item(null, "Serviço", "1", "300.00")), 0);

        mvc.perform(authorized(post("/api/work-orders/{w}/quotes/{q}/revisions/{r}/present", workOrder, quote, revision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisions[0].status").value("PRESENTED"))
                .andExpect(jsonPath("$.revisions[0].presentedAt").exists())
                .andExpect(jsonPath("$.revisions[0].validUntil").exists())
                .andExpect(jsonPath("$.revisions[0].expired").value(false))
                .andExpect(jsonPath("$.items[0].revisions[0].presented").value(true))
                .andExpect(jsonPath("$.items[0].revisions[0].availability").value("AVAILABLE"));

        Long days = jdbc.queryForObject(
                "select extract(day from (valid_until - presented_at))::bigint from workshop.quote_revision where id=?::uuid",
                Long.class, revision);
        assertThat(days).isEqualTo(7L);
    }

    @Test
    void draftVersionDoesNotSupersedeThePresentedOne() throws Exception {
        String workOrder = openWorkOrder("33344455566", "QTC1A11");
        String quote = openQuote(workOrder);
        String first = revisionId(createRevision(quote, workOrder, item(null, "Caixa de direção", "1", "500.00")), 0);
        present(workOrder, quote, first);
        String itemId = itemId(getQuote(workOrder, quote), 0);

        // A-v2 existe apenas em rascunho: DR-0001, opção B — não substitui a proposta apresentada.
        String body = createRevision(quote, workOrder, item(itemId, "Caixa de direção", "1", "600.00"));
        assertThat(JsonPath.<List<Object>>read(body, "$.items[0].revisions")).hasSize(2);
        assertThat(JsonPath.<String>read(body, "$.items[0].revisions[0].availability")).isEqualTo("AVAILABLE");
        assertThat(JsonPath.<String>read(body, "$.items[0].revisions[1].availability")).isEqualTo("NOT_PRESENTED");
        assertThat(JsonPath.<Double>read(body, "$.items[0].revisions[1].unitPrice")).isEqualTo(600.0);
    }

    @Test
    void presentingANewerVersionSupersedesThePreviousOne() throws Exception {
        String workOrder = openWorkOrder("44455566677", "QTD1A11");
        String quote = openQuote(workOrder);
        String first = revisionId(createRevision(quote, workOrder, item(null, "Caixa de direção", "1", "500.00")), 0);
        present(workOrder, quote, first);
        String itemId = itemId(getQuote(workOrder, quote), 0);
        String second = revisionId(createRevision(quote, workOrder, item(itemId, "Caixa de direção", "1", "600.00")), 1);

        String body = present(workOrder, quote, second);
        assertThat(JsonPath.<String>read(body, "$.items[0].revisions[0].availability")).isEqualTo("SUPERSEDED");
        assertThat(JsonPath.<String>read(body, "$.items[0].revisions[1].availability")).isEqualTo("AVAILABLE");
        // A versão anterior continua existindo: histórico não é apagado nem migrado.
        assertThat(JsonPath.<Double>read(body, "$.items[0].revisions[0].unitPrice")).isEqualTo(500.0);
        assertThat(jdbc.queryForObject("select count(*) from workshop.quote_item_revision", Long.class)).isEqualTo(2);
    }

    @Test
    void complementReusingTheSameVersionKeepsItDecidable() throws Exception {
        String workOrder = openWorkOrder("55566677788", "QTE1A11");
        String quote = openQuote(workOrder);
        String first = revisionId(createRevision(quote, workOrder, item(null, "Caixa de direção", "1", "500.00")), 0);
        present(workOrder, quote, first);
        String itemId = itemId(getQuote(workOrder, quote), 0);

        String second = revisionId(createRevision(quote, workOrder,
                item(itemId, "Caixa de direção", "1", "500.00") + "," + item(null, "Mangueira", "2", "80.00")), 1);
        String body = present(workOrder, quote, second);

        // Termos idênticos reaproveitam A-v1: nenhuma segunda versão do item A foi criada.
        assertThat(JsonPath.<List<Object>>read(body, "$.items[0].revisions")).hasSize(1);
        assertThat(JsonPath.<String>read(body, "$.items[0].revisions[0].availability")).isEqualTo("AVAILABLE");
        assertThat(JsonPath.<String>read(body, "$.items[1].revisions[0].availability")).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from workshop.quote_revision_item", Long.class)).isEqualTo(3);
    }

    @Test
    void expiredPresentationBlocksItemsThatAreStillPending() throws Exception {
        String workOrder = openWorkOrder("66677788899", "QTF1A11");
        String quote = openQuote(workOrder);
        String revision = revisionId(createRevision(quote, workOrder, item(null, "Serviço", "1", "150.00")), 0);
        present(workOrder, quote, revision);

        jdbc.update("update workshop.quote_revision set presented_at = now() - interval '10 days',"
                + " valid_until = now() - interval '3 days' where id=?::uuid", revision);

        mvc.perform(authorized(get("/api/work-orders/{w}/quotes/{q}", workOrder, quote)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisions[0].expired").value(true))
                .andExpect(jsonPath("$.items[0].revisions[0].presented").value(true))
                .andExpect(jsonPath("$.items[0].revisions[0].availability").value("EXPIRED"));
    }

    @Test
    void refusesReadingAQuoteThroughAnotherWorkOrder() throws Exception {
        String first = openWorkOrder("77788899900", "QTG1A11");
        String second = openWorkOrder("88899900011", "QTH1A11");
        String quote = openQuote(first);

        mvc.perform(authorized(get("/api/work-orders/{w}/quotes/{q}", second, quote)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUOTE_NOT_FOUND"));
        mvc.perform(authorized(get("/api/work-orders/{w}/quotes/{q}", first, UUID.randomUUID())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUOTE_NOT_FOUND"));
        mvc.perform(authorized(post("/api/work-orders/{w}/quotes", UUID.randomUUID())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_FOUND"));
        mvc.perform(authorized(post("/api/work-orders/{w}/quotes/{q}/revisions/{r}/present",
                        first, quote, UUID.randomUUID())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUOTE_REVISION_NOT_FOUND"));
    }

    @Test
    void rejectsInvalidRevisionRequests() throws Exception {
        String workOrder = openWorkOrder("99900011122", "QTI1A11");
        String quote = openQuote(workOrder);

        mvc.perform(revisionRequest(workOrder, quote, "{\"items\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(revisionRequest(workOrder, quote, "{\"items\":[" + item(null, "  ", "1", "10.00") + "]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(revisionRequest(workOrder, quote, "{\"items\":[" + item(null, "Serviço", "0", "10.00") + "]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(revisionRequest(workOrder, quote, "{\"items\":[" + item(null, "Serviço", "1", "-1.00") + "]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(revisionRequest(workOrder, quote,
                        "{\"items\":[" + item(UUID.randomUUID().toString(), "Serviço", "1", "10.00") + "]}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUOTE_ITEM_NOT_FOUND"));
        mvc.perform(revisionRequest(workOrder, quote, "{\"items\":[{\"description\":\"Serviço\",\"quantity\":1,"
                        + "\"unitPrice\":\"10.00\",\"workOrderServiceId\":\"" + UUID.randomUUID() + "\"}]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_SERVICE_NOT_FOUND"));
        assertThat(jdbc.queryForObject("select count(*) from workshop.quote_item", Long.class)).isZero();
    }

    /**
     * Fronteiras do arredondamento aprovado na DR-0007: multiplica com precisao integral e leva
     * somente o resultado a duas casas com HALF_UP.
     */
    @Test
    void roundsTheItemTotalWithHalfUpOnEveryBoundary() throws Exception {
        String workOrder = openWorkOrder("10011122233", "QTJ1A11");
        String quote = openQuote(workOrder);

        String body = createRevision(quote, workOrder, String.join(",",
                item(null, "Terceira casa menor que cinco", "1", "0.1240"),
                item(null, "Terceira casa igual a cinco", "1", "0.1250"),
                item(null, "Terceira casa maior que cinco", "1", "0.1260"),
                item(null, "Quantidade fracionaria", "2.5", "42.90"),
                item(null, "Quantidade fracionaria que sobe", "0.5", "0.05"),
                item(null, "Preco unitario com quatro casas", "7", "0.1429")));

        assertThat(JsonPath.<Double>read(body, "$.items[0].revisions[0].totalPrice")).isEqualTo(0.12);
        assertThat(JsonPath.<Double>read(body, "$.items[1].revisions[0].totalPrice")).isEqualTo(0.13);
        assertThat(JsonPath.<Double>read(body, "$.items[2].revisions[0].totalPrice")).isEqualTo(0.13);
        assertThat(JsonPath.<Double>read(body, "$.items[3].revisions[0].totalPrice")).isEqualTo(107.25);
        assertThat(JsonPath.<Double>read(body, "$.items[4].revisions[0].totalPrice")).isEqualTo(0.03);
        assertThat(JsonPath.<Double>read(body, "$.items[5].revisions[0].totalPrice")).isEqualTo(1.0);

        // O banco guarda o valor ja arredondado, nao o produto bruto.
        assertThat(jdbc.queryForObject("select total_price from workshop.quote_item_revision"
                + " where description = 'Terceira casa igual a cinco'", BigDecimal.class))
                .isEqualByComparingTo("0.13");
    }

    @Test
    void totalOfThePresentationSumsItemTotalsAlreadyRounded() throws Exception {
        String workOrder = openWorkOrder("10022233344", "QTP1A11");
        String quote = openQuote(workOrder);

        // Tres itens de 0,125: somar depois daria 0,375 -> 0,38. A politica soma parcelas ja
        // arredondadas, entao o total e 0,39 e bate com o que o cliente ve item a item.
        String body = createRevision(quote, workOrder, String.join(",",
                item(null, "Item A", "1", "0.1250"),
                item(null, "Item B", "1", "0.1250"),
                item(null, "Item C", "1", "0.1250")));
        assertThat(JsonPath.<Double>read(body, "$.revisions[0].total")).isEqualTo(0.39);

        String presented = present(workOrder, quote, revisionId(body, 0));
        assertThat(JsonPath.<Double>read(presented, "$.availableTotal")).isEqualTo(0.39);
    }

    @Test
    void refusesQuantityAndPriceBeyondFourDecimals() throws Exception {
        String workOrder = openWorkOrder("10033344455", "QTQ1A11");
        String quote = openQuote(workOrder);

        mvc.perform(revisionRequest(workOrder, quote, "{\"items\":[" + item(null, "Insumo", "0.00001", "1.00") + "]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(revisionRequest(workOrder, quote, "{\"items\":[" + item(null, "Insumo", "1", "0.00001") + "]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("select count(*) from workshop.quote_item_revision", Long.class)).isZero();
    }

    @Test
    void refusesToReadAnItemRevisionWhoseStoredTotalWasTamperedWith() throws Exception {
        String workOrder = openWorkOrder("10044455566", "QTR1A11");
        String quote = openQuote(workOrder);
        createRevision(quote, workOrder, item(null, "Servico", "1", "100.00"));

        jdbc.update("update workshop.quote_item_revision set total_price = 1 where quote_id = ?::uuid", quote);
        mvc.perform(authorized(get("/api/work-orders/{w}/quotes/{q}", workOrder, quote)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUOTE_ITEM_REVISION_TOTAL_MISMATCH"));
    }

    @Test
    void presentingRequiresTheQuotePresentPermission() throws Exception {
        String workOrder = openWorkOrder("10055566677", "QTS1A11");
        String quote = openQuote(workOrder);
        String revision = revisionId(createRevision(quote, workOrder, item(null, "Servico", "1", "100.00")), 0);

        // GERENTE_FINANCEIRO nao recebe QUOTE_PRESENT por perfil.
        Session financial = createUserSession("financeiro-quote@example.test", "GERENTE_FINANCEIRO");
        mvc.perform(post("/api/work-orders/{w}/quotes/{q}/revisions/{r}/present", workOrder, quote, revision)
                        .cookie(financial.cookie()).header(financial.csrfHeader(), financial.csrfToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(jdbc.queryForObject("select status from workshop.quote_revision where id = ?::uuid",
                String.class, revision)).isEqualTo("DRAFT");

        // Sem sessao o CSRF barra antes; com CSRF e sem sessao, a autenticacao barra.
        mvc.perform(post("/api/work-orders/{w}/quotes/{q}/revisions/{r}/present", workOrder, quote, revision))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/work-orders/{w}/quotes/{q}/revisions/{r}/present", workOrder, quote, revision)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        // O DONO tem a permissao pelo perfil e consegue apresentar.
        mvc.perform(authorized(post("/api/work-orders/{w}/quotes/{q}/revisions/{r}/present",
                        workOrder, quote, revision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisions[0].status").value("PRESENTED"));
    }

    @Test
    void quotePresentPermissionIsSeededOnlyForTheApprovedProfiles() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '9' and success",
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("select p.code from iam.profile_permission pp"
                        + " join iam.profile p on p.id = pp.profile_id"
                        + " join iam.permission perm on perm.id = pp.permission_id"
                        + " where perm.code = 'QUOTE_PRESENT' order by p.code", String.class))
                .containsExactly("DONO", "GERENTE_ADMINISTRATIVO");
    }

    @Test
    void rejectsPresentingTwiceOrOutOfOrderAndDetectsConcurrentPresentation() throws Exception {
        String workOrder = openWorkOrder("11122233355", "QTK1A11");
        String quote = openQuote(workOrder);
        String first = revisionId(createRevision(quote, workOrder, item(null, "Serviço", "1", "100.00")), 0);
        String itemId = itemId(getQuote(workOrder, quote), 0);
        String second = revisionId(createRevision(quote, workOrder, item(itemId, "Serviço", "1", "120.00")), 1);

        present(workOrder, quote, second);
        mvc.perform(authorized(post("/api/work-orders/{w}/quotes/{q}/revisions/{r}/present", workOrder, quote, second)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_REVISION_ALREADY_PRESENTED"));
        mvc.perform(authorized(post("/api/work-orders/{w}/quotes/{q}/revisions/{r}/present", workOrder, quote, first)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_REVISION_OUT_OF_ORDER"));

        assertThat(jdbc.queryForObject("select count(*) from workshop.quote_revision where status='PRESENTED'",
                Long.class)).isEqualTo(1);
    }

    @Test
    void optimisticLockingRefusesToPresentARevisionThatChangedSinceItWasRead() throws Exception {
        String workOrder = openWorkOrder("15566677788", "QTO1A11");
        String quote = openQuote(workOrder);
        String revision = revisionId(createRevision(quote, workOrder, item(null, "Serviço", "1", "90.00")), 0);

        Quote loaded = transactions.execute(status -> repository.findById(UUID.fromString(quote)).orElseThrow());
        assertThat(loaded).isNotNull();
        QuoteRevision draft = loaded.revision(UUID.fromString(revision)).orElseThrow();
        QuoteRevision presented = draft.present(Instant.now(), QuoteRevision.DEFAULT_VALIDITY);

        // Simula a concorrência real: outra operação já avançou a versão da revisão.
        assertThat(presentInTransaction(presented, draft.version() + 1)).isFalse();
        assertThat(jdbc.queryForObject("select status from workshop.quote_revision where id=?::uuid",
                String.class, revision)).isEqualTo("DRAFT");

        assertThat(presentInTransaction(presented, draft.version())).isTrue();
        assertThat(jdbc.queryForObject("select status from workshop.quote_revision where id=?::uuid",
                String.class, revision)).isEqualTo("PRESENTED");
        assertThat(presentInTransaction(presented, draft.version())).isFalse();
    }

    @Test
    void linksQuoteItemToWorkOrderServiceAndKeepsHistoryChronological() throws Exception {
        String workOrder = openWorkOrder("12233344455", "QTL1A11");
        String service = createService();
        String workOrderService = JsonPath.read(mvc.perform(authorized(
                        post("/api/work-orders/{id}/services", workOrder))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"serviceId\":\"" + service + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.services[0].id");
        String quote = openQuote(workOrder);

        String body = createRevision(quote, workOrder, "{\"workOrderServiceId\":\"" + workOrderService + "\","
                + "\"description\":\"Alinhamento hidráulico\",\"quantity\":1,\"unitPrice\":\"250.00\"}");
        assertThat(JsonPath.<String>read(body, "$.items[0].workOrderServiceId")).isEqualTo(workOrderService);
        present(workOrder, quote, revisionId(body, 0));

        mvc.perform(authorized(get("/api/work-orders/{w}/quotes/{q}/history", workOrder, quote)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.type=='REVISION_PRESENTED')].revisionNumber").value(1))
                .andExpect(jsonPath("$[?(@.type=='ITEM_REVISION_CREATED')].revisionSequence").value(1));
    }

    @Test
    void postgresRejectsCrossQuoteAndMalformedCommercialRows() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='8' and success",
                Long.class)).isEqualTo(1);
        String workOrderA = openWorkOrder("13344455566", "QTM1A11");
        String workOrderB = openWorkOrder("14455566677", "QTN1A11");
        String quoteA = openQuote(workOrderA);
        String quoteB = openQuote(workOrderB);
        String revisionA = revisionId(createRevision(quoteA, workOrderA, item(null, "A", "1", "10.00")), 0);
        createRevision(quoteB, workOrderB, item(null, "B", "1", "20.00"));
        UUID itemRevisionB = UUID.fromString(jdbc.queryForObject(
                "select id::text from workshop.quote_item_revision where quote_id=?::uuid", String.class, quoteB));

        // Núcleo do finding HIGH-01: a revisão do Quote A não alcança a versão de item do Quote B.
        assertThatThrownBy(() -> jdbc.update("insert into workshop.quote_revision_item"
                        + " (quote_revision_id, quote_item_revision_id, quote_id, display_order)"
                        + " values (?::uuid, ?, ?::uuid, 9)", revisionA, itemRevisionB, quoteA))
                .hasMessageContaining("fk_quote_revision_item_item_revision_quote");
        assertThatThrownBy(() -> jdbc.update("insert into workshop.quote_revision_item"
                        + " (quote_revision_id, quote_item_revision_id, quote_id, display_order)"
                        + " values (?::uuid, ?, ?::uuid, 9)", revisionA, itemRevisionB, quoteB))
                .hasMessageContaining("fk_quote_revision_item_revision_quote");

        assertThatThrownBy(() -> jdbc.update("update workshop.quote_revision set status='PRESENTED' where id=?::uuid",
                revisionA)).hasMessageContaining("ck_quote_revision_presentation");
        assertThatThrownBy(() -> jdbc.update("update workshop.quote_revision set status='EXPIRED' where id=?::uuid",
                revisionA)).hasMessageContaining("quote_revision");
        assertThat(jdbc.queryForObject("select status from workshop.quote_revision where id=?::uuid",
                String.class, revisionA)).isEqualTo("DRAFT");
        assertThatThrownBy(() -> jdbc.update("update workshop.quote_item_revision set quantity=0 where quote_id=?::uuid",
                quoteA)).hasMessageContaining("ck_quote_item_revision_quantity");
        assertThatThrownBy(() -> jdbc.update("update workshop.quote_item_revision set unit_price=-1 where quote_id=?::uuid",
                quoteA)).hasMessageContaining("ck_quote_item_revision_unit_price");
        assertThatThrownBy(() -> jdbc.update("update workshop.quote_revision_item set display_order=0 where quote_id=?::uuid",
                quoteA)).hasMessageContaining("ck_quote_revision_display_order");
        assertThatThrownBy(() -> jdbc.update("update workshop.quote_revision set revision_number=0 where id=?::uuid",
                revisionA)).hasMessageContaining("ck_quote_revision_number");
    }

    /** O JPQL de atualização condicional exige transação própria quando chamado fora da aplicação. */
    private boolean presentInTransaction(QuoteRevision revision, long expectedVersion) {
        return Boolean.TRUE.equals(transactions.execute(status -> repository.present(revision, expectedVersion)));
    }

    // ----- infraestrutura do teste -----

    private static String item(String quoteItemId, String description, String quantity, String unitPrice) {
        return "{" + (quoteItemId == null ? "" : "\"quoteItemId\":\"" + quoteItemId + "\",")
                + "\"description\":\"" + description + "\","
                + "\"quantity\":\"" + quantity + "\",\"unitPrice\":\"" + unitPrice + "\"}";
    }

    private String createRevision(String quote, String workOrder, String itemsJson) throws Exception {
        return mvc.perform(revisionRequest(workOrder, quote, "{\"items\":[" + itemsJson + "]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private MockHttpServletRequestBuilder revisionRequest(String workOrder, String quote, String body) {
        return authorized(post("/api/work-orders/{w}/quotes/{q}/revisions", workOrder, quote))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String present(String workOrder, String quote, String revision) throws Exception {
        return mvc.perform(authorized(post("/api/work-orders/{w}/quotes/{q}/revisions/{r}/present",
                        workOrder, quote, revision)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String getQuote(String workOrder, String quote) throws Exception {
        return mvc.perform(authorized(get("/api/work-orders/{w}/quotes/{q}", workOrder, quote)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String revisionId(String quoteJson, int index) {
        return JsonPath.read(quoteJson, "$.revisions[" + index + "].id");
    }

    private static String itemId(String quoteJson, int index) {
        return JsonPath.read(quoteJson, "$.items[" + index + "].id");
    }

    private String openQuote(String workOrder) throws Exception {
        return JsonPath.read(mvc.perform(authorized(post("/api/work-orders/{id}/quotes", workOrder)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String openWorkOrder(String document, String plate) throws Exception {
        String customer = JsonPath.read(mvc.perform(authorized(post("/api/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personType\":\"PF\",\"name\":\"Cliente Orçamento\",\"document\":\"" + document + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String vehicle = JsonPath.read(mvc.perform(authorized(post("/api/vehicles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer + "\",\"plate\":\"" + plate + "\",\"manufacturer\":\"Ford\","
                                + "\"model\":\"Cargo\",\"modelYear\":2022,\"mileage\":5000}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        return JsonPath.read(mvc.perform(authorized(post("/api/work-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle
                                + "\",\"entryMileage\":5000}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String createService() throws Exception {
        return JsonPath.read(mvc.perform(authorized(post("/api/services"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alinhamento hidráulico\",\"description\":\"Serviço\","
                                + "\"basePrice\":\"250.00\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** Cria um usuario com o perfil pedido e devolve uma sessao dele, ja com a senha trocada. */
    private Session createUserSession(String email, String profileCode) throws Exception {
        // Usuários não são apagados entre testes porque a auditoria os referencia; reaproveita-se
        // aquele já criado, que a esta altura já teve a senha trocada.
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

    private String ownerId() {
        return jdbc.queryForObject("select id::text from iam.app_user where normalized_email=?", String.class,
                OWNER_EMAIL.toLowerCase());
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
        return builder.cookie(session.cookie()).header(session.csrfHeader(), session.csrfToken());
    }

    /** Login real seguido da troca obrigatória de senha: é assim que a sessão fica utilizável. */
    private Session openSession() throws Exception {
        Session bootstrap = login(BOOTSTRAP_PASSWORD, true);
        if (bootstrap != null) {
            mvc.perform(post("/api/iam/password/change").cookie(bootstrap.cookie())
                            .header(bootstrap.csrfHeader(), bootstrap.csrfToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"" + BOOTSTRAP_PASSWORD + "\",\"newPassword\":\""
                                    + PASSWORD + "\"}"))
                    .andExpect(status().isNoContent());
        }
        return login(PASSWORD, false);
    }

    private Session login(String password, boolean optional) throws Exception {
        return login(OWNER_EMAIL, password, optional);
    }

    private Session login(String email, String password, boolean optional) throws Exception {
        MvcResult csrf = mvc.perform(get("/api/iam/csrf")).andExpect(status().isOk()).andReturn();
        Cookie anonymous = csrf.getResponse().getCookie("SESSION");
        String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        String header = JsonPath.read(csrf.getResponse().getContentAsString(), "$.headerName");
        MvcResult result = mvc.perform(post("/api/iam/auth/login").cookie(anonymous).header(header, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            if (optional) return null;
            throw new IllegalStateException("Login falhou: " + result.getResponse().getStatus());
        }
        return new Session(result.getResponse().getCookie("SESSION"), header, token);
    }

    private record Session(Cookie cookie, String csrfHeader, String csrfToken) {}
}
