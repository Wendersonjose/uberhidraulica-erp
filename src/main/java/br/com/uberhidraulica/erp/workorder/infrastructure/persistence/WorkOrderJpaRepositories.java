package br.com.uberhidraulica.erp.workorder.infrastructure.persistence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
interface WorkOrderJpaRepository extends JpaRepository<WorkOrderEntity,UUID>{
    List<WorkOrderEntity> findAllByOrderByOpenedAtDescIdAsc();
    List<WorkOrderEntity> findByCustomerIdOrderByOpenedAtDescIdAsc(UUID customerId);
    List<WorkOrderEntity> findByVehicleIdOrderByOpenedAtDescIdAsc(UUID vehicleId);
    long countByStatusId(UUID statusId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select w from WorkOrderEntity w where w.id = :id") Optional<WorkOrderEntity> lockById(@Param("id") UUID id);
}
interface StatusAutomationJpaRepository extends JpaRepository<StatusAutomationEntity,String>{List<StatusAutomationEntity> findAllByOrderByEventAsc();}
interface WorkflowStatusJpaRepository extends JpaRepository<WorkflowStatusEntity,UUID>{List<WorkflowStatusEntity> findAllByOrderByPositionAscNameAsc();}
interface WorkOrderStatusHistoryJpaRepository extends JpaRepository<WorkOrderStatusHistoryEntity,UUID>{List<WorkOrderStatusHistoryEntity> findByWorkOrderIdOrderByChangedAtAscIdAsc(UUID workOrderId);}
interface WorkOrderServiceJpaRepository extends JpaRepository<WorkOrderServiceEntity,UUID>{List<WorkOrderServiceEntity> findByWorkOrderIdOrderByAddedAtAscIdAsc(UUID id);}
interface WorkOrderProductJpaRepository extends JpaRepository<WorkOrderProductEntity,UUID>{List<WorkOrderProductEntity> findByWorkOrderIdOrderByAddedAtAscIdAsc(UUID id);}
