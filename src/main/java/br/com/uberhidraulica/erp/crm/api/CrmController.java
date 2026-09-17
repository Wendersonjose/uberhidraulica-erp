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
    @PostMapping("/api/customers") ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody CustomerRequest r) { var x=CustomerResponse.from(application.createCustomer(r.personType(),r.name(),r.document(),r.phone(),r.email(),r.addressValue())); return ResponseEntity.created(URI.create("/api/customers/"+x.id())).body(x); }
    @GetMapping("/api/customers/{id}") CustomerResponse getCustomer(@PathVariable UUID id) { return CustomerResponse.from(application.getCustomer(id)); }
    /** Lista completa ordenada por nome, usada pelos seletores; telas de consulta usam a busca paginada. */
    @GetMapping("/api/customers") List<CustomerResponse> listCustomers() { return application.listCustomers().stream().map(CustomerResponse::from).toList(); }
    @GetMapping("/api/customers/search") PageResponse<CustomerResponse> searchCustomers(@RequestParam(required=false) String q, @RequestParam(required=false) Customer.PersonType personType,
            @RequestParam(required=false) Customer.Status status, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        var result=application.searchCustomers(new CustomerSearch(q, personType, status, page, size));
        return new PageResponse<>(result.items().stream().map(CustomerResponse::from).toList(), result.totalItems(), result.page(), result.size(), result.totalPages());
    }
    @PutMapping("/api/customers/{id}") CustomerResponse updateCustomer(@PathVariable UUID id,@Valid @RequestBody CustomerRequest r) { return CustomerResponse.from(application.updateCustomer(id,r.personType(),r.name(),r.document(),r.phone(),r.email(),r.addressValue())); }
    @PostMapping("/api/customers/{id}/inactivate") CustomerResponse inactivateCustomer(@PathVariable UUID id) { return CustomerResponse.from(application.inactivateCustomer(id)); }
    @PostMapping("/api/customers/{id}/reactivate") CustomerResponse reactivateCustomer(@PathVariable UUID id) { return CustomerResponse.from(application.reactivateCustomer(id)); }
    @PostMapping("/api/vehicles") ResponseEntity<VehicleResponse> createVehicle(@Valid @RequestBody VehicleRequest r) {
        var x=VehicleResponse.from(application.createVehicle(r.customerId(),r.plate(),r.manufacturer(),r.model(),r.modelYear(),r.mileage(),r.steeringGearManufacturer(),r.color(),r.notes()));
        return ResponseEntity.created(URI.create("/api/vehicles/"+x.id())).body(x);
    }
    @GetMapping("/api/vehicles/{id}") VehicleResponse getVehicle(@PathVariable UUID id) { return VehicleResponse.from(application.getVehicle(id)); }
    @GetMapping("/api/customers/{id}/vehicles") List<VehicleResponse> listVehicles(@PathVariable UUID id) { return application.listVehicles(id).stream().map(VehicleResponse::from).toList(); }
    @GetMapping("/api/vehicles/search") PageResponse<VehicleListItem> searchVehicles(@RequestParam(required=false) String q, @RequestParam(required=false) UUID customerId,
            @RequestParam(required=false) Boolean active, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        var result=application.searchVehicles(new VehicleSearch(q, customerId, active, page, size));
        return new PageResponse<>(result.items().stream().map(r->new VehicleListItem(VehicleResponse.from(r.vehicle()), r.customerName())).toList(),
                result.totalItems(), result.page(), result.size(), result.totalPages());
    }
    @PutMapping("/api/vehicles/{id}") VehicleResponse updateVehicle(@PathVariable UUID id,@Valid @RequestBody VehicleUpdateRequest r) {
        return VehicleResponse.from(application.updateVehicle(id,r.plate(),r.manufacturer(),r.model(),r.modelYear(),r.mileage(),r.steeringGearManufacturer(),r.color(),r.notes()));
    }
    @PostMapping("/api/vehicles/{id}/owner") VehicleResponse transferVehicle(@PathVariable UUID id,@Valid @RequestBody TransferRequest r) { return VehicleResponse.from(application.transferVehicle(id,r.customerId())); }
    @GetMapping("/api/vehicles/{id}/ownership-history") List<OwnershipResponse> ownershipHistory(@PathVariable UUID id) {
        return application.ownershipHistory(id).stream().map(o->new OwnershipResponse(o.id(),o.customerId(),application.getCustomer(o.customerId()).name(),o.startedAt(),o.endedAt())).toList();
    }
    @PostMapping("/api/vehicles/{id}/inactivate") VehicleResponse inactivateVehicle(@PathVariable UUID id) { return VehicleResponse.from(application.inactivateVehicle(id)); }
    @PostMapping("/api/vehicles/{id}/reactivate") VehicleResponse reactivateVehicle(@PathVariable UUID id) { return VehicleResponse.from(application.reactivateVehicle(id)); }

    public record CustomerRequest(@NotNull Customer.PersonType personType,@NotBlank @Size(max=160) String name,@Size(max=24) String document,
                                  @NotBlank @Size(max=24) String phone,@Size(max=254) String email,@Valid AddressPayload address) {
        Customer.Address addressValue() { return address==null?null:address.toDomain(); }
    }
    public record AddressPayload(@Size(max=10) String zipCode,@Size(max=160) String street,@Size(max=20) String number,@Size(max=80) String complement,
                                 @Size(max=80) String district,@Size(max=80) String city,@Size(max=2) String state) {
        Customer.Address toDomain() { return new Customer.Address(zipCode,street,number,complement,district,city,state); }
        static AddressPayload from(Customer.Address a) { return a==null?null:new AddressPayload(a.zipCode(),a.street(),a.number(),a.complement(),a.district(),a.city(),a.state()); }
    }
    public record CustomerResponse(UUID id,Customer.PersonType personType,String name,String document,String phone,String email,AddressPayload address,
                                   Customer.Status status,Instant createdAt,Instant updatedAt) {
        static CustomerResponse from(Customer c){return new CustomerResponse(c.id(),c.personType(),c.name(),c.document(),c.phone(),c.email(),AddressPayload.from(c.address()),c.status(),c.createdAt(),c.updatedAt());}
    }
    public record PageResponse<T>(List<T> items,long totalItems,int page,int size,int totalPages) {}
    public record VehicleRequest(@NotNull UUID customerId,@NotBlank @Size(max=16) String plate,@NotBlank @Size(max=100) String manufacturer,@NotBlank @Size(max=100) String model,
                                 Integer modelYear,@PositiveOrZero Long mileage,@Size(max=100) String steeringGearManufacturer,@Size(max=40) String color,@Size(max=1000) String notes) {}
    public record VehicleUpdateRequest(@NotBlank @Size(max=16) String plate,@NotBlank @Size(max=100) String manufacturer,@NotBlank @Size(max=100) String model,
                                       Integer modelYear,@PositiveOrZero Long mileage,@Size(max=100) String steeringGearManufacturer,@Size(max=40) String color,@Size(max=1000) String notes) {}
    public record TransferRequest(@NotNull UUID customerId) {}
    public record VehicleResponse(UUID id,UUID customerId,String plate,String manufacturer,String model,Integer modelYear,Long mileage,String steeringGearManufacturer,
                                  String color,String notes,boolean active,Instant createdAt,Instant updatedAt) {
        static VehicleResponse from(Vehicle v){return new VehicleResponse(v.id(),v.customerId(),v.plate(),v.manufacturer(),v.model(),v.modelYear(),v.mileage(),v.steeringGearManufacturer(),v.color(),v.notes(),v.active(),v.createdAt(),v.updatedAt());}
    }
    public record VehicleListItem(VehicleResponse vehicle,String customerName) {}
    public record OwnershipResponse(UUID id,UUID customerId,String customerName,Instant startedAt,Instant endedAt) {}
}
