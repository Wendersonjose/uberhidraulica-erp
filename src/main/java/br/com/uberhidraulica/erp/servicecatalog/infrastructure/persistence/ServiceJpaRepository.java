package br.com.uberhidraulica.erp.servicecatalog.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

interface ServiceJpaRepository extends JpaRepository<ServiceEntity, UUID> {
    List<ServiceEntity> findAllByOrderByNameAscIdAsc();
}
