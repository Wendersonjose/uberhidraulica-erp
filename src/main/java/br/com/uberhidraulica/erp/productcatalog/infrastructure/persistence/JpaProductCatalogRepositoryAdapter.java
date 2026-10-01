package br.com.uberhidraulica.erp.productcatalog.infrastructure.persistence;

import br.com.uberhidraulica.erp.productcatalog.domain.CatalogProduct;
import br.com.uberhidraulica.erp.productcatalog.port.ProductCatalogRepositoryPort;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JpaProductCatalogRepositoryAdapter implements ProductCatalogRepositoryPort {
    private final ProductJpaRepository repository;

    JpaProductCatalogRepositoryAdapter(ProductJpaRepository repository) { this.repository = repository; }

    @Override public CatalogProduct save(CatalogProduct value) { return map(repository.saveAndFlush(entity(value))); }
    @Override public Optional<CatalogProduct> findById(UUID id) { return repository.findById(id).map(this::map); }
    @Override public List<CatalogProduct> findAll() {
        return repository.findAllByOrderByDescriptionAscIdAsc().stream().map(this::map).toList();
    }

    private ProductEntity entity(CatalogProduct value) {
        ProductEntity e = new ProductEntity();
        e.id = value.id(); e.description = value.description(); e.internalCode = value.internalCode();
        e.category = value.category(); e.type = value.type(); e.unit = value.unit();
        e.referenceCost = value.referenceCost(); e.salePrice = value.salePrice(); e.minimumStock = value.minimumStock();
        e.active = value.active(); e.createdAt = value.createdAt(); e.updatedAt = value.updatedAt();
        return e;
    }

    private CatalogProduct map(ProductEntity e) {
        return new CatalogProduct(e.id, e.description, e.internalCode, e.category, e.type, e.unit,
                e.referenceCost, e.salePrice, e.minimumStock, e.active, e.createdAt, e.updatedAt);
    }
}
