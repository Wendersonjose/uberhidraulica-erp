package br.com.uberhidraulica.erp.crm.application;

import br.com.uberhidraulica.erp.crm.CustomerVehicleQuery;
import br.com.uberhidraulica.erp.crm.domain.*;
import br.com.uberhidraulica.erp.crm.port.*;
import br.com.uberhidraulica.erp.iam.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class CrmApplicationService implements CustomerVehicleQuery {
    private final CustomerRepositoryPort customers;
    private final VehicleRepositoryPort vehicles;
    private final CurrentUser currentUser;
    public CrmApplicationService(CustomerRepositoryPort customers, VehicleRepositoryPort vehicles, CurrentUser currentUser) {
        this.customers = customers; this.vehicles = vehicles; this.currentUser = currentUser;
    }

    @Transactional public Customer createCustomer(Customer.PersonType type, String name, String document, String phone,
                                                  String email, Customer.Address address) {
        try { return customers.save(Customer.create(type, name, document, phone, email, address, Instant.now())); }
        catch (DataIntegrityViolationException e) { throw documentAlreadyExists(); }
    }
    @Transactional(readOnly=true) public Customer getCustomer(UUID id) { return customers.findById(id).orElseThrow(() -> new CrmException("CUSTOMER_NOT_FOUND", "Cliente não encontrado")); }
    @Transactional(readOnly=true) public List<Customer> listCustomers() { return customers.findAll(); }
    @Transactional(readOnly=true) public CustomerSearch.Page<Customer> searchCustomers(CustomerSearch criteria) { return customers.search(criteria); }
    @Transactional public Customer updateCustomer(UUID id, Customer.PersonType type, String name, String document, String phone,
                                                  String email, Customer.Address address) {
        Customer current = getCustomer(id);
        try { return customers.save(current.update(type, name, document, phone, email, address, Instant.now())); }
        catch (DataIntegrityViolationException e) { throw documentAlreadyExists(); }
    }
    /** Inativação lógica: o cliente, seus veículos e o histórico de OS permanecem consultáveis. */
    @Transactional public Customer inactivateCustomer(UUID id) { return customers.save(getCustomer(id).inactivate(Instant.now())); }
    @Transactional public Customer reactivateCustomer(UUID id) { return customers.save(getCustomer(id).reactivate(Instant.now())); }
    private static CrmException documentAlreadyExists() { return new CrmException("CUSTOMER_DOCUMENT_ALREADY_EXISTS", "CPF/CNPJ já cadastrado"); }

    @Transactional public Vehicle createVehicle(UUID customerId, String plate, String manufacturer, String model, Integer year,
                                                Long mileage, String steering, String color, String notes) {
        requireActiveCustomer(customerId);
        Instant now = Instant.now();
        try {
            Vehicle vehicle = vehicles.save(Vehicle.create(customerId, plate, manufacturer, model, year, mileage, steering, color, notes, now));
            vehicles.openOwnership(new Vehicle.Ownership(UUID.randomUUID(), vehicle.id(), customerId, now, null, currentUser.id().orElse(null)));
            return vehicle;
        } catch (DataIntegrityViolationException e) { throw plateAlreadyExists(); }
    }
    @Transactional(readOnly=true) public Vehicle getVehicle(UUID id) { return vehicles.findById(id).orElseThrow(CrmApplicationService::vehicleNotFound); }
    @Transactional(readOnly=true) public List<Vehicle> listVehicles(UUID customerId) { getCustomer(customerId); return vehicles.findByCustomerId(customerId); }
    @Transactional(readOnly=true) public CustomerSearch.Page<VehicleSearch.Row> searchVehicles(VehicleSearch criteria) { return vehicles.search(criteria); }
    @Transactional public Vehicle updateVehicle(UUID id, String plate, String manufacturer, String model, Integer year, Long mileage,
                                                String steering, String color, String notes) {
        Vehicle current = getVehicle(id);
        try { return vehicles.save(current.update(plate, manufacturer, model, year, mileage, steering, color, notes, Instant.now())); }
        catch (DataIntegrityViolationException e) { throw plateAlreadyExists(); }
    }

    /**
     * Troca o proprietário atual encerrando o período anterior no histórico.
     *
     * <p>O veículo é bloqueado para que duas trocas simultâneas não abram dois períodos. OS existentes
     * não são tocadas: guardam o cliente da abertura.</p>
     */
    @Transactional public Vehicle transferVehicle(UUID id, UUID newCustomerId) {
        Vehicle current = vehicles.findByIdForUpdate(id).orElseThrow(CrmApplicationService::vehicleNotFound);
        requireActiveCustomer(newCustomerId);
        Instant now = Instant.now();
        Vehicle transferred = vehicles.save(current.transferTo(newCustomerId, now));
        vehicles.closeCurrentOwnership(id, now);
        vehicles.openOwnership(new Vehicle.Ownership(UUID.randomUUID(), id, newCustomerId, now, null, currentUser.id().orElse(null)));
        return transferred;
    }
    @Transactional(readOnly=true) public List<Vehicle.Ownership> ownershipHistory(UUID id) { getVehicle(id); return vehicles.ownershipHistory(id); }
    @Transactional public Vehicle inactivateVehicle(UUID id) { return vehicles.save(getVehicle(id).inactivate(Instant.now())); }
    @Transactional public Vehicle reactivateVehicle(UUID id) { return vehicles.save(getVehicle(id).reactivate(Instant.now())); }

    private void requireActiveCustomer(UUID customerId) {
        if (getCustomer(customerId).status() != Customer.Status.ACTIVE)
            throw new CrmException("CUSTOMER_INACTIVE", "Cliente inativo não pode receber veículos");
    }
    private static CrmException vehicleNotFound() { return new CrmException("VEHICLE_NOT_FOUND", "Veículo não encontrado"); }
    private static CrmException plateAlreadyExists() { return new CrmException("VEHICLE_PLATE_ALREADY_EXISTS", "Placa já cadastrada"); }

    @Override @Transactional(readOnly=true) public Optional<CustomerReference> customer(UUID id) {
        return customers.findById(id).map(c -> new CustomerReference(c.id(), c.name(), c.status() == Customer.Status.ACTIVE));
    }
    @Override @Transactional(readOnly=true) public Optional<VehicleReference> vehicle(UUID id) {
        return vehicles.findById(id).map(v -> new VehicleReference(v.id(), v.customerId(), v.plate(), v.manufacturer(), v.model(), v.active()));
    }
}
