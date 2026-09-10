package br.com.uberhidraulica.erp.iam.application;

import br.com.uberhidraulica.erp.iam.domain.IamException;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;

@Service
public class BootstrapConfigurationValidator {
    private final Validator validator;

    public BootstrapConfigurationValidator(Validator validator) { this.validator = validator; }

    public void validate(String name, String email, String password) {
        if (!validator.validate(new Configuration(name, email, password)).isEmpty()) {
            throw new IamException("BOOTSTRAP_CONFIGURATION_INVALID", "Configuração de bootstrap inválida");
        }
    }

    record Configuration(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 1024) String password) {}
}
