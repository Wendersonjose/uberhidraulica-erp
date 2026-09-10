package br.com.uberhidraulica.erp.servicecatalog.port;

import br.com.uberhidraulica.erp.servicecatalog.domain.CatalogService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceCatalogRepositoryPort {
    CatalogService save(CatalogService service);
    Optional<CatalogService> findById(UUID id);
    List<CatalogService> findAll();
}
