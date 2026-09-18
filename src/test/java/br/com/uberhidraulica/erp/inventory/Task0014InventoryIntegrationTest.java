package br.com.uberhidraulica.erp.inventory;

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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TASK-0014: saldo, movimentações, custo médio, baixa pela OS e histórico imutável. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class Task0014InventoryIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Inventory");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> "owner-inventory@example.test");
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> "inventory-bootstrap-password");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired br.com.uberhidraulica.erp.inventory.application.InventoryApplicationService inventory;

    private int sequence;

    @BeforeEach
    void clean() {
        jdbc.update("delete from inventory.stock_movement");
        jdbc.update("delete from inventory.stock_balance");
        jdbc.update("delete from workorder.work_order_product");
        jdbc.update("delete from workorder.work_order_service");
        jdbc.update("delete from workorder.work_order_status_history");
        jdbc.update("delete from workorder.work_order");
        jdbc.update("delete from crm.vehicle_ownership");
        jdbc.update("delete from crm.vehicle");
        jdbc.update("delete from crm.customer");
        jdbc.update("delete from productcatalog.product");
        jdbc.update("update inventory.settings set value = 'ITEM_LAUNCH' where key = 'WORK_ORDER_WRITE_OFF'");
    }

    @Test
    void endpointsRequireAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/api/inventory/stock")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/inventory/products/{id}/entries", UUID.randomUUID()).with(user("operator"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":\"1\"}")).andExpect(status().isForbidden());
    }

    @Test
    void entriesKeepWeightedAverageCostAndExitsNeverGoNegative() throws Exception {
        String product = createProduct("Óleo ATF", "LITRO", "20");
        entry(product, "10", "20.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceAfter").value(10.000))
                .andExpect(jsonPath("$.averageCostAfter").value(20.0000));
        entry(product, "10", "30.00").andExpect(status().isCreated()).andExpect(jsonPath("$.averageCostAfter").value(25.0000));
        // Entrada sem custo preserva o médio: custo desconhecido não é custo zero.
        entry(product, "5", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceAfter").value(25.000)).andExpect(jsonPath("$.averageCostAfter").value(25.0000));
        send(post("/api/inventory/products/" + product + "/exits"), "{\"quantity\":\"5\",\"reason\":\"Uso interno\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.balanceAfter").value(20.000))
                .andExpect(jsonPath("$.averageCostAfter").value(25.0000));
        send(post("/api/inventory/products/" + product + "/exits"), "{\"quantity\":\"100\",\"reason\":\"Excesso\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(jdbc.queryForObject("select quantity from inventory.stock_balance where product_id = ?::uuid", java.math.BigDecimal.class, product))
                .isEqualByComparingTo("20.000");
    }

    @Test
    void adjustmentsRequireReasonAndCorrectionIsAlwaysANewMovement() throws Exception {
        String product = createProduct("Retentor", "UNIDADE", null);
        entry(product, "4", "10.00").andExpect(status().isCreated());
        send(post("/api/inventory/products/" + product + "/adjustments"), "{\"quantity\":\"1\",\"direction\":\"OUT\"}")
                .andExpect(status().isBadRequest());
        String adjustment = id(send(post("/api/inventory/products/" + product + "/adjustments"),
                "{\"quantity\":\"1\",\"direction\":\"OUT\",\"reason\":\"Quebra no manuseio\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.balanceAfter").value(3.000)));

        send(post("/api/inventory/movements/" + adjustment + "/reverse"), "{\"reason\":\"Ajuste indevido\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.balanceAfter").value(4.000))
                .andExpect(jsonPath("$.type").value("REVERSAL"));
        send(post("/api/inventory/movements/" + adjustment + "/reverse"), "{\"reason\":\"De novo\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MOVEMENT_ALREADY_REVERSED"));

        mvc.perform(get("/api/inventory/products/{id}/movements", product).with(user("operator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[0].type").value("REVERSAL"));
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movement where product_id = ?::uuid", Long.class, product)).isEqualTo(3);
    }

    @Test
    void workOrderWritesOffOnItemLaunchAndReturnsOnCancellation() throws Exception {
        String product = createProduct("Bomba", "UNIDADE", null);
        entry(product, "2", "500.00").andExpect(status().isCreated());
        String order = openWorkOrder();

        addProduct(order, product, "5").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(jdbc.queryForObject("select count(*) from workorder.work_order_product", Long.class)).isZero();

        addProduct(order, product, "1").andExpect(status().isCreated());
        assertThat(balance(product)).isEqualByComparingTo("1.000");
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movement where movement_type = 'WORK_ORDER_OUT'", Long.class)).isEqualTo(1);

        send(post("/api/work-orders/" + order + "/cancel"), "{\"reason\":\"Cliente desistiu\"}").andExpect(status().isOk());
        assertThat(balance(product)).isEqualByComparingTo("2.000");
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movement where movement_type = 'WORK_ORDER_RETURN'", Long.class)).isEqualTo(1);
    }

    @Test
    void writeOffModeChangesWhenTheStockMovesAndCanBeDisabled() throws Exception {
        send(put("/api/inventory/settings/write-off"), "{\"mode\":\"WORK_ORDER_FINISH\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.WORK_ORDER_WRITE_OFF").value("WORK_ORDER_FINISH"));
        send(put("/api/inventory/settings/write-off"), "{\"mode\":\"QUANDO_DER\"}").andExpect(status().isBadRequest());

        String product = createProduct("Filtro", "UNIDADE", null);
        entry(product, "1", "50.00").andExpect(status().isCreated());
        String order = openWorkOrder();
        addProduct(order, product, "2").andExpect(status().isCreated());
        assertThat(balance(product)).isEqualByComparingTo("1.000");

        send(post("/api/work-orders/" + order + "/start-execution"), "").andExpect(status().isOk());
        send(post("/api/work-orders/" + order + "/finish"), "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat((String) JsonPath.read(mvc.perform(get("/api/work-orders/{id}", order).with(user("operator")))
                .andReturn().getResponse().getContentAsString(), "$.status")).isEqualTo("EM_EXECUCAO");

        entry(product, "1", "50.00").andExpect(status().isCreated());
        send(post("/api/work-orders/" + order + "/finish"), "").andExpect(status().isOk());
        assertThat(balance(product)).isEqualByComparingTo("0.000");

        send(put("/api/inventory/settings/write-off"), "{\"mode\":\"DISABLED\"}").andExpect(status().isOk());
        entry(product, "3", "50.00").andExpect(status().isCreated());
        String other = openWorkOrder();
        addProduct(other, product, "2").andExpect(status().isCreated());
        assertThat(balance(product)).isEqualByComparingTo("3.000");
    }

    @Test
    void stockListingSupportsSearchMinimumFilterAndPackagingUnits() throws Exception {
        String oil = createProduct("Óleo ATF Dexron", "GALAO_5L", "4");
        String seal = createProduct("Retentor pequeno", "UNIDADE", "10");
        createProduct("Graxa", "QUILOGRAMA", null);
        entry(oil, "2", "180.00").andExpect(status().isCreated());
        entry(seal, "20", "12.00").andExpect(status().isCreated());

        stock("").andExpect(jsonPath("$.totalItems").value(3));
        stock("q=retentor").andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].quantity").value(20.000));
        stock("belowMinimum=true").andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].productId").value(oil))
                .andExpect(jsonPath("$.items[0].unit").value("GALAO_5L")).andExpect(jsonPath("$.items[0].belowMinimum").value(true));
        stock("size=2").andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.totalPages").value(2));
        stock("size=2&page=1").andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/inventory/stock?size=101").with(user("operator"))).andExpect(status().isBadRequest());
    }

    @Test
    void postgresGuardsBalanceAdjustmentReasonAndSingleReversal() throws Exception {
        String product = createProduct("Parafuso", "UNIDADE", null);
        entry(product, "1", "1.00").andExpect(status().isCreated());
        UUID productId = UUID.fromString(product);
        assertThatThrownBy(() -> jdbc.update("update inventory.stock_balance set quantity = -1 where product_id = ?", productId))
                .hasMessageContaining("ck_stock_balance_nonnegative");
        assertThatThrownBy(() -> jdbc.update("insert into inventory.stock_movement(id, product_id, movement_type, direction, quantity, balance_after, source_type, occurred_at) "
                + "values (?, ?, 'ADJUSTMENT_OUT', 'OUT', 1, 0, 'MANUAL', now())", UUID.randomUUID(), productId))
                .hasMessageContaining("ck_stock_movement_adjustment_reason");
        UUID movement = jdbc.queryForObject("select id from inventory.stock_movement where product_id = ? limit 1", UUID.class, productId);
        jdbc.update("insert into inventory.stock_movement(id, product_id, movement_type, direction, quantity, balance_after, source_type, occurred_at, reverses_movement_id) "
                + "values (?, ?, 'REVERSAL', 'OUT', 1, 0, 'MANUAL', now(), ?)", UUID.randomUUID(), productId, movement);
        assertThatThrownBy(() -> jdbc.update("insert into inventory.stock_movement(id, product_id, movement_type, direction, quantity, balance_after, source_type, occurred_at, reverses_movement_id) "
                + "values (?, ?, 'REVERSAL', 'OUT', 1, 0, 'MANUAL', now(), ?)", UUID.randomUUID(), productId, movement))
                .hasMessageContaining("uq_stock_movement_reversal");
    }

    /**
     * Duas saidas concorrentes sobre o mesmo produto, com saldo para apenas uma.
     *
     * <p>Mock nao serve aqui: o que impede o consumo alem do saldo e o {@code SELECT ... FOR UPDATE}
     * sobre a linha do saldo no PostgreS\"L. As duas transacoes sao reais e simultaneas.</p>
     */
    @Test
    void concurrentExitsOnTheSameProductCannotConsumeMoreThanTheBalance() throws Exception {
        String product = createProduct("Mangueira", "METRO", null);
        entry(product, "10", "8.00").andExpect(status().isCreated());
        UUID productId = UUID.fromString(product);

        var results = inParallel(2, () ->
                inventory.registerExit(productId, new java.math.BigDecimal("6.000"), "Baixa concorrente"));

        assertThat(results.stream().filter(Outcome::success).count()).isEqualTo(1);
        assertThat(results.stream().filter(outcome -> !outcome.success()).map(Outcome::message))
                .allMatch(message -> message.contains("Saldo insuficiente"));
        assertThat(balance(product)).isEqualByComparingTo("4.000");
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movement where movement_type = 'EXIT'", Long.class)).isEqualTo(1);
    }

    /** Duas baixas concorrentes do mesmo item da OS descontam uma unica vez. */
    @Test
    void concurrentWriteOffsOfTheSameWorkOrderItemDiscountOnlyOnce() throws Exception {
        send(put("/api/inventory/settings/write-off"), "{\"mode\":\"DISABLED\"}").andExpect(status().isOk());
        String product = createProduct("Cilindro", "UNIDADE", null);
        entry(product, "10", "300.00").andExpect(status().isCreated());
        String order = openWorkOrder();
        String item = JsonPath.read(addProduct(order, product, "2").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.products[0].id");

        var results = inParallel(2, () -> {
            inventory.writeOffForWorkOrder(UUID.fromString(order), UUID.fromString(item), UUID.fromString(product),
                    new java.math.BigDecimal("2.000"));
            return null;
        });

        // Uma das duas pode ser recusada pelo indice unico; o que nao pode e descontar duas vezes.
        assertThat(results.stream().filter(outcome -> !outcome.success()).map(Outcome::message))
                .allMatch(message -> message.contains("baixa de estoque registrada"));
        assertThat(balance(product)).isEqualByComparingTo("8.000");
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movement where work_order_item_id = ?::uuid "
                + "and movement_type = 'WORK_ORDER_OUT'", Long.class, item)).isEqualTo(1);
    }

    /**
     * O item baixado no lancamento nao baixa de novo na finalizacao quando o modo muda no meio do
     * caminho. A chave de idempotencia e o item da OS, nao o momento em que a baixa foi disparada.
     */
    @Test
    void changingTheWriteOffModeAfterLaunchDoesNotDiscountTheSameItemTwice() throws Exception {
        String product = createProduct("Vedacao", "UNIDADE", null);
        entry(product, "5", "15.00").andExpect(status().isCreated());
        String order = openWorkOrder();
        addProduct(order, product, "2").andExpect(status().isCreated());
        assertThat(balance(product)).isEqualByComparingTo("3.000");

        send(put("/api/inventory/settings/write-off"), "{\"mode\":\"WORK_ORDER_FINISH\"}").andExpect(status().isOk());
        send(post("/api/work-orders/" + order + "/start-execution"), "").andExpect(status().isOk());
        send(post("/api/work-orders/" + order + "/finish"), "").andExpect(status().isOk());

        assertThat(balance(product)).isEqualByComparingTo("3.000");
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movement where movement_type = 'WORK_ORDER_OUT'", Long.class)).isEqualTo(1);
    }

    /** Repetir a finalizacao nao gera segunda baixa dos mesmos itens. */
    @Test
    void repeatingTheFinishDoesNotDiscountTwice() throws Exception {
        send(put("/api/inventory/settings/write-off"), "{\"mode\":\"WORK_ORDER_FINISH\"}").andExpect(status().isOk());
        String product = createProduct("Anel", "UNIDADE", null);
        entry(product, "6", "4.00").andExpect(status().isCreated());
        String order = openWorkOrder();
        addProduct(order, product, "2").andExpect(status().isCreated());
        send(post("/api/work-orders/" + order + "/start-execution"), "").andExpect(status().isOk());
        send(post("/api/work-orders/" + order + "/finish"), "").andExpect(status().isOk());
        assertThat(balance(product)).isEqualByComparingTo("4.000");

        // A segunda finalizacao e recusada pelo proprio fluxo da OS; o estoque nao pode mudar de todo jeito.
        send(post("/api/work-orders/" + order + "/finish"), "");
        assertThat(balance(product)).isEqualByComparingTo("4.000");
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movement where movement_type = 'WORK_ORDER_OUT'", Long.class)).isEqualTo(1);
    }

    /**
     * Finalizacao com varios itens e tudo-ou-nada: se falta saldo para um, nenhum outro fica
     * baixado pela metade.
     */
    @Test
    void finishingWithOneItemWithoutBalanceLeavesNoPartialMovement() throws Exception {
        send(put("/api/inventory/settings/write-off"), "{\"mode\":\"WORK_ORDER_FINISH\"}").andExpect(status().isOk());
        String plenty = createProduct("Abracadeira", "UNIDADE", null);
        String scarce = createProduct("Bomba especial", "UNIDADE", null);
        entry(plenty, "10", "3.00").andExpect(status().isCreated());
        entry(scarce, "1", "900.00").andExpect(status().isCreated());
        String order = openWorkOrder();
        addProduct(order, plenty, "4").andExpect(status().isCreated());
        addProduct(order, scarce, "3").andExpect(status().isCreated());

        send(post("/api/work-orders/" + order + "/start-execution"), "").andExpect(status().isOk());
        send(post("/api/work-orders/" + order + "/finish"), "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        assertThat(balance(plenty)).isEqualByComparingTo("10.000");
        assertThat(balance(scarce)).isEqualByComparingTo("1.000");
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movement where movement_type = 'WORK_ORDER_OUT'", Long.class)).isZero();
    }

    private record Outcome(boolean success, String message) {}

    /** Dispara chamadas simultaneas, liberadas por uma barreira comum. */
    private List<Outcome> inParallel(int threads, Callable<?> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<Outcome>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++)
                futures.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        action.call();
                        return new Outcome(true, null);
                    } catch (Exception failure) {
                        return new Outcome(false, String.valueOf(failure.getMessage()));
                    }
                }));
            List<Outcome> results = new java.util.ArrayList<>();
            for (Future<Outcome> future : futures) results.add(future.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private java.math.BigDecimal balance(String product) {
        return jdbc.queryForObject("select quantity from inventory.stock_balance where product_id = ?::uuid", java.math.BigDecimal.class, product);
    }

    private ResultActions entry(String product, String quantity, String unitCost) throws Exception {
        return send(post("/api/inventory/products/" + product + "/entries"),
                "{\"quantity\":\"" + quantity + "\"" + (unitCost == null ? "" : ",\"unitCost\":\"" + unitCost + "\"") + ",\"reason\":\"Compra\"}");
    }

    private ResultActions stock(String query) throws Exception {
        return mvc.perform(get("/api/inventory/stock?" + query).with(user("operator"))).andExpect(status().isOk());
    }

    private ResultActions addProduct(String order, String product, String quantity) throws Exception {
        return send(post("/api/work-orders/" + order + "/products"), "{\"productId\":\"" + product + "\",\"quantity\":\"" + quantity + "\"}");
    }

    private String createProduct(String description, String unit, String minimumStock) throws Exception {
        return id(send(post("/api/products"), "{\"description\":\"" + description + "\",\"type\":\"PART\",\"unit\":\"" + unit
                + "\",\"salePrice\":\"100.00\"" + (minimumStock == null ? "" : ",\"minimumStock\":\"" + minimumStock + "\"") + "}")
                .andExpect(status().isCreated()));
    }

    private String openWorkOrder() throws Exception {
        sequence++;
        String customer = id(send(post("/api/customers"), "{\"personType\":\"PF\",\"name\":\"Cliente Estoque\",\"phone\":\"34999990000\"}"));
        String vehicle = id(send(post("/api/vehicles"), "{\"customerId\":\"" + customer + "\",\"plate\":\"EST" + (1000 + sequence)
                + "\",\"manufacturer\":\"Ford\",\"model\":\"Cargo\"}"));
        return id(send(post("/api/work-orders"), "{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle
                + "\",\"complaint\":\"Vazamento\"}").andExpect(status().isCreated()));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.with(user("operator")).with(csrf());
        if (!body.isEmpty()) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
