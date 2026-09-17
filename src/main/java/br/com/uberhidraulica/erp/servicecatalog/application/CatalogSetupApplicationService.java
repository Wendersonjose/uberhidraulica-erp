package br.com.uberhidraulica.erp.servicecatalog.application;

import br.com.uberhidraulica.erp.crm.CustomerVehicleQuery;
import br.com.uberhidraulica.erp.servicecatalog.domain.CatalogSetup;
import br.com.uberhidraulica.erp.servicecatalog.domain.ServiceCatalogException;
import br.com.uberhidraulica.erp.servicecatalog.port.CatalogSetupRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Categorias de serviço e grupos de veículos. */
@Service
public class CatalogSetupApplicationService {
    private final CatalogSetupRepositoryPort setup;
    private final CustomerVehicleQuery crm;

    public CatalogSetupApplicationService(CatalogSetupRepositoryPort setup, CustomerVehicleQuery crm) {
        this.setup = setup;
        this.crm = crm;
    }

    @Transactional(readOnly = true) public List<CatalogSetup.Category> categories() { return setup.listCategories(); }

    @Transactional
    public CatalogSetup.Category createCategory(String name) {
        return ServiceCatalogApplicationService.uniqueName(() -> setup.saveCategory(CatalogSetup.Category.create(name, now())),
                "CATEGORY_NAME_ALREADY_EXISTS", "Já existe categoria com este nome");
    }

    @Transactional
    public CatalogSetup.Category renameCategory(UUID id, String name) {
        var current = category(id);
        return ServiceCatalogApplicationService.uniqueName(() -> setup.saveCategory(current.rename(name, now())),
                "CATEGORY_NAME_ALREADY_EXISTS", "Já existe categoria com este nome");
    }

    @Transactional
    public CatalogSetup.Category setCategoryActive(UUID id, boolean active) {
        return setup.saveCategory(category(id).withActive(active, now()));
    }

    private CatalogSetup.Category category(UUID id) {
        return setup.findCategory(id).orElseThrow(() -> new ServiceCatalogException("CATEGORY_NOT_FOUND", "Categoria não encontrada"));
    }

    @Transactional(readOnly = true) public List<CatalogSetup.VehicleGroup> groups() { return setup.listGroups(); }
    @Transactional(readOnly = true) public Map<UUID, Long> groupVehicleCounts() { return setup.groupVehicleCounts(); }
    @Transactional(readOnly = true) public CatalogSetup.VehicleGroup group(UUID id) { return setup.findGroup(id).orElseThrow(CatalogSetupApplicationService::groupNotFound); }

    @Transactional
    public CatalogSetup.VehicleGroup createGroup(String name, String description) {
        return ServiceCatalogApplicationService.uniqueName(() -> setup.saveGroup(CatalogSetup.VehicleGroup.create(name, description, now())),
                "VEHICLE_GROUP_NAME_ALREADY_EXISTS", "Já existe grupo com este nome");
    }

    @Transactional
    public CatalogSetup.VehicleGroup updateGroup(UUID id, String name, String description) {
        var current = group(id);
        return ServiceCatalogApplicationService.uniqueName(() -> setup.saveGroup(current.update(name, description, now())),
                "VEHICLE_GROUP_NAME_ALREADY_EXISTS", "Já existe grupo com este nome");
    }

    @Transactional
    public CatalogSetup.VehicleGroup setGroupActive(UUID id, boolean active) {
        return setup.saveGroup(group(id).withActive(active, now()));
    }

    /** Veículos do grupo com placa e descrição obtidas pelo contrato público do CRM. */
    @Transactional(readOnly = true)
    public List<CustomerVehicleQuery.VehicleReference> groupVehicles(UUID groupId) {
        group(groupId);
        return setup.groupVehicles(groupId).stream().map(crm::vehicle).flatMap(Optional::stream).toList();
    }

    @Transactional(readOnly = true)
    public Optional<CatalogSetup.VehicleGroup> groupOfVehicle(UUID vehicleId) {
        return setup.groupOfVehicle(vehicleId).flatMap(setup::findGroup);
    }

    @Transactional
    public void assignVehicle(UUID groupId, UUID vehicleId) {
        if (!group(groupId).active()) throw new ServiceCatalogException("VEHICLE_GROUP_INACTIVE", "Grupo inativo não recebe veículos");
        crm.vehicle(vehicleId).orElseThrow(() -> new ServiceCatalogException("VEHICLE_NOT_FOUND", "Veículo não encontrado"));
        setup.assignVehicle(groupId, vehicleId);
    }

    @Transactional
    public void removeVehicle(UUID groupId, UUID vehicleId) {
        group(groupId);
        if (!setup.removeVehicle(groupId, vehicleId))
            throw new ServiceCatalogException("VEHICLE_NOT_IN_GROUP", "Veículo não pertence a este grupo");
    }

    static ServiceCatalogException groupNotFound() {
        return new ServiceCatalogException("VEHICLE_GROUP_NOT_FOUND", "Grupo de veículos não encontrado");
    }

    private static Instant now() { return Instant.now(Clock.systemUTC()); }
}
