package br.com.uberhidraulica.erp.workorder.port;
import br.com.uberhidraulica.erp.workorder.domain.WorkOrder;
import java.util.*;
public interface WorkOrderRepositoryPort {
    WorkOrder save(WorkOrder value);
    Optional<WorkOrder> findById(UUID id);
    List<WorkOrder> findAll();
    List<WorkOrder> findByCustomerId(UUID customerId);
    List<WorkOrder> findByVehicleId(UUID vehicleId);
    WorkOrder.ServiceItem addService(UUID workOrderId,WorkOrder.ServiceItem item);
    WorkOrder.ProductItem addProduct(UUID workOrderId,WorkOrder.ProductItem item);
}
