package br.com.uberhidraulica.erp.crm.api;
import br.com.uberhidraulica.erp.crm.application.CrmApplicationService;
import br.com.uberhidraulica.erp.crm.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@RestController
public class CrmController {
    private final CrmApplicationService application;
    public CrmController(CrmApplicationService application) { this.application=application; }
    @PostMapping("/api/customers") ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody CustomerRequest r) { var x=CustomerResponse.from(application.createCustomer(r.personType(),r.name(),r.document(),r.status())); return ResponseEntity.created(URI.create("/api/customers/"+x.id())).body(x); }
    @GetMapping("/api/customers/{id}") CustomerResponse getCustomer(@PathVariable UUID id) { return CustomerResponse.from(application.getCustomer(id)); }
    @GetMapping("/api/customers") List<CustomerResponse> listCustomers() { return application.listCustomers().stream().map(CustomerResponse::from).toList(); }
    @PutMapping("/api/customers/{id}") CustomerResponse updateCustomer(@PathVariable UUID id,@Valid @RequestBody CustomerRequest r) { return CustomerResponse.from(application.updateCustomer(id,r.personType(),r.name(),r.document(),r.status())); }
    @PostMapping("/api/vehicles") ResponseEntity<VehicleResponse> createVehicle(@Valid @RequestBody VehicleRequest r) { var x=VehicleResponse.from(application.createVehicle(r.customerId(),r.plate(),r.manufacturer(),r.model(),r.modelYear(),r.mileage(),r.steeringGearManufacturer())); return ResponseEntity.created(URI.create("/api/vehicles/"+x.id())).body(x); }
    @GetMapping("/api/vehicles/{id}") VehicleResponse getVehicle(@PathVariable UUID id) { return VehicleResponse.from(application.getVehicle(id)); }
    @GetMapping("/api/customers/{id}/vehicles") List<VehicleResponse> listVehicles(@PathVariable UUID id) { return application.listVehicles(id).stream().map(VehicleResponse::from).toList(); }
    @PutMapping("/api/vehicles/{id}") VehicleResponse updateVehicle(@PathVariable UUID id,@Valid @RequestBody VehicleUpdateRequest r) { return VehicleResponse.from(application.updateVehicle(id,r.plate(),r.manufacturer(),r.model(),r.modelYear(),r.mileage(),r.steeringGearManufacturer())); }

    public record CustomerRequest(@NotNull Customer.PersonType personType,@NotBlank @Size(max=160) String name,@NotBlank String document,Customer.Status status) {}
    public record CustomerResponse(UUID id,Customer.PersonType personType,String name,String document,Customer.Status status,Instant createdAt,Instant updatedAt) { static CustomerResponse from(Customer c){return new CustomerResponse(c.id(),c.personType(),c.name(),c.document(),c.status(),c.createdAt(),c.updatedAt());} }
    public record VehicleRequest(@NotNull UUID customerId,@NotBlank String plate,@NotBlank @Size(max=100) String manufacturer,@NotBlank @Size(max=100) String model,@Positive int modelYear,@PositiveOrZero Long mileage,@Size(max=100) String steeringGearManufacturer) {}
    public record VehicleUpdateRequest(@NotBlank String plate,@NotBlank @Size(max=100) String manufacturer,@NotBlank @Size(max=100) String model,@Positive int modelYear,@PositiveOrZero Long mileage,@Size(max=100) String steeringGearManufacturer) {}
    public record VehicleResponse(UUID id,UUID customerId,String plate,String manufacturer,String model,int modelYear,Long mileage,String steeringGearManufacturer,Instant createdAt,Instant updatedAt) { static VehicleResponse from(Vehicle v){return new VehicleResponse(v.id(),v.customerId(),v.plate(),v.manufacturer(),v.model(),v.modelYear(),v.mileage(),v.steeringGearManufacturer(),v.createdAt(),v.updatedAt());} }
}
