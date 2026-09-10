package br.com.uberhidraulica.erp.servicecatalog;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
public interface ServiceCatalogQuery {
    Optional<ServiceReference> service(UUID id);
    record ServiceReference(UUID id,String name,String description,BigDecimal basePrice,int defaultWarrantyDays,boolean active) {}
}
