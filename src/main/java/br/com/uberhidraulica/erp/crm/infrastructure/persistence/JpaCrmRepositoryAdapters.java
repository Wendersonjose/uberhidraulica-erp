package br.com.uberhidraulica.erp.crm.infrastructure.persistence;
import br.com.uberhidraulica.erp.crm.domain.*;
import br.com.uberhidraulica.erp.crm.port.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
class JpaCustomerRepositoryAdapter implements CustomerRepositoryPort {
    private final CustomerJpaRepository repository;
    JpaCustomerRepositoryAdapter(CustomerJpaRepository repository) { this.repository=repository; }
    public Customer save(Customer c) { CustomerEntity e=new CustomerEntity(); e.id=c.id();e.personType=c.personType();e.name=c.name();e.document=c.document();e.status=c.status();e.createdAt=c.createdAt();e.updatedAt=c.updatedAt(); return map(repository.saveAndFlush(e)); }
    public Optional<Customer> findById(UUID id) { return repository.findById(id).map(this::map); }
    public List<Customer> findAll() { return repository.findAllByOrderByNameAscIdAsc().stream().map(this::map).toList(); }
    private Customer map(CustomerEntity e) { return new Customer(e.id,e.personType,e.name,e.document,e.status,e.createdAt,e.updatedAt); }
}

@Component
class JpaVehicleRepositoryAdapter implements VehicleRepositoryPort {
    private final VehicleJpaRepository repository;
    JpaVehicleRepositoryAdapter(VehicleJpaRepository repository) { this.repository=repository; }
    public Vehicle save(Vehicle v) { VehicleEntity e=new VehicleEntity();e.id=v.id();e.customerId=v.customerId();e.plate=v.plate();e.manufacturer=v.manufacturer();e.model=v.model();e.modelYear=v.modelYear();e.mileage=v.mileage();e.steeringGearManufacturer=v.steeringGearManufacturer();e.createdAt=v.createdAt();e.updatedAt=v.updatedAt();return map(repository.saveAndFlush(e)); }
    public Optional<Vehicle> findById(UUID id) { return repository.findById(id).map(this::map); }
    public List<Vehicle> findByCustomerId(UUID id) { return repository.findByCustomerIdOrderByModelAscIdAsc(id).stream().map(this::map).toList(); }
    private Vehicle map(VehicleEntity e) { return new Vehicle(e.id,e.customerId,e.plate,e.manufacturer,e.model,e.modelYear,e.mileage,e.steeringGearManufacturer,e.createdAt,e.updatedAt); }
}
