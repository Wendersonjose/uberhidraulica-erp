package br.com.uberhidraulica.erp.servicecatalog;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ServiceCatalogIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Service Test");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> "owner-service@example.test");
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> "service-bootstrap-password");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanCatalog() { jdbc.update("delete from servicecatalog.service"); }

    @Test
    void endpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/services")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/services").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mutationRequiresCsrf() throws Exception {
        mvc.perform(post("/api/services").with(user("operator")).contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isForbidden());
    }

    @Test
    void createsConsultsListsAndUpdatesService() throws Exception {
        MvcResult created = mvc.perform(post("/api/services").with(user("operator")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Reparo da caixa"))
                .andExpect(jsonPath("$.basePrice").value(500.25))
                .andExpect(jsonPath("$.defaultWarrantyDays").value(90))
                .andExpect(jsonPath("$.active").value(true)).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/services/{id}", id).with(user("operator"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Recondicionamento"));
        mvc.perform(get("/api/services").with(user("operator"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));

        mvc.perform(put("/api/services/{id}", id).with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Reparo premium\",\"description\":\"Completo\",\"basePrice\":\"650.00\",\"defaultWarrantyDays\":120,\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.defaultWarrantyDays").value(120));
    }

    @Test
    void rejectsInvalidServiceAndMissingId() throws Exception {
        mvc.perform(post("/api/services").with(user("operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"description\":\"x\",\"basePrice\":-1,\"defaultWarrantyDays\":-1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/services/{id}", UUID.randomUUID()).with(user("operator"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
    }

    @Test
    void postgresConstraintsRejectInvalidRows() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='3' and success", Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("insert into servicecatalog.service(id,name,description,base_price,default_warranty_days,active,created_at,updated_at) values (?,'Bad','Bad',-0.01,90,true,now(),now())", UUID.randomUUID()))
                .hasMessageContaining("ck_service_base_price_nonnegative");
        assertThatThrownBy(() -> jdbc.update("insert into servicecatalog.service(id,name,description,base_price,default_warranty_days,active,created_at,updated_at) values (?,'Bad','Bad',1,-1,true,now(),now())", UUID.randomUUID()))
                .hasMessageContaining("ck_service_warranty_nonnegative");
        assertThat(jdbc.queryForObject("select count(*) from servicecatalog.service", Long.class)).isZero();
    }

    private static String validRequest() {
        return "{\"name\":\"Reparo da caixa\",\"description\":\"Recondicionamento\",\"category\":\"Direção\",\"basePrice\":\"500.25\"}";
    }
}
