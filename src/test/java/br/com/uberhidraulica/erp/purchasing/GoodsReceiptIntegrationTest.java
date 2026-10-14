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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-0021, I4: recebimento físico contra PostgreSQL real, provando as decisões aprovadas da DR-0022 (D1, D3, D5).
 *
 * <p>As notas são <b>sintéticas</b> (uma ou duas linhas): servem para provar o recebimento, não a NF-e verdadeira,
 * que tem teste de aceitação próprio. Cada cenário usa produtos e notas próprios, então a classe não depende de
 * limpeza entre testes.</p>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class GoodsReceiptIntegrationTest {
    private static final String OWNER_EMAIL = "owner-receipt@example.test";
    private static final String BOOTSTRAP_PASSWORD = "receipt-bootstrap-password";
    private static final String PASSWORD = "receipt-operational-password";
    private static final AtomicInteger SEQUENCE = new AtomicInteger(9000);

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Receipt");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private ApiSessions api;
    private Session owner;
    private Session admin;
    private Session finance;
    private UUID expenseCategory;

    @BeforeEach
    void sessions() throws Exception {
        api = new ApiSessions(mvc, PASSWORD);
        owner = api.owner(OWNER_EMAIL, BOOTSTRAP_PASSWORD);
        admin = api.user(owner, "admin-receipt@example.test", "GERENTE_ADMINISTRATIVO");
        finance = api.user(owner, "finance-receipt@example.test", "GERENTE_FINANCEIRO");
        expenseCategory = expenseCategory();
    }

    // ================================================================ apoio

    /** Compra importada: id, itens (na ordem das linhas) e produtos. */
    private record Bought(UUID purchaseId, List<UUID> itemIds, List<UUID> productIds, NfeFixtures note) {}

    private NfeFixtures note(String... qtyPriceTotal) {
        NfeFixtures fixture = NfeFixtures.valid();
        fixture.number = SEQUENCE.incrementAndGet();
        fixture.lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < qtyPriceTotal.length; i += 3) {
            fixture.lines.add(new NfeFixtures.Line("R-" + fixture.number + "-" + (i / 3 + 1), "Item recebimento " + (i / 3 + 1),
                    qtyPriceTotal[i], qtyPriceTotal[i + 1], qtyPriceTotal[i + 2]));
            total = total.add(new BigDecimal(qtyPriceTotal[i + 2]));
        }
        fixture.installments = new ArrayList<>(List.<String[]>of(new String[]{"001", "2026-11-10", total.toPlainString()}));
        return fixture;
    }

    /** Importa a nota vinculando cada linha a um produto novo em UNIDADE. */
    private Bought buy(NfeFixtures fixture) throws Exception {
        List<UUID> products = new ArrayList<>();
        StringBuilder decisions = new StringBuilder("[");
        for (int i = 0; i < fixture.lines.size(); i++) {
            UUID product = product("Produto recebimento " + SEQUENCE.incrementAndGet());
            products.add(product);
            if (i > 0) decisions.append(',');
            decisions.append("{\"lineNo\":").append(i + 1).append(",\"productId\":\"").append(product).append("\"}");
        }
        decisions.append(']');
        String body = mvc.perform(post("/api/purchasing/nfe/confirm").cookie(owner.cookie())
                        .header(owner.csrfHeader(), owner.csrfToken()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"xml\":" + quote(fixture.xml()) + ",\"createSupplier\":true,\"expenseCategoryId\":\""
                                + expenseCategory + "\",\"decisions\":" + decisions + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID purchaseId = UUID.fromString(JsonPath.read(body, "$.purchaseId"));
        String detail = api.read(owner, "/api/purchasing/purchases/" + purchaseId).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(detail, "$.items[*].id");
        return new Bought(purchaseId, ids.stream().map(UUID::fromString).toList(), products, fixture);
    }

    private UUID expenseCategory() throws Exception {
        String body = api.send(owner, post("/api/finance/expense-categories"),
                        "{\"name\":\"Peças recebimento " + SEQUENCE.incrementAndGet() + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID product(String description) throws Exception {
        String body = api.send(owner, post("/api/products"),
                        "{\"description\":\"" + description + "\",\"type\":\"PART\",\"unit\":\"UNIDADE\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
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
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }

    private static String line(UUID itemId, String quantity) {
        return "{\"purchaseItemId\":\"" + itemId + "\",\"quantity\":" + quantity + "}";
    }

    private static String line(UUID itemId, String quantity, boolean close, String justification) {
        return "{\"purchaseItemId\":\"" + itemId + "\",\"quantity\":" + quantity + ",\"closeRemaining\":" + close
                + (justification == null ? "" : ",\"justification\":\"" + justification + "\"") + "}";
    }

    private static String receiptBody(String... lines) {
        return "{\"lines\":[" + String.join(",", lines) + "]}";
    }

    private ResultActions receive(Session as, UUID purchaseId, String key, String body) throws Exception {
        var request = post("/api/purchasing/purchases/" + purchaseId + "/receipts").cookie(as.cookie())
                .header(as.csrfHeader(), as.csrfToken()).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }

    private ResultActions reverse(Session as, UUID receiptId, String key, String reason) throws Exception {
        var request = post("/api/purchasing/receipts/" + receiptId + "/reversal").cookie(as.cookie())
                .header(as.csrfHeader(), as.csrfToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}");
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }

    private static UUID idOf(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id"));
    }

    private BigDecimal stock(UUID productId) {
        List<BigDecimal> rows = jdbc.queryForList("select quantity from inventory.stock_balance where product_id = ?",
                BigDecimal.class, productId);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
    }

    private BigDecimal averageCost(UUID productId) {
        return jdbc.queryForObject("select average_cost from inventory.stock_balance where product_id = ?",
                BigDecimal.class, productId);
    }

    private long movements(UUID productId) {
        return jdbc.queryForObject("select count(*) from inventory.stock_movement where product_id = ?", Long.class, productId);
    }

    private String sheet(UUID purchaseId) throws Exception {
        return api.read(owner, "/api/purchasing/purchases/" + purchaseId + "/receiving").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static BigDecimal number(String json, String path) {
        return new BigDecimal(String.valueOf((Object) JsonPath.read(json, path)));
    }

    // ================================================================ D3: pedido não é entrada

    @Test
    void importingTheNoteLeavesEverythingPendingAndTheStockUntouched() throws Exception {
        Bought bought = buy(note("10.0000", "5.00", "50.00"));

        String sheet = sheet(bought.purchaseId());
        assertThat((String) JsonPath.read(sheet, "$.status")).isEqualTo("NONE");
        assertThat(number(sheet, "$.lines[0].ordered")).isEqualByComparingTo("10");
        assertThat(number(sheet, "$.lines[0].received")).isEqualByComparingTo("0");
        assertThat(number(sheet, "$.lines[0].pending")).isEqualByComparingTo("10");
        assertThat((String) JsonPath.read(sheet, "$.lines[0].productUnit")).isEqualTo("UNIDADE");
        assertThat(movements(bought.productIds().get(0))).isZero();
        assertThat(stock(bought.productIds().get(0))).isEqualByComparingTo("0");
    }

    // ================================================================ recebimento parcial e total

    @Test
    void aPartialReceiptEntersOnlyWhatArrivedAtTheAcquisitionCostAndLeavesTheRestPending() throws Exception {
        Bought bought = buy(note("10.0000", "5.00", "50.00"));
        UUID product = bought.productIds().get(0);

        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(), receiptBody(line(bought.itemIds().get(0), "6")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].divergence").value("NONE"))
                .andExpect(jsonPath("$.items[0].stockMovementId").isNotEmpty());

        assertThat(stock(product)).isEqualByComparingTo("6");
        assertThat(averageCost(product)).isEqualByComparingTo("5.0000");
        String sheet = sheet(bought.purchaseId());
        assertThat((String) JsonPath.read(sheet, "$.status")).isEqualTo("PARTIAL");
        assertThat(number(sheet, "$.lines[0].received")).isEqualByComparingTo("6");
        assertThat(number(sheet, "$.lines[0].pending")).isEqualByComparingTo("4");

        // A lista de compras mostra a situação derivada, sem coluna armazenada.
        String list = api.read(owner, "/api/purchasing/purchases?q=" + bought.note().accessKey()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(list, "$.items[0].receiptStatus")).isEqualTo("PARTIAL");

        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(), receiptBody(line(bought.itemIds().get(0), "4")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.items[0].divergence").value("NONE"));
        assertThat(stock(product)).isEqualByComparingTo("10");
        assertThat((String) JsonPath.read(sheet(bought.purchaseId()), "$.status")).isEqualTo("COMPLETE");
        assertThat(jdbc.queryForObject("select count(*) from purchasing.goods_receipt where purchase_id = ?", Long.class,
                bought.purchaseId())).isEqualTo(2L);
    }

    @Test
    void theAverageCostWeightsTheNewEntryAgainstTheExistingBalance() throws Exception {
        Bought bought = buy(note("10.0000", "5.00", "50.00"));
        UUID product = bought.productIds().get(0);
        api.send(owner, post("/api/inventory/products/" + product + "/entries"),
                "{\"quantity\":\"10\",\"unitCost\":\"2.00\",\"reason\":\"Saldo inicial\"}").andExpect(status().isCreated());

        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(), receiptBody(line(bought.itemIds().get(0), "10")))
                .andExpect(status().isCreated());

        // (10 × 2,00 + 10 × 5,00) ÷ 20 = 3,50. O custo vem do item da compra (D1), nunca digitado no recebimento.
        assertThat(stock(product)).isEqualByComparingTo("20");
        assertThat(averageCost(product)).isEqualByComparingTo("3.5000");
        assertThat(jdbc.queryForObject("select unit_cost from inventory.stock_movement where product_id = ? and source_type = 'PURCHASE'",
                BigDecimal.class, product)).isEqualByComparingTo("5.0000");
    }

    @Test
    void receivingNeverTouchesThePayablesBecauseReceiptAndPaymentAreDifferentFacts() throws Exception {
        Bought bought = buy(note("3.0000", "10.00", "30.00"));
        long before = jdbc.queryForObject("select count(*) from purchasing.purchase_payable where purchase_id = ?", Long.class,
                bought.purchaseId());
        BigDecimal amount = jdbc.queryForObject("select sum(amount) from purchasing.purchase_payable where purchase_id = ?",
                BigDecimal.class, bought.purchaseId());

        UUID receipt = idOf(receive(owner, bought.purchaseId(), UUID.randomUUID().toString(),
                receiptBody(line(bought.itemIds().get(0), "3"))).andExpect(status().isCreated()));
        reverse(owner, receipt, UUID.randomUUID().toString(), "Conferência errada").andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select count(*) from purchasing.purchase_payable where purchase_id = ?", Long.class,
                bought.purchaseId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select sum(amount) from purchasing.purchase_payable where purchase_id = ?",
                BigDecimal.class, bought.purchaseId())).isEqualByComparingTo(amount);
        // Nem recebimento nem estorno criam pagamento: a conta segue em aberto.
        assertThat(jdbc.queryForObject("""
                select count(*) from finance.payable_payment s join purchasing.purchase_payable pp on pp.payable_id = s.payable_id
                where pp.purchase_id = ?""", Long.class, bought.purchaseId())).isZero();
    }

    // ================================================================ D3: divergência

    @Test
    void receivingMoreThanPendingNeedsJustificationAndAuthorizationAndEntersWhatPhysicallyArrived() throws Exception {
        Bought bought = buy(note("4.0000", "5.00", "20.00"));
        UUID product = bought.productIds().get(0);
        UUID item = bought.itemIds().get(0);

        // Sem justificativa: recusado, e nada entra no estoque.
        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(), receiptBody(line(item, "5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_DIVERGENCE_JUSTIFICATION_REQUIRED"));
        assertThat(stock(product)).isEqualByComparingTo("0");

        // Com justificativa mas sem a permissão de aceitar divergência: 403.
        revokeDivergenceAcceptance();
        try {
            receive(admin, bought.purchaseId(), UUID.randomUUID().toString(), receiptBody(line(item, "5", false, "Fornecedor enviou a mais")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PURCHASE_DIVERGENCE_NOT_AUTHORIZED"));
        } finally {
            restoreDivergenceAcceptance();
        }
        assertThat(stock(product)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from purchasing.goods_receipt where purchase_id = ?", Long.class,
                bought.purchaseId())).isZero();

        // Com a permissão e a justificativa: entra o que chegou fisicamente (5), e a divergência fica gravada.
        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(), receiptBody(line(item, "5", false, "Fornecedor enviou a mais")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].divergence").value("OVER"))
                .andExpect(jsonPath("$.items[0].justification").value("Fornecedor enviou a mais"))
                .andExpect(jsonPath("$.items[0].acceptedBy").isNotEmpty());
        assertThat(stock(product)).isEqualByComparingTo("5");
        String sheet = sheet(bought.purchaseId());
        assertThat(number(sheet, "$.lines[0].pending")).isEqualByComparingTo("-1");
        assertThat((String) JsonPath.read(sheet, "$.status")).isEqualTo("COMPLETE");
        // A conta a pagar segue a nota, não o recebido.
        assertThat(jdbc.queryForObject("select sum(amount) from purchasing.purchase_payable where purchase_id = ?",
                BigDecimal.class, bought.purchaseId())).isEqualByComparingTo("20.00");
    }

    @Test
    void closingTheRemainderRecordsTheCancelledQuantityAndSettlesTheLineWithoutRewritingTheOrder() throws Exception {
        Bought bought = buy(note("10.0000", "5.00", "50.00"));
        UUID product = bought.productIds().get(0);
        UUID item = bought.itemIds().get(0);

        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(), receiptBody(line(item, "8", true, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_DIVERGENCE_JUSTIFICATION_REQUIRED"));

        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(),
                receiptBody(line(item, "8", true, "Fornecedor sem estoque do restante")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].divergence").value("SHORT_CLOSED"))
                .andExpect(jsonPath("$.items[0].cancelledQuantity").value(2.0));

        assertThat(stock(product)).isEqualByComparingTo("8");
        String sheet = sheet(bought.purchaseId());
        assertThat(number(sheet, "$.lines[0].ordered")).isEqualByComparingTo("10");
        assertThat(number(sheet, "$.lines[0].received")).isEqualByComparingTo("8");
        assertThat(number(sheet, "$.lines[0].closed")).isEqualByComparingTo("2");
        assertThat(number(sheet, "$.lines[0].pending")).isEqualByComparingTo("0");
        assertThat((String) JsonPath.read(sheet, "$.status")).isEqualTo("COMPLETE");
        // O item da compra permanece como foi pedido: planejado × realizado, sem esconder a divergência.
        assertThat(jdbc.queryForObject("select quantity from purchasing.purchase_item where id = ?", BigDecimal.class, item))
                .isEqualByComparingTo("10");

        // Linha encerrada: qualquer quantidade a mais agora é excesso e volta a exigir justificativa.
        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(), receiptBody(line(item, "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_DIVERGENCE_JUSTIFICATION_REQUIRED"));
    }

    @Test
    void aCountableUnitRefusesAFractionalReceivedQuantity() throws Exception {
        Bought bought = buy(note("3.0000", "10.00", "30.00"));
        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(), receiptBody(line(bought.itemIds().get(0), "1.5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_RECEIPT_QUANTITY_INVALID"));
        assertThat(stock(bought.productIds().get(0))).isEqualByComparingTo("0");
    }

    // ================================================================ idempotência e concorrência

    @Test
    void retryingTheSameReceiptReturnsItAndNeverEntersTheStockTwice() throws Exception {
        Bought bought = buy(note("5.0000", "4.00", "20.00"));
        UUID product = bought.productIds().get(0);
        String body = receiptBody(line(bought.itemIds().get(0), "5"));
        String key = UUID.randomUUID().toString();

        UUID first = idOf(receive(owner, bought.purchaseId(), key, body).andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Idempotent-Replay", "false")));
        UUID again = idOf(receive(owner, bought.purchaseId(), key, body).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Idempotent-Replay", "true")));

        assertThat(again).isEqualTo(first);
        assertThat(stock(product)).isEqualByComparingTo("5");
        assertThat(movements(product)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from purchasing.goods_receipt where purchase_id = ?", Long.class,
                bought.purchaseId())).isEqualTo(1L);
    }

    @Test
    void theSameKeyWithDifferentContentIsRefusedAndAMissingKeyIsRequired() throws Exception {
        Bought bought = buy(note("5.0000", "4.00", "20.00"));
        UUID item = bought.itemIds().get(0);
        String key = UUID.randomUUID().toString();

        receive(owner, bought.purchaseId(), key, receiptBody(line(item, "2"))).andExpect(status().isCreated());
        receive(owner, bought.purchaseId(), key, receiptBody(line(item, "3")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURCHASE_IDEMPOTENCY_KEY_REUSED"));
        receive(owner, bought.purchaseId(), null, receiptBody(line(item, "3")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PURCHASE_IDEMPOTENCY_KEY_REQUIRED"));

        assertThat(stock(bought.productIds().get(0))).isEqualByComparingTo("2");
    }

    @Test
    void twoSimultaneousReceiptsOfTheLastBalanceNeverBothPassWithoutAcceptance() throws Exception {
        Bought bought = buy(note("4.0000", "5.00", "20.00"));
        UUID item = bought.itemIds().get(0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String key = UUID.randomUUID().toString();
                Callable<Integer> call = () -> {
                    start.await();
                    return receive(owner, bought.purchaseId(), key, receiptBody(line(item, "4"))).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            for (Future<Integer> f : results) statuses.add(f.get());
        } finally {
            pool.shutdownNow();
        }

        // O primeiro leva o saldo; o segundo, depois do bloqueio da compra, já vê pendente zero: é excesso e,
        // sem justificativa, é recusado. O estoque nunca ganha as 8 unidades.
        assertThat(statuses).containsExactlyInAnyOrder(201, 400);
        assertThat(stock(bought.productIds().get(0))).isEqualByComparingTo("4");
        assertThat(jdbc.queryForObject("select count(*) from purchasing.goods_receipt where purchase_id = ?", Long.class,
                bought.purchaseId())).isEqualTo(1L);
    }

    // ================================================================ atomicidade

    @Test
    void aFailureOnTheSecondLineRollsBackTheWholeReceiptIncludingTheFirstStockEntry() throws Exception {
        Bought bought = buy(note("2.0000", "10.00", "20.00", "3.0000", "10.00", "30.00"));
        UUID secondProduct = bought.productIds().get(1);
        // Inativar o produto depois da compra: a importação aceitou, mas o Estoque recusa entrada em produto inativo.
        api.send(owner, put("/api/products/" + secondProduct),
                        "{\"description\":\"Produto inativo\",\"type\":\"PART\",\"unit\":\"UNIDADE\",\"active\":false}")
                .andExpect(status().isOk());

        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(),
                receiptBody(line(bought.itemIds().get(0), "2"), line(bought.itemIds().get(1), "3")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURCHASE_STOCK_REFUSED"));

        assertThat(stock(bought.productIds().get(0))).isEqualByComparingTo("0");
        assertThat(movements(bought.productIds().get(0))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from purchasing.goods_receipt where purchase_id = ?", Long.class,
                bought.purchaseId())).isZero();
    }

    // ================================================================ D5: estorno e cancelamento

    @Test
    void reversingARestoresTheStockAndLeavesTheOriginalReceiptIntact() throws Exception {
        Bought bought = buy(note("4.0000", "5.00", "20.00"));
        UUID product = bought.productIds().get(0);
        UUID receipt = idOf(receive(owner, bought.purchaseId(), UUID.randomUUID().toString(),
                receiptBody(line(bought.itemIds().get(0), "4"))).andExpect(status().isCreated()));
        assertThat(stock(product)).isEqualByComparingTo("4");

        String key = UUID.randomUUID().toString();
        reverse(owner, receipt, key, "Mercadoria devolvida na conferência").andExpect(status().isCreated())
                .andExpect(jsonPath("$.reversal.reason").value("Mercadoria devolvida na conferência"));

        assertThat(stock(product)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForList("select movement_type from inventory.stock_movement where product_id = ? order by occurred_at, id",
                String.class, product)).containsExactly("ENTRY", "REVERSAL");
        // O recebimento original permanece; o estorno é registro próprio e a situação volta a pendente.
        assertThat(jdbc.queryForObject("select count(*) from purchasing.goods_receipt where id = ?", Long.class, receipt)).isEqualTo(1L);
        String sheet = sheet(bought.purchaseId());
        assertThat((String) JsonPath.read(sheet, "$.status")).isEqualTo("NONE");
        assertThat(number(sheet, "$.lines[0].pending")).isEqualByComparingTo("4");

        // Repetir com a mesma chave devolve o mesmo estorno; outra chave é recusada; o estoque não se mexe.
        reverse(owner, receipt, key, "Mercadoria devolvida na conferência").andExpect(status().isOk());
        reverse(owner, receipt, UUID.randomUUID().toString(), "Outra tentativa").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_RECEIPT_ALREADY_REVERSED"));
        assertThat(stock(product)).isEqualByComparingTo("0");
        assertThat(movements(product)).isEqualTo(2L);

        // Depois do estorno o saldo pode ser recebido de novo normalmente.
        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(), receiptBody(line(bought.itemIds().get(0), "4")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.items[0].divergence").value("NONE"));
        assertThat(stock(product)).isEqualByComparingTo("4");
    }

    @Test
    void aReceiptWhosePartWasAlreadyConsumedCannotBeReversedAndNothingIsReversedHalfway() throws Exception {
        Bought bought = buy(note("2.0000", "10.00", "20.00", "3.0000", "10.00", "30.00"));
        UUID receipt = idOf(receive(owner, bought.purchaseId(), UUID.randomUUID().toString(),
                receiptBody(line(bought.itemIds().get(0), "2"), line(bought.itemIds().get(1), "3"))).andExpect(status().isCreated()));
        // A segunda peça foi toda consumida depois de recebida.
        api.send(owner, post("/api/inventory/products/" + bought.productIds().get(1) + "/exits"),
                "{\"quantity\":\"3\",\"reason\":\"Usada na oficina\"}").andExpect(status().isCreated());

        reverse(owner, receipt, UUID.randomUUID().toString(), "Erro de conferência").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_RECEIPT_STOCK_CONSUMED"));

        // A primeira linha não ficou estornada pela metade, e o recebimento segue ativo.
        assertThat(stock(bought.productIds().get(0))).isEqualByComparingTo("2");
        assertThat(movements(bought.productIds().get(0))).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from purchasing.goods_receipt_reversal where receipt_id = ?", Long.class,
                receipt)).isZero();
    }

    @Test
    void cancellingThePurchaseIsRefusedWhileAReceiptIsActiveAndWorksAfterTheReversal() throws Exception {
        Bought bought = buy(note("4.0000", "5.00", "20.00"));
        UUID receipt = idOf(receive(owner, bought.purchaseId(), UUID.randomUUID().toString(),
                receiptBody(line(bought.itemIds().get(0), "4"))).andExpect(status().isCreated()));

        api.send(owner, post("/api/purchasing/purchases/" + bought.purchaseId() + "/cancel"), "{\"reason\":\"Pedido errado\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURCHASE_HAS_ACTIVE_RECEIPT"));
        assertThat(jdbc.queryForObject("select status from purchasing.purchase where id = ?", String.class, bought.purchaseId()))
                .isEqualTo("APPROVED");

        reverse(owner, receipt, UUID.randomUUID().toString(), "Devolvido ao fornecedor").andExpect(status().isCreated());
        api.send(owner, post("/api/purchasing/purchases/" + bought.purchaseId() + "/cancel"), "{\"reason\":\"Pedido errado\"}")
                .andExpect(status().isOk());

        // Compra cancelada não recebe mais.
        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(), receiptBody(line(bought.itemIds().get(0), "1")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURCHASE_NOT_RECEIVABLE"));
    }

    // ================================================================ validação do pedido

    @Test
    void theReceiptDateCannotBeInTheFutureAndTheDocumentMustBelongToThePurchase() throws Exception {
        Bought bought = buy(note("4.0000", "5.00", "20.00"));
        Bought other = buy(note("1.0000", "5.00", "5.00"));
        UUID item = bought.itemIds().get(0);

        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(),
                "{\"receivedOn\":\"" + LocalDate.now().plusDays(5) + "\",\"lines\":[" + line(item, "1") + "]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PURCHASE_RECEIPT_DATE_INVALID"));

        UUID foreign = jdbc.queryForObject("select id from purchasing.purchase_document where purchase_id = ?", UUID.class,
                other.purchaseId());
        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(),
                "{\"documentId\":\"" + foreign + "\",\"lines\":[" + line(item, "1") + "]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PURCHASE_RECEIPT_DOCUMENT_INVALID"));

        UUID own = jdbc.queryForObject("select id from purchasing.purchase_document where purchase_id = ?", UUID.class,
                bought.purchaseId());
        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(),
                "{\"documentId\":\"" + own + "\",\"notes\":\"Entrega do motoboy\",\"lines\":[" + line(item, "1") + "]}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.documentId").value(own.toString()))
                .andExpect(jsonPath("$.notes").value("Entrega do motoboy"));

        // Item de outra compra, item repetido e recebimento vazio.
        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(), receiptBody(line(other.itemIds().get(0), "1")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PURCHASE_RECEIPT_LINE_INVALID"));
        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(), receiptBody(line(item, "1"), line(item, "1")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PURCHASE_RECEIPT_LINE_INVALID"));
        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(), "{\"lines\":[]}").andExpect(status().isBadRequest());
        receive(owner, bought.purchaseId(), UUID.randomUUID().toString(), receiptBody(line(item, "0")))
                .andExpect(status().isBadRequest());
    }

    // ================================================================ permissões e auditoria

    @Test
    void financeMayReadTheReceivingButNeitherReceivesNorReversesAndAnonymousIsRejected() throws Exception {
        Bought bought = buy(note("4.0000", "5.00", "20.00"));
        UUID receipt = idOf(receive(owner, bought.purchaseId(), UUID.randomUUID().toString(),
                receiptBody(line(bought.itemIds().get(0), "2"))).andExpect(status().isCreated()));

        api.read(finance, "/api/purchasing/purchases/" + bought.purchaseId() + "/receiving").andExpect(status().isOk());
        api.read(finance, "/api/purchasing/purchases/" + bought.purchaseId() + "/receipts").andExpect(status().isOk());
        receive(finance, bought.purchaseId(), UUID.randomUUID().toString(), receiptBody(line(bought.itemIds().get(0), "1")))
                .andExpect(status().isForbidden());
        reverse(finance, receipt, UUID.randomUUID().toString(), "Sem permissão").andExpect(status().isForbidden());
        mvc.perform(get("/api/purchasing/purchases/" + bought.purchaseId() + "/receiving")).andExpect(status().isUnauthorized());
        assertThat(stock(bought.productIds().get(0))).isEqualByComparingTo("2");
    }

    @Test
    void theAuditTrailRecordsTheReceiptAndTheReversalWithTheActor() throws Exception {
        Bought bought = buy(note("4.0000", "5.00", "20.00"));
        UUID receipt = idOf(receive(owner, bought.purchaseId(), UUID.randomUUID().toString(),
                receiptBody(line(bought.itemIds().get(0), "4"))).andExpect(status().isCreated()));
        reverse(owner, receipt, UUID.randomUUID().toString(), "Conferência refeita").andExpect(status().isCreated());

        List<String> types = jdbc.queryForList("select event_type from purchasing.purchase_event where purchase_id = ? order by occurred_at, id",
                String.class, bought.purchaseId());
        assertThat(types).contains("IMPORTED", "RECEIPT_REGISTERED", "RECEIPT_REVERSED");
        UUID ownerId = jdbc.queryForObject("select id from iam.app_user where email = ?", UUID.class, OWNER_EMAIL);
        assertThat(jdbc.queryForObject("select actor_id from purchasing.purchase_event where purchase_id = ? and event_type = 'RECEIPT_REGISTERED'",
                UUID.class, bought.purchaseId())).isEqualTo(ownerId);
        assertThat(jdbc.queryForObject("select reason from purchasing.purchase_event where purchase_id = ? and event_type = 'RECEIPT_REVERSED'",
                String.class, bought.purchaseId())).isEqualTo("Conferência refeita");
    }

    // ================================================================ garantias do banco

    @Test
    void theDatabaseRefusesToRewriteReceiptsAndItsOwnInconsistencies() throws Exception {
        Bought bought = buy(note("4.0000", "5.00", "20.00"));
        UUID receipt = idOf(receive(owner, bought.purchaseId(), UUID.randomUUID().toString(),
                receiptBody(line(bought.itemIds().get(0), "4"))).andExpect(status().isCreated()));

        assertThatThrownBy(() -> jdbc.update("update purchasing.goods_receipt set notes = 'reescrito' where id = ?", receipt))
                .hasMessageContaining("purchasing_record_is_append_only");
        assertThatThrownBy(() -> jdbc.update("delete from purchasing.goods_receipt_item where receipt_id = ?", receipt))
                .hasMessageContaining("purchasing_record_is_append_only");
        assertThatThrownBy(() -> jdbc.update("delete from purchasing.goods_receipt where id = ?", receipt))
                .hasMessageContaining("purchasing_record_is_append_only");

        // Divergência sem justificativa/aceite é recusada pelo próprio banco, mesmo sem a aplicação.
        assertThatThrownBy(() -> jdbc.update("""
                insert into purchasing.goods_receipt_item (id, receipt_id, purchase_item_id, product_id, quantity_received,
                    unit_cost, expected_quantity, divergence_type, cancelled_quantity)
                values (gen_random_uuid(), ?, ?, ?, 9, 5, 4, 'OVER', 0)""",
                receipt, bought.itemIds().get(0), bought.productIds().get(0)))
                .hasMessageContaining("ck_goods_receipt_item_divergence");
        assertThatThrownBy(() -> jdbc.update("""
                insert into inventory.stock_movement (id, product_id, movement_type, direction, quantity, balance_after,
                    source_type, occurred_at)
                values (gen_random_uuid(), ?, 'ENTRY', 'IN', 1, 1, 'PURCHASE', now())""", bought.productIds().get(0)))
                .hasMessageContaining("ck_stock_movement_purchase");

        // Um item recebido gera no máximo uma entrada de estoque.
        UUID receiptItem = jdbc.queryForObject("select id from purchasing.goods_receipt_item where receipt_id = ?", UUID.class, receipt);
        assertThatThrownBy(() -> jdbc.update("""
                insert into inventory.stock_movement (id, product_id, movement_type, direction, quantity, balance_after,
                    source_type, purchase_receipt_item_id, occurred_at)
                values (gen_random_uuid(), ?, 'ENTRY', 'IN', 1, 5, 'PURCHASE', ?, now())""",
                bought.productIds().get(0), receiptItem)).hasMessageContaining("uq_stock_movement_purchase_entry");
    }

    // ================================================================ permissão de aceitar divergência

    /** Retira só a permissão de aceitar divergência do perfil administrativo, para provar o 403 do caso de uso. */
    private void revokeDivergenceAcceptance() {
        jdbc.update("""
                delete from iam.profile_permission
                where profile_id = (select id from iam.profile where code = 'GERENTE_ADMINISTRATIVO')
                  and permission_id = (select id from iam.permission where code = 'PURCHASE_DIVERGENCE_ACCEPT')""");
    }

    private void restoreDivergenceAcceptance() {
        jdbc.update("""
                insert into iam.profile_permission (profile_id, permission_id)
                select p.id, permission.id from iam.profile p cross join iam.permission permission
                where p.code = 'GERENTE_ADMINISTRATIVO' and permission.code = 'PURCHASE_DIVERGENCE_ACCEPT'
                on conflict do nothing""");
    }
}
