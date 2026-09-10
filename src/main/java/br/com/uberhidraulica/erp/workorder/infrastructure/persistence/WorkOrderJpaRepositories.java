package br.com.uberhidraulica.erp.workorder.infrastructure.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
interface WorkOrderJpaRepository extends JpaRepository<WorkOrderEntity,UUID>{List<WorkOrderEntity> findAllByOrderByOpenedAtDescIdAsc();}
interface WorkOrderServiceJpaRepository extends JpaRepository<WorkOrderServiceEntity,UUID>{List<WorkOrderServiceEntity> findByWorkOrderIdOrderByAddedAtAscIdAsc(UUID id);}
