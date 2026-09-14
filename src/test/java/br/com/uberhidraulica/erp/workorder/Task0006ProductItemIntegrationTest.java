package br.com.uberhidraulica.erp.workorder;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class Task0006ProductItemIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Product Item");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> "owner-product-item@example.test");
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> "product-item-bootstrap-password");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from workorder.work_order_product");
        jdbc.update("delete from workorder.work_order_service");
        jdbc.update("delete from workorder.work_order");
        jdbc.update("delete from crm.vehicle");
        jdbc.update("delete from crm.customer");
        jdbc.update("delete from productcatalog.product");
    }

    @Test
    void requiresAuthenticationAndCsrf() throws Exception {
        UUID order = UUID.randomUUID();
        mvc.perform(post("/api/work-orders/{id}/products", order).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(quantity(UUID.randomUUID().toString(), "1")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/work-orders/{id}/products", order).with(user("operator"))
                .contentType(MediaType.APPLICATION_JSON).content(quantity(UUID.randomUUID().toString(), "1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void launchesProductOnWorkOrderAndKeepsSnapshotAfterCatalogChange() throws Exception {
        String order = openWorkOrder();
        String product = createProduct("Óleo ATF Dexron III", "ATF-D3", "LITRO", "42.90");

        mvc.perform(addProduct(order, product, "2.500")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].productId").value(product))
                .andExpect(jsonPath("$.products[0].description").value("Óleo ATF Dexron III"))
                .andExpect(jsonPath("$.products[0].internalCode").value("ATF-D3"))
                .andExpect(jsonPath("$.products[0].unit").value("LITRO"))
                .andExpect(jsonPath("$.products[0].quantity").value(2.500))
                .andExpect(jsonPath("$.products[0].unitPrice").value(42.90));

        // Alterar o catálogo depois do lançamento não pode reescrever a OS.
        mvc.perform(put("/api/products/{id}", product).with(user("operator")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Óleo ATF renomeado\",\"internalCode\":\"ATF-NOVO\",\"type\":\"SUPPLY\","
                                + "\"unit\":\"LITRO\",\"salePrice\":\"99.90\",\"active\":false}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/work-orders/{id}", order).with(user("operator"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].description").value("Óleo ATF Dexron III"))
                .andExpect(jsonPath("$.products[0].internalCode").value("ATF-D3"))
                .andExpect(jsonPath("$.products[0].unitPrice").value(42.90));
    }

    @Test
    void acceptsSeveralProductsAndPreservesInclusionOrder() throws Exception {
        String order = openWorkOrder();
        String first = createProduct("Retentor", "RET-1", "UNIDADE", "35.00");
        String second = createProduct("Mangueira", null, "METRO", "18.75");

        mvc.perform(addProduct(order, first, "2")).andExpect(status().isCreated());
        mvc.perform(addProduct(order, second, "1.750")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.products.length()").value(2))
                .andExpect(jsonPath("$.products[0].description").value("Retentor"))
                .andExpect(jsonPath("$.products[1].description").value("Mangueira"))
                .andExpect(jsonPath("$.products[1].internalCode").doesNotExist())
                .andExpect(jsonPath("$.products[1].unit").value("METRO"));
    }

    @Test
    void rejectsUnknownInactiveAndUnpricedProductAndInvalidQuantity() throws Exception {
        String order = openWorkOrder();

        mvc.perform(addProduct(order, UUID.randomUUID().toString(), "1")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

        String unpriced = createProduct("Sem preço", "SEM-PRECO", "UNIDADE", null);
        mvc.perform(addProduct(order, unpriced, "1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_WITHOUT_SALE_PRICE"));

        String inactive = createProduct("Inativo", "INAT", "UNIDADE", "10.00");
        mvc.perform(put("/api/products/{id}", inactive).with(user("operator")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Inativo\",\"internalCode\":\"INAT\",\"type\":\"PART\","
                                + "\"unit\":\"UNIDADE\",\"salePrice\":\"10.00\",\"active\":false}"))
                .andExpect(status().isOk());
        mvc.perform(addProduct(order, inactive, "1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE"));

        String valid = createProduct("Válido", "VAL", "UNIDADE", "10.00");
        mvc.perform(addProduct(order, valid, "0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(addProduct(order, valid, "-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(addProduct(order, valid, "1.0001")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(addProduct(UUID.randomUUID().toString(), valid, "1")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_FOUND"));

        assertThat(jdbc.queryForObject("select count(*) from workorder.work_order_product", Long.class)).isZero();
    }

    @Test
    void postgresConstraintsProtectTheWorkOrderProductSnapshot() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='7' and success", Long.class)).isEqualTo(1);
        String order = openWorkOrder();
        String product = createProduct("Rolamento", "ROL-1", "UNIDADE", "80.00");
        UUID orderId = UUID.fromString(order), productId = UUID.fromString(product);

        assertThatThrownBy(() -> insert(orderId, UUID.randomUUID(), "Fantasma", "UNIDADE", "1", "1"))
                .hasMessageContaining("work_order_product_product_id_fkey");
        assertThatThrownBy(() -> insert(UUID.randomUUID(), productId, "Rolamento", "UNIDADE", "1", "1"))
                .hasMessageContaining("work_order_product_work_order_id_fkey");
        assertThatThrownBy(() -> insert(orderId, productId, "Rolamento", "UNIDADE", "0", "1"))
                .hasMessageContaining("ck_work_order_product_quantity");
        assertThatThrownBy(() -> insert(orderId, productId, "Rolamento", "UNIDADE", "1", "-0.01"))
                .hasMessageContaining("ck_work_order_product_unit_price");
        assertThatThrownBy(() -> insert(orderId, productId, "   ", "UNIDADE", "1", "1"))
                .hasMessageContaining("ck_work_order_product_description");
        assertThatThrownBy(() -> insert(orderId, productId, "Rolamento", "  ", "1", "1"))
                .hasMessageContaining("ck_work_order_product_unit");

        // A OS referenciada por um item físico não pode ser apagada silenciosamente.
        insert(orderId, productId, "Rolamento", "UNIDADE", "1", "80.00");
        assertThatThrownBy(() -> jdbc.update("delete from workorder.work_order where id=?", orderId))
                .hasMessageContaining("work_order_product_work_order_id_fkey");
        assertThat(jdbc.queryForObject("select count(*) from workorder.work_order_product", Long.class)).isEqualTo(1);
    }

    private void insert(UUID orderId, UUID productId, String description, String unit, String quantity, String unitPrice) {
        jdbc.update("insert into workorder.work_order_product"
                        + " (id, work_order_id, product_id, product_description, product_internal_code, unit,"
                        + " quantity, unit_price, added_at)"
                        + " values (?, ?, ?, ?, null, ?, cast(? as numeric), cast(? as numeric), now())",
                UUID.randomUUID(), orderId, productId, description, unit, quantity, unitPrice);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder addProduct(String order, String product, String quantity) {
        return post("/api/work-orders/{id}/products", order).with(user("operator")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(quantity(product, quantity));
    }

    private static String quantity(String product, String value) {
        return "{\"productId\":\"" + product + "\",\"quantity\":" + value + "}";
    }

    private String openWorkOrder() throws Exception {
        String customer = JsonPath.read(mvc.perform(post("/api/customers").with(user("operator")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"personType\":\"PF\",\"name\":\"Cliente Item\",\"document\":\"55566677788\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String vehicle = JsonPath.read(mvc.perform(post("/api/vehicles").with(user("operator")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + customer + "\",\"plate\":\"ITM1A23\",\"manufacturer\":\"Ford\","
                        + "\"model\":\"Cargo\",\"modelYear\":2022,\"mileage\":9000}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        return JsonPath.read(mvc.perform(post("/api/work-orders").with(user("operator")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle + "\",\"entryMileage\":9000}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String createProduct(String description, String internalCode, String unit, String salePrice) throws Exception {
        String body = "{\"description\":\"" + description + "\""
                + (internalCode == null ? "" : ",\"internalCode\":\"" + internalCode + "\"")
                + ",\"type\":\"PART\",\"unit\":\"" + unit + "\""
                + (salePrice == null ? "" : ",\"salePrice\":\"" + salePrice + "\"") + "}";
        return JsonPath.read(mvc.perform(post("/api/products").with(user("operator")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }
}
