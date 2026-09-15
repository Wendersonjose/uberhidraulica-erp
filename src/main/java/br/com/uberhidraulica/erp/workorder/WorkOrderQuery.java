package br.com.uberhidraulica.erp.workorder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Contrato público da Ordem de Serviço.
 *
 * <p>Expõe a existência da OS e os itens que podem originar um item comercial. Não expõe entidade
 * JPA, repositório nem o estado interno da OS.</p>
 */
public interface WorkOrderQuery {
    Optional<WorkOrderReference> workOrder(UUID id);

    /** Serviços lançados na OS, na ordem de inclusão, já com o snapshot comercial da OS. */
    List<ServiceItemReference> serviceItems(UUID workOrderId);

    record WorkOrderReference(UUID id, Long number, UUID customerId, UUID vehicleId) {}

    record ServiceItemReference(UUID id, UUID serviceId, String name, String description, BigDecimal basePrice) {}
}
