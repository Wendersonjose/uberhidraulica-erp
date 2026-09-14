package br.com.uberhidraulica.erp.productcatalog.port;

import br.com.uberhidraulica.erp.productcatalog.domain.CatalogProduct;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductCatalogRepositoryPort {
    CatalogProduct save(CatalogProduct product);
    Optional<CatalogProduct> findById(UUID id);
    List<CatalogProduct> findAll();
}
