package br.com.uberhidraulica.erp.servicecatalog;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TASK-0011: categorias, busca, inativação, grupos de veículos e prioridade de preço. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class Task0011ServiceCatalogIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Services");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> "owner-services@example.test");
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> "services-bootstrap-password");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from workorder.work_order_product");
        jdbc.update("delete from workorder.work_order_service");
        jdbc.update("delete from workorder.work_order");
        jdbc.update("delete from servicecatalog.service_price");
        jdbc.update("delete from servicecatalog.vehicle_group_member");
        jdbc.update("delete from servicecatalog.vehicle_group");
        jdbc.update("delete from servicecatalog.service");
        jdbc.update("delete from servicecatalog.service_category");
        jdbc.update("delete from crm.vehicle_ownership");
        jdbc.update("delete from crm.vehicle");
        jdbc.update("delete from crm.customer");
    }

    @Test
    void newEndpointsRequireAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/api/service-categories")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/vehicle-groups")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/service-categories").with(user("operator")).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/services/{id}/prices/vehicles/{v}", UUID.randomUUID(), UUID.randomUUID()).with(user("operator"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"price\":1}")).andExpect(status().isForbidden());
    }

    @Test
    void createsServiceWithoutDescriptionOrBasePriceAndWithCategory() throws Exception {
        String category = id(send(post("/api/service-categories"), "{\"name\":\"Direção\"}").andExpect(status().isCreated()));
        send(post("/api/service-categories"), "{\"name\":\"DIREÇÃO\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_NAME_ALREADY_EXISTS"));

        send(post("/api/services"), "{\"name\":\"Diagnóstico\",\"categoryId\":\"" + category + "\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.basePrice").doesNotExist())
                .andExpect(jsonPath("$.category").value("Direção"))
                .andExpect(jsonPath("$.active").value(true));
        send(post("/api/services"), "{\"name\":\"Sem categoria válida\",\"categoryId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));

        send(post("/api/service-categories/" + category + "/inactivate"), "").andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        send(post("/api/services"), "{\"name\":\"Nova\",\"categoryId\":\"" + category + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATEGORY_INACTIVE"));
    }

    @Test
    void searchesFiltersAndInactivatesServices() throws Exception {
        String category = id(send(post("/api/service-categories"), "{\"name\":\"Hidráulica\"}"));
        String pump = id(send(post("/api/services"), "{\"name\":\"Troca de bomba\",\"description\":\"Bomba de direção\",\"basePrice\":\"300.00\",\"categoryId\":\"" + category + "\"}"));
        send(post("/api/services"), "{\"name\":\"Alinhamento\",\"basePrice\":\"80.00\"}").andExpect(status().isCreated());
        send(post("/api/services"), "{\"name\":\"Revisão da caixa\",\"description\":\"inclui bomba\"}").andExpect(status().isCreated());

        search("q=BOMBA").andExpect(jsonPath("$.totalItems").value(2));
        search("categoryId=" + category).andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(pump));
        search("size=2").andExpect(jsonPath("$.items[0].name").value("Alinhamento")).andExpect(jsonPath("$.totalPages").value(2));

        send(post("/api/services/" + pump + "/inactivate"), "").andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        send(post("/api/services/" + pump + "/inactivate"), "").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SERVICE_ALREADY_INACTIVE"));
        search("active=false").andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(pump));
        search("active=true").andExpect(jsonPath("$.totalItems").value(2));
        send(post("/api/services/" + pump + "/reactivate"), "").andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        assertThat(jdbc.queryForObject("select count(*) from servicecatalog.service", Long.class)).isEqualTo(3);
    }

    @Test
    void resolvesPriceByVehicleThenGroupThenBaseAndWorkOrderKeepsPracticedValue() throws Exception {
        String customer = createCustomer();
        String truck = createVehicle(customer, "TRK1A11");
        String car = createVehicle(customer, "CAR2B22");
        String other = createVehicle(customer, "OTH3C33");
        String service = id(send(post("/api/services"), "{\"name\":\"Reparo de caixa\",\"basePrice\":\"500.00\"}"));
        String heavy = id(send(post("/api/vehicle-groups"), "{\"name\":\"Pesados\",\"description\":\"Caminhões\"}").andExpect(status().isCreated()));
        String light = id(send(post("/api/vehicle-groups"), "{\"name\":\"Leves\"}"));

        send(put("/api/vehicle-groups/" + heavy + "/vehicles/" + truck), "").andExpect(status().isNoContent());
        send(put("/api/vehicle-groups/" + heavy + "/vehicles/" + car), "").andExpect(status().isNoContent());
        send(put("/api/vehicle-groups/" + light + "/vehicles/" + car), "").andExpect(status().isNoContent());
        mvc.perform(get("/api/vehicle-groups/{id}/vehicles", heavy).with(user("operator"))).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].plate").value("TRK1A11"));
        mvc.perform(get("/api/vehicles/{id}/group", car).with(user("operator"))).andExpect(jsonPath("$.name").value("Leves"));
        mvc.perform(get("/api/vehicle-groups").with(user("operator"))).andExpect(jsonPath("$[1].name").value("Pesados"))
                .andExpect(jsonPath("$[1].vehicleCount").value(1));

        send(put("/api/services/" + service + "/prices/groups/" + heavy), "{\"price\":\"800.00\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleGroupName").value("Pesados"));
        send(put("/api/services/" + service + "/prices/groups/" + heavy), "{\"price\":\"850.00\"}").andExpect(status().isOk());
        String truckPrice = id(send(put("/api/services/" + service + "/prices/vehicles/" + truck), "{\"price\":\"950.00\"}").andExpect(status().isOk()));
        mvc.perform(get("/api/services/{id}/prices", service).with(user("operator"))).andExpect(jsonPath("$.length()").value(2));

        suggestion(service, truck).andExpect(jsonPath("$.price").value(950.00)).andExpect(jsonPath("$.source").value("VEHICLE"));
        send(delete("/api/services/" + service + "/prices/" + truckPrice), "").andExpect(status().isNoContent());
        suggestion(service, truck).andExpect(jsonPath("$.price").value(850.00)).andExpect(jsonPath("$.source").value("GROUP"));
        suggestion(service, other).andExpect(jsonPath("$.price").value(500.00)).andExpect(jsonPath("$.source").value("BASE"));
        send(post("/api/vehicle-groups/" + heavy + "/inactivate"), "").andExpect(status().isOk());
        suggestion(service, truck).andExpect(jsonPath("$.source").value("BASE"));
        send(post("/api/vehicle-groups/" + heavy + "/reactivate"), "").andExpect(status().isOk());

        String order = openOrder(customer, truck);
        addService(order, "{\"serviceId\":\"" + service + "\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.services[0].basePrice").value(850.00))
                .andExpect(jsonPath("$.services[0].priceSource").value("GROUP"));
        String second = openOrder(customer, truck);
        addService(second, "{\"serviceId\":\"" + service + "\",\"price\":\"700.00\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.services[0].basePrice").value(700.00))
                .andExpect(jsonPath("$.services[0].priceSource").value("MANUAL"));

        send(put("/api/services/" + service + "/prices/groups/" + heavy), "{\"price\":\"999.00\"}").andExpect(status().isOk());
        mvc.perform(get("/api/work-orders/{id}", order).with(user("operator"))).andExpect(jsonPath("$.services[0].basePrice").value(850.00));
    }

    @Test
    void workOrderRejectsInactiveServiceAndRequiresPriceWhenNoneApplies() throws Exception {
        String customer = createCustomer();
        String vehicle = createVehicle(customer, "ABC1D23");
        String unpriced = id(send(post("/api/services"), "{\"name\":\"Avaliação\"}"));
        String inactive = id(send(post("/api/services"), "{\"name\":\"Antigo\",\"basePrice\":\"10.00\"}"));
        send(post("/api/services/" + inactive + "/inactivate"), "").andExpect(status().isOk());
        String order = openOrder(customer, vehicle);

        addService(order, "{\"serviceId\":\"" + inactive + "\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SERVICE_INACTIVE"));
        addService(order, "{\"serviceId\":\"" + unpriced + "\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SERVICE_PRICE_REQUIRED"));
        addService(order, "{\"serviceId\":\"" + unpriced + "\",\"price\":\"120.00\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.services[0].priceSource").value("MANUAL"))
                .andExpect(jsonPath("$.services[0].description").doesNotExist());
    }

    @Test
    void groupRulesAndPostgresConstraints() throws Exception {
        String customer = createCustomer();
        String vehicle = createVehicle(customer, "GRP1A11");
        String group = id(send(post("/api/vehicle-groups"), "{\"name\":\"Utilitários\"}"));
        send(post("/api/vehicle-groups"), "{\"name\":\"utilitários\"}").andExpect(status().isConflict());
        send(delete("/api/vehicle-groups/" + group + "/vehicles/" + vehicle), "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VEHICLE_NOT_IN_GROUP"));
        send(put("/api/vehicle-groups/" + group + "/vehicles/" + UUID.randomUUID()), "").andExpect(status().isNotFound());
        send(post("/api/vehicle-groups/" + group + "/inactivate"), "").andExpect(status().isOk());
        send(put("/api/vehicle-groups/" + group + "/vehicles/" + vehicle), "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VEHICLE_GROUP_INACTIVE"));

        String service = id(send(post("/api/services"), "{\"name\":\"Teste\"}"));
        assertThatThrownBy(() -> jdbc.update("insert into servicecatalog.service_price(id,service_id,vehicle_id,vehicle_group_id,price,created_at,updated_at) values (?,?,?,?,1,now(),now())",
                UUID.randomUUID(), UUID.fromString(service), UUID.fromString(vehicle), UUID.fromString(group)))
                .hasMessageContaining("ck_service_price_single_target");
        assertThatThrownBy(() -> jdbc.update("insert into servicecatalog.service_price(id,service_id,vehicle_id,price,created_at,updated_at) values (?,?,?,-1,now(),now())",
                UUID.randomUUID(), UUID.fromString(service), UUID.fromString(vehicle)))
                .hasMessageContaining("ck_service_price_nonnegative");
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body) throws Exception {
        request.with(user("operator")).with(csrf());
        if (!body.isEmpty()) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }

    private ResultActions search(String query) throws Exception {
        return mvc.perform(get("/api/services/search?" + query).with(user("operator"))).andExpect(status().isOk());
    }

    private ResultActions suggestion(String service, String vehicle) throws Exception {
        return mvc.perform(get("/api/services/{id}/price-suggestion?vehicleId={v}", service, vehicle).with(user("operator"))).andExpect(status().isOk());
    }

    private ResultActions addService(String order, String body) throws Exception {
        return send(post("/api/work-orders/" + order + "/services"), body);
    }

    private String createCustomer() throws Exception {
        return id(send(post("/api/customers"), "{\"personType\":\"PF\",\"name\":\"Cliente Serviço\",\"phone\":\"34999990000\"}"));
    }

    private String createVehicle(String customer, String plate) throws Exception {
        return id(send(post("/api/vehicles"), "{\"customerId\":\"" + customer + "\",\"plate\":\"" + plate + "\",\"manufacturer\":\"Volvo\",\"model\":\"FH\"}"));
    }

    private String openOrder(String customer, String vehicle) throws Exception {
        return id(send(post("/api/work-orders"), "{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle + "\",\"entryMileage\":1000}")
                .andExpect(status().isCreated()));
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
