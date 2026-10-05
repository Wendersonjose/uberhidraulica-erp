package br.com.uberhidraulica.erp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Produção falha fechada: nada disto precisa de Docker nem de banco, porque a trava roda antes de qualquer bean. */
class ProductionStartupGuardTest {
    private static final String STRONG = "Zq8-vK2m-Xt9r-Lw4p";

    private static Map<String, Object> valid() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.username", "erp_app");
        properties.put("spring.datasource.password", STRONG);
        properties.put("server.servlet.session.cookie.secure", "true");
        return properties;
    }

    private static List<String> check(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return ProductionStartupGuard.violations(environment);
    }

    @Test
    void acceptsAStrongConfiguration() {
        assertThat(check(valid())).isEmpty();
    }

    @Test
    void rejectsAMissingDatabasePassword() {
        Map<String, Object> properties = valid();
        properties.remove("spring.datasource.password");
        assertThat(check(properties)).anyMatch(message -> message.contains("Senha do banco ausente"));
        properties.put("spring.datasource.password", "  ");
        assertThat(check(properties)).anyMatch(message -> message.contains("Senha do banco ausente"));
    }

    @Test
    void rejectsAnUnresolvedPlaceholderAsMissing() {
        Map<String, Object> properties = valid();
        properties.put("spring.datasource.password", "${DB_PASSWORD_QUE_NAO_EXISTE_NO_AMBIENTE}");
        assertThat(check(properties)).anyMatch(message -> message.contains("Senha do banco ausente"));
    }

    @Test
    void rejectsKnownDefaultsAndWeakPasswords() {
        for (String weak : List.of("postgres", "password", "admin", "123456", "secret", "changeme", "ChangeMe", "uberhidraulica_dev",
                "troque-esta-senha", "root", "qwerty", "short-1", "aaaaaaaaaaaaaaaa", "my-changeme-value-123")) {
            Map<String, Object> properties = valid();
            properties.put("spring.datasource.password", weak);
            assertThat(check(properties)).as("senha %s", weak).anyMatch(message -> message.startsWith("Senha do banco recusada"));
        }
    }

    @Test
    void rejectsAPasswordEqualToTheUsername() {
        Map<String, Object> properties = valid();
        properties.put("spring.datasource.username", "erp_application_user");
        properties.put("spring.datasource.password", "erp_application_user");
        assertThat(check(properties)).anyMatch(message -> message.contains("igual ao usuário"));
    }

    @Test
    void neverEchoesTheSecretInTheMessages() {
        Map<String, Object> properties = valid();
        properties.put("spring.datasource.password", "changeme");
        properties.put("IAM_BOOTSTRAP_OWNER_PASSWORD", "password");
        assertThat(String.join(" ", check(properties))).doesNotContain("changeme").doesNotContain("password\"");
        assertThat(String.join(" ", check(properties))).doesNotContain("=password");
    }

    @Test
    void rejectsAKnownWeakBootstrapPassword() {
        Map<String, Object> properties = valid();
        properties.put("IAM_BOOTSTRAP_OWNER_PASSWORD", "12345678");
        assertThat(check(properties)).anyMatch(message -> message.contains("IAM_BOOTSTRAP_OWNER_PASSWORD"));
        properties.put("IAM_BOOTSTRAP_OWNER_PASSWORD", "uma-senha-provisoria-longa");
        assertThat(check(properties)).isEmpty();
    }

    @Test
    void insecureCookieOnlyWithAnExplicitHomologationEnvironment() {
        Map<String, Object> properties = valid();
        properties.put("server.servlet.session.cookie.secure", "false");
        assertThat(check(properties)).anyMatch(message -> message.contains("SESSION_COOKIE_SECURE=false"));
        properties.put("app.environment", "production");
        assertThat(check(properties)).anyMatch(message -> message.contains("SESSION_COOKIE_SECURE=false"));
        properties.put("app.environment", "homologation");
        assertThat(check(properties)).isEmpty();
    }

    @Test
    void absentCookieSettingCountsAsInsecure() {
        Map<String, Object> properties = valid();
        properties.remove("server.servlet.session.cookie.secure");
        assertThat(check(properties)).anyMatch(message -> message.contains("SESSION_COOKIE_SECURE=false"));
    }

    @Test
    void unknownEnvironmentNamesAreRejected() {
        Map<String, Object> properties = valid();
        properties.put("app.environment", "staging-ish");
        assertThat(check(properties)).anyMatch(message -> message.contains("APP_ENVIRONMENT"));
    }

    @Test
    void reportsEveryProblemAtOnce() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.password", "postgres");
        properties.put("server.servlet.session.cookie.secure", "false");
        assertThat(check(properties)).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void doesNothingOutsideTheProdProfile() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("spring.datasource.password", "postgres")));
        new ProductionStartupGuard(new DeferredLogs()).postProcessEnvironment(environment, null);
    }

    /** O registro em META-INF/spring.factories funciona: o app de verdade recusa subir, sem banco e sem Docker. */
    @Test
    void theRealApplicationRefusesToStartInProdWithAWeakPassword() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(UberhidraulicaErpApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .run("--spring.datasource.password=postgres", "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/none"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Configuração de produção recusada")
                .hasMessageContaining("Senha do banco recusada")
                .hasMessageNotContaining("=postgres");
    }

    @Test
    void theRealApplicationRefusesToStartInProdWithoutThePassword() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(UberhidraulicaErpApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .properties("spring.datasource.url=jdbc:postgresql://127.0.0.1:1/none")
                .run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Senha do banco ausente");
    }
}
