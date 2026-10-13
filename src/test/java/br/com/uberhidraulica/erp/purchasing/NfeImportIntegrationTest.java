package br.com.uberhidraulica.erp.purchasing;

import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeFixtures;
import br.com.uberhidraulica.erp.support.ApiSessions;
import br.com.uberhidraulica.erp.support.ApiSessions.Session;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-0021, I3: importação e confirmação de NF-e contra PostgreSQL real, provando as decisões aprovadas da
 * DR-0022.
 *
 * <p>O cenário base é a NF-e nº 21 declarada pelo proprietário: 12 itens somando 1.943,10 e três duplicatas
 * de 647,70. O XML é <b>sintético</b> e em nenhum momento é tratado como a nota fiscal verdadeira — o arquivo
 * real ainda não foi fornecido.</p>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class NfeImportIntegrationTest {
    private static final String OWNER_EMAIL = "owner-nfe@example.test";
    private static final String BOOTSTRAP_PASSWORD = "nfe-bootstrap-password";
    private static final String PASSWORD = "nfe-operational-password";
    private static final AtomicInteger SEQUENCE = new AtomicInteger(5000);

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Nfe");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private ApiSessions api;
    private Session owner;
    private Session finance;
    private UUID expenseCategory;

    @BeforeEach
    void sessions() throws Exception {
        api = new ApiSessions(mvc, PASSWORD);
        owner = api.owner(OWNER_EMAIL, BOOTSTRAP_PASSWORD);
        finance = api.user(owner, "finance-nfe@example.test", "GERENTE_FINANCEIRO");
        expenseCategory = expenseCategory();
    }

    // ------------------------------------------------------------------ apoio

    /** Cada nota do teste usa um número próprio, para a chave de acesso não colidir entre cenários. */
    private NfeFixtures note() {
        NfeFixtures fixture = NfeFixtures.valid();
        fixture.number = SEQUENCE.incrementAndGet();
        return fixture;
    }

    /** Nota de um item só, para os cenários de conversão e custo ficarem legíveis. */
    private NfeFixtures singleItemNote(String code, String qty, String unitPrice, String total) {
        NfeFixtures fixture = note();
        fixture.lines = new java.util.ArrayList<>(List.of(new NfeFixtures.Line(code, "Item unico", qty, unitPrice, total)));
        fixture.installments = new java.util.ArrayList<>(List.<String[]>of(new String[]{"001", "2026-11-10", total}));
        return fixture;
    }

    private UUID expenseCategory() throws Exception {
        String body = api.send(owner, post("/api/finance/expense-categories"),
                        "{\"name\":\"Peças e insumos " + SEQUENCE.incrementAndGet() + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID product(String description, String unit) throws Exception {
        String body = api.send(owner, post("/api/products"),
                        "{\"description\":\"" + description + "\",\"type\":\"PART\",\"unit\":\"" + unit + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private String preview(Session as, NfeFixtures fixture) throws Exception {
        return mvc.perform(post("/api/purchasing/nfe/preview").cookie(as.cookie())
                        .header(as.csrfHeader(), as.csrfToken())
                        .contentType(org.springframework.http.MediaType.APPLICATION_XML)
                        .content(fixture.xml()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        }

    /** Decisões de item: vincula cada linha da nota a um produto e guarda o vínculo. */
    private String decisions(NfeFixtures fixture, UUID productId, String factor) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < fixture.lines.size(); i++) {
            if (i > 0) out.append(',');
            out.append("{\"lineNo\":").append(i + 1).append(",\"productId\":\"").append(productId).append('"');
            if (factor != null) out.append(",\"unitConversionFactor\":").append(factor);
            out.append(",\"saveLink\":true}");
        }
        return out.append(']').toString();
    }

    private String confirmBody(NfeFixtures fixture, String decisions, String extra) {
        return "{\"xml\":" + quote(fixture.xml()) + ",\"createSupplier\":true,\"expenseCategoryId\":\""
                + expenseCategory + "\",\"decisions\":" + decisions + (extra == null ? "" : "," + extra) + "}";
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    private org.springframework.test.web.servlet.ResultActions confirm(Session as, String body, String key)
            throws Exception {
        return mvc.perform(post("/api/purchasing/nfe/confirm").cookie(as.cookie())
                .header(as.csrfHeader(), as.csrfToken()).header("Idempotency-Key", key)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body));
    }

    // ================================================================ D9 destinatário

    @Test
    void aNoteAddressedToAnotherTaxIdIsRefusedEvenWhenItIsTheSameGroupRoot() throws Exception {
        NfeFixtures fixture = note();
        // Mesma raiz 01637003, filial 0002: a D9 recusa, porque a raiz não prova identidade.
        fixture.recipient = "01637003000290";
        UUID productId = product("Produto D9 " + SEQUENCE.incrementAndGet(), "UNIDADE");

        String body = preview(owner, fixture);
        assertThat((String) JsonPath.read(body, "$.recipientCheck")).isEqualTo("RECIPIENT_MISMATCH");
        assertThat((Boolean) JsonPath.read(body, "$.confirmable")).isFalse();

        confirm(owner, confirmBody(fixture, decisions(fixture, productId, null), null), UUID.randomUUID().toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_RECIPIENT_NOT_AUTHORIZED"));
        // Nada foi gravado para esta nota. Contar a tabela inteira não serviria: a classe compartilha o banco
        // entre os cenários e outros testes criam compras legítimas.
        assertThat(jdbc.queryForObject("select count(*) from purchasing.purchase_document where access_key = ?",
                Long.class, fixture.accessKey())).isZero();
    }

    @Test
    void theAuthorizedEstablishmentIsTheSeededTaxIdAndTheRootAloneIsRejectedByTheSetting() throws Exception {
        String body = api.read(owner, "/api/purchasing/settings").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.ownTaxId")).isEqualTo(NfeFixtures.RECIPIENT);
        assertThat((Boolean) JsonPath.read(body, "$.ownTaxIdConfigured")).isTrue();
        // A condição que o proprietário anexou à D1 é fato, não opção.
        assertThat((Boolean) JsonPath.read(body, "$.costParametersValidatedByAccountant")).isFalse();

        api.send(owner, put("/api/purchasing/settings/OWN_TAX_ID"), "{\"value\":\"01637003\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_SETTING_INVALID"));
    }

    // ================================================================ D4/D11 obrigações

    @Test
    void theThreeDuplicatesOfNoteTwentyOneBecomeThreeOpenPayablesThatCloseWithTheTotal() throws Exception {
        NfeFixtures fixture = note();
        UUID productId = product("Produto NF21 " + SEQUENCE.incrementAndGet(), "UNIDADE");

        String body = confirm(owner, confirmBody(fixture, decisions(fixture, productId, null), null),
                UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString();

        UUID purchaseId = UUID.fromString(JsonPath.read(body, "$.purchaseId"));
        assertThat(new BigDecimal(JsonPath.read(body, "$.payableTotal").toString())).isEqualByComparingTo("1943.10");
        assertThat((List<?>) JsonPath.read(body, "$.installments")).hasSize(3);

        // Σ parcelas = total, conferido no banco e não só na resposta.
        BigDecimal sum = jdbc.queryForObject(
                "select sum(amount) from purchasing.purchase_payable where purchase_id = ?", BigDecimal.class,
                purchaseId);
        assertThat(sum).isEqualByComparingTo("1943.10");

        // Nasce em aberto: importar NÃO marca conta como paga.
        Long paid = jdbc.queryForObject("""
                select count(*) from finance.payable p
                join purchasing.purchase_payable pp on pp.payable_id = p.id
                where pp.purchase_id = ? and exists (select 1 from finance.payable_payment s where s.payable_id = p.id)
                """, Long.class, purchaseId);
        assertThat(paid).isZero();

        // E a compra fica ligada às três contas, que era o que o modelo antigo não permitia provar.
        assertThat(jdbc.queryForObject(
                "select count(*) from purchasing.purchase_payable where purchase_id = ?", Long.class, purchaseId))
                .isEqualTo(3L);
    }

    @Test
    void importingDoesNotMoveStockBecausePhysicalEntryIsTheReceipt() throws Exception {
        NfeFixtures fixture = note();
        UUID productId = product("Produto sem estoque " + SEQUENCE.incrementAndGet(), "UNIDADE");

        confirm(owner, confirmBody(fixture, decisions(fixture, productId, null), null), UUID.randomUUID().toString())
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movement where product_id = ?",
                Long.class, productId)).isZero();
    }

    @Test
    void aNoteWithoutDuplicatesDemandsTheTermsAndThenSplitsWithTheResidualOnTheLast() throws Exception {
        NfeFixtures fixture = singleItemNote("S-500", "1.0000", "100.00", "100.00");
        fixture.installments = new java.util.ArrayList<>();
        UUID productId = product("Produto a vista " + SEQUENCE.incrementAndGet(), "UNIDADE");
        String decisions = decisions(fixture, productId, null);

        confirm(owner, confirmBody(fixture, decisions, null), UUID.randomUUID().toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_INSTALLMENTS_REQUIRED"));

        String body = confirm(owner, confirmBody(fixture, decisions,
                        "\"installments\":{\"count\":3,\"firstDueDate\":\"2026-11-01\",\"intervalDays\":30}"),
                UUID.randomUUID().toString()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.read(body, "$.installments[*].amount").toString()).contains("33.33", "33.34");
        UUID purchaseId = UUID.fromString(JsonPath.read(body, "$.purchaseId"));
        assertThat(jdbc.queryForObject("select sum(amount) from purchasing.purchase_payable where purchase_id = ?",
                BigDecimal.class, purchaseId)).isEqualByComparingTo("100.00");
    }

    // ================================================================ idempotência e D5

    @Test
    void retryingWithTheSameIdempotencyKeyReturnsTheSamePurchaseAndCreatesNothingNew() throws Exception {
        NfeFixtures fixture = note();
        UUID productId = product("Produto idem " + SEQUENCE.incrementAndGet(), "UNIDADE");
        String body = confirmBody(fixture, decisions(fixture, productId, null), null);
        String key = UUID.randomUUID().toString();

        String first = confirm(owner, body, key).andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false)).andReturn().getResponse().getContentAsString();
        String again = confirm(owner, body, key).andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true)).andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(again, "$.purchaseId")).isEqualTo(JsonPath.read(first, "$.purchaseId"));
        UUID purchaseId = UUID.fromString(JsonPath.read(first, "$.purchaseId"));
        assertThat(jdbc.queryForObject("select count(*) from purchasing.purchase_payable where purchase_id = ?",
                Long.class, purchaseId)).isEqualTo(3L);
    }

    @Test
    void confirmingWithoutAnIdempotencyKeyIsRefusedSoARetryCannotDuplicateThePurchase() throws Exception {
        NfeFixtures fixture = note();
        UUID productId = product("Produto sem chave " + SEQUENCE.incrementAndGet(), "UNIDADE");
        mvc.perform(post("/api/purchasing/nfe/confirm").cookie(owner.cookie())
                        .header(owner.csrfHeader(), owner.csrfToken())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(confirmBody(fixture, decisions(fixture, productId, null), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void theSameNoteImportedTwiceWithDifferentKeysIsRefusedByTheAccessKey() throws Exception {
        NfeFixtures fixture = note();
        UUID productId = product("Produto dup " + SEQUENCE.incrementAndGet(), "UNIDADE");
        String body = confirmBody(fixture, decisions(fixture, productId, null), null);

        confirm(owner, body, UUID.randomUUID().toString()).andExpect(status().isOk());
        confirm(owner, body, UUID.randomUUID().toString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_DUPLICATE_NFE"));

        assertThat(jdbc.queryForObject(
                "select count(*) from purchasing.purchase_document where access_key = ? and voided_at is null",
                Long.class, fixture.accessKey())).isEqualTo(1L);
    }

    @Test
    void twoSimultaneousImportsOfTheSameNoteCreateExactlyOnePurchase() throws Exception {
        NfeFixtures fixture = note();
        UUID productId = product("Produto corrida " + SEQUENCE.incrementAndGet(), "UNIDADE");
        String body = confirmBody(fixture, decisions(fixture, productId, null), null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> attempt = () -> {
            start.await();
            return confirm(owner, body, UUID.randomUUID().toString()).andReturn().getResponse().getStatus();
        };
        try {
            Future<Integer> first = pool.submit(attempt);
            Future<Integer> second = pool.submit(attempt);
            start.countDown();
            List<Integer> statuses = List.of(first.get(), second.get());
            assertThat(statuses).contains(200);
            assertThat(statuses).containsAnyOf(409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from purchasing.purchase_document where access_key = ? and voided_at is null",
                Long.class, fixture.accessKey())).isEqualTo(1L);
    }

    @Test
    void cancellingThePurchaseCancelsItsPayablesAndThenVoidingFreesTheAccessKey() throws Exception {
        NfeFixtures fixture = note();
        UUID productId = product("Produto anula " + SEQUENCE.incrementAndGet(), "UNIDADE");
        String body = confirmBody(fixture, decisions(fixture, productId, null), null);
        String first = confirm(owner, body, UUID.randomUUID().toString()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID purchaseId = UUID.fromString(JsonPath.read(first, "$.purchaseId"));

        // Anular documento de compra ativa é recusado: deixaria a compra sem nota e a dívida em pé.
        String documentId = JsonPath.read(api.read(owner, "/api/purchasing/purchases/" + purchaseId)
                .andReturn().getResponse().getContentAsString(), "$.documents[0].id");
        api.send(owner, post("/api/purchasing/documents/" + documentId + "/void"),
                        "{\"reason\":\"tentativa indevida\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_NOT_CANCELLED"));

        api.send(owner, post("/api/purchasing/purchases/" + purchaseId + "/cancel"),
                        "{\"reason\":\"mercadoria nunca chegou\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        // As três contas ficam canceladas, preservadas no histórico.
        assertThat(jdbc.queryForObject("""
                select count(*) from finance.payable p join purchasing.purchase_payable pp on pp.payable_id = p.id
                where pp.purchase_id = ? and p.cancelled_at is not null
                """, Long.class, purchaseId)).isEqualTo(3L);

        api.send(owner, post("/api/purchasing/documents/" + documentId + "/void"),
                        "{\"reason\":\"nota cancelada pelo fornecedor\"}")
                .andExpect(status().isOk());

        // D5: a linha anulada permanece e a chave volta a poder ser importada.
        assertThat(jdbc.queryForObject("select count(*) from purchasing.purchase_document where access_key = ?",
                Long.class, fixture.accessKey())).isEqualTo(1L);
        String reimported = confirm(owner, body, UUID.randomUUID().toString()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(reimported, "$.purchaseId")).isNotEqualTo(purchaseId.toString());
        // Compra nova tem contas novas, não as canceladas da anterior.
        assertThat(jdbc.queryForObject("""
                select count(*) from finance.payable p join purchasing.purchase_payable pp on pp.payable_id = p.id
                where pp.purchase_id = cast(? as uuid) and p.cancelled_at is null
                """, Long.class, JsonPath.read(reimported, "$.purchaseId").toString())).isEqualTo(3L);
    }

    @Test
    void theSameAccessKeyWithDifferentContentIsBlockedAndNeverOverwrites() throws Exception {
        NfeFixtures fixture = note();
        UUID productId = product("Produto hash " + SEQUENCE.incrementAndGet(), "UNIDADE");
        String first = confirm(owner, confirmBody(fixture, decisions(fixture, productId, null), null),
                UUID.randomUUID().toString()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID purchaseId = UUID.fromString(JsonPath.read(first, "$.purchaseId"));
        String documentId = JsonPath.read(api.read(owner, "/api/purchasing/purchases/" + purchaseId)
                .andReturn().getResponse().getContentAsString(), "$.documents[0].id");

        api.send(owner, post("/api/purchasing/purchases/" + purchaseId + "/cancel"), "{\"reason\":\"troca\"}")
                .andExpect(status().isOk());
        api.send(owner, post("/api/purchasing/documents/" + documentId + "/void"), "{\"reason\":\"reemissao\"}")
                .andExpect(status().isOk());

        // Mesma chave, conteúdo diferente: nota alterada. Bloqueio, nunca sobrescrita.
        NfeFixtures altered = note();
        altered.number = fixture.number;
        altered.keyOverride = fixture.accessKey();
        altered.lines = new java.util.ArrayList<>(List.of(
                new NfeFixtures.Line("S-900", "Item trocado", "1.0000", "1943.10", "1943.10")));
        confirm(owner, confirmBody(altered, decisions(altered, productId, null), null),
                UUID.randomUUID().toString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_NFE_CONTENT_DIVERGES"));
    }

    // ================================================================ D7 unidade e D1 custo

    @Test
    void aBoxWithoutAFactorDoesNotConfirmAndWithTheFactorTheQuantityAndCostAreConverted() throws Exception {
        NfeFixtures fixture = singleItemNote("S-CX1", "1.0000", "120.00", "120.00");
        fixture.commercialUnit = "CX";
        UUID productId = product("Produto caixa " + SEQUENCE.incrementAndGet(), "UNIDADE");

        String body = preview(owner, fixture);
        assertThat((Boolean) JsonPath.read(body, "$.items[0].needsFactor")).isTrue();

        confirm(owner, confirmBody(fixture, "[{\"lineNo\":1,\"productId\":\"" + productId + "\"}]", null),
                UUID.randomUUID().toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_UNIT_FACTOR_REQUIRED"));

        String confirmed = confirm(owner, confirmBody(fixture,
                        "[{\"lineNo\":1,\"productId\":\"" + productId + "\",\"unitConversionFactor\":12,\"saveLink\":true}]",
                        null), UUID.randomUUID().toString()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID purchaseId = UUID.fromString(JsonPath.read(confirmed, "$.purchaseId"));

        // 1 CX de 12 vira 12 UNIDADE, e o custo unitário é dividido pelo fator.
        assertThat(jdbc.queryForObject("select quantity from purchasing.purchase_item where purchase_id = ?",
                BigDecimal.class, purchaseId)).isEqualByComparingTo("12.000");
        assertThat(jdbc.queryForObject("select unit_cost from purchasing.purchase_item where purchase_id = ?",
                BigDecimal.class, purchaseId)).isEqualByComparingTo("10.0000");
        // A unidade e a quantidade originais da nota ficam preservadas, senão a conversão não é auditável.
        assertThat(jdbc.queryForObject("select unit_original from purchasing.purchase_item where purchase_id = ?",
                String.class, purchaseId)).isEqualTo("CX");
    }

    @Test
    void theRecordedCostCompositionSumsToTheRecordedTotalAndTheDatabaseEnforcesIt() throws Exception {
        NfeFixtures fixture = singleItemNote("S-CST", "2.0000", "100.00", "200.00");
        fixture.freightTotal = "30.00";
        fixture.ipiTotal = "10.00";
        fixture.invoiceAmount = "240.00";
        fixture.installments = new java.util.ArrayList<>(List.<String[]>of(new String[]{"001", "2026-11-10", "240.00"}));
        UUID productId = product("Produto custo " + SEQUENCE.incrementAndGet(), "UNIDADE");

        String confirmed = confirm(owner, confirmBody(fixture,
                        "[{\"lineNo\":1,\"productId\":\"" + productId + "\",\"saveLink\":true}]", null),
                UUID.randomUUID().toString()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID purchaseId = UUID.fromString(JsonPath.read(confirmed, "$.purchaseId"));

        // O CHECK ck_purchase_item_cost_closes já garantiu isto na gravação; aqui confirmamos o valor.
        assertThat(jdbc.queryForObject("""
                select acquisition_total - (cost_base_amount + cost_ipi_amount + cost_icms_st_amount
                    + cost_icms_normal_amount + cost_freight_share + cost_insurance_share + cost_other_share)
                from purchasing.purchase_item where purchase_id = ?
                """, BigDecimal.class, purchaseId)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("select cost_freight_share from purchasing.purchase_item where purchase_id = ?",
                BigDecimal.class, purchaseId)).isEqualByComparingTo("30.00");
    }

    @Test
    void theFreightAllocationAcrossTheTwelveItemsClosesExactlyWithTheInvoiceFreight() throws Exception {
        NfeFixtures fixture = note();
        fixture.freightTotal = "100.00";
        fixture.invoiceAmount = "2043.10";
        fixture.installments = new java.util.ArrayList<>(List.<String[]>of(new String[]{"001", "2026-11-10", "2043.10"}));
        UUID productId = product("Produto rateio " + SEQUENCE.incrementAndGet(), "UNIDADE");

        String confirmed = confirm(owner, confirmBody(fixture, decisions(fixture, productId, null), null),
                UUID.randomUUID().toString()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID purchaseId = UUID.fromString(JsonPath.read(confirmed, "$.purchaseId"));

        // D2: a soma dos rateios é exatamente o frete da nota, sem centavo sobrando nem faltando.
        assertThat(jdbc.queryForObject(
                "select sum(cost_freight_share) from purchasing.purchase_item where purchase_id = ?",
                BigDecimal.class, purchaseId)).isEqualByComparingTo("100.00");
    }

    // ================================================================ D6 vínculo

    @Test
    void theLinkIsLearnedOnTheFirstConfirmationAndReusedOnTheNextNote() throws Exception {
        NfeFixtures first = singleItemNote("S-LINK", "1.0000", "50.00", "50.00");
        UUID productId = product("Produto vinculo " + SEQUENCE.incrementAndGet(), "UNIDADE");

        confirm(owner, confirmBody(first,
                        "[{\"lineNo\":1,\"productId\":\"" + productId + "\",\"saveLink\":true}]", null),
                UUID.randomUUID().toString()).andExpect(status().isOk());

        // Segunda nota do mesmo fornecedor com o mesmo código: a prévia já vem casada por LINK.
        NfeFixtures second = singleItemNote("S-LINK", "2.0000", "50.00", "100.00");
        String body = preview(owner, second);
        assertThat((String) JsonPath.read(body, "$.items[0].matchedBy")).isEqualTo("LINK");
        assertThat((String) JsonPath.read(body, "$.items[0].productId")).isEqualTo(productId.toString());

        // E confirma sem precisar repetir a decisão.
        confirm(owner, confirmBody(second, "[]", null), UUID.randomUUID().toString())
                .andExpect(status().isOk());
    }

    @Test
    void anItemWithoutALinkBlocksTheConfirmationInsteadOfGuessingByDescription() throws Exception {
        NfeFixtures fixture = singleItemNote("S-NOLINK", "1.0000", "50.00", "50.00");
        product("Item unico parecido " + SEQUENCE.incrementAndGet(), "UNIDADE");

        String body = preview(owner, fixture);
        assertThat((String) JsonPath.read(body, "$.items[0].matchedBy")).isEqualTo("NONE");
        assertThat((Boolean) JsonPath.read(body, "$.confirmable")).isFalse();

        confirm(owner, confirmBody(fixture, "[]", null), UUID.randomUUID().toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_ITEM_UNLINKED"));
    }

    @Test
    void removingAWrongLinkDoesNotRewriteWhatWasAlreadyPurchased() throws Exception {
        NfeFixtures fixture = singleItemNote("S-DEL", "1.0000", "50.00", "50.00");
        UUID productId = product("Produto remove " + SEQUENCE.incrementAndGet(), "UNIDADE");
        String confirmed = confirm(owner, confirmBody(fixture,
                        "[{\"lineNo\":1,\"productId\":\"" + productId + "\",\"saveLink\":true}]", null),
                UUID.randomUUID().toString()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID purchaseId = UUID.fromString(JsonPath.read(confirmed, "$.purchaseId"));
        UUID supplierId = jdbc.queryForObject("select supplier_id from purchasing.purchase where id = ?",
                UUID.class, purchaseId);

        api.send(owner, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/purchasing/suppliers/" + supplierId + "/product-links/S-DEL"), null)
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select count(*) from purchasing.supplier_product_link where supplier_id = ?", Long.class, supplierId))
                .isZero();
        // O item comprado continua apontando para o produto usado na época.
        assertThat(jdbc.queryForObject("select product_id from purchasing.purchase_item where purchase_id = ?",
                UUID.class, purchaseId)).isEqualTo(productId);
    }

    // ================================================================ permissões e auditoria

    @Test
    void financeMayReadPurchasesButNeitherImportsNorCancels() throws Exception {
        NfeFixtures fixture = note();
        UUID productId = product("Produto permissao " + SEQUENCE.incrementAndGet(), "UNIDADE");
        String confirmed = confirm(owner, confirmBody(fixture, decisions(fixture, productId, null), null),
                UUID.randomUUID().toString()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID purchaseId = UUID.fromString(JsonPath.read(confirmed, "$.purchaseId"));

        api.read(finance, "/api/purchasing/purchases").andExpect(status().isOk());
        api.read(finance, "/api/purchasing/purchases/" + purchaseId).andExpect(status().isOk());

        mvc.perform(post("/api/purchasing/nfe/preview").cookie(finance.cookie())
                        .header(finance.csrfHeader(), finance.csrfToken())
                        .contentType(org.springframework.http.MediaType.APPLICATION_XML).content(fixture.xml()))
                .andExpect(status().isForbidden());
        api.send(finance, post("/api/purchasing/purchases/" + purchaseId + "/cancel"), "{\"reason\":\"nao pode\"}")
                .andExpect(status().isForbidden());
        api.send(finance, put("/api/purchasing/settings/COST_INCLUDE_IPI"), "{\"value\":\"false\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnauthenticatedCallerReachesNeitherThePreviewNorThePurchases() throws Exception {
        mvc.perform(get("/api/purchasing/purchases")).andExpect(status().isUnauthorized());
    }

    @Test
    void theAuditTrailRecordsTheImportAndThatPayablesCameFromPurchasingPermission() throws Exception {
        NfeFixtures fixture = note();
        UUID productId = product("Produto auditoria " + SEQUENCE.incrementAndGet(), "UNIDADE");
        String confirmed = confirm(owner, confirmBody(fixture, decisions(fixture, productId, null), null),
                UUID.randomUUID().toString()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID purchaseId = UUID.fromString(JsonPath.read(confirmed, "$.purchaseId"));

        String events = api.read(owner, "/api/purchasing/purchases/" + purchaseId + "/events")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.read(events, "$.items[*].type").toString())
                .contains("IMPORTED", "PAYABLES_REGISTERED");

        // O XML nunca entra no evento: ele fica só em purchase_document.
        assertThat(jdbc.queryForObject("""
                select count(*) from purchasing.purchase_event
                where purchase_id = ? and payload::text like '%<%'
                """, Long.class, purchaseId)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from purchasing.purchase_document where purchase_id = ? and xml_content is not null
                """, Long.class, purchaseId)).isEqualTo(1L);
    }

    @Test
    void theAuditTrailCannotBeRewrittenEvenBySql() throws Exception {
        NfeFixtures fixture = note();
        UUID productId = product("Produto imutavel " + SEQUENCE.incrementAndGet(), "UNIDADE");
        String confirmed = confirm(owner, confirmBody(fixture, decisions(fixture, productId, null), null),
                UUID.randomUUID().toString()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID purchaseId = UUID.fromString(JsonPath.read(confirmed, "$.purchaseId"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "update purchasing.purchase_event set reason = 'alterado' where purchase_id = ?", purchaseId))
                .hasMessageContaining("purchasing_record_is_append_only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "delete from purchasing.purchase where id = ?", purchaseId))
                .hasMessageContaining("purchasing_record_is_append_only");
    }

}
