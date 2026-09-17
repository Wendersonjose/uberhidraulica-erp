package br.com.uberhidraulica.erp.workorder.port;
import br.com.uberhidraulica.erp.workorder.domain.WorkOrder;
import br.com.uberhidraulica.erp.workorder.domain.WorkflowStatus;
import java.util.*;
public interface WorkOrderRepositoryPort {
    WorkOrder save(WorkOrder value);
    Optional<WorkOrder> findById(UUID id);
    /** Bloqueia a OS até o fim da transação, serializando mudanças de status concorrentes. */
    Optional<WorkOrder> findByIdForUpdate(UUID id);
    List<WorkOrder> findAll();
    List<WorkOrder> findByCustomerId(UUID customerId);
    List<WorkOrder> findByVehicleId(UUID vehicleId);
    WorkOrder.ServiceItem addService(UUID workOrderId,WorkOrder.ServiceItem item);
    WorkOrder.ProductItem addProduct(UUID workOrderId,WorkOrder.ProductItem item);

    void addStatusChange(WorkOrder.StatusChange change);
    List<WorkOrder.StatusChange> statusHistory(UUID workOrderId);

    List<WorkflowStatus> statuses();
    Optional<WorkflowStatus> findStatus(UUID id);
    WorkflowStatus saveStatus(WorkflowStatus status);
    long countOrdersInStatus(UUID statusId);
}
