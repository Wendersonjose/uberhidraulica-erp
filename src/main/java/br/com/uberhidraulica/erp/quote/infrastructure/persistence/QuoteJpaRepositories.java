package br.com.uberhidraulica.erp.quote.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface QuoteJpaRepository extends JpaRepository<QuoteEntity, UUID> {
    List<QuoteEntity> findByWorkOrderIdOrderByCreatedAtAscIdAsc(UUID workOrderId);
}

interface QuoteRevisionJpaRepository extends JpaRepository<QuoteRevisionEntity, UUID> {
    List<QuoteRevisionEntity> findByQuoteIdOrderByRevisionNumberAsc(UUID quoteId);

    /**
     * Apresenta a revisão somente se ela continuar na versão lida.
     *
     * <p>É o ponto de serialização entre apresentar e decidir exigido pela arquitetura aprovada:
     * a atualização condicional impede que duas operações concorrentes apresentem a mesma revisão.</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update QuoteRevisionEntity r
               set r.status = br.com.uberhidraulica.erp.quote.domain.RevisionStatus.PRESENTED,
                   r.presentedAt = :presentedAt,
                   r.validUntil = :validUntil,
                   r.version = r.version + 1
             where r.id = :id and r.version = :expectedVersion
            """)
    int present(@Param("id") UUID id, @Param("presentedAt") Instant presentedAt,
                @Param("validUntil") Instant validUntil, @Param("expectedVersion") long expectedVersion);
}

interface QuoteItemJpaRepository extends JpaRepository<QuoteItemEntity, UUID> {
    List<QuoteItemEntity> findByQuoteIdOrderByCreatedAtAscIdAsc(UUID quoteId);
}

interface QuoteItemRevisionJpaRepository extends JpaRepository<QuoteItemRevisionEntity, UUID> {
    List<QuoteItemRevisionEntity> findByQuoteIdOrderByRevisionSequenceAsc(UUID quoteId);
}

interface QuoteRevisionItemJpaRepository extends JpaRepository<QuoteRevisionItemEntity, QuoteRevisionItemEntity.Key> {
    List<QuoteRevisionItemEntity> findByQuoteIdOrderByDisplayOrderAsc(UUID quoteId);
}
