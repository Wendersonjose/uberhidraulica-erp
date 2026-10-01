package br.com.uberhidraulica.erp.productcatalog.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

interface ProductJpaRepository extends JpaRepository<ProductEntity, UUID> {
    List<ProductEntity> findAllByOrderByDescriptionAscIdAsc();
}
