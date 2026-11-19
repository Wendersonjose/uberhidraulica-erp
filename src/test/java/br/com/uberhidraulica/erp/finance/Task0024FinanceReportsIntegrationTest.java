package br.com.uberhidraulica.erp.finance;

import br.com.uberhidraulica.erp.support.ApiSessions;
import br.com.uberhidraulica.erp.support.ApiSessions.Session;
import br.com.uberhidraulica.erp.support.CommercialFixtures;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-0024, etapa A, contra PostgreSQL real: os relatórios fecham com o painel gerencial e com os lançamentos de origem,
 * respeitam estornos e cancelamentos, e só abrem para quem tem FINANCE_VIEW. Nenhum relatório grava.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class Task0024FinanceReportsIntegrationTest {
    private static final String OWNER_EMAIL = "owner-reports@example.test";
    private static final String BOOTSTRAP_PASSWORD = "reports-bootstrap-password";
    private static final String PASSWORD = "reports-operational-password";
    private static final ZoneId WORKSHOP = ZoneId.of("America/Sao_Paulo");

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Reports");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private ApiSessions api;
    private Session owner;
    private int sequence;

    @BeforeEach
    void clean() throws Exception {
        CommercialFixtures.clean(jdbc);
        for (String table : new String[]{"finance.cash_movement", "finance.cash_session", "inventory.stock_movement", "inventory.stock_balance",
                "workorder.work_order_product", "workorder.work_order_service", "workorder.work_order_status_history", "workorder.work_order",
                "crm.vehicle_ownership", "crm.vehicle", "crm.customer", "productcatalog.product", "finance.expense_category"})
            jdbc.update("delete from " + table);
        jdbc.update("update finance.settings set value = '0' where key = 'DEFAULT_RECEIVABLE_DUE_DAYS'");
        jdbc.update("update inventory.settings set value = 'DISABLED' where key = 'WORK_ORDER_WRITE_OFF'");
        jdbc.update("update workorder.status_automation set enabled = true");
        api = new ApiSessions(mvc, PASSWORD);
        owner = api.owner(OWNER_EMAIL, BOOTSTRAP_PASSWORD);
    }

    /** Cenário único que exercita todos os relatórios e prova que eles fecham entre si e com o painel. */
    @Test
    void reportsReconcileWithDashboardAndLedgers() throws Exception {
        send(put("/api/inventory/settings/write-off"), "{\"mode\":\"ITEM_LAUNCH\"}").andExpect(status().isOk());
        String product = id(send(post("/api/products"), "{\"description\":\"Peça Relatório\",\"type\":\"PART\",\"unit\":\"UNIDADE\","
                + "\"salePrice\":\"50.00\"}").andExpect(status().isCreated()));
        send(post("/api/inventory/products/" + product + "/entries"), "{\"quantity\":\"10\",\"unitCost\":\"20.00\",\"reason\":\"Compra inicial\"}")
                .andExpect(status().isCreated());

        // OS A: 300.00, peça baixada 2 x 20.00 = 40.00, desconto 10.00, recebe 150.00 em PIX.
        String orderA = openWorkOrder();
        send(post("/api/work-orders/" + orderA + "/products"), "{\"productId\":\"" + product + "\",\"quantity\":\"2\"}").andExpect(status().isCreated());
        String receivableA = billed(orderA, "Serviço A", "300.00");
        adjust(receivableA, "rep-disc", "DISCOUNT", "10.00", "Desconto de pontualidade").andExpect(status().isCreated());
        receipt(receivableA, "rep-rec-a", "150.00", method("PIX")).andExpect(status().isCreated());

        // OS B: 200.00, acréscimo 5.00, vencida há 40 dias, recebimento estornado (não conta).
        String orderB = openWorkOrder();
        String receivableB = billed(orderB, "Serviço B", "200.00");
        adjust(receivableB, "rep-sur", "SURCHARGE", "5.00", "Taxa de entrega").andExpect(status().isCreated());
        String receiptB = id(receipt(receivableB, "rep-rec-b", "50.00", method("PIX")).andExpect(status().isCreated()));
        api.send(owner, post("/api/finance/receipts/" + receiptB + "/reversal").header("Idempotency-Key", "rep-rev-b"),
                "{\"reason\":\"Pix devolvido\"}").andExpect(status().isCreated());
        jdbc.update("update finance.receivable set due_date = ? where id = ?::uuid", Date.valueOf(today().minusDays(40)), receivableB);

        // Despesas: Aluguel 80.00 pago; Energia 30.00 aberta vencendo em 5 dias; Energia 99.00 cancelada (fora).
        String rent = category("Aluguel");
        String power = category("Energia");
        String paidPayable = payable("rep-p1", "Aluguel novembro", rent, "80.00", today());
        payment(paidPayable, "rep-pay1", "80.00", method("PIX")).andExpect(status().isCreated());
        payable("rep-p2", "Conta de luz", power, "30.00", today().plusDays(5));
        String cancelled = payable("rep-p3", "Luz duplicada", power, "99.00", today().plusDays(5));
        api.send(owner, post("/api/finance/payables/" + cancelled + "/cancel"), "{\"reason\":\"Lançada em dobro\"}").andExpect(status().isOk());

        String period = "?from=" + today() + "&to=" + today();

        // ---- A1: painel ampliado
        api.read(owner, "/api/finance/reports/overview" + period).andExpect(status().isOk())
                .andExpect(jsonPath("$.period.revenue").value(500.00))
                .andExpect(jsonPath("$.period.partsCost").value(40.00))
                .andExpect(jsonPath("$.cash.open").value(false))
                .andExpect(jsonPath("$.receivables.openCount").value(2))
                // A: 300 - 10 - 150 = 140 (a vencer hoje); B: 200 + 5 = 205 (vencida)
                .andExpect(jsonPath("$.receivables.openAmount").value(345.00))
                .andExpect(jsonPath("$.receivables.overdueAmount").value(205.00))
                .andExpect(jsonPath("$.receivables.dueIn7Amount").value(140.00))
                .andExpect(jsonPath("$.payables.openAmount").value(30.00))
                .andExpect(jsonPath("$.payables.dueIn7Count").value(1))
                .andExpect(jsonPath("$.payables.overdueCount").value(0));

        // ---- A2: inadimplência — a soma das faixas é o total em aberto
        String aging = api.read(owner, "/api/finance/reports/aging").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOutstanding").value(345.00))
                .andExpect(jsonPath("$.buckets[0].code").value("A_VENCER")).andExpect(jsonPath("$.buckets[0].amount").value(140.00))
                .andExpect(jsonPath("$.buckets[2].code").value("DE_31_A_60")).andExpect(jsonPath("$.buckets[2].amount").value(205.00))
                .andExpect(jsonPath("$.buckets.length()").value(5))
                .andExpect(jsonPath("$.debtors[0].overdue").value(205.00))
                .andExpect(jsonPath("$.debtors[0].customerName").value("Cliente Relatório"))
                .andReturn().getResponse().getContentAsString();
        List<Number> bucketAmounts = JsonPath.read(aging, "$.buckets[*].amount");
        assertThat(bucketAmounts.stream().mapToDouble(Number::doubleValue).sum()).isEqualTo(345.00);

        // ---- A3: despesas por categoria — cancelada fora; bate com o painel
        api.read(owner, "/api/finance/reports/expenses-by-category" + period).andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(110.00)).andExpect(jsonPath("$.paid").value(80.00))
                .andExpect(jsonPath("$.categories.length()").value(2))
                .andExpect(jsonPath("$.categories[0].categoryName").value("Aluguel"));
        api.read(owner, "/api/finance/dashboard" + period).andExpect(jsonPath("$.expensesRegistered").value(110.00))
                .andExpect(jsonPath("$.expensesPaid").value(80.00));

        // ---- A4: resultado por OS — custo histórico só na OS A
        api.read(owner, "/api/finance/reports/work-orders" + period).andExpect(status().isOk())
                .andExpect(jsonPath("$.workOrders.length()").value(2))
                .andExpect(jsonPath("$.netRevenue").value(495.00))   // 290 + 205
                .andExpect(jsonPath("$.partsCost").value(40.00))
                .andExpect(jsonPath("$.result").value(455.00))
                .andExpect(jsonPath("$.workOrders[?(@.workOrderId == '" + orderA + "')].result").value(250.00))
                .andExpect(jsonPath("$.workOrders[?(@.workOrderId == '" + orderA + "')].received").value(150.00))
                .andExpect(jsonPath("$.workOrders[?(@.workOrderId == '" + orderB + "')].received").value(0.0))
                .andExpect(jsonPath("$.workOrders[?(@.workOrderId == '" + orderB + "')].status").value("VENCIDO"));

        // ---- A5: demonstrativo gerencial — receita 500 - 10 + 5 = 495; - peças 40 = 455; - despesas 110 = 345
        api.read(owner, "/api/finance/reports/income-statement" + period).andExpect(status().isOk())
                .andExpect(jsonPath("$.grossRevenue").value(500.00)).andExpect(jsonPath("$.discounts").value(10.00))
                .andExpect(jsonPath("$.surcharges").value(5.00)).andExpect(jsonPath("$.netRevenue").value(495.00))
                .andExpect(jsonPath("$.partsCost").value(40.00)).andExpect(jsonPath("$.grossProfit").value(455.00))
                .andExpect(jsonPath("$.totalExpenses").value(110.00)).andExpect(jsonPath("$.operatingResult").value(345.00))
                .andExpect(jsonPath("$.operatingMargin").value(69.70))   // 345 / 495 = 69,6969...%
                .andExpect(jsonPath("$.cashReceived").value(150.00)).andExpect(jsonPath("$.cashPaid").value(80.00))
                .andExpect(jsonPath("$.cashResult").value(70.00));

        // ---- A6: listas por vencimento (inclui canceladas, com status)
        String wide = "?from=" + today().minusDays(60) + "&to=" + today().plusDays(30);
        api.read(owner, "/api/finance/reports/receivables" + wide).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].status").value("VENCIDO"));
        api.read(owner, "/api/finance/reports/payables" + wide).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.status == 'CANCELADO')].amount").value(99.00))
                .andExpect(jsonPath("$[?(@.status == 'QUITADO')].paid").value(80.00));
    }

    @Test
    void overviewShowsTheExpectedBalanceOfTheOpenCashSession() throws Exception {
        api.send(owner, post("/api/finance/cash/sessions"), "{\"countedBalance\":\"100.00\",\"differenceReason\":\"Fundo de troco inicial\"}").andExpect(status().isCreated());
        api.send(owner, post("/api/finance/cash/movements/supply").header("Idempotency-Key", "rep-supply"),
                "{\"amount\":\"50.00\",\"reason\":\"Troco\"}").andExpect(status().isCreated());
        api.read(owner, "/api/finance/reports/overview?from=" + today() + "&to=" + today()).andExpect(status().isOk())
                .andExpect(jsonPath("$.cash.open").value(true)).andExpect(jsonPath("$.cash.expectedBalance").value(150.00));
    }

    @Test
    void emptyDatabaseReturnsZerosNotErrors() throws Exception {
        String period = "?from=" + today() + "&to=" + today();
        api.read(owner, "/api/finance/reports/aging").andExpect(status().isOk()).andExpect(jsonPath("$.totalOutstanding").value(0))
                .andExpect(jsonPath("$.buckets.length()").value(5)).andExpect(jsonPath("$.debtors.length()").value(0));
        api.read(owner, "/api/finance/reports/income-statement" + period).andExpect(status().isOk())
                .andExpect(jsonPath("$.netRevenue").value(0)).andExpect(jsonPath("$.operatingMargin").value(0));
        api.read(owner, "/api/finance/reports/work-orders" + period).andExpect(jsonPath("$.workOrders.length()").value(0));
        api.read(owner, "/api/finance/reports/expenses-by-category" + period).andExpect(jsonPath("$.categories.length()").value(0));
    }

    @Test
    void invalidPeriodIsRefused() throws Exception {
        api.read(owner, "/api/finance/reports/income-statement?from=" + today() + "&to=" + today().minusDays(1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
        api.read(owner, "/api/finance/reports/work-orders?from=" + today().minusDays(400) + "&to=" + today())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
    }

    @Test
    void reportsRequireFinanceView() throws Exception {
        // Os três perfis têm FINANCE_VIEW (V18/V20); a negação é por exceção de usuário (DENY), como no IAM.
        Session denied = api.user(owner, "denied-reports@example.test", "GERENTE_ADMINISTRATIVO");
        String deniedId = jdbc.queryForObject("select id::text from iam.app_user where email = 'denied-reports@example.test'", String.class);
        send(put("/api/iam/users/" + deniedId + "/permission-exceptions/FINANCE_VIEW"), "{\"resolution\":\"DENY\"}")
                .andExpect(status().isNoContent());
        Session admin = api.user(owner, "admin-reports@example.test", "GERENTE_ADMINISTRATIVO");
        Session finance = api.user(owner, "finance-reports@example.test", "GERENTE_FINANCEIRO");
        String period = "?from=" + today() + "&to=" + today();
        for (String path : new String[]{"/overview" + period, "/aging", "/expenses-by-category" + period, "/work-orders" + period,
                "/income-statement" + period, "/receivables" + period, "/payables" + period}) {
            api.read(denied, "/api/finance/reports" + path).andExpect(status().isForbidden());
            api.read(admin, "/api/finance/reports" + path).andExpect(status().isOk());
            api.read(finance, "/api/finance/reports" + path).andExpect(status().isOk());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/finance/reports/aging"))
                .andExpect(status().isUnauthorized());
    }

    // ================================================================ apoio

    private String billed(String order, String description, String price) throws Exception {
        String quote = id(send(post("/api/work-orders/" + order + "/quotes"), "").andExpect(status().isCreated()));
        String draft = send(post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions"),
                "{\"items\":[{\"description\":\"" + description + "\",\"quantity\":\"1\",\"unitPrice\":\"" + price + "\"}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        List<String> revisions = JsonPath.read(draft, "$.revisions[*].id");
        String revision = revisions.get(revisions.size() - 1);
        send(post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions/" + revision + "/present"), "").andExpect(status().isOk());
        String content = api.read(owner, "/api/work-orders/" + order + "/quotes/" + quote).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(content, "$.items[*].revisions[?(@.description == '" + description + "')].id");
        send(post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions/" + revision + "/decisions"),
                "{\"contactChannel\":\"PRESENCIAL\",\"authorizedBy\":\"Cliente\",\"decisions\":[{\"itemReference\":\"" + ids.get(0)
                        + "\",\"decision\":\"APPROVE\"}]}").andExpect(status().isOk());
        send(post("/api/work-orders/" + order + "/start-execution"), "").andExpect(status().isOk());
        send(post("/api/work-orders/" + order + "/finish"), "").andExpect(status().isOk());
        return JsonPath.read(api.read(owner, "/api/finance/work-orders/" + order + "/receivable").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String category(String name) throws Exception {
        return id(send(post("/api/finance/expense-categories"), "{\"name\":\"" + name + "\"}").andExpect(status().isCreated()));
    }

    private String payable(String key, String description, String category, String amount, LocalDate due) throws Exception {
        return id(api.send(owner, post("/api/finance/payables").header("Idempotency-Key", key), "{\"description\":\"" + description
                + "\",\"categoryId\":\"" + category + "\",\"amount\":\"" + amount + "\",\"dueDate\":\"" + due + "\"}").andExpect(status().isCreated()));
    }

    private ResultActions receipt(String receivable, String key, String amount, String methodId) throws Exception {
        return api.send(owner, post("/api/finance/receivables/" + receivable + "/receipts").header("Idempotency-Key", key),
                "{\"amount\":\"" + amount + "\",\"paymentMethodId\":\"" + methodId + "\"}");
    }

    private ResultActions adjust(String receivable, String key, String type, String amount, String reason) throws Exception {
        return api.send(owner, post("/api/finance/receivables/" + receivable + "/adjustments").header("Idempotency-Key", key),
                "{\"type\":\"" + type + "\",\"amount\":\"" + amount + "\",\"reason\":\"" + reason + "\"}");
    }

    private ResultActions payment(String payable, String key, String amount, String methodId) throws Exception {
        return api.send(owner, post("/api/finance/payables/" + payable + "/payments").header("Idempotency-Key", key),
                "{\"amount\":\"" + amount + "\",\"paymentMethodId\":\"" + methodId + "\"}");
    }

    private String method(String code) {
        return jdbc.queryForObject("select id::text from finance.payment_method where code = ?", String.class, code);
    }

    private String openWorkOrder() throws Exception {
        sequence++;
        String customer = id(send(post("/api/customers"), "{\"personType\":\"PF\",\"name\":\"Cliente Relatório\",\"phone\":\"34999990000\"}")
                .andExpect(status().isCreated()));
        String vehicle = id(send(post("/api/vehicles"), "{\"customerId\":\"" + customer + "\",\"plate\":\"REL" + (1000 + sequence)
                + "\",\"manufacturer\":\"Volvo\",\"model\":\"FH\"}").andExpect(status().isCreated()));
        return id(send(post("/api/work-orders"), "{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle
                + "\",\"complaint\":\"Revisão hidráulica\"}").andExpect(status().isCreated()));
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body) throws Exception {
        return api.send(owner, request, body);
    }

    private static LocalDate today() { return LocalDate.now(WORKSHOP); }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
