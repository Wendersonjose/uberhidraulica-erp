package br.com.uberhidraulica.erp.workorder.infrastructure.persistence;

import br.com.uberhidraulica.erp.workorder.domain.WorkOrder;
import br.com.uberhidraulica.erp.workorder.domain.WorkflowStatus;
import br.com.uberhidraulica.erp.workorder.port.WorkOrderRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class JpaWorkOrderRepositoryAdapter implements WorkOrderRepositoryPort {
    private final WorkOrderJpaRepository orders;
    private final WorkOrderServiceJpaRepository items;
    private final WorkOrderProductJpaRepository productItems;
    private final WorkflowStatusJpaRepository statuses;
    private final WorkOrderStatusHistoryJpaRepository history;

    JpaWorkOrderRepositoryAdapter(WorkOrderJpaRepository orders, WorkOrderServiceJpaRepository items, WorkOrderProductJpaRepository productItems,
                                  WorkflowStatusJpaRepository statuses, WorkOrderStatusHistoryJpaRepository history) {
        this.orders = orders; this.items = items; this.productItems = productItems; this.statuses = statuses; this.history = history;
    }

    @Override
    public WorkOrder save(WorkOrder w) {
        WorkOrderEntity e = new WorkOrderEntity();
        e.id = w.id(); e.number = w.number(); e.customerId = w.customerId(); e.vehicleId = w.vehicleId(); e.entryMileage = w.entryMileage();
        e.openedAt = w.openedAt(); e.statusId = w.status().id(); e.complaint = w.complaint(); e.notes = w.notes();
        var l = w.lifecycle();
        e.executionStartedAt = l.executionStartedAt(); e.finishedAt = l.finishedAt(); e.finishedBy = l.finishedBy();
        e.deliveredAt = l.deliveredAt(); e.deliveredBy = l.deliveredBy(); e.cancelledAt = l.cancelledAt(); e.cancelledBy = l.cancelledBy();
        e.cancellationReason = l.cancellationReason(); e.createdAt = w.createdAt(); e.updatedAt = w.updatedAt();
        return map(orders.saveAndFlush(e), statusMap());
    }

    @Override public Optional<WorkOrder> findById(UUID id) { return orders.findById(id).map(e -> map(e, statusMap())); }
    @Override public Optional<WorkOrder> findByIdForUpdate(UUID id) { return orders.lockById(id).map(e -> map(e, statusMap())); }
    @Override public List<WorkOrder> findAll() { return mapAll(orders.findAllByOrderByOpenedAtDescIdAsc()); }
    @Override public List<WorkOrder> findByCustomerId(UUID id) { return mapAll(orders.findByCustomerIdOrderByOpenedAtDescIdAsc(id)); }
    @Override public List<WorkOrder> findByVehicleId(UUID id) { return mapAll(orders.findByVehicleIdOrderByOpenedAtDescIdAsc(id)); }

    @Override
    public WorkOrder.ServiceItem addService(UUID orderId, WorkOrder.ServiceItem i) {
        WorkOrderServiceEntity e = new WorkOrderServiceEntity();
        e.id = i.id(); e.workOrderId = orderId; e.serviceId = i.serviceId(); e.serviceName = i.name(); e.serviceDescription = i.description();
        e.basePrice = i.basePrice(); e.warrantyDays = i.warrantyDays(); e.addedAt = i.addedAt(); e.priceSource = i.priceSource();
        return map(items.saveAndFlush(e));
    }

    @Override
    public WorkOrder.ProductItem addProduct(UUID orderId, WorkOrder.ProductItem i) {
        WorkOrderProductEntity e = new WorkOrderProductEntity();
        e.id = i.id(); e.workOrderId = orderId; e.productId = i.productId(); e.productDescription = i.description(); e.productInternalCode = i.internalCode();
        e.unit = i.unit(); e.quantity = i.quantity(); e.unitPrice = i.unitPrice(); e.addedAt = i.addedAt();
        return map(productItems.saveAndFlush(e));
    }

    @Override
    public void addStatusChange(WorkOrder.StatusChange c) {
        var e = new WorkOrderStatusHistoryEntity();
        e.id = c.id(); e.workOrderId = c.workOrderId(); e.fromStatusId = c.fromStatusId(); e.toStatusId = c.toStatusId();
        e.changedAt = c.changedAt(); e.changedBy = c.changedBy(); e.reason = c.reason(); e.automatic = c.automatic();
        history.saveAndFlush(e);
    }

    @Override
    public List<WorkOrder.StatusChange> statusHistory(UUID workOrderId) {
        return history.findByWorkOrderIdOrderByChangedAtAscIdAsc(workOrderId).stream()
                .map(e -> new WorkOrder.StatusChange(e.id, e.workOrderId, e.fromStatusId, e.toStatusId, e.changedAt, e.changedBy, e.reason, e.automatic)).toList();
    }

    @Override public List<WorkflowStatus> statuses() { return statuses.findAllByOrderByPositionAscNameAsc().stream().map(this::map).toList(); }
    @Override public Optional<WorkflowStatus> findStatus(UUID id) { return statuses.findById(id).map(this::map); }
    @Override public long countOrdersInStatus(UUID statusId) { return orders.countByStatusId(statusId); }

    @Override
    public WorkflowStatus saveStatus(WorkflowStatus s) {
        var e = new WorkflowStatusEntity();
        e.id = s.id(); e.name = s.name(); e.stage = s.stage(); e.position = s.position(); e.active = s.active(); e.stageDefault = s.stageDefault();
        e.createdAt = s.createdAt(); e.updatedAt = s.updatedAt();
        return map(statuses.saveAndFlush(e));
    }

    private Map<UUID, WorkflowStatus> statusMap() {
        return statuses.findAll().stream().map(this::map).collect(Collectors.toMap(WorkflowStatus::id, Function.identity()));
    }

    private List<WorkOrder> mapAll(List<WorkOrderEntity> entities) {
        var map = statusMap();
        return entities.stream().map(e -> map(e, map)).toList();
    }

    private WorkOrder map(WorkOrderEntity e, Map<UUID, WorkflowStatus> statusById) {
        var lifecycle = new WorkOrder.Lifecycle(e.executionStartedAt, e.finishedAt, e.finishedBy, e.deliveredAt, e.deliveredBy, e.cancelledAt, e.cancelledBy, e.cancellationReason);
        return new WorkOrder(e.id, e.number, e.customerId, e.vehicleId, e.entryMileage, e.openedAt, statusById.get(e.statusId), e.complaint, e.notes, lifecycle,
                e.createdAt, e.updatedAt,
                items.findByWorkOrderIdOrderByAddedAtAscIdAsc(e.id).stream().map(this::map).toList(),
                productItems.findByWorkOrderIdOrderByAddedAtAscIdAsc(e.id).stream().map(this::map).toList());
    }

    private WorkflowStatus map(WorkflowStatusEntity e) {
        return new WorkflowStatus(e.id, e.name, e.stage, e.position, e.active, e.stageDefault, e.createdAt, e.updatedAt);
    }

    private WorkOrder.ServiceItem map(WorkOrderServiceEntity e) {
        return new WorkOrder.ServiceItem(e.id, e.serviceId, e.serviceName, e.serviceDescription, e.basePrice, e.warrantyDays, e.addedAt, e.priceSource);
    }

    private WorkOrder.ProductItem map(WorkOrderProductEntity e) {
        return new WorkOrder.ProductItem(e.id, e.productId, e.productDescription, e.productInternalCode, e.unit, e.quantity, e.unitPrice, e.addedAt);
    }
}
