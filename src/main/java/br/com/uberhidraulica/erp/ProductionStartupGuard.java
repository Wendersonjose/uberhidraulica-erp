package br.com.uberhidraulica.erp;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Produção falha fechada: com o perfil {@code prod}, a aplicação se recusa a iniciar se uma credencial obrigatória
 * estiver ausente ou for um valor conhecido/fraco, ou se o cookie de sessão não for {@code Secure}.
 *
 * <p>Roda como {@link EnvironmentPostProcessor}, isto é, antes de qualquer bean, de qualquer conexão com o banco e
 * da migration do Flyway: um segredo inaceitável não chega a ser usado nem para conectar. As mensagens dizem
 * <b>qual</b> variável e <b>por quê</b>, e nunca repetem o valor.</p>
 *
 * <p>Não é política de negócio: é higiene de infraestrutura. O cookie sem {@code Secure} só é aceito quando
 * {@code APP_ENVIRONMENT=homologation} é declarado de propósito (homologação em HTTP puro, temporária).</p>
 */
public class ProductionStartupGuard implements EnvironmentPostProcessor, Ordered {
    static final String PROD_PROFILE = "prod";
    static final String ENVIRONMENT_PROPERTY = "app.environment";
    static final String PRODUCTION = "production";
    static final String HOMOLOGATION = "homologation";
    static final int MIN_DB_PASSWORD_LENGTH = 12;

    /** Valores que nunca servem como segredo de produção (comparação sem maiúsculas e sem espaços nas pontas). */
    private static final Set<String> KNOWN_WEAK = Set.of(
            "postgres", "postgresql", "password", "passwd", "pass", "admin", "administrator", "root", "123456",
            "12345678", "123456789", "1234567890", "qwerty", "secret", "changeme", "change-me", "change_me",
            "troque-esta-senha", "uberhidraulica", "uberhidraulica_dev", "test", "teste", "example", "default",
            "letmein", "welcome");

    private final Log log;

    public ProductionStartupGuard(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(ProductionStartupGuard.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of(PROD_PROFILE))) {
            // APP_ENVIRONMENT só é declarado pelo deploy (compose.yaml). Declarado sem o perfil prod, quase certamente é um
            // erro de digitação em SPRING_PROFILES_ACTIVE: valeriam os padrões de desenvolvimento (senha do banco
            // conhecida, cookie sem Secure) em um servidor de verdade.
            String declared = value(environment, ENVIRONMENT_PROPERTY);
            if (declared != null && !declared.isBlank())
                throw new IllegalStateException("Configuração recusada (o sistema falha fechado):\n - APP_ENVIRONMENT="
                        + declared.trim() + " foi declarado, mas o perfil 'prod' não está ativo (perfis ativos: "
                        + String.join(",", environment.getActiveProfiles()) + "). Sem ele valem os padrões de "
                        + "desenvolvimento. Corrija SPRING_PROFILES_ACTIVE.");
            return;
        }
        List<String> violations = violations(environment);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Configuração de produção recusada (o sistema falha fechado):\n - "
                    + String.join("\n - ", violations)
                    + "\nCorrija o .env e suba de novo. Ver docs/architecture/devops/DEPLOY-piloto.md.");
        }
        if (HOMOLOGATION.equals(environmentName(environment)))
            log.warn("APP_ENVIRONMENT=homologation: cookie de sessão sem Secure permitido. Use somente em "
                    + "homologação em HTTP puro; produção exige HTTPS e SESSION_COOKIE_SECURE=true.");
    }

    /** Todas as violações de uma vez, para o operador corrigir de uma só passada. */
    static List<String> violations(Environment environment) {
        List<String> found = new ArrayList<>();

        String environmentName = environmentName(environment);
        if (!PRODUCTION.equals(environmentName) && !HOMOLOGATION.equals(environmentName))
            found.add("APP_ENVIRONMENT deve ser 'production' (padrão) ou 'homologation'.");

        String username = value(environment, "spring.datasource.username");
        String password = value(environment, "spring.datasource.password");
        List<String> passwordProblems = weakness(password, MIN_DB_PASSWORD_LENGTH);
        if (password == null || password.isBlank()) {
            found.add("Senha do banco ausente (DB_PASSWORD, ou SUPABASE_DB_PASSWORD no perfil supabase).");
        } else {
            passwordProblems.forEach(problem -> found.add("Senha do banco recusada: " + problem));
            if (username != null && password.equalsIgnoreCase(username.trim()))
                found.add("Senha do banco recusada: igual ao usuário do banco.");
        }

        String bootstrap = value(environment, "IAM_BOOTSTRAP_OWNER_PASSWORD");
        if (bootstrap != null && !bootstrap.isBlank() && isKnownWeak(bootstrap))
            found.add("IAM_BOOTSTRAP_OWNER_PASSWORD recusada: valor conhecido/fraco.");

        boolean secureCookie = Boolean.parseBoolean(value(environment, "server.servlet.session.cookie.secure"));
        if (!secureCookie && !HOMOLOGATION.equals(environmentName))
            found.add("SESSION_COOKIE_SECURE=false só é aceito com APP_ENVIRONMENT=homologation (HTTP temporário); "
                    + "em produção o cookie de sessão precisa ser Secure e o acesso, por HTTPS.");
        return found;
    }

    /** Motivos pelos quais o valor não serve como segredo; vazio quando serve. */
    static List<String> weakness(String secret, int minLength) {
        List<String> problems = new ArrayList<>();
        if (secret == null || secret.isBlank()) return problems;
        if (secret.contains("${")) problems.add("valor não resolvido (variável de ambiente ausente).");
        if (isKnownWeak(secret)) problems.add("valor padrão/conhecido.");
        if (secret.length() < minLength) problems.add("menos de " + minLength + " caracteres.");
        if (secret.chars().distinct().count() < 5) problems.add("pouca variedade de caracteres.");
        return problems;
    }

    static boolean isKnownWeak(String secret) {
        String normalized = secret.trim().toLowerCase(Locale.ROOT);
        return KNOWN_WEAK.contains(normalized) || normalized.contains("changeme") || normalized.contains("troque-esta");
    }

    private static String environmentName(Environment environment) {
        String name = value(environment, ENVIRONMENT_PROPERTY);
        return name == null || name.isBlank() ? PRODUCTION : name.trim().toLowerCase(Locale.ROOT);
    }

    /** Propriedade resolvida; um placeholder sem valor (variável de ambiente ausente) equivale a ausente. */
    private static String value(Environment environment, String key) {
        try {
            return environment.getProperty(key);
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return null;
        }
    }

    @Override
    public int getOrder() {
        // Depois do ConfigDataEnvironmentPostProcessor (application-prod.yml já está carregado).
        return Ordered.LOWEST_PRECEDENCE;
    }
}
