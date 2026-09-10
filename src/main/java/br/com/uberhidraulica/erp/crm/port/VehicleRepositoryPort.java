package br.com.uberhidraulica.erp.crm.port;
import br.com.uberhidraulica.erp.crm.domain.Vehicle;
import java.util.*;
public interface VehicleRepositoryPort {
    Vehicle save(Vehicle vehicle);
    Optional<Vehicle> findById(UUID id);
    List<Vehicle> findByCustomerId(UUID customerId);
}
