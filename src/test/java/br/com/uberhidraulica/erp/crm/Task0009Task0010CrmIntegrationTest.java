package br.com.uberhidraulica.erp.crm;

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

/** TASK-0009 (clientes) e TASK-0010 (veículos) sobre PostgreSQL real. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class Task0009Task0010CrmIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner CRM");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> "owner-crm@example.test");
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> "crm-bootstrap-password");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from workorder.work_order_product");
        jdbc.update("delete from workorder.work_order_service");
        jdbc.update("delete from workorder.work_order");
        jdbc.update("delete from crm.vehicle_ownership");
        jdbc.update("delete from crm.vehicle");
        jdbc.update("delete from crm.customer");
    }

    // ---------------------------------------------------------------- TASK-0009 — clientes

    @Test
    void newEndpointsRequireAuthenticationAndCsrf() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/api/customers/search")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/vehicles/search")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/customers/{id}/inactivate", id).with(user("operator"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/vehicles/{id}/owner", id).with(user("operator")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createsCustomerWithContactAndAddressAndOptionalDocument() throws Exception {
        String body = """
                {"personType":"PF","name":"  Maria Souza ","phone":"(34) 99876-5432","email":"Maria@Example.COM",
                 "address":{"zipCode":"38400-100","street":"Av. Rondon Pacheco","number":"1000","district":"Centro","city":"Uberlândia","state":"mg"}}""";
        String id = id(createCustomer(body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Maria Souza"))
                .andExpect(jsonPath("$.document").doesNotExist())
                .andExpect(jsonPath("$.phone").value("34998765432"))
                .andExpect(jsonPath("$.email").value("maria@example.com"))
                .andExpect(jsonPath("$.address.zipCode").value("38400100"))
                .andExpect(jsonPath("$.address.state").value("MG"))
                .andExpect(jsonPath("$.status").value("ACTIVE")));

        createCustomer("{\"personType\":\"PJ\",\"name\":\"Sem documento Ltda\",\"phone\":\"3432109876\"}").andExpect(status().isCreated());
        mvc.perform(get("/api/customers/{id}", id).with(user("operator"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.address.city").value("Uberlândia"));
        assertThat(jdbc.queryForObject("select count(*) from crm.customer where document is null", Long.class)).isEqualTo(2);
    }

    @Test
    void rejectsMissingPhoneInvalidContactAndIncompatibleDocument() throws Exception {
        createCustomer("{\"personType\":\"PF\",\"name\":\"Sem telefone\"}").andExpect(status().isBadRequest());
        createCustomer("{\"personType\":\"PF\",\"name\":\"Curto\",\"phone\":\"12345\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CUSTOMER"));
        createCustomer("{\"personType\":\"PF\",\"name\":\"Email\",\"phone\":\"34999990000\",\"email\":\"sem-arroba\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CUSTOMER"));
        createCustomer("{\"personType\":\"PF\",\"name\":\"CNPJ em PF\",\"phone\":\"34999990000\",\"document\":\"12345678000190\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CUSTOMER"));
        createCustomer("{\"personType\":\"PF\",\"name\":\"UF\",\"phone\":\"34999990000\",\"address\":{\"state\":\"M1\"}}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from crm.customer", Long.class)).isZero();
    }

    @Test
    void documentStaysUniqueWhenPresentIncludingOnUpdate() throws Exception {
        createCustomer(pf("Primeiro", "111.222.333-44", "34999990001")).andExpect(status().isCreated());
        String second = id(createCustomer(pf("Segundo", null, "34999990002")).andExpect(status().isCreated()));
        createCustomer(pf("Duplicado", "11122233344", "34999990003")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUSTOMER_DOCUMENT_ALREADY_EXISTS"));
        mvc.perform(put("/api/customers/{id}", second).with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(pf("Segundo", "11122233344", "34999990002")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUSTOMER_DOCUMENT_ALREADY_EXISTS"));
    }

    @Test
    void updatesCustomerKeepingStatusAndRequiresPhone() throws Exception {
        String id = id(createCustomer(pf("Original", null, "34999990000")));
        mvc.perform(post("/api/customers/{id}/inactivate", id).with(user("operator")).with(csrf())).andExpect(status().isOk());
        mvc.perform(put("/api/customers/{id}", id).with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personType\":\"PJ\",\"name\":\"Renomeada Ltda\",\"phone\":\"3432101010\",\"document\":\"12.345.678/0001-90\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personType").value("PJ"))
                .andExpect(jsonPath("$.document").value("12345678000190"))
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        mvc.perform(put("/api/customers/{id}", id).with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personType\":\"PJ\",\"name\":\"Sem telefone\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/customers/{id}", UUID.randomUUID()).with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(pf("Inexistente", null, "34999990000")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
    }

    @Test
    void inactivatesAndReactivatesWithoutDeletingHistory() throws Exception {
        String id = id(createCustomer(pf("Cliente Ciclo", null, "34999990000")));
        String vehicle = id(createVehicle(id, "CIC1A23"));
        mvc.perform(post("/api/customers/{id}/inactivate", id).with(user("operator")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
        mvc.perform(post("/api/customers/{id}/inactivate", id).with(user("operator")).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUSTOMER_ALREADY_INACTIVE"));
        mvc.perform(get("/api/customers/{id}/vehicles", id).with(user("operator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(vehicle));
        createVehicle(id, "OUT9Z99").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"));
        mvc.perform(post("/api/customers/{id}/reactivate", id).with(user("operator")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(post("/api/customers/{id}/reactivate", id).with(user("operator")).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUSTOMER_ALREADY_ACTIVE"));
        assertThat(jdbc.queryForObject("select count(*) from crm.customer", Long.class)).isEqualTo(1);
    }

    @Test
    void searchesByNameDocumentPhoneAndPlateWithPaginationAndFilters() throws Exception {
        String ana = id(createCustomer(pf("Ana Hidráulica", "98765432100", "34991112222")));
        createCustomer(pf("Bruno Freios", null, "34993334444")).andExpect(status().isCreated());
        String carla = id(createCustomer("{\"personType\":\"PJ\",\"name\":\"carla transportes\",\"phone\":\"3432225555\"}"));
        createVehicle(carla, "TRK-2B45").andExpect(status().isCreated());
        mvc.perform(post("/api/customers/{id}/inactivate", ana).with(user("operator")).with(csrf())).andExpect(status().isOk());

        search("q=ANA").andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(ana));
        search("q=987.654").andExpect(jsonPath("$.items[0].id").value(ana));
        search("q=333-4444").andExpect(jsonPath("$.items[0].name").value("Bruno Freios"));
        search("q=trk2b").andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(carla));
        search("personType=PJ").andExpect(jsonPath("$.totalItems").value(1));
        search("status=INACTIVE").andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(ana));
        search("size=2&page=0").andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.items[0].name").value("Ana Hidráulica"))
                .andExpect(jsonPath("$.items[1].name").value("Bruno Freios"));
        search("size=2&page=1").andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].name").value("carla transportes"));
        search("q=50%25").andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/customers/search?size=101").with(user("operator"))).andExpect(status().isBadRequest());
    }

    @Test
    void postgresKeepsDocumentUniqueAndValidatesContactColumns() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version in ('11','12') and success", Long.class)).isEqualTo(2);
        jdbc.update("insert into crm.customer(id,person_type,name,document,status,created_at,updated_at) values (?, 'PF', 'Legado', null, 'ACTIVE', now(), now())", UUID.randomUUID());
        jdbc.update("insert into crm.customer(id,person_type,name,document,status,created_at,updated_at) values (?, 'PF', 'Legado 2', null, 'ACTIVE', now(), now())", UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("insert into crm.customer(id,person_type,name,phone,status,created_at,updated_at) values (?, 'PF', 'Fone', '12ab', 'ACTIVE', now(), now())", UUID.randomUUID()))
                .hasMessageContaining("ck_crm_customer_phone");
        assertThatThrownBy(() -> jdbc.update("insert into crm.customer(id,person_type,name,document,status,created_at,updated_at) values (?, 'PJ', 'Doc', '12345678901', 'ACTIVE', now(), now())", UUID.randomUUID()))
                .hasMessageContaining("ck_crm_customer_document");
    }

    // ---------------------------------------------------------------- TASK-0010 — veículos

    @Test
    void createsVehicleWithOptionalYearColorNotesAndOpensOwnership() throws Exception {
        String customer = id(createCustomer(pf("Dono", null, "34999990000")));
        String vehicle = id(createVehicle(customer, "{\"customerId\":\"" + customer + "\",\"plate\":\"abc-1d23\",\"manufacturer\":\"Volvo\",\"model\":\"FH 540\",\"color\":\"Branco\",\"notes\":\"Caixa ZF\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plate").value("ABC1D23"))
                .andExpect(jsonPath("$.modelYear").doesNotExist())
                .andExpect(jsonPath("$.color").value("Branco"))
                .andExpect(jsonPath("$.active").value(true)));
        mvc.perform(get("/api/vehicles/{id}/ownership-history", vehicle).with(user("operator"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].customerName").value("Dono"))
                .andExpect(jsonPath("$[0].endedAt").doesNotExist());
        createVehicle(customer, "{\"customerId\":\"" + customer + "\",\"plate\":\"XYZ9999\",\"manufacturer\":\"Volvo\",\"model\":\"FH\",\"modelYear\":1800}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VEHICLE"));
        createVehicle(customer, "ABC1D23").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VEHICLE_PLATE_ALREADY_EXISTS"));
    }

    @Test
    void transfersOwnerPreservingHistoryAndExistingWorkOrders() throws Exception {
        String first = id(createCustomer(pf("Primeiro Dono", null, "34999990001")));
        String second = id(createCustomer(pf("Segundo Dono", null, "34999990002")));
        String vehicle = id(createVehicle(first, "TRF1A11"));
        String order = JsonPath.read(mvc.perform(post("/api/work-orders").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + first + "\",\"vehicleId\":\"" + vehicle + "\",\"entryMileage\":1000}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        transfer(vehicle, second).andExpect(status().isOk()).andExpect(jsonPath("$.customerId").value(second));
        transfer(vehicle, second).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VEHICLE_ALREADY_OWNED_BY_CUSTOMER"));
        transfer(vehicle, UUID.randomUUID().toString()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));

        mvc.perform(get("/api/vehicles/{id}/ownership-history", vehicle).with(user("operator"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].customerId").value(first))
                .andExpect(jsonPath("$[0].endedAt").exists())
                .andExpect(jsonPath("$[1].customerId").value(second))
                .andExpect(jsonPath("$[1].endedAt").doesNotExist());
        mvc.perform(get("/api/work-orders/{id}", order).with(user("operator"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(first));
        mvc.perform(get("/api/work-orders?vehicleId=" + vehicle).with(user("operator"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(order));
        mvc.perform(get("/api/work-orders?customerId=" + second).with(user("operator"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/customers/{id}/vehicles", second).with(user("operator"))).andExpect(jsonPath("$[0].id").value(vehicle));
        mvc.perform(get("/api/customers/{id}/vehicles", first).with(user("operator"))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void updatesInactivatesReactivatesAndSearchesVehicles() throws Exception {
        String owner = id(createCustomer(pf("Transportadora Silva", null, "34999990000")));
        String other = id(createCustomer(pf("Pedro", null, "34999990009")));
        String scania = id(createVehicle(owner, "{\"customerId\":\"" + owner + "\",\"plate\":\"SCA1N00\",\"manufacturer\":\"Scania\",\"model\":\"R 450\",\"modelYear\":2021}"));
        createVehicle(other, "{\"customerId\":\"" + other + "\",\"plate\":\"MBZ2C11\",\"manufacturer\":\"Mercedes-Benz\",\"model\":\"Atego\"}").andExpect(status().isCreated());

        mvc.perform(put("/api/vehicles/{id}", scania).with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plate\":\"SCA-1N00\",\"manufacturer\":\"Scania\",\"model\":\"R 500\",\"color\":\"Vermelho\",\"mileage\":250000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.model").value("R 500")).andExpect(jsonPath("$.modelYear").doesNotExist())
                .andExpect(jsonPath("$.customerId").value(owner));
        mvc.perform(post("/api/vehicles/{id}/inactivate", scania).with(user("operator")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(post("/api/vehicles/{id}/inactivate", scania).with(user("operator")).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VEHICLE_ALREADY_INACTIVE"));
        mvc.perform(get("/api/vehicles/{id}", scania).with(user("operator"))).andExpect(status().isOk());

        vehicles("q=silva").andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].customerName").value("Transportadora Silva"));
        vehicles("q=sca-1n").andExpect(jsonPath("$.items[0].vehicle.id").value(scania));
        vehicles("q=atego").andExpect(jsonPath("$.items[0].vehicle.plate").value("MBZ2C11"));
        vehicles("active=false").andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].vehicle.id").value(scania));
        vehicles("active=true").andExpect(jsonPath("$.totalItems").value(1));
        vehicles("customerId=" + other).andExpect(jsonPath("$.items[0].vehicle.plate").value("MBZ2C11"));
        vehicles("size=1").andExpect(jsonPath("$.items[0].vehicle.plate").value("MBZ2C11")).andExpect(jsonPath("$.totalPages").value(2));

        mvc.perform(post("/api/vehicles/{id}/reactivate", scania).with(user("operator")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void postgresAllowsOnlyOneOpenOwnershipPerVehicle() throws Exception {
        String owner = id(createCustomer(pf("Dono Único", null, "34999990000")));
        String vehicle = id(createVehicle(owner, "ONE1A11"));
        assertThatThrownBy(() -> jdbc.update("insert into crm.vehicle_ownership(id,vehicle_id,customer_id,started_at) values (?,?,?,now())",
                UUID.randomUUID(), UUID.fromString(vehicle), UUID.fromString(owner)))
                .hasMessageContaining("uq_crm_vehicle_ownership_current");
    }

    private ResultActions createCustomer(String json) throws Exception {
        return mvc.perform(post("/api/customers").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions createVehicle(String customer, String plateOrJson) throws Exception {
        String json = plateOrJson.startsWith("{") ? plateOrJson
                : "{\"customerId\":\"" + customer + "\",\"plate\":\"" + plateOrJson + "\",\"manufacturer\":\"Ford\",\"model\":\"Cargo\",\"modelYear\":2022}";
        return mvc.perform(post("/api/vehicles").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions transfer(String vehicle, String customer) throws Exception {
        return mvc.perform(post("/api/vehicles/{id}/owner", vehicle).with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + customer + "\"}"));
    }

    private ResultActions search(String query) throws Exception {
        return mvc.perform(get("/api/customers/search?" + query).with(user("operator"))).andExpect(status().isOk());
    }

    private ResultActions vehicles(String query) throws Exception {
        return mvc.perform(get("/api/vehicles/search?" + query).with(user("operator"))).andExpect(status().isOk());
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String pf(String name, String document, String phone) {
        return "{\"personType\":\"PF\",\"name\":\"" + name + "\",\"phone\":\"" + phone + "\""
                + (document == null ? "" : ",\"document\":\"" + document + "\"") + "}";
    }
}
