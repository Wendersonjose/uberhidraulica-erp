package br.com.uberhidraulica.erp.iam;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auditoria de permissões dos módulos: {@code productcatalog}, {@code inventory}, {@code workorder}
 * (exceto {@code finish}, que já exigia {@code FINANCE_BILL}), {@code crm} e {@code servicecatalog} não
 * tinham nenhum {@code @PreAuthorize}. Cobre, para cada permissão nova, pelo menos um caso permitido e um
 * caso negado num endpoint representativo do módulo — não é o objetivo exercitar cada endpoint mutado.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ModulePermissionsIntegrationTest {
    private static final String OWNER_EMAIL = "owner-module-permissions@example.test";
    private static final String BOOTSTRAP_PASSWORD = "module-permissions-bootstrap-password";
    private static final String PASSWORD = "module-permissions-operational-password";

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Module Permissions");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private ApiSessions api;
    private Session owner;
    private Session admin;
    private Session finance;

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
        jdbc.update("delete from inventory.stock_movement");
        jdbc.update("delete from inventory.stock_balance");
        jdbc.update("delete from workorder.work_order_product");
        jdbc.update("delete from workorder.work_order_service");
        jdbc.update("delete from workorder.work_order_status_history");
        jdbc.update("delete from workorder.work_order");
        jdbc.update("delete from servicecatalog.service_price");
        jdbc.update("delete from servicecatalog.service");
        jdbc.update("delete from crm.vehicle_ownership");
        jdbc.update("delete from crm.vehicle");
        jdbc.update("delete from crm.customer");
        jdbc.update("delete from productcatalog.product");

        api = new ApiSessions(mvc, PASSWORD);
        owner = api.owner(OWNER_EMAIL, BOOTSTRAP_PASSWORD);
        admin = api.user(owner, "admin-module-permissions@example.test", "GERENTE_ADMINISTRATIVO");
        finance = api.user(owner, "finance-module-permissions@example.test", "GERENTE_FINANCEIRO");
    }

    // ------------------------------------------------------------------ PRODUCT_MANAGE

    @Test
    void productManageGatesProductCreation() throws Exception {
        api.send(finance, post("/api/products"), productBody("Sem permissão", "10.00", "10.00"))
                .andExpect(status().isForbidden());
        api.send(admin, post("/api/products"), productBody("Com permissão", "10.00", "10.00"))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ PRODUCT_COST_MANAGE

    @Test
    void productCostManageGatesCostAndPriceChangesOnUpdate() throws Exception {
        String productId = id(api.send(admin, post("/api/products"), productBody("Retentor", "10.00", "20.00"))
                .andExpect(status().isCreated()));

        // GERENTE_ADMINISTRATIVO tem PRODUCT_MANAGE mas não PRODUCT_COST_MANAGE: mudar custo/preço é recusado.
        api.send(admin, put("/api/products/{id}", productId), updateBody("Retentor", "15.00", "20.00", true))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRODUCT_COST_MANAGE_REQUIRED"));
        // Mesmo ator, mesmo PUT, mas sem tocar custo/preço: passa, porque PRODUCT_MANAGE já cobre a edição de rotina.
        api.send(admin, put("/api/products/{id}", productId), updateBody("Retentor renomeado", "10.00", "20.00", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Retentor renomeado"));
        // GERENTE_FINANCEIRO tem PRODUCT_COST_MANAGE mas não PRODUCT_MANAGE, que gate o endpoint inteiro:
        // mesmo mudando só a descrição (sem custo/preço), o 403 acontece antes, pela falta de PRODUCT_MANAGE.
        api.send(finance, put("/api/products/{id}", productId), updateBody("Outra descrição", "10.00", "20.00", true))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ INVENTORY_MOVE vs INVENTORY_ADJUST

    @Test
    void inventoryMoveAndInventoryAdjustAreIndependentGates() throws Exception {
        String productId = id(api.send(admin, post("/api/products"), productBody("Mangueira", "5.00", "10.00"))
                .andExpect(status().isCreated()));

        // GERENTE_ADMINISTRATIVO tem INVENTORY_MOVE: consegue registrar entrada.
        api.send(admin, post("/api/inventory/products/{id}/entries", productId),
                        "{\"quantity\":\"10\",\"unitCost\":\"5.00\",\"reason\":\"Compra\"}")
                .andExpect(status().isCreated());
        // Mas não tem INVENTORY_ADJUST: ajuste de estoque é recusado.
        api.send(admin, post("/api/inventory/products/{id}/adjustments", productId),
                        "{\"quantity\":\"1\",\"direction\":\"IN\",\"reason\":\"Contagem\"}")
                .andExpect(status().isForbidden());
        // GERENTE_FINANCEIRO tem INVENTORY_ADJUST mas não INVENTORY_MOVE.
        api.send(finance, post("/api/inventory/products/{id}/entries", productId),
                        "{\"quantity\":\"1\",\"reason\":\"Compra\"}")
                .andExpect(status().isForbidden());
        api.send(finance, post("/api/inventory/products/{id}/adjustments", productId),
                        "{\"quantity\":\"1\",\"direction\":\"IN\",\"reason\":\"Contagem\"}")
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ WORKORDER_MANAGE

    @Test
    void workOrderManageGatesOpeningANewOrder() throws Exception {
        String customer = id(api.send(admin, post("/api/customers"),
                "{\"personType\":\"PF\",\"name\":\"Cliente Permissões\",\"phone\":\"34999990000\"}").andExpect(status().isCreated()));
        String vehicle = id(api.send(admin, post("/api/vehicles"),
                "{\"customerId\":\"" + customer + "\",\"plate\":\"PRM1A11\",\"manufacturer\":\"Ford\",\"model\":\"Cargo\"}")
                .andExpect(status().isCreated()));

        api.send(finance, post("/api/work-orders"),
                        "{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle + "\",\"complaint\":\"Ruído na direção\"}")
                .andExpect(status().isForbidden());
        api.send(admin, post("/api/work-orders"),
                        "{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle + "\",\"complaint\":\"Ruído na direção\"}")
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ CRM_MANAGE

    @Test
    void crmManageGatesCustomerCreation() throws Exception {
        api.send(finance, post("/api/customers"),
                        "{\"personType\":\"PF\",\"name\":\"Cliente Negado\",\"phone\":\"34999990001\"}")
                .andExpect(status().isForbidden());
        api.send(admin, post("/api/customers"),
                        "{\"personType\":\"PF\",\"name\":\"Cliente Permitido\",\"phone\":\"34999990002\"}")
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ SERVICE_MANAGE vs SERVICE_PRICE_MANAGE

    @Test
    void serviceManageAndServicePriceManageAreIndependentGates() throws Exception {
        // GERENTE_ADMINISTRATIVO tem SERVICE_MANAGE: cria o serviço.
        String serviceId = id(api.send(admin, post("/api/services"), "{\"name\":\"Alinhamento\",\"basePrice\":\"100.00\"}")
                .andExpect(status().isCreated()));
        // Mas não tem SERVICE_PRICE_MANAGE: alterar preço por veículo é recusado, mesmo com o veículo inexistente
        // (o gate de permissão intercepta antes de qualquer busca de domínio).
        api.send(admin, put("/api/services/{id}/prices/vehicles/{v}", serviceId, UUID.randomUUID()), "{\"price\":\"120.00\"}")
                .andExpect(status().isForbidden());
        // GERENTE_FINANCEIRO tem SERVICE_PRICE_MANAGE.
        api.send(finance, put("/api/services/{id}/prices/vehicles/{v}", serviceId, UUID.randomUUID()), "{\"price\":\"120.00\"}")
                .andExpect(status().isNotFound()); // permissão concedida: chega ao domínio, que recusa o veículo inexistente.
        // E não tem SERVICE_MANAGE: criar serviço é recusado.
        api.send(finance, post("/api/services"), "{\"name\":\"Sem permissão\",\"basePrice\":\"50.00\"}")
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ QUOTE_MANAGE

    @Test
    void quoteManageGatesQuoteCreation() throws Exception {
        String customer = id(api.send(admin, post("/api/customers"),
                "{\"personType\":\"PF\",\"name\":\"Cliente Orçamento\",\"phone\":\"34999990003\"}").andExpect(status().isCreated()));
        String vehicle = id(api.send(admin, post("/api/vehicles"),
                "{\"customerId\":\"" + customer + "\",\"plate\":\"QOT1A11\",\"manufacturer\":\"Ford\",\"model\":\"Cargo\"}")
                .andExpect(status().isCreated()));
        String order = id(api.send(admin, post("/api/work-orders"),
                        "{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle + "\",\"complaint\":\"Vazamento\"}")
                .andExpect(status().isCreated()));

        api.send(finance, post("/api/work-orders/" + order + "/quotes"), "").andExpect(status().isForbidden());
        api.send(admin, post("/api/work-orders/" + order + "/quotes"), "").andExpect(status().isCreated());
    }

    private static String productBody(String description, String referenceCost, String salePrice) {
        return "{\"description\":\"" + description + "\",\"type\":\"PART\",\"unit\":\"UNIDADE\","
                + "\"referenceCost\":\"" + referenceCost + "\",\"salePrice\":\"" + salePrice + "\"}";
    }

    private static String updateBody(String description, String referenceCost, String salePrice, boolean active) {
        return "{\"description\":\"" + description + "\",\"type\":\"PART\",\"unit\":\"UNIDADE\","
                + "\"referenceCost\":\"" + referenceCost + "\",\"salePrice\":\"" + salePrice + "\",\"active\":" + active + "}";
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
