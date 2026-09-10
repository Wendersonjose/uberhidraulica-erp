package br.com.uberhidraulica.erp.crm;

import java.util.Optional;
import java.util.UUID;

public interface CustomerVehicleQuery {
    Optional<CustomerReference> customer(UUID id);
    Optional<VehicleReference> vehicle(UUID id);

    record CustomerReference(UUID id, String name, boolean active) {}
    record VehicleReference(UUID id, UUID customerId, String plate, String manufacturer, String model) {
        public boolean belongsTo(UUID expectedCustomerId) { return customerId.equals(expectedCustomerId); }
    }
}
