package br.com.uberhidraulica.erp.workorder;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DR-0019 (decidida em 2026-11-12, opções A + B) contra PostgreSQL real: o item físico rejeitado pelo cliente devolve
 * a baixa de estoque, o item não aprovado pode ser removido da OS com motivo, e o item reaprovado em revisão posterior
 * volta a ser baixado na finalização.
 *
 * <p>O modo de baixa é o padrão do sistema ({@code ITEM_LAUNCH}): o estoque sai no lançamento do item.</p>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RejectedProductItemIntegrationTest {
    private static final String OWNER_EMAIL = "owner-dr19@example.test";
    private static final String BOOTSTRAP_PASSWORD = "dr19-bootstrap-password";
    private static final String PASSWORD = "dr19-operational-password";
    private static final AtomicInteger SEQUENCE = new AtomicInteger(5000);

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner DR19");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private ApiSessions api;
    private Session owner;

    @BeforeEach
    void session() throws Exception {
        jdbc.update("update inventory.settings set value = 'ITEM_LAUNCH' where key = 'WORK_ORDER_WRITE_OFF'");
        api = new ApiSessions(mvc, PASSWORD);
        owner = api.owner(OWNER_EMAIL, BOOTSTRAP_PASSWORD);
    }

    // ================================================================ opção A: rejeição devolve o estoque

    @Test
    void aRejectedPhysicalItemReturnsItsStockAndIsNotWrittenOffAgainAtFinish() throws Exception {
        String product = productWithStock("Bomba hidráulica", "900.00", "5", "400.00");
        String order = openWorkOrder();
        String physical = addProduct(order, product, "2");
        assertThat(stock(product)).isEqualByComparingTo("3");

        String quote = openQuote(order);
        String revision = presentedRevision(order, quote, linkedItem(physical, "Bomba hidráulica", "900.00") + ","
                + item("Mão de obra", "200.00"));
        decide(order, quote, revision, "Bomba hidráulica", "REJECT").andExpect(status().isOk());
        decide(order, quote, revision, "Mão de obra", "APPROVE").andExpect(status().isOk());

        // A rejeição devolveu a baixa, com movimento próprio que referencia a baixa original.
        assertThat(stock(product)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("""
                select count(*) from inventory.stock_movement r
                join inventory.stock_movement o on o.id = r.reverses_movement_id
                where r.movement_type = 'WORK_ORDER_RETURN' and o.movement_type = 'WORK_ORDER_OUT'
                  and r.work_order_item_id = ?::uuid""", Long.class, physical)).isEqualTo(1);
        String detail = workOrder(order);
        assertThat((String) JsonPath.read(detail, "$.products[0].customerDecision")).isEqualTo("REJECTED");

        // Finalizar não baixa de novo o que o cliente recusou, e o custo de peças da OS é zero.
        send(post("/api/work-orders/" + order + "/start-execution"), "").andExpect(status().isOk());
        send(post("/api/work-orders/" + order + "/finish"), "").andExpect(status().isOk());
        assertThat(stock(product)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("""
                select coalesce(sum(case when movement_type = 'WORK_ORDER_OUT' then quantity else -quantity end), 0)
                from inventory.stock_movement where work_order_id = ?::uuid""", BigDecimal.class, order)).isEqualByComparingTo("0");
    }

    @Test
    void anItemRejectedAndThenApprovedInALaterRevisionIsWrittenOffAgainAtFinishAsASecondCycle() throws Exception {
        String product = productWithStock("Mangueira", "150.00", "4", "60.00");
        String order = openWorkOrder();
        String physical = addProduct(order, product, "1");
        String quote = openQuote(order);
        String revision = presentedRevision(order, quote, linkedItem(physical, "Mangueira", "150.00"));
        decide(order, quote, revision, "Mangueira", "REJECT").andExpect(status().isOk());
        assertThat(stock(product)).isEqualByComparingTo("4");

        // Nova versão comercial do mesmo item, com desconto: o cliente aceita.
        String content = api.read(owner, "/api/work-orders/" + order + "/quotes/" + quote).andReturn().getResponse().getContentAsString();
        String itemId = JsonPath.<List<String>>read(content, "$.items[*].id").get(0);
        String second = presentedRevision(order, quote, "{\"quoteItemId\":\"" + itemId
                + "\",\"description\":\"Mangueira v2\",\"quantity\":\"1\",\"unitPrice\":\"120.00\"}");
        decide(order, quote, second, "Mangueira v2", "APPROVE").andExpect(status().isOk());

        // A decisão do cliente não mexe no estoque (faltar saldo não pode recusar a aprovação): a baixa volta na finalização.
        assertThat((String) JsonPath.read(workOrder(order), "$.products[0].customerDecision")).isEqualTo("APPROVED");
        assertThat(stock(product)).isEqualByComparingTo("4");
        send(post("/api/work-orders/" + order + "/start-execution"), "").andExpect(status().isOk());
        send(post("/api/work-orders/" + order + "/finish"), "").andExpect(status().isOk());

        assertThat(stock(product)).isEqualByComparingTo("3");
        assertThat(jdbc.queryForList("""
                select work_order_cycle from inventory.stock_movement
                where work_order_item_id = ?::uuid and movement_type = 'WORK_ORDER_OUT' order by work_order_cycle""",
                Integer.class, physical)).containsExactly(1, 2);
    }

    // ================================================================ opção B: remoção manual

    @Test
    void anUndecidedItemIsRemovedWithAReasonReturningItsStockAndStayingAsHistory() throws Exception {
        String product = productWithStock("Filtro", "80.00", "3", "30.00");
        String order = openWorkOrder();
        String physical = addProduct(order, product, "1");
        assertThat(stock(product)).isEqualByComparingTo("2");

        remove(order, physical, "ab").andExpect(status().isBadRequest());
        remove(order, physical, "Lançado por engano").andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].removedAt").isNotEmpty())
                .andExpect(jsonPath("$.products[0].removalReason").value("Lançado por engano"));
        assertThat(stock(product)).isEqualByComparingTo("3");

        remove(order, physical, "De novo").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_PRODUCT_ALREADY_REMOVED"));
        remove(order, UUID.randomUUID().toString(), "Não existe").andExpect(status().isNotFound());

        // Item removido não origina item de orçamento novo, e não é baixado na finalização.
        revision(order, openQuote(order), linkedItem(physical, "Filtro", "80.00")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_PRODUCT_NOT_FOUND"));
        assertThat(jdbc.queryForObject("select removed_by is not null from workorder.work_order_product where id = ?::uuid",
                Boolean.class, physical)).isTrue();
    }

    @Test
    void anApprovedItemCannotBeRemovedAndARemovedItemCannotBeApproved() throws Exception {
        String product = productWithStock("Retentor", "50.00", "6", "20.00");
        String order = openWorkOrder();
        String approved = addProduct(order, product, "1");
        String pending = addProduct(order, product, "1");
        String quote = openQuote(order);
        String revision = presentedRevision(order, quote, linkedItem(approved, "Retentor A", "50.00") + ","
                + linkedItem(pending, "Retentor B", "50.00"));
        decide(order, quote, revision, "Retentor A", "APPROVE").andExpect(status().isOk());

        remove(order, approved, "Tentativa de remover item aprovado").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_PRODUCT_APPROVED"));

        // O item ainda não decidido sai da OS; a aprovação que chegar depois é recusada inteira e nada é gravado.
        remove(order, pending, "Cliente desistiu por telefone").andExpect(status().isOk());
        assertThat(stock(product)).isEqualByComparingTo("5");
        decide(order, quote, revision, "Retentor B", "APPROVE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_ITEM_REMOVED_FROM_WORK_ORDER"));
        assertThat(decisionsOf(quote)).as("só a aprovação do Retentor A ficou gravada").isEqualTo(1);
        // Rejeitar o que já foi removido é inofensivo.
        decide(order, quote, revision, "Retentor B", "REJECT").andExpect(status().isOk());
        assertThat(stock(product)).isEqualByComparingTo("5");
    }

    // ================================================================ apoio

    private long decisionsOf(String quote) {
        return jdbc.queryForObject("""
                select count(*) from workshop.quote_decision d
                join workshop.quote_item_revision r on r.id = d.quote_item_revision_id where r.quote_id = ?::uuid""",
                Long.class, quote);
    }

    private BigDecimal stock(String product) {
        return jdbc.queryForObject("select quantity from inventory.stock_balance where product_id = ?::uuid",
                BigDecimal.class, product);
    }

    private String productWithStock(String description, String salePrice, String quantity, String unitCost) throws Exception {
        String product = id(send(post("/api/products"), "{\"description\":\"" + description + " " + SEQUENCE.incrementAndGet()
                + "\",\"type\":\"PART\",\"unit\":\"UNIDADE\",\"salePrice\":\"" + salePrice + "\"}").andExpect(status().isCreated()));
        send(post("/api/inventory/products/" + product + "/entries"),
                "{\"quantity\":\"" + quantity + "\",\"unitCost\":\"" + unitCost + "\",\"reason\":\"Compra\"}")
                .andExpect(status().isCreated());
        return product;
    }

    private String openWorkOrder() throws Exception {
        int n = SEQUENCE.incrementAndGet();
        String customer = id(send(post("/api/customers"), "{\"personType\":\"PF\",\"name\":\"Cliente DR19\",\"phone\":\"34999990000\"}")
                .andExpect(status().isCreated()));
        String vehicle = id(send(post("/api/vehicles"), "{\"customerId\":\"" + customer + "\",\"plate\":\"DRX" + n
                + "\",\"manufacturer\":\"Volvo\",\"model\":\"FH\"}").andExpect(status().isCreated()));
        return id(send(post("/api/work-orders"), "{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle
                + "\",\"complaint\":\"Vazamento no cilindro\"}").andExpect(status().isCreated()));
    }

    private String addProduct(String order, String product, String quantity) throws Exception {
        String content = send(post("/api/work-orders/" + order + "/products"), "{\"productId\":\"" + product
                + "\",\"quantity\":\"" + quantity + "\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(content, "$.products[*].id");
        return ids.get(ids.size() - 1);
    }

    private ResultActions remove(String order, String item, String reason) throws Exception {
        return send(delete("/api/work-orders/" + order + "/products/" + item), "{\"reason\":\"" + reason + "\"}");
    }

    private String workOrder(String order) throws Exception {
        return api.read(owner, "/api/work-orders/" + order).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String openQuote(String order) throws Exception {
        return id(send(post("/api/work-orders/" + order + "/quotes"), "").andExpect(status().isCreated()));
    }

    private ResultActions revision(String order, String quote, String items) throws Exception {
        return send(post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions"), "{\"items\":[" + items + "]}");
    }

    private String presentedRevision(String order, String quote, String items) throws Exception {
        String draft = revision(order, quote, items).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        List<String> revisions = JsonPath.read(draft, "$.revisions[*].id");
        String revision = revisions.get(revisions.size() - 1);
        send(post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions/" + revision + "/present"), "").andExpect(status().isOk());
        return revision;
    }

    private ResultActions decide(String order, String quote, String revision, String description, String decision) throws Exception {
        String content = api.read(owner, "/api/work-orders/" + order + "/quotes/" + quote).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(content, "$.items[*].revisions[?(@.description == '" + description + "')].id");
        return send(post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions/" + revision + "/decisions"),
                "{\"contactChannel\":\"PRESENCIAL\",\"authorizedBy\":\"Cliente\",\"decisions\":[{\"itemReference\":\"" + ids.get(0)
                        + "\",\"decision\":\"" + decision + "\"}]}");
    }

    private static String item(String description, String price) {
        return "{\"description\":\"" + description + "\",\"quantity\":\"1\",\"unitPrice\":\"" + price + "\"}";
    }

    private static String linkedItem(String workOrderProductId, String description, String price) {
        return "{\"workOrderProductId\":\"" + workOrderProductId + "\",\"description\":\"" + description
                + "\",\"quantity\":\"1\",\"unitPrice\":\"" + price + "\"}";
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return api.send(owner, request, body);
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
