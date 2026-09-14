package br.com.uberhidraulica.erp.workorder.infrastructure.persistence;
import br.com.uberhidraulica.erp.workorder.domain.WorkOrder;
import br.com.uberhidraulica.erp.workorder.port.WorkOrderRepositoryPort;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class JpaWorkOrderRepositoryAdapter implements WorkOrderRepositoryPort {
 private final WorkOrderJpaRepository orders; private final WorkOrderServiceJpaRepository items; private final WorkOrderProductJpaRepository productItems;
 JpaWorkOrderRepositoryAdapter(WorkOrderJpaRepository orders,WorkOrderServiceJpaRepository items,WorkOrderProductJpaRepository productItems){this.orders=orders;this.items=items;this.productItems=productItems;}
 public WorkOrder save(WorkOrder w){WorkOrderEntity e=new WorkOrderEntity();e.id=w.id();e.number=w.number();e.customerId=w.customerId();e.vehicleId=w.vehicleId();e.entryMileage=w.entryMileage();e.openedAt=w.openedAt();e.status=w.status();e.createdAt=w.createdAt();e.updatedAt=w.updatedAt();return map(orders.saveAndFlush(e));}
 public Optional<WorkOrder> findById(UUID id){return orders.findById(id).map(this::map);}
 public List<WorkOrder> findAll(){return orders.findAllByOrderByOpenedAtDescIdAsc().stream().map(this::map).toList();}
 public WorkOrder.ServiceItem addService(UUID orderId,WorkOrder.ServiceItem i){WorkOrderServiceEntity e=new WorkOrderServiceEntity();e.id=i.id();e.workOrderId=orderId;e.serviceId=i.serviceId();e.serviceName=i.name();e.serviceDescription=i.description();e.basePrice=i.basePrice();e.warrantyDays=i.warrantyDays();e.addedAt=i.addedAt();return map(items.saveAndFlush(e));}
 public WorkOrder.ProductItem addProduct(UUID orderId,WorkOrder.ProductItem i){WorkOrderProductEntity e=new WorkOrderProductEntity();e.id=i.id();e.workOrderId=orderId;e.productId=i.productId();e.productDescription=i.description();e.productInternalCode=i.internalCode();e.unit=i.unit();e.quantity=i.quantity();e.unitPrice=i.unitPrice();e.addedAt=i.addedAt();return map(productItems.saveAndFlush(e));}
 private WorkOrder map(WorkOrderEntity e){return new WorkOrder(e.id,e.number,e.customerId,e.vehicleId,e.entryMileage,e.openedAt,e.status,e.createdAt,e.updatedAt,
   items.findByWorkOrderIdOrderByAddedAtAscIdAsc(e.id).stream().map(this::map).toList(),
   productItems.findByWorkOrderIdOrderByAddedAtAscIdAsc(e.id).stream().map(this::map).toList());}
 private WorkOrder.ServiceItem map(WorkOrderServiceEntity e){return new WorkOrder.ServiceItem(e.id,e.serviceId,e.serviceName,e.serviceDescription,e.basePrice,e.warrantyDays,e.addedAt);}
 private WorkOrder.ProductItem map(WorkOrderProductEntity e){return new WorkOrder.ProductItem(e.id,e.productId,e.productDescription,e.productInternalCode,e.unit,e.quantity,e.unitPrice,e.addedAt);}
}
