package br.com.uberhidraulica.erp.workorder.port;
import br.com.uberhidraulica.erp.workorder.domain.WorkOrder;
import java.util.*;
public interface WorkOrderRepositoryPort {
    WorkOrder save(WorkOrder value);
    Optional<WorkOrder> findById(UUID id);
    List<WorkOrder> findAll();
    WorkOrder.ServiceItem addService(UUID workOrderId,WorkOrder.ServiceItem item);
}
