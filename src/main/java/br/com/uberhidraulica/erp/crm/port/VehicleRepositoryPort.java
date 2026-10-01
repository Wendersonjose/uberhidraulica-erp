package br.com.uberhidraulica.erp.crm.port;
import br.com.uberhidraulica.erp.crm.domain.CustomerSearch;
import br.com.uberhidraulica.erp.crm.domain.Vehicle;
import br.com.uberhidraulica.erp.crm.domain.VehicleSearch;
import java.time.Instant;
import java.util.*;
public interface VehicleRepositoryPort {
    Vehicle save(Vehicle vehicle);
    Optional<Vehicle> findById(UUID id);
    /** Bloqueia o veículo até o fim da transação, serializando trocas de proprietário concorrentes. */
    Optional<Vehicle> findByIdForUpdate(UUID id);
    List<Vehicle> findByCustomerId(UUID customerId);
    CustomerSearch.Page<VehicleSearch.Row> search(VehicleSearch criteria);
    void openOwnership(Vehicle.Ownership ownership);
    void closeCurrentOwnership(UUID vehicleId, Instant endedAt);
    List<Vehicle.Ownership> ownershipHistory(UUID vehicleId);
}
