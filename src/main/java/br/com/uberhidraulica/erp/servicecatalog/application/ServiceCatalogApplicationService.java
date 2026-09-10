package br.com.uberhidraulica.erp.servicecatalog.application;

import br.com.uberhidraulica.erp.servicecatalog.domain.*;
import br.com.uberhidraulica.erp.servicecatalog.ServiceCatalogQuery;
import br.com.uberhidraulica.erp.servicecatalog.port.ServiceCatalogRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ServiceCatalogApplicationService implements ServiceCatalogQuery {
    private final ServiceCatalogRepositoryPort repository;

    public ServiceCatalogApplicationService(ServiceCatalogRepositoryPort repository) {
        this.repository = repository;
    }

    @Transactional
    public CatalogService create(String name, String description, String category, BigDecimal basePrice,
                                 Integer warrantyDays, Boolean active) {
        return repository.save(CatalogService.create(name, description, category, basePrice, warrantyDays, active, Instant.now(Clock.systemUTC())));
    }

    @Transactional(readOnly = true)
    public CatalogService get(UUID id) { return repository.findById(id).orElseThrow(ServiceCatalogApplicationService::notFound); }

    @Transactional(readOnly = true)
    public List<CatalogService> list() { return repository.findAll(); }

    @Override @Transactional(readOnly = true)
    public java.util.Optional<ServiceReference> service(UUID id) {
        return repository.findById(id).map(s -> new ServiceReference(s.id(), s.name(), s.description(),
                s.basePrice(), s.defaultWarrantyDays(), s.active()));
    }

    @Transactional
    public CatalogService update(UUID id, String name, String description, String category, BigDecimal basePrice,
                                 Integer warrantyDays, Boolean active) {
        CatalogService current = get(id);
        return repository.save(current.update(name, description, category, basePrice, warrantyDays, active, Instant.now(Clock.systemUTC())));
    }

    private static ServiceCatalogException notFound() {
        return new ServiceCatalogException("SERVICE_NOT_FOUND", "Serviço não encontrado");
    }
}
