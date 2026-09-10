package br.com.uberhidraulica.erp.iam.api;

import br.com.uberhidraulica.erp.iam.application.UserAdminService;
import br.com.uberhidraulica.erp.iam.domain.ProfileCode;
import br.com.uberhidraulica.erp.iam.infrastructure.security.IamPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/iam")
public class ProfileController {
    private final UserAdminService service;
    public ProfileController(UserAdminService service) { this.service = service; }

    @GetMapping("/profiles") @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'IAM_CATALOG_READ')")
    public List<ProfileResponse> profiles() { return Arrays.stream(ProfileCode.values()).map(code -> new ProfileResponse(code, service.profilePermissions(code))).toList(); }

    @GetMapping("/profiles/{profileCode}") @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'IAM_CATALOG_READ')")
    public ProfileResponse profile(@PathVariable ProfileCode profileCode) { return new ProfileResponse(profileCode, service.profilePermissions(profileCode)); }

    @GetMapping("/permissions") @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'IAM_CATALOG_READ')")
    public Set<String> permissions() { return service.permissions(); }

    @PutMapping("/profiles/{profileCode}/permissions/{permissionCode}") @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'IAM_PROFILE_PERMISSIONS_MANAGE')")
    public void add(@AuthenticationPrincipal IamPrincipal actor, @PathVariable ProfileCode profileCode, @PathVariable String permissionCode) {
        service.addProfilePermission(actor.id(), profileCode, permissionCode);
    }

    @DeleteMapping("/profiles/{profileCode}/permissions/{permissionCode}") @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'IAM_PROFILE_PERMISSIONS_MANAGE')")
    public void remove(@AuthenticationPrincipal IamPrincipal actor, @PathVariable ProfileCode profileCode, @PathVariable String permissionCode) {
        service.removeProfilePermission(actor.id(), profileCode, permissionCode);
    }

    public record ProfileResponse(ProfileCode code, Set<String> permissions) {}
}
