package br.com.uberhidraulica.erp.productcatalog;

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
import org.springframework.test.web.servlet.MvcResult;
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
class ProductCatalogIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Product Test");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> "owner-product@example.test");
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> "product-bootstrap-password");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductCatalogQuery catalog;

    @BeforeEach
    void cleanCatalog() { jdbc.update("delete from productcatalog.product"); }

    @Test
    void endpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/products").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(fluid()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mutationRequiresCsrf() throws Exception {
        mvc.perform(post("/api/products").with(user("operator")).contentType(MediaType.APPLICATION_JSON).content(fluid()))
                .andExpect(status().isForbidden());
    }

    @Test
    void createsConsultsListsAndUpdatesProduct() throws Exception {
        MvcResult created = mvc.perform(post("/api/products").with(user("operator")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(fluid()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value("Óleo ATF Dexron III"))
                .andExpect(jsonPath("$.internalCode").value("ATF-D3"))
                .andExpect(jsonPath("$.unit").value("LITRO"))
                .andExpect(jsonPath("$.type").value("SUPPLY"))
                .andExpect(jsonPath("$.salePrice").value(42.90))
                .andExpect(jsonPath("$.minimumStock").value(20.000))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/products/{id}", id).with(user("operator"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceCost").value(28.50))
                .andExpect(jsonPath("$.category").value("Fluidos"));
        mvc.perform(get("/api/products").with(user("operator"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));

        mvc.perform(put("/api/products/{id}", id).with(user("operator")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Óleo ATF Dexron VI\",\"internalCode\":\"atf-d6\",\"category\":\"Fluidos\","
                                + "\"type\":\"SUPPLY\",\"unit\":\"LITRO\",\"referenceCost\":\"31.00\",\"salePrice\":\"49.90\","
                                + "\"minimumStock\":\"15.500\",\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Óleo ATF Dexron VI"))
                .andExpect(jsonPath("$.internalCode").value("ATF-D6"))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void createsProductWithoutOptionalFieldsAndKeepsItActive() throws Exception {
        mvc.perform(post("/api/products").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Retentor genérico\",\"type\":\"PART\",\"unit\":\"UNIDADE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.internalCode").doesNotExist())
                .andExpect(jsonPath("$.category").doesNotExist())
                .andExpect(jsonPath("$.referenceCost").doesNotExist())
                .andExpect(jsonPath("$.salePrice").doesNotExist())
                .andExpect(jsonPath("$.minimumStock").doesNotExist())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void rejectsInvalidProductAndUnknownId() throws Exception {
        mvc.perform(post("/api/products").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"  \",\"type\":\"PART\",\"unit\":\"UNIDADE\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/products").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Peça\",\"type\":\"PART\",\"unit\":\"UNIDADE\",\"salePrice\":\"-1.00\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/products").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Peça\",\"type\":\"PART\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/products").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Peça\",\"type\":\"PART\",\"unit\":\"CAIXA\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/products").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Peça\",\"type\":\"PART\",\"unit\":\"UNIDADE\",\"internalCode\":\"codigo invalido\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PRODUCT"));
        mvc.perform(get("/api/products/{id}", UUID.randomUUID()).with(user("operator")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void rejectsDuplicatedInternalCodeIgnoringCaseAndSpaces() throws Exception {
        mvc.perform(post("/api/products").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(fluid())).andExpect(status().isCreated());
        mvc.perform(post("/api/products").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Outro fluido\",\"internalCode\":\" atf-d3 \",\"type\":\"SUPPLY\",\"unit\":\"LITRO\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_INTERNAL_CODE_ALREADY_EXISTS"));
        assertThat(jdbc.queryForObject("select count(*) from productcatalog.product", Long.class)).isEqualTo(1);
    }

    @Test
    void publicContractExposesProductWithoutStockBalance() throws Exception {
        MvcResult created = mvc.perform(post("/api/products").with(user("operator")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(fluid())).andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));

        ProductCatalogQuery.ProductReference reference = catalog.product(id).orElseThrow();
        assertThat(reference.description()).isEqualTo("Óleo ATF Dexron III");
        assertThat(reference.internalCode()).isEqualTo("ATF-D3");
        assertThat(reference.unit()).isEqualTo("LITRO");
        assertThat(reference.salePrice()).isEqualByComparingTo("42.90");
        assertThat(reference.active()).isTrue();
        assertThat(catalog.product(UUID.randomUUID())).isEmpty();
    }

    @Test
    void postgresConstraintsRejectInvalidRows() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='6' and success", Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> insert("Bad", null, "PART", "UNIDADE", "-0.01", null, null))
                .hasMessageContaining("ck_product_reference_cost_nonnegative");
        assertThatThrownBy(() -> insert("Bad", null, "PART", "UNIDADE", null, "-0.01", null))
                .hasMessageContaining("ck_product_sale_price_nonnegative");
        assertThatThrownBy(() -> insert("Bad", null, "PART", "UNIDADE", null, null, "-1"))
                .hasMessageContaining("ck_product_minimum_stock_nonnegative");
        assertThatThrownBy(() -> insert("   ", null, "PART", "UNIDADE", null, null, null))
                .hasMessageContaining("ck_product_description_not_blank");
        assertThatThrownBy(() -> insert("Bad", null, "OUTRO", "UNIDADE", null, null, null))
                .hasMessageContaining("ck_product_item_type");
        assertThatThrownBy(() -> insert("Bad", null, "PART", "CAIXA", null, null, null))
                .hasMessageContaining("ck_product_unit");
        assertThatThrownBy(() -> insert("Bad", "minusculo", "PART", "UNIDADE", null, null, null))
                .hasMessageContaining("ck_product_internal_code");

        insert("Item A", "COD1", "PART", "UNIDADE", null, null, null);
        assertThatThrownBy(() -> insert("Item B", "COD1", "PART", "UNIDADE", null, null, null))
                .hasMessageContaining("uq_product_internal_code");
        // Código interno nulo não colide: a unicidade é parcial.
        insert("Item C", null, "PART", "UNIDADE", null, null, null);
        insert("Item D", null, "PART", "UNIDADE", null, null, null);
        assertThat(jdbc.queryForObject("select count(*) from productcatalog.product", Long.class)).isEqualTo(3);
    }

    private void insert(String description, String internalCode, String type, String unit,
                        String referenceCost, String salePrice, String minimumStock) {
        jdbc.update("insert into productcatalog.product"
                        + " (id, description, internal_code, item_type, unit, reference_cost, sale_price, minimum_stock,"
                        + " active, created_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, cast(? as numeric), cast(? as numeric), cast(? as numeric), true, now(), now())",
                UUID.randomUUID(), description, internalCode, type, unit, referenceCost, salePrice, minimumStock);
    }

    private static String fluid() {
        return "{\"description\":\"Óleo ATF Dexron III\",\"internalCode\":\"atf-d3\",\"category\":\"Fluidos\","
                + "\"type\":\"SUPPLY\",\"unit\":\"LITRO\",\"referenceCost\":\"28.50\",\"salePrice\":\"42.90\","
                + "\"minimumStock\":\"20.000\"}";
    }
}
