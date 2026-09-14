package br.com.uberhidraulica.erp.productcatalog.application;

import br.com.uberhidraulica.erp.productcatalog.ProductCatalogQuery;
import br.com.uberhidraulica.erp.productcatalog.domain.*;
import br.com.uberhidraulica.erp.productcatalog.port.ProductCatalogRepositoryPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProductCatalogApplicationService implements ProductCatalogQuery {
    private final ProductCatalogRepositoryPort repository;

    public ProductCatalogApplicationService(ProductCatalogRepositoryPort repository) {
        this.repository = repository;
    }

    @Transactional
    public CatalogProduct create(String description, String internalCode, String category, ProductType type,
                                 ProductUnit unit, BigDecimal referenceCost, BigDecimal salePrice,
                                 BigDecimal minimumStock) {
        CatalogProduct product = CatalogProduct.create(description, internalCode, category, type, unit,
                referenceCost, salePrice, minimumStock, Instant.now());
        return persist(product);
    }

    @Transactional(readOnly = true)
    public CatalogProduct get(UUID id) {
        return repository.findById(id).orElseThrow(ProductCatalogApplicationService::notFound);
    }

    @Transactional(readOnly = true)
    public List<CatalogProduct> list() { return repository.findAll(); }

    @Transactional
    public CatalogProduct update(UUID id, String description, String internalCode, String category, ProductType type,
                                 ProductUnit unit, BigDecimal referenceCost, BigDecimal salePrice,
                                 BigDecimal minimumStock, Boolean active) {
        CatalogProduct current = get(id);
        return persist(current.update(description, internalCode, category, type, unit, referenceCost, salePrice,
                minimumStock, active, Instant.now()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductReference> product(UUID id) {
        return repository.findById(id).map(p -> new ProductReference(p.id(), p.description(), p.internalCode(),
                p.unit().name(), p.salePrice(), p.active()));
    }

    /** A unicidade do código interno é garantida pelo índice do PostgreSQL, não por leitura prévia. */
    private CatalogProduct persist(CatalogProduct product) {
        try {
            return repository.save(product);
        } catch (DataIntegrityViolationException exception) {
            throw new ProductCatalogException("PRODUCT_INTERNAL_CODE_ALREADY_EXISTS", "Código interno já cadastrado");
        }
    }

    private static ProductCatalogException notFound() {
        return new ProductCatalogException("PRODUCT_NOT_FOUND", "Produto não encontrado");
    }
}
