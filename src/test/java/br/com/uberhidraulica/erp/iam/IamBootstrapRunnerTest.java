package br.com.uberhidraulica.erp.iam;

import br.com.uberhidraulica.erp.iam.application.BootstrapService;
import br.com.uberhidraulica.erp.iam.application.BootstrapConfigurationValidator;
import br.com.uberhidraulica.erp.iam.config.IamBootstrapRunner;
import br.com.uberhidraulica.erp.iam.domain.IamException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;
import jakarta.validation.Validation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IamBootstrapRunnerTest {
    @Test
    void entirelyAbsentConfigurationIsAControlledNoOp() {
        Environment environment = mock(Environment.class);
        BootstrapService bootstrap = mock(BootstrapService.class);
        new IamBootstrapRunner(environment, bootstrap, validator()).run(new DefaultApplicationArguments());
        verifyNoInteractions(bootstrap);
    }

    @Test
    void incompleteConfigurationFailsBeforeBootstrap() {
        Environment environment = mock(Environment.class);
        BootstrapService bootstrap = mock(BootstrapService.class);
        when(environment.getProperty(IamBootstrapRunner.OWNER_NAME)).thenReturn("Owner");
        when(environment.getProperty(IamBootstrapRunner.OWNER_EMAIL)).thenReturn("owner@example.test");

        assertThatThrownBy(() -> new IamBootstrapRunner(environment, bootstrap, validator())
                .run(new DefaultApplicationArguments()))
                .isInstanceOfSatisfying(IamException.class,
                        error -> assertThat(error.code()).isEqualTo("BOOTSTRAP_CONFIGURATION_INVALID"));
        verifyNoInteractions(bootstrap);
    }

    @Test
    void semanticAndTechnicalLimitsAreValidatedBeforeBootstrap() {
        assertInvalid("Owner", "not-an-email", "secret");
        assertInvalid(" ", "owner@example.test", "secret");
        assertInvalid("x".repeat(161), "owner@example.test", "secret");
        assertInvalid("Owner", "a".repeat(310) + "@example.test", "secret");
        assertInvalid("Owner", "owner@example.test", "x".repeat(1025));
    }

    private void assertInvalid(String name, String email, String password) {
        Environment environment = mock(Environment.class);
        BootstrapService bootstrap = mock(BootstrapService.class);
        when(environment.getProperty(IamBootstrapRunner.OWNER_NAME)).thenReturn(name);
        when(environment.getProperty(IamBootstrapRunner.OWNER_EMAIL)).thenReturn(email);
        when(environment.getProperty(IamBootstrapRunner.OWNER_PASSWORD)).thenReturn(password);
        assertThatThrownBy(() -> new IamBootstrapRunner(environment, bootstrap, validator())
                .run(new DefaultApplicationArguments())).isInstanceOf(IamException.class);
        verifyNoInteractions(bootstrap);
    }

    private BootstrapConfigurationValidator validator() {
        return new BootstrapConfigurationValidator(Validation.buildDefaultValidatorFactory().getValidator());
    }
}
