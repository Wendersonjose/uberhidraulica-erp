package br.com.uberhidraulica.erp.servicecatalog.infrastructure.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "service", schema = "servicecatalog")
class ServiceEntity {
    @Id UUID id;
    @Column(nullable = false, length = 160) String name;
    @Column(nullable = false, length = 1000) String description;
    @Column(length = 100) String category;
    @Column(name = "base_price", nullable = false, precision = 15, scale = 2) BigDecimal basePrice;
    @Column(name = "default_warranty_days", nullable = false) int defaultWarrantyDays;
    @Column(nullable = false) boolean active;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
}
