package br.com.uberhidraulica.erp.quote.infrastructure.persistence;

import br.com.uberhidraulica.erp.quote.domain.RevisionStatus;
import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "quote", schema = "workshop")
class QuoteEntity {
    @Id UUID id;
    @Column(name = "work_order_id", nullable = false) UUID workOrderId;
    @Column(nullable = false) long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "created_by", nullable = false) UUID createdBy;
}

@Entity
@Table(name = "quote_revision", schema = "workshop")
class QuoteRevisionEntity {
    @Id UUID id;
    @Column(name = "quote_id", nullable = false) UUID quoteId;
    @Column(name = "revision_number", nullable = false) int revisionNumber;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) RevisionStatus status;
    @Column(name = "presented_at") Instant presentedAt;
    @Column(name = "valid_until") Instant validUntil;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "created_by", nullable = false) UUID createdBy;
    @Column(nullable = false) long version;
}

@Entity
@Table(name = "quote_item", schema = "workshop")
class QuoteItemEntity {
    @Id UUID id;
    @Column(name = "quote_id", nullable = false) UUID quoteId;
    @Column(name = "work_order_service_id") UUID workOrderServiceId;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "created_by", nullable = false) UUID createdBy;
}

@Entity
@Table(name = "quote_item_revision", schema = "workshop")
class QuoteItemRevisionEntity {
    @Id UUID id;
    @Column(name = "quote_id", nullable = false) UUID quoteId;
    @Column(name = "quote_item_id", nullable = false) UUID quoteItemId;
    @Column(name = "revision_sequence", nullable = false) int revisionSequence;
    @Column(nullable = false, length = 1000) String description;
    @Column(nullable = false, precision = 19, scale = 4) BigDecimal quantity;
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4) BigDecimal unitPrice;
    @Column(name = "total_price", nullable = false, precision = 19, scale = 4) BigDecimal totalPrice;
    @Column(name = "revision_reason", length = 50) String revisionReason;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "created_by", nullable = false) UUID createdBy;
}

@Entity
@Table(name = "quote_revision_item", schema = "workshop")
@IdClass(QuoteRevisionItemEntity.Key.class)
class QuoteRevisionItemEntity {
    @Id @Column(name = "quote_revision_id", nullable = false) UUID quoteRevisionId;
    @Id @Column(name = "quote_item_revision_id", nullable = false) UUID quoteItemRevisionId;
    @Column(name = "quote_id", nullable = false) UUID quoteId;
    @Column(name = "display_order", nullable = false) int displayOrder;

    static class Key implements Serializable {
        UUID quoteRevisionId;
        UUID quoteItemRevisionId;

        Key() {}
        Key(UUID quoteRevisionId, UUID quoteItemRevisionId) {
            this.quoteRevisionId = quoteRevisionId;
            this.quoteItemRevisionId = quoteItemRevisionId;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(quoteRevisionId, key.quoteRevisionId)
                    && Objects.equals(quoteItemRevisionId, key.quoteItemRevisionId);
        }

        @Override public int hashCode() { return Objects.hash(quoteRevisionId, quoteItemRevisionId); }
    }
}
