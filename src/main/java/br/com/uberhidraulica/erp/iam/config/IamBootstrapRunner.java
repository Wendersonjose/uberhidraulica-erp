package br.com.uberhidraulica.erp.iam.config;

import br.com.uberhidraulica.erp.iam.application.BootstrapService;
import br.com.uberhidraulica.erp.iam.application.BootstrapConfigurationValidator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.stream.Stream;

@Component
public class IamBootstrapRunner implements ApplicationRunner {
    public static final String OWNER_NAME = "IAM_BOOTSTRAP_OWNER_NAME";
    public static final String OWNER_EMAIL = "IAM_BOOTSTRAP_OWNER_EMAIL";
    public static final String OWNER_PASSWORD = "IAM_BOOTSTRAP_OWNER_PASSWORD";

    private final Environment environment;
    private final BootstrapService bootstrap;
    private final BootstrapConfigurationValidator validator;

    public IamBootstrapRunner(Environment environment, BootstrapService bootstrap,
                              BootstrapConfigurationValidator validator) {
        this.environment = environment;
        this.bootstrap = bootstrap;
        this.validator = validator;
    }

    @Override public void run(ApplicationArguments args) {
        String name = environment.getProperty(OWNER_NAME);
        String email = environment.getProperty(OWNER_EMAIL);
        String password = environment.getProperty(OWNER_PASSWORD);
        if (Stream.of(name, email, password).allMatch(IamBootstrapRunner::blank)) return;
        validator.validate(name, email, password);
        bootstrap.bootstrap(name, email, password);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
