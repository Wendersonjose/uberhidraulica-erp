package br.com.uberhidraulica.erp.servicecatalog.port;

import br.com.uberhidraulica.erp.servicecatalog.domain.CatalogService;
import br.com.uberhidraulica.erp.servicecatalog.domain.CatalogSetup;

import java.util.*;

public interface CatalogSetupRepositoryPort {
    CatalogSetup.Category saveCategory(CatalogSetup.Category category);
    Optional<CatalogSetup.Category> findCategory(UUID id);
    List<CatalogSetup.Category> listCategories();

    CatalogSetup.VehicleGroup saveGroup(CatalogSetup.VehicleGroup group);
    Optional<CatalogSetup.VehicleGroup> findGroup(UUID id);
    List<CatalogSetup.VehicleGroup> listGroups();
    List<UUID> groupVehicles(UUID groupId);
    Map<UUID, Long> groupVehicleCounts();
    Optional<UUID> groupOfVehicle(UUID vehicleId);
    /** Coloca o veículo no grupo, retirando-o do grupo anterior se houver. */
    void assignVehicle(UUID groupId, UUID vehicleId);
    boolean removeVehicle(UUID groupId, UUID vehicleId);

    CatalogSetup.ServicePrice savePrice(CatalogSetup.ServicePrice price);
    List<CatalogSetup.ServicePrice> listPrices(UUID serviceId);
    Optional<CatalogSetup.ServicePrice> findVehiclePrice(UUID serviceId, UUID vehicleId);
    Optional<CatalogSetup.ServicePrice> findGroupPrice(UUID serviceId, UUID groupId);
    boolean deletePrice(UUID serviceId, UUID priceId);

    ServicePage searchServices(String text, UUID categoryId, Boolean active, int page, int size);

    record ServicePage(List<CatalogService> items, long totalItems) {}
}
