package br.com.uberhidraulica.erp.workorder.infrastructure.persistence;
import br.com.uberhidraulica.erp.workorder.domain.WorkOrder;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="work_order",schema="workorder")
class WorkOrderEntity {
 @Id UUID id; @org.hibernate.annotations.Generated(event = org.hibernate.generator.EventType.INSERT) @Column(insertable=false,nullable=false) Long number;
 @Column(name="customer_id",nullable=false) UUID customerId; @Column(name="vehicle_id",nullable=false) UUID vehicleId;
 @Column(name="entry_mileage",nullable=false) long entryMileage; @Column(name="opened_at",nullable=false) Instant openedAt;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) WorkOrder.Status status;
 @Column(name="created_at",nullable=false) Instant createdAt; @Column(name="updated_at",nullable=false) Instant updatedAt;
}
@Entity @Table(name="work_order_service",schema="workorder")
class WorkOrderServiceEntity {
 @Id UUID id; @Column(name="work_order_id",nullable=false) UUID workOrderId; @Column(name="service_id",nullable=false) UUID serviceId;
 @Column(name="service_name",nullable=false,length=160) String serviceName; @Column(name="service_description",length=1000) String serviceDescription; @Column(name="price_source",length=8) String priceSource;
 @Column(name="base_price",nullable=false,precision=15,scale=2) BigDecimal basePrice; @Column(name="warranty_days",nullable=false) int warrantyDays;
 @Column(name="added_at",nullable=false) Instant addedAt;
}
@Entity @Table(name="work_order_product",schema="workorder")
class WorkOrderProductEntity {
 @Id UUID id; @Column(name="work_order_id",nullable=false) UUID workOrderId; @Column(name="product_id",nullable=false) UUID productId;
 @Column(name="product_description",nullable=false,length=200) String productDescription;
 @Column(name="product_internal_code",length=60) String productInternalCode;
 @Column(nullable=false,length=16) String unit;
 @Column(nullable=false,precision=15,scale=3) BigDecimal quantity;
 @Column(name="unit_price",nullable=false,precision=15,scale=2) BigDecimal unitPrice;
 @Column(name="added_at",nullable=false) Instant addedAt;
}
