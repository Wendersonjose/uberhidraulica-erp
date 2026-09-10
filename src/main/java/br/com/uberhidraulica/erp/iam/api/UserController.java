package br.com.uberhidraulica.erp.iam.api;

import br.com.uberhidraulica.erp.iam.application.UserAdminService;
import br.com.uberhidraulica.erp.iam.domain.*;
import br.com.uberhidraulica.erp.iam.infrastructure.security.IamPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/iam/users")
public class UserController {
    private final UserAdminService service;
    public UserController(UserAdminService service) { this.service = service; }

    @GetMapping @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'IAM_USERS_READ')")
    public UserPageResponse list(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        UserPage result = service.list(page, size);
        return new UserPageResponse(result.content().stream().map(UserResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @GetMapping("/{id}") @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'IAM_USERS_READ')")
    public UserResponse get(@PathVariable UUID id) { return UserResponse.from(service.get(id)); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'IAM_USERS_MANAGE')")
    public CreatedUserResponse create(@AuthenticationPrincipal IamPrincipal actor, @Valid @RequestBody CreateUserRequest body) {
        UserAdminService.CreatedUser created = service.create(actor.id(), body.name(), body.email(), body.profileCode());
        return new CreatedUserResponse(UserResponse.from(created.user()), created.temporaryPassword());
    }

    @PatchMapping("/{id}") @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'IAM_USERS_MANAGE')")
    public UserResponse state(@AuthenticationPrincipal IamPrincipal actor, @PathVariable UUID id, @Valid @RequestBody ChangeStateRequest body) {
        return UserResponse.from(service.changeState(actor.id(), id, body.state()));
    }

    @PostMapping("/{id}/password-reset")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'IAM_PASSWORD_RESET')")
    public TemporaryPasswordResponse reset(@AuthenticationPrincipal IamPrincipal actor, @PathVariable UUID id) {
        return new TemporaryPasswordResponse(service.resetPassword(actor.id(), actor.profileCode(), id).temporaryPassword());
    }

    @GetMapping("/{id}/permission-exceptions") @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'IAM_USER_EXCEPTIONS_MANAGE')")
    public Map<String, PermissionResolution> exceptions(@PathVariable UUID id) { return service.exceptions(id); }

    @PutMapping("/{id}/permission-exceptions/{permissionCode}") @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'IAM_USER_EXCEPTIONS_MANAGE')")
    public void exception(@AuthenticationPrincipal IamPrincipal actor, @PathVariable UUID id, @PathVariable String permissionCode,
                          @Valid @RequestBody ExceptionRequest body) {
        service.setException(actor.id(), id, permissionCode, body.resolution());
    }

    public record CreateUserRequest(@NotBlank @Size(max=160) String name, @Email @NotBlank @Size(max=320) String email, @NotNull ProfileCode profileCode) {}
    public record ChangeStateRequest(@NotNull UserState state) {}
    public record ExceptionRequest(@NotNull PermissionResolution resolution) {}
    public record TemporaryPasswordResponse(String temporaryPassword) {}
    public record CreatedUserResponse(UserResponse user, String temporaryPassword) {}
    public record UserPageResponse(List<UserResponse> content, int page, int size, long totalElements, int totalPages) {}
    public record UserResponse(UUID id, String name, String email, ProfileCode profileCode, UserState state, boolean mustChangePassword) {
        static UserResponse from(IamUser user) { return new UserResponse(user.id(), user.name(), user.email(), user.profileCode(), user.state(), user.mustChangePassword()); }
    }
}
