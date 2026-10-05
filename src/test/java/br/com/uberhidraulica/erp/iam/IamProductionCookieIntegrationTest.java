package br.com.uberhidraulica.erp.iam;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O perfil `prod` falha fechado sem senha de banco forte (ProductionStartupGuard), e a trava roda ao preparar o
 * ambiente, antes de qualquer {@code @DynamicPropertySource}: por isso a senha é informada como propriedade do
 * teste (a mesma do contêiner), como o .env real a informaria. Isso também prova que uma configuração válida inicia.
 */
@Testcontainers
@SpringBootTest(properties = "DB_PASSWORD=Zq8-vK2m-Xt9r-Lw4p")
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class IamProductionCookieIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withPassword("Zq8-vK2m-Xt9r-Lw4p");

    @Autowired MockMvc mvc;

    @Test
    void productionCookieIsHttpOnlySecureAndSameSiteLax() throws Exception {
        var response = mvc.perform(get("/api/iam/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        Cookie cookie = response.getCookie("SESSION");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(response.getHeader("Set-Cookie")).contains("Secure", "HttpOnly", "SameSite=Lax");
    }
}
