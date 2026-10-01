package br.com.uberhidraulica.erp.servicecatalog;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
public interface ServiceCatalogQuery {
    Optional<ServiceReference> service(UUID id);

    /**
     * Preço sugerido do serviço para o veículo: preço do veículo, depois do grupo ativo do veículo,
     * depois o preço base. {@code price} é nulo quando nenhuma dessas fontes define valor.
     */
    PriceSuggestion suggestPrice(UUID serviceId, UUID vehicleId);

    record ServiceReference(UUID id,String name,String description,BigDecimal basePrice,int defaultWarrantyDays,boolean active) {}
    record PriceSuggestion(UUID serviceId, UUID vehicleId, BigDecimal price, String source) {}
}
