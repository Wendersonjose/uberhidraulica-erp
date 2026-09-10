package br.com.uberhidraulica.erp.iam.api;

import br.com.uberhidraulica.erp.iam.application.PasswordService;
import br.com.uberhidraulica.erp.iam.infrastructure.security.IamPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/iam/password")
public class PasswordController {
    private final PasswordService passwords;
    public PasswordController(PasswordService passwords) { this.passwords = passwords; }

    @PostMapping("/change") public ResponseEntity<Void> change(@AuthenticationPrincipal IamPrincipal principal, @Valid @RequestBody ChangePasswordRequest body) {
        passwords.change(principal.id(), body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 1024) String currentPassword, @NotBlank @Size(max = 1024) String newPassword) {}
}
