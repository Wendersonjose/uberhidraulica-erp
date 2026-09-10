package br.com.uberhidraulica.erp.crm.application;

import br.com.uberhidraulica.erp.crm.CustomerVehicleQuery;
import br.com.uberhidraulica.erp.crm.domain.*;
import br.com.uberhidraulica.erp.crm.port.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class CrmApplicationService implements CustomerVehicleQuery {
    private final CustomerRepositoryPort customers;
    private final VehicleRepositoryPort vehicles;
    public CrmApplicationService(CustomerRepositoryPort customers, VehicleRepositoryPort vehicles) {
        this.customers = customers; this.vehicles = vehicles;
    }

    @Transactional public Customer createCustomer(Customer.PersonType type, String name, String document, Customer.Status status) {
        try { return customers.save(Customer.create(type, name, document, status, Instant.now())); }
        catch (DataIntegrityViolationException e) { throw new CrmException("CUSTOMER_DOCUMENT_ALREADY_EXISTS", "CPF/CNPJ já cadastrado"); }
    }
    @Transactional(readOnly=true) public Customer getCustomer(UUID id) { return customers.findById(id).orElseThrow(() -> new CrmException("CUSTOMER_NOT_FOUND", "Cliente não encontrado")); }
    @Transactional(readOnly=true) public List<Customer> listCustomers() { return customers.findAll(); }
    @Transactional public Customer updateCustomer(UUID id, Customer.PersonType type, String name, String document, Customer.Status status) {
        Customer current = getCustomer(id);
        try { return customers.save(current.update(type, name, document, status, Instant.now())); }
        catch (DataIntegrityViolationException e) { throw new CrmException("CUSTOMER_DOCUMENT_ALREADY_EXISTS", "CPF/CNPJ já cadastrado"); }
    }

    @Transactional public Vehicle createVehicle(UUID customerId, String plate, String manufacturer, String model,
                                                int year, Long mileage, String steering) {
        getCustomer(customerId);
        try { return vehicles.save(Vehicle.create(customerId, plate, manufacturer, model, year, mileage, steering, Instant.now())); }
        catch (DataIntegrityViolationException e) { throw new CrmException("VEHICLE_PLATE_ALREADY_EXISTS", "Placa já cadastrada"); }
    }
    @Transactional(readOnly=true) public Vehicle getVehicle(UUID id) { return vehicles.findById(id).orElseThrow(() -> new CrmException("VEHICLE_NOT_FOUND", "Veículo não encontrado")); }
    @Transactional(readOnly=true) public List<Vehicle> listVehicles(UUID customerId) { getCustomer(customerId); return vehicles.findByCustomerId(customerId); }
    @Transactional public Vehicle updateVehicle(UUID id, String plate, String manufacturer, String model, int year, Long mileage, String steering) {
        Vehicle current = getVehicle(id);
        try { return vehicles.save(current.update(plate, manufacturer, model, year, mileage, steering, Instant.now())); }
        catch (DataIntegrityViolationException e) { throw new CrmException("VEHICLE_PLATE_ALREADY_EXISTS", "Placa já cadastrada"); }
    }

    @Override @Transactional(readOnly=true) public Optional<CustomerReference> customer(UUID id) {
        return customers.findById(id).map(c -> new CustomerReference(c.id(), c.name(), c.status() == Customer.Status.ACTIVE));
    }
    @Override @Transactional(readOnly=true) public Optional<VehicleReference> vehicle(UUID id) {
        return vehicles.findById(id).map(v -> new VehicleReference(v.id(), v.customerId(), v.plate(), v.manufacturer(), v.model()));
    }
}
