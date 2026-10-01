package br.com.uberhidraulica.erp.workorder.infrastructure.persistence;
import br.com.uberhidraulica.erp.workorder.domain.WorkflowStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="work_order",schema="workorder")
class WorkOrderEntity {
 @Id UUID id; @org.hibernate.annotations.Generated(event = org.hibernate.generator.EventType.INSERT) @Column(insertable=false,updatable=false,nullable=false) Long number;
 @Column(name="customer_id",nullable=false) UUID customerId; @Column(name="vehicle_id",nullable=false) UUID vehicleId;
 @Column(name="entry_mileage") Long entryMileage; @Column(name="opened_at",nullable=false) Instant openedAt;
 @Column(name="status_id",nullable=false) UUID statusId;
 @Column(length=2000) String complaint; @Column(length=2000) String notes; @Column(length=4000) String diagnosis;
 @Column(name="diagnosed_at") Instant diagnosedAt; @Column(name="diagnosed_by") UUID diagnosedBy;
 @Column(name="execution_started_at") Instant executionStartedAt;
 @Column(name="finished_at") Instant finishedAt; @Column(name="finished_by") UUID finishedBy;
 @Column(name="delivered_at") Instant deliveredAt; @Column(name="delivered_by") UUID deliveredBy;
 @Column(name="cancelled_at") Instant cancelledAt; @Column(name="cancelled_by") UUID cancelledBy;
 @Column(name="cancellation_reason",length=500) String cancellationReason;
 @Column(name="created_at",nullable=false) Instant createdAt; @Column(name="updated_at",nullable=false) Instant updatedAt;
}
@Entity @Table(name="status",schema="workorder")
class WorkflowStatusEntity {
 @Id UUID id; @Column(nullable=false,length=60) String name;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) WorkflowStatus.Stage stage;
 @Column(nullable=false) int position; @Column(nullable=false) boolean active;
 @Column(name="stage_default",nullable=false) boolean stageDefault;
 @Column(name="created_at",nullable=false) Instant createdAt; @Column(name="updated_at",nullable=false) Instant updatedAt;
}
@Entity @Table(name="status_automation",schema="workorder")
class StatusAutomationEntity {
 @Id @Column(length=32) String event; @Column(nullable=false) boolean enabled; @Column(name="updated_at",nullable=false) Instant updatedAt;
}
@Entity @Table(name="work_order_status_history",schema="workorder")
class WorkOrderStatusHistoryEntity {
 @Id UUID id; @Column(name="work_order_id",nullable=false) UUID workOrderId;
 @Column(name="from_status_id") UUID fromStatusId; @Column(name="to_status_id",nullable=false) UUID toStatusId;
 @Column(name="changed_at",nullable=false) Instant changedAt; @Column(name="changed_by") UUID changedBy;
 @Column(length=500) String reason; @Column(nullable=false) boolean automatic;
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
