package br.com.uberhidraulica.erp.workorder.api;

import br.com.uberhidraulica.erp.workorder.application.WorkflowStatusApplicationService;
import br.com.uberhidraulica.erp.workorder.domain.WorkflowStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/work-order-statuses")
public class WorkflowStatusController {
    private final WorkflowStatusApplicationService application;

    public WorkflowStatusController(WorkflowStatusApplicationService application) { this.application = application; }

    @GetMapping
    List<StatusUsageResponse> list() {
        return application.list().stream().map(u -> new StatusUsageResponse(WorkOrderController.StatusResponse.from(u.status()), u.orderCount())).toList();
    }

    @PostMapping
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'WORKORDER_CONFIG')")
    ResponseEntity<WorkOrderController.StatusResponse> create(@Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(WorkOrderController.StatusResponse.from(application.create(request.name(), request.stage())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'WORKORDER_CONFIG')")
    WorkOrderController.StatusResponse rename(@PathVariable UUID id, @Valid @RequestBody RenameRequest request) {
        return WorkOrderController.StatusResponse.from(application.rename(id, request.name()));
    }

    @PostMapping("/{id}/inactivate")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'WORKORDER_CONFIG')")
    WorkOrderController.StatusResponse inactivate(@PathVariable UUID id) { return WorkOrderController.StatusResponse.from(application.setActive(id, false)); }
    @PostMapping("/{id}/reactivate")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'WORKORDER_CONFIG')")
    WorkOrderController.StatusResponse reactivate(@PathVariable UUID id) { return WorkOrderController.StatusResponse.from(application.setActive(id, true)); }
    @PostMapping("/{id}/make-default")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'WORKORDER_CONFIG')")
    WorkOrderController.StatusResponse makeDefault(@PathVariable UUID id) { return WorkOrderController.StatusResponse.from(application.makeDefault(id)); }

    @PutMapping("/order")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'WORKORDER_CONFIG')")
    List<WorkOrderController.StatusResponse> reorder(@Valid @RequestBody ReorderRequest request) {
        return application.reorder(request.statusIds()).stream().map(WorkOrderController.StatusResponse::from).toList();
    }

    @GetMapping("/automations") Map<String, Boolean> automations() { return application.automations(); }

    @PutMapping("/automations/{event}")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'WORKORDER_CONFIG')")
    Map<String, Boolean> setAutomation(@PathVariable String event, @Valid @RequestBody AutomationRequest request) {
        return application.setAutomation(event, request.enabled());
    }

    public record AutomationRequest(@NotNull Boolean enabled) {}
    public record CreateRequest(@NotBlank @Size(max = 60) String name, @NotNull WorkflowStatus.Stage stage) {}
    public record RenameRequest(@NotBlank @Size(max = 60) String name) {}
    public record ReorderRequest(@NotEmpty List<UUID> statusIds) {}
    public record StatusUsageResponse(WorkOrderController.StatusResponse status, long orderCount) {}
}
