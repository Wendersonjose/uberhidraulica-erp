package br.com.uberhidraulica.erp.crm.infrastructure.persistence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;
interface CustomerJpaRepository extends JpaRepository<CustomerEntity, UUID> { List<CustomerEntity> findAllByOrderByNameAscIdAsc(); }
interface VehicleJpaRepository extends JpaRepository<VehicleEntity, UUID> {
    List<VehicleEntity> findByCustomerIdOrderByModelAscIdAsc(UUID customerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select v from VehicleEntity v where v.id = :id") Optional<VehicleEntity> lockById(@Param("id") UUID id);
}
interface VehicleOwnershipJpaRepository extends JpaRepository<VehicleOwnershipEntity, UUID> {
    List<VehicleOwnershipEntity> findByVehicleIdOrderByStartedAtAscIdAsc(UUID vehicleId);
    @Modifying(flushAutomatically=true, clearAutomatically=true)
    @Query("update VehicleOwnershipEntity o set o.endedAt = :endedAt where o.vehicleId = :vehicleId and o.endedAt is null")
    int closeCurrent(@Param("vehicleId") UUID vehicleId, @Param("endedAt") Instant endedAt);
}
