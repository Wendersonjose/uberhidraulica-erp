package br.com.uberhidraulica.erp.productcatalog.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ProductJpaRepository extends JpaRepository<ProductEntity, UUID> {
    List<ProductEntity> findAllByOrderByDescriptionAscIdAsc();

    /** Único por índice parcial no banco (`uq_product_gtin`), por isso devolve no máximo um. */
    Optional<ProductEntity> findByGtin(String gtin);
}
