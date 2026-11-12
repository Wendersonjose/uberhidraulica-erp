package br.com.uberhidraulica.erp.productcatalog.port;

import br.com.uberhidraulica.erp.productcatalog.domain.CatalogProduct;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductCatalogRepositoryPort {
    CatalogProduct save(CatalogProduct product);
    Optional<CatalogProduct> findById(UUID id);
    List<CatalogProduct> findAll();

    /** Produto pelo GTIN, para o critério 2 de casamento da importação de NF-e (DR-0022 D6). */
    Optional<CatalogProduct> findByGtin(String gtin);
}
