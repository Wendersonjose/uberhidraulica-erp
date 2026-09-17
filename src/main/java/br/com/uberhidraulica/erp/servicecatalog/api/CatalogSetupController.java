package br.com.uberhidraulica.erp.servicecatalog.api;

import br.com.uberhidraulica.erp.servicecatalog.application.CatalogSetupApplicationService;
import br.com.uberhidraulica.erp.servicecatalog.domain.CatalogSetup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class CatalogSetupController {
    private final CatalogSetupApplicationService application;

    public CatalogSetupController(CatalogSetupApplicationService application) { this.application = application; }

    @GetMapping("/api/service-categories")
    List<CategoryResponse> categories() { return application.categories().stream().map(CategoryResponse::from).toList(); }

    @PostMapping("/api/service-categories")
    ResponseEntity<CategoryResponse> createCategory(@Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryResponse.from(application.createCategory(request.name())));
    }

    @PutMapping("/api/service-categories/{id}")
    CategoryResponse renameCategory(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        return CategoryResponse.from(application.renameCategory(id, request.name()));
    }

    @PostMapping("/api/service-categories/{id}/inactivate")
    CategoryResponse inactivateCategory(@PathVariable UUID id) { return CategoryResponse.from(application.setCategoryActive(id, false)); }

    @PostMapping("/api/service-categories/{id}/reactivate")
    CategoryResponse reactivateCategory(@PathVariable UUID id) { return CategoryResponse.from(application.setCategoryActive(id, true)); }

    @GetMapping("/api/vehicle-groups")
    List<GroupResponse> groups() {
        var counts = application.groupVehicleCounts();
        return application.groups().stream().map(g -> GroupResponse.from(g, counts.getOrDefault(g.id(), 0L))).toList();
    }

    @PostMapping("/api/vehicle-groups")
    ResponseEntity<GroupResponse> createGroup(@Valid @RequestBody GroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(GroupResponse.from(application.createGroup(request.name(), request.description()), 0));
    }

    @PutMapping("/api/vehicle-groups/{id}")
    GroupResponse updateGroup(@PathVariable UUID id, @Valid @RequestBody GroupRequest request) {
        return withCount(application.updateGroup(id, request.name(), request.description()));
    }

    @PostMapping("/api/vehicle-groups/{id}/inactivate")
    GroupResponse inactivateGroup(@PathVariable UUID id) { return withCount(application.setGroupActive(id, false)); }

    @PostMapping("/api/vehicle-groups/{id}/reactivate")
    GroupResponse reactivateGroup(@PathVariable UUID id) { return withCount(application.setGroupActive(id, true)); }

    @GetMapping("/api/vehicle-groups/{id}/vehicles")
    List<GroupVehicleResponse> groupVehicles(@PathVariable UUID id) {
        return application.groupVehicles(id).stream()
                .map(v -> new GroupVehicleResponse(v.id(), v.customerId(), v.plate(), v.manufacturer(), v.model(), v.active())).toList();
    }

    @PutMapping("/api/vehicle-groups/{id}/vehicles/{vehicleId}")
    ResponseEntity<Void> assignVehicle(@PathVariable UUID id, @PathVariable UUID vehicleId) {
        application.assignVehicle(id, vehicleId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/vehicle-groups/{id}/vehicles/{vehicleId}")
    ResponseEntity<Void> removeVehicle(@PathVariable UUID id, @PathVariable UUID vehicleId) {
        application.removeVehicle(id, vehicleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/vehicles/{vehicleId}/group")
    ResponseEntity<GroupResponse> vehicleGroup(@PathVariable UUID vehicleId) {
        return application.groupOfVehicle(vehicleId).map(this::withCount).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    private GroupResponse withCount(CatalogSetup.VehicleGroup group) {
        return GroupResponse.from(group, application.groupVehicleCounts().getOrDefault(group.id(), 0L));
    }

    public record CategoryRequest(@NotBlank @Size(max = 100) String name) {}
    public record CategoryResponse(UUID id, String name, boolean active, Instant createdAt, Instant updatedAt) {
        static CategoryResponse from(CatalogSetup.Category c) { return new CategoryResponse(c.id(), c.name(), c.active(), c.createdAt(), c.updatedAt()); }
    }
    public record GroupRequest(@NotBlank @Size(max = 100) String name, @Size(max = 500) String description) {}
    public record GroupResponse(UUID id, String name, String description, boolean active, long vehicleCount, Instant createdAt, Instant updatedAt) {
        static GroupResponse from(CatalogSetup.VehicleGroup g, long count) {
            return new GroupResponse(g.id(), g.name(), g.description(), g.active(), count, g.createdAt(), g.updatedAt());
        }
    }
    public record GroupVehicleResponse(UUID id, UUID customerId, String plate, String manufacturer, String model, boolean active) {}
}
