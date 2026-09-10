package br.com.uberhidraulica.erp.crm.infrastructure.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
interface CustomerJpaRepository extends JpaRepository<CustomerEntity, UUID> { List<CustomerEntity> findAllByOrderByNameAscIdAsc(); }
interface VehicleJpaRepository extends JpaRepository<VehicleEntity, UUID> { List<VehicleEntity> findByCustomerIdOrderByModelAscIdAsc(UUID customerId); }
