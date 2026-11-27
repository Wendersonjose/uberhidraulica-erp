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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Compra de mercadoria para estoque no Resumo financeiro, com valores conhecidos e PostgreSQL real.
 *
 * <p>Regressão do defeito em que a conta da NF-e de compra era somada às despesas do período <b>e</b> a mesma
 * peça voltava como custo de peças quando consumida na OS. A regra: a compra é obrigação (Contas a pagar) e saída
 * de caixa quando paga, mas não é despesa operacional; o resultado só sente a peça quando ela é usada.</p>
 *
 * <pre>
 * NF-e: 10 un × R$ 100,00 = R$ 1.000,00, recebida inteira (estoque 10 a custo 100,00)
 * Despesa manual (aluguel): R$ 200,00
 * OS: 3 un consumidas (custo 300,00), serviço aprovado de R$ 950,00
 *
 * faturamento 950,00 − custo de peças 300,00 = lucro bruto 650,00
 * lucro bruto 650,00 − despesas operacionais 200,00 = resultado operacional 450,00
 * (com a dupla contagem: 650,00 − 1.200,00 = −550,00)
 * </pre>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class StockPurchaseResultIntegrationTest {
    private static final String OWNER_EMAIL = "owner-stock-result@example.test";
    private static final String BOOTSTRAP_PASSWORD = "stock-result-bootstrap-password";
    private static final String PASSWORD = "stock-result-operational-password";
    private static final ZoneId WORKSHOP = ZoneId.of("America/Sao_Paulo");
    private static final AtomicInteger SEQUENCE = new AtomicInteger(7000);

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Stock Result");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private ApiSessions api;
    private Session owner;

    @BeforeEach
    void sessions() throws Exception {
        api = new ApiSessions(mvc, PASSWORD);
        owner = api.owner(OWNER_EMAIL, BOOTSTRAP_PASSWORD);
    }

    @Test
    void aStockPurchaseIsAnObligationAndACashOutflowButOnlyTheConsumedPartIsACost() throws Exception {
        String before = dashboard();
        String statementBefore = report("income-statement");
        String byCategoryBefore = report("expenses-by-category");

        // 1. NF-e de 10 unidades a R$ 100,00, recebida inteira: estoque sobe, conta a pagar de R$ 1.000,00 em aberto.
        UUID merchandise = category("Mercadoria NF-e");
        UUID product = product("Bomba hidráulica");
        UUID purchase = importNote(merchandise, product, "10", "100.00", "1000.00");
        String detail = api.read(owner, "/api/purchasing/purchases/" + purchase).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String itemId = JsonPath.read(detail, "$.items[0].id");
        receive(purchase, "{\"lines\":[{\"purchaseItemId\":\"" + itemId + "\",\"quantity\":10}]}").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select quantity from inventory.stock_balance where product_id = ?", BigDecimal.class, product))
                .isEqualByComparingTo("10");
        String purchasePayable = jdbc.queryForObject(
                "select payable_id::text from purchasing.purchase_payable where purchase_id = ?", String.class, purchase);

        // 2. Despesa operacional comum.
        UUID rent = category("Aluguel");
        api.send(owner, post("/api/finance/payables").header("Idempotency-Key", "rent-" + SEQUENCE.incrementAndGet()),
                "{\"description\":\"Aluguel\",\"categoryId\":\"" + rent + "\",\"amount\":\"200.00\",\"dueDate\":\"" + today() + "\"}")
                .andExpect(status().isCreated());

        // 3. OS que consome 3 unidades (baixa na hora do lançamento) e fatura um serviço de R$ 950,00.
        send(put("/api/inventory/settings/write-off"), "{\"mode\":\"ITEM_LAUNCH\"}").andExpect(status().isOk());
        String order = openWorkOrder();
        send(post("/api/work-orders/" + order + "/products"), "{\"productId\":\"" + product + "\",\"quantity\":\"3\"}")
                .andExpect(status().isCreated());
        approvedQuote(order, "Reparo do cilindro", "950.00");
        send(post("/api/work-orders/" + order + "/start-execution"), "").andExpect(status().isOk());
        send(post("/api/work-orders/" + order + "/finish"), "").andExpect(status().isOk());

        String after = dashboard();
        assertThat(delta(before, after, "$.revenue")).isEqualByComparingTo("950.00");
        assertThat(delta(before, after, "$.partsCost")).isEqualByComparingTo("300.00");
        // A compra NÃO é despesa operacional; o aluguel é.
        assertThat(delta(before, after, "$.expensesRegistered")).isEqualByComparingTo("200.00");
        assertThat(delta(before, after, "$.stockPurchasesRegistered")).isEqualByComparingTo("1000.00");
        assertThat(delta(before, after, "$.grossProfit")).isEqualByComparingTo("650.00");
        assertThat(delta(before, after, "$.operatingResult")).isEqualByComparingTo("450.00");
        assertThat(delta(before, after, "$.expensesPaid")).isEqualByComparingTo("0.00");

        // O demonstrativo gerencial (TASK-0024) segue a mesma regra: 950 − 300 − 200 = 450.
        String statement = report("income-statement");
        assertThat(delta(statementBefore, statement, "$.partsCost")).isEqualByComparingTo("300.00");
        assertThat(delta(statementBefore, statement, "$.totalExpenses")).isEqualByComparingTo("200.00");
        assertThat(delta(statementBefore, statement, "$.operatingResult")).isEqualByComparingTo("450.00");
        assertThat(delta(statementBefore, statement, "$.stockPurchasesRegistered")).isEqualByComparingTo("1000.00");
        List<String> statementCategories = JsonPath.read(statement, "$.expenses[*].categoryId");
        assertThat(statementCategories).doesNotContain(merchandise.toString()).contains(rent.toString());

        // Despesas por categoria: a categoria da NF-e não aparece; a compra vem à parte.
        String byCategory = report("expenses-by-category");
        assertThat(delta(byCategoryBefore, byCategory, "$.registered")).isEqualByComparingTo("200.00");
        assertThat(delta(byCategoryBefore, byCategory, "$.stockPurchasesRegistered")).isEqualByComparingTo("1000.00");
        List<String> listedCategories = JsonPath.read(byCategory, "$.categories[*].categoryId");
        assertThat(listedCategories).doesNotContain(merchandise.toString());

        // 4. A obrigação da NF-e continua em Contas a pagar, em aberto, com o valor da nota.
        api.read(owner, "/api/finance/payables/" + purchasePayable).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(1000.00));

        // 5. Pagar a NF-e é saída de caixa, mas não muda o resultado: a peça já entrou como custo ao ser usada.
        String pix = jdbc.queryForObject("select id::text from finance.payment_method where code = 'PIX'", String.class);
        String cashBefore = api.read(owner, "/api/finance/cash-flow?from=" + today() + "&to=" + today()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        api.send(owner, post("/api/finance/payables/" + purchasePayable + "/payments").header("Idempotency-Key", "pay-" + SEQUENCE.incrementAndGet()),
                "{\"amount\":\"1000.00\",\"paymentMethodId\":\"" + pix + "\"}").andExpect(status().isCreated());
        String cashAfter = api.read(owner, "/api/finance/cash-flow?from=" + today() + "&to=" + today()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(delta(cashBefore, cashAfter, "$.realized.outflows")).isEqualByComparingTo("1000.00");

        String paid = dashboard();
        assertThat(delta(after, paid, "$.expensesPaid")).isEqualByComparingTo("1000.00");
        assertThat(delta(after, paid, "$.operatingResult")).isEqualByComparingTo("0.00");
        assertThat(delta(after, paid, "$.expensesRegistered")).isEqualByComparingTo("0.00");
        assertThat(delta(after, paid, "$.stockPurchasesRegistered")).isEqualByComparingTo("0.00");

        // No demonstrativo, o pagamento entra só na visão de caixa.
        String statementPaid = report("income-statement");
        assertThat(delta(statement, statementPaid, "$.cashPaid")).isEqualByComparingTo("1000.00");
        assertThat(delta(statement, statementPaid, "$.operatingResult")).isEqualByComparingTo("0.00");
        String byCategoryPaid = report("expenses-by-category");
        assertThat(delta(byCategory, byCategoryPaid, "$.paid")).isEqualByComparingTo("0.00");
        assertThat(delta(byCategory, byCategoryPaid, "$.stockPurchasesPaid")).isEqualByComparingTo("1000.00");
    }

    @Test
    void aCancelledPurchasePayableLeavesTheStockPurchasesTotal() throws Exception {
        String before = dashboard();
        UUID purchase = importNote(category("Mercadoria cancelada"), product("Filtro"), "2", "50.00", "100.00");
        assertThat(delta(before, dashboard(), "$.stockPurchasesRegistered")).isEqualByComparingTo("100.00");

        jdbc.update("update finance.payable set cancelled_at = now(), cancelled_by = created_by, cancellation_reason = 'teste'"
                + " where id in (select payable_id from purchasing.purchase_payable where purchase_id = ?)", purchase);
        String after = dashboard();
        assertThat(delta(before, after, "$.stockPurchasesRegistered")).isEqualByComparingTo("0.00");
        assertThat(delta(before, after, "$.expensesRegistered")).isEqualByComparingTo("0.00");
    }

    /** A chave "PURCHASE:..." classifica a conta como compra; uma conta manual não pode usá-la. */
    @Test
    void aManualPayableCannotTakeThePurchaseKeyPrefix() throws Exception {
        UUID rent = category("Reservada");
        api.send(owner, post("/api/finance/payables").header("Idempotency-Key", "PURCHASE:" + UUID.randomUUID() + ":1"),
                        "{\"description\":\"Disfarce\",\"categoryId\":\"" + rent + "\",\"amount\":\"10.00\",\"dueDate\":\"" + today() + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_RESERVED"));
        assertThat(jdbc.queryForObject("select count(*) from finance.payable where description = 'Disfarce'", Long.class)).isZero();
    }

    // ================================================================ apoio

    private String dashboard() throws Exception {
        return api.read(owner, "/api/finance/dashboard?from=" + today() + "&to=" + today()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String report(String name) throws Exception {
        return api.read(owner, "/api/finance/reports/" + name + "?from=" + today() + "&to=" + today()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static BigDecimal delta(String before, String after, String path) {
        return number(after, path).subtract(number(before, path));
    }

    private static BigDecimal number(String json, String path) {
        return new BigDecimal(String.valueOf((Object) JsonPath.read(json, path)));
    }

    private UUID importNote(UUID category, UUID product, String quantity, String unitPrice, String total) throws Exception {
        NfeFixtures note = NfeFixtures.valid();
        note.number = SEQUENCE.incrementAndGet();
        note.lines = new ArrayList<>(List.of(new NfeFixtures.Line("SR-" + note.number, "Item estoque", quantity, unitPrice, total)));
        note.installments = new ArrayList<>(List.<String[]>of(new String[]{"001", today().plusDays(30).toString(), total}));
        String body = mvc.perform(post("/api/purchasing/nfe/confirm").cookie(owner.cookie())
                        .header(owner.csrfHeader(), owner.csrfToken()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"xml\":" + quote(note.xml()) + ",\"createSupplier\":true,\"expenseCategoryId\":\"" + category
                                + "\",\"decisions\":[{\"lineNo\":1,\"productId\":\"" + product + "\"}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.purchaseId"));
    }

    private ResultActions receive(UUID purchaseId, String body) throws Exception {
        return mvc.perform(post("/api/purchasing/purchases/" + purchaseId + "/receipts").cookie(owner.cookie())
                .header(owner.csrfHeader(), owner.csrfToken()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID category(String name) throws Exception {
        return UUID.fromString(id(send(post("/api/finance/expense-categories"),
                "{\"name\":\"" + name + " " + SEQUENCE.incrementAndGet() + "\"}").andExpect(status().isCreated())));
    }

    private UUID product(String description) throws Exception {
        return UUID.fromString(id(send(post("/api/products"), "{\"description\":\"" + description + " " + SEQUENCE.incrementAndGet()
                + "\",\"type\":\"PART\",\"unit\":\"UNIDADE\",\"salePrice\":\"180.00\"}").andExpect(status().isCreated())));
    }

    private String openWorkOrder() throws Exception {
        int n = SEQUENCE.incrementAndGet();
        String customer = id(send(post("/api/customers"), "{\"personType\":\"PF\",\"name\":\"Cliente Estoque\",\"phone\":\"34999990000\"}")
                .andExpect(status().isCreated()));
        String vehicle = id(send(post("/api/vehicles"), "{\"customerId\":\"" + customer + "\",\"plate\":\"EST" + n
                + "\",\"manufacturer\":\"Volvo\",\"model\":\"FH\"}").andExpect(status().isCreated()));
        return id(send(post("/api/work-orders"), "{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle
                + "\",\"complaint\":\"Vazamento no cilindro\"}").andExpect(status().isCreated()));
    }

    /** Orçamento aprovado pela API: revisão, apresentação e decisão interna, como na operação real. */
    private void approvedQuote(String order, String description, String price) throws Exception {
        String quote = id(send(post("/api/work-orders/" + order + "/quotes"), "").andExpect(status().isCreated()));
        String draft = send(post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions"), "{\"items\":[{\"description\":\""
                + description + "\",\"quantity\":\"1\",\"unitPrice\":\"" + price + "\"}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        List<String> revisions = JsonPath.read(draft, "$.revisions[*].id");
        String revision = revisions.get(revisions.size() - 1);
        send(post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions/" + revision + "/present"), "").andExpect(status().isOk());
        String content = api.read(owner, "/api/work-orders/" + order + "/quotes/" + quote).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(content, "$.items[*].revisions[?(@.description == '" + description + "')].id");
        send(post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions/" + revision + "/decisions"),
                "{\"contactChannel\":\"PRESENCIAL\",\"authorizedBy\":\"Cliente\",\"decisions\":[{\"itemReference\":\"" + ids.get(0)
                        + "\",\"decision\":\"APPROVE\"}]}").andExpect(status().isOk());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return api.send(owner, request, body);
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static LocalDate today() { return LocalDate.now(WORKSHOP); }

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
}
