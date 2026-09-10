package br.com.uberhidraulica.erp.servicecatalog.infrastructure.persistence;

import br.com.uberhidraulica.erp.servicecatalog.domain.CatalogService;
import br.com.uberhidraulica.erp.servicecatalog.port.ServiceCatalogRepositoryPort;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class JpaServiceCatalogRepositoryAdapter implements ServiceCatalogRepositoryPort {
    private final ServiceJpaRepository repository;

    JpaServiceCatalogRepositoryAdapter(ServiceJpaRepository repository) { this.repository = repository; }

    @Override public CatalogService save(CatalogService value) { return map(repository.saveAndFlush(entity(value))); }
    @Override public Optional<CatalogService> findById(UUID id) { return repository.findById(id).map(this::map); }
    @Override public List<CatalogService> findAll() { return repository.findAllByOrderByNameAscIdAsc().stream().map(this::map).toList(); }

    private ServiceEntity entity(CatalogService value) {
        ServiceEntity e = new ServiceEntity();
        e.id=value.id(); e.name=value.name(); e.description=value.description(); e.category=value.category();
        e.basePrice=value.basePrice(); e.defaultWarrantyDays=value.defaultWarrantyDays(); e.active=value.active();
        e.createdAt=value.createdAt(); e.updatedAt=value.updatedAt(); return e;
    }

    private CatalogService map(ServiceEntity e) {
        return new CatalogService(e.id, e.name, e.description, e.category, e.basePrice,
                e.defaultWarrantyDays, e.active, e.createdAt, e.updatedAt);
    }
}
