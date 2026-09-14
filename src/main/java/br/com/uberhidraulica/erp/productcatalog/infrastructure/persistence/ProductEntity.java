package br.com.uberhidraulica.erp.productcatalog.infrastructure.persistence;

import br.com.uberhidraulica.erp.productcatalog.domain.ProductType;
import br.com.uberhidraulica.erp.productcatalog.domain.ProductUnit;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product", schema = "productcatalog")
class ProductEntity {
    @Id UUID id;
    @Column(nullable = false, length = 200) String description;
    @Column(name = "internal_code", length = 60) String internalCode;
    @Column(length = 100) String category;
    @Enumerated(EnumType.STRING) @Column(name = "item_type", nullable = false, length = 24) ProductType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) ProductUnit unit;
    @Column(name = "reference_cost", precision = 15, scale = 2) BigDecimal referenceCost;
    @Column(name = "sale_price", precision = 15, scale = 2) BigDecimal salePrice;
    @Column(name = "minimum_stock", precision = 15, scale = 3) BigDecimal minimumStock;
    @Column(nullable = false) boolean active;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
}
