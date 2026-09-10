package br.com.uberhidraulica.erp.workorder;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class Task0004VerticalIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18-alpine");
    @DynamicPropertySource static void bootstrap(DynamicPropertyRegistry p){p.add("IAM_BOOTSTRAP_OWNER_NAME",()->"Owner Vertical");p.add("IAM_BOOTSTRAP_OWNER_EMAIL",()->"owner-vertical@example.test");p.add("IAM_BOOTSTRAP_OWNER_PASSWORD",()->"vertical-bootstrap-password");}
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean(){jdbc.update("delete from workorder.work_order_service");jdbc.update("delete from workorder.work_order");jdbc.update("delete from crm.vehicle");jdbc.update("delete from crm.customer");jdbc.update("delete from servicecatalog.service");}

    @Test void requiresAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/api/customers")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/customers").with(user("operator")).contentType(MediaType.APPLICATION_JSON).content(pf("12345678901"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/work-orders").with(user("operator")).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
    }

    @Test void createsPfAndPjNormalizesListsUpdatesAndKeepsGlobalUniqueness() throws Exception {
        String pfId=createCustomer(pf("123.456.789-01"));
        mvc.perform(get("/api/customers/{id}",pfId).with(user("operator"))).andExpect(status().isOk()).andExpect(jsonPath("$.document").value("12345678901")).andExpect(jsonPath("$.status").value("ACTIVE"));
        String pjId=createCustomer("{\"personType\":\"PJ\",\"name\":\"Hidráulica Teste Ltda\",\"document\":\"12.345.678/0001-90\"}");
        mvc.perform(get("/api/customers").with(user("operator"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(put("/api/customers/{id}",pfId).with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"personType\":\"PF\",\"name\":\"Cliente Inativo\",\"document\":\"12345678901\",\"status\":\"INACTIVE\"}" )).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
        mvc.perform(post("/api/customers").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(pf("123-456-789-01"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUSTOMER_DOCUMENT_ALREADY_EXISTS"));
        mvc.perform(post("/api/customers").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(pf("123ABC45678901"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CUSTOMER"));
        assertThat(jdbc.queryForObject("select count(*) from crm.customer where document='12345678901'",Long.class)).isEqualTo(1);
        assertThat(pjId).isNotBlank();
    }

    @Test void vehicleRequiresExistingCustomerNormalizesAndLists() throws Exception {
        String customerId=createCustomer(pf("11122233344"));
        String vehicleId=createVehicle(customerId,"abc-1d23");
        mvc.perform(get("/api/vehicles/{id}",vehicleId).with(user("operator"))).andExpect(status().isOk()).andExpect(jsonPath("$.plate").value("ABC1D23"));
        mvc.perform(get("/api/customers/{id}/vehicles",customerId).with(user("operator"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].customerId").value(customerId));
        mvc.perform(put("/api/vehicles/{id}",vehicleId).with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"plate\":\"ABC-1D23\",\"manufacturer\":\"Ford\",\"model\":\"Cargo Atualizado\",\"modelYear\":2023,\"mileage\":13000}" )).andExpect(status().isOk()).andExpect(jsonPath("$.model").value("Cargo Atualizado")).andExpect(jsonPath("$.customerId").value(customerId));
        mvc.perform(post("/api/vehicles").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(vehicle(UUID.randomUUID().toString(),"ZZZ9999"))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
    }

    @Test void workOrderRequiresMileageAndRejectsVehicleFromAnotherCustomer() throws Exception {
        String a=createCustomer(pf("11111111111"));String b=createCustomer(pf("22222222222"));String vehicle=createVehicle(a,"AAA1111");
        mvc.perform(post("/api/work-orders").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"customerId\":\""+a+"\",\"vehicleId\":\""+vehicle+"\"}" )).andExpect(status().isBadRequest());
        mvc.perform(post("/api/work-orders").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(order(b,vehicle,1000))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VEHICLE_CUSTOMER_MISMATCH"));
        assertThat(jdbc.queryForObject("select count(*) from workorder.work_order",Long.class)).isZero();
    }

    @Test void verticalCustomerVehicleServiceWorkOrderAndQueryUsesRealPostgres() throws Exception {
        String customer=createCustomer("{\"personType\":\"PJ\",\"name\":\"Oficina Cliente Ltda\",\"document\":\"98765432000199\"}");
        String vehicle=createVehicle(customer,"QWE-4R56");String service=createService();
        MvcResult opened=mvc.perform(post("/api/work-orders").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(order(customer,vehicle,88000))).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ABERTA")).andExpect(jsonPath("$.number").isNumber()).andExpect(jsonPath("$.services.length()").value(0)).andReturn();
        String orderId=JsonPath.read(opened.getResponse().getContentAsString(),"$.id");
        mvc.perform(post("/api/work-orders/{id}/services",orderId).with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"serviceId\":\""+service+"\"}" )).andExpect(status().isCreated()).andExpect(jsonPath("$.services[0].serviceId").value(service)).andExpect(jsonPath("$.services[0].warrantyDays").value(90));
        mvc.perform(get("/api/work-orders/{id}",orderId).with(user("operator"))).andExpect(status().isOk()).andExpect(jsonPath("$.entryMileage").value(88000)).andExpect(jsonPath("$.status").value("ABERTA")).andExpect(jsonPath("$.services[0].name").value("Alinhamento hidráulico"));
        mvc.perform(get("/api/work-orders").with(user("operator"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(orderId));
        mvc.perform(post("/api/work-orders/{id}/services",orderId).with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"serviceId\":\""+UUID.randomUUID()+"\"}" )).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
    }

    @Test void postgresEnforcesDocumentOwnershipMileageAndStatus() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version in ('4','5') and success",Long.class)).isEqualTo(2);
        UUID customer=UUID.randomUUID();jdbc.update("insert into crm.customer values (?,'PF','Direct','33333333333','ACTIVE',now(),now())",customer);
        assertThatThrownBy(()->jdbc.update("insert into crm.customer values (?,'PF','Duplicate','33333333333','INACTIVE',now(),now())",UUID.randomUUID())).hasMessageContaining("uq_crm_customer_document");
        assertThatThrownBy(()->jdbc.update("insert into crm.vehicle values (?,?, 'ABC1234','Maker','Model',2020,0,null,now(),now())",UUID.randomUUID(),UUID.randomUUID())).hasMessageContaining("vehicle_customer_id_fkey");
        UUID vehicle=UUID.randomUUID();jdbc.update("insert into crm.vehicle values (?,?,'ABC1234','Maker','Model',2020,0,null,now(),now())",vehicle,customer);
        UUID another=UUID.randomUUID();jdbc.update("insert into crm.customer values (?,'PF','Another','44444444444','ACTIVE',now(),now())",another);
        assertThatThrownBy(()->jdbc.update("insert into workorder.work_order(id,customer_id,vehicle_id,entry_mileage,opened_at,status,created_at,updated_at) values (?,?,?,0,now(),'ABERTA',now(),now())",UUID.randomUUID(),another,vehicle)).hasMessageContaining("fk_work_order_vehicle_customer");
        assertThatThrownBy(()->jdbc.update("insert into workorder.work_order(id,customer_id,vehicle_id,entry_mileage,opened_at,status,created_at,updated_at) values (?,?,?,-1,now(),'ABERTA',now(),now())",UUID.randomUUID(),customer,vehicle)).hasMessageContaining("ck_work_order_entry_mileage");
        assertThatThrownBy(()->jdbc.update("insert into workorder.work_order(id,customer_id,vehicle_id,entry_mileage,opened_at,status,created_at,updated_at) values (?,?,?,0,now(),'FUTURO',now(),now())",UUID.randomUUID(),customer,vehicle)).hasMessageContaining("ck_work_order_status_initial");
    }

    private String createCustomer(String json)throws Exception{return JsonPath.read(mvc.perform(post("/api/customers").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"$.id");}
    private String createVehicle(String customer,String plate)throws Exception{return JsonPath.read(mvc.perform(post("/api/vehicles").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(vehicle(customer,plate))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"$.id");}
    private String createService()throws Exception{return JsonPath.read(mvc.perform(post("/api/services").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Alinhamento hidráulico\",\"description\":\"Serviço de teste\",\"basePrice\":\"250.00\"}" )).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"$.id");}
    private static String pf(String doc){return "{\"personType\":\"PF\",\"name\":\"Cliente Teste\",\"document\":\""+doc+"\"}";}
    private static String vehicle(String customer,String plate){return "{\"customerId\":\""+customer+"\",\"plate\":\""+plate+"\",\"manufacturer\":\"Ford\",\"model\":\"Cargo\",\"modelYear\":2022,\"mileage\":12000,\"steeringGearManufacturer\":\"TRW\"}";}
    private static String order(String customer,String vehicle,long km){return "{\"customerId\":\""+customer+"\",\"vehicleId\":\""+vehicle+"\",\"entryMileage\":"+km+"}";}
}
