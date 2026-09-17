package br.com.uberhidraulica.erp.servicecatalog.application;

import br.com.uberhidraulica.erp.crm.CustomerVehicleQuery;
import br.com.uberhidraulica.erp.servicecatalog.ServiceCatalogQuery;
import br.com.uberhidraulica.erp.servicecatalog.domain.*;
import br.com.uberhidraulica.erp.servicecatalog.port.CatalogSetupRepositoryPort;
import br.com.uberhidraulica.erp.servicecatalog.port.ServiceCatalogRepositoryPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
public class ServiceCatalogApplicationService implements ServiceCatalogQuery {
    public static final int MAX_PAGE_SIZE = 100;

    private final ServiceCatalogRepositoryPort repository;
    private final CatalogSetupRepositoryPort setup;
    private final CustomerVehicleQuery crm;

    public ServiceCatalogApplicationService(ServiceCatalogRepositoryPort repository, CatalogSetupRepositoryPort setup, CustomerVehicleQuery crm) {
        this.repository = repository;
        this.setup = setup;
        this.crm = crm;
    }

    // ------------------------------------------------------------------ serviços

    @Transactional
    public CatalogService create(String name, String description, UUID categoryId, BigDecimal basePrice, Integer warrantyDays, Boolean active) {
        requireUsableCategory(categoryId, null);
        return repository.save(CatalogService.create(name, description, categoryId, basePrice, warrantyDays, active, now()));
    }

    @Transactional(readOnly = true)
    public CatalogService get(UUID id) { return repository.findById(id).orElseThrow(ServiceCatalogApplicationService::notFound); }

    @Transactional(readOnly = true)
    public List<CatalogService> list() { return repository.findAll(); }

    @Transactional(readOnly = true)
    public Page<CatalogService> search(String text, UUID categoryId, Boolean active, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE)
            throw new ServiceCatalogException("INVALID_SERVICE", "Página inválida ou tamanho fora de 1 a " + MAX_PAGE_SIZE);
        var result = setup.searchServices(text == null || text.isBlank() ? null : text.trim(), categoryId, active, page, size);
        return new Page<>(result.items(), result.totalItems(), page, size);
    }

    @Transactional
    public CatalogService update(UUID id, String name, String description, UUID categoryId, BigDecimal basePrice, Integer warrantyDays, Boolean active) {
        CatalogService current = get(id);
        requireUsableCategory(categoryId, current.categoryId());
        return repository.save(current.update(name, description, categoryId, basePrice, warrantyDays, active, now()));
    }

    @Transactional public CatalogService inactivate(UUID id) { return repository.save(get(id).inactivate(now())); }
    @Transactional public CatalogService reactivate(UUID id) { return repository.save(get(id).reactivate(now())); }

    /** Categoria inativa só é aceita quando o serviço já estava nela (DR-0011). */
    private void requireUsableCategory(UUID categoryId, UUID currentCategoryId) {
        if (categoryId == null) return;
        var category = setup.findCategory(categoryId)
                .orElseThrow(() -> new ServiceCatalogException("CATEGORY_NOT_FOUND", "Categoria não encontrada"));
        if (!category.active() && !categoryId.equals(currentCategoryId))
            throw new ServiceCatalogException("CATEGORY_INACTIVE", "Categoria inativa não pode receber serviços");
    }

    // ------------------------------------------------------------------ preços

    @Transactional(readOnly = true)
    public List<CatalogSetup.ServicePrice> prices(UUID serviceId) {
        get(serviceId);
        return setup.listPrices(serviceId);
    }

    @Transactional
    public CatalogSetup.ServicePrice setVehiclePrice(UUID serviceId, UUID vehicleId, BigDecimal price) {
        get(serviceId);
        crm.vehicle(vehicleId).orElseThrow(() -> new ServiceCatalogException("VEHICLE_NOT_FOUND", "Veículo não encontrado"));
        Instant now = now();
        var existing = setup.findVehiclePrice(serviceId, vehicleId);
        return setup.savePrice(new CatalogSetup.ServicePrice(existing.map(CatalogSetup.ServicePrice::id).orElseGet(UUID::randomUUID),
                serviceId, vehicleId, null, price, existing.map(CatalogSetup.ServicePrice::createdAt).orElse(now), now));
    }

    @Transactional
    public CatalogSetup.ServicePrice setGroupPrice(UUID serviceId, UUID groupId, BigDecimal price) {
        get(serviceId);
        setup.findGroup(groupId).orElseThrow(CatalogSetupApplicationService::groupNotFound);
        Instant now = now();
        var existing = setup.findGroupPrice(serviceId, groupId);
        return setup.savePrice(new CatalogSetup.ServicePrice(existing.map(CatalogSetup.ServicePrice::id).orElseGet(UUID::randomUUID),
                serviceId, null, groupId, price, existing.map(CatalogSetup.ServicePrice::createdAt).orElse(now), now));
    }

    @Transactional
    public void deletePrice(UUID serviceId, UUID priceId) {
        get(serviceId);
        if (!setup.deletePrice(serviceId, priceId)) throw new ServiceCatalogException("SERVICE_PRICE_NOT_FOUND", "Preço não encontrado");
    }

    @Override
    @Transactional(readOnly = true)
    public PriceSuggestion suggestPrice(UUID serviceId, UUID vehicleId) {
        CatalogService service = get(serviceId);
        if (vehicleId != null) {
            var vehiclePrice = setup.findVehiclePrice(serviceId, vehicleId);
            if (vehiclePrice.isPresent())
                return new PriceSuggestion(serviceId, vehicleId, vehiclePrice.get().price(), CatalogSetup.PriceSource.VEHICLE.name());
            var groupPrice = setup.groupOfVehicle(vehicleId)
                    .flatMap(setup::findGroup)
                    .filter(CatalogSetup.VehicleGroup::active)
                    .flatMap(group -> setup.findGroupPrice(serviceId, group.id()));
            if (groupPrice.isPresent())
                return new PriceSuggestion(serviceId, vehicleId, groupPrice.get().price(), CatalogSetup.PriceSource.GROUP.name());
        }
        if (service.basePrice() != null)
            return new PriceSuggestion(serviceId, vehicleId, service.basePrice(), CatalogSetup.PriceSource.BASE.name());
        return new PriceSuggestion(serviceId, vehicleId, null, CatalogSetup.PriceSource.NONE.name());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ServiceReference> service(UUID id) {
        return repository.findById(id).map(s -> new ServiceReference(s.id(), s.name(), s.description(),
                s.basePrice(), s.defaultWarrantyDays(), s.active()));
    }

    private static Instant now() { return Instant.now(Clock.systemUTC()); }

    static ServiceCatalogException notFound() {
        return new ServiceCatalogException("SERVICE_NOT_FOUND", "Serviço não encontrado");
    }

    static <T> T uniqueName(java.util.function.Supplier<T> action, String code, String message) {
        try { return action.get(); }
        catch (DataIntegrityViolationException e) { throw new ServiceCatalogException(code, message); }
    }

    public record Page<T>(List<T> items, long totalItems, int page, int size) {
        public int totalPages() { return (int) ((totalItems + size - 1) / size); }
    }
}
