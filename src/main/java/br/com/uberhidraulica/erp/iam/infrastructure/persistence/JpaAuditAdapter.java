package br.com.uberhidraulica.erp.iam.infrastructure.persistence;

import br.com.uberhidraulica.erp.iam.port.AuditPort;
import br.com.uberhidraulica.erp.iam.port.CorrelationIdPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.util.UUID;

@Component
public class JpaAuditAdapter implements AuditPort {
    private final AuditEventJpaRepository repository;
    private final CorrelationIdPort correlationIds;
    JpaAuditAdapter(AuditEventJpaRepository repository, CorrelationIdPort correlationIds) {
        this.repository = repository;
        this.correlationIds = correlationIds;
    }

    @Override @Transactional public void record(UUID actorId, String action, String targetType, String targetId,
                                 String result, String beforeState, String afterState) {
        persist(actorId, action, targetType, targetId, result, beforeState, afterState);
    }

    @Override @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(UUID actorId, String action, String targetType, String targetId,
                                  String result, String beforeState, String afterState) {
        persist(actorId, action, targetType, targetId, result, beforeState, afterState);
    }

    private void persist(UUID actorId, String action, String targetType, String targetId,
                         String result, String beforeState, String afterState) {
        AuditEventEntity event = new AuditEventEntity();
        event.id = UUID.randomUUID(); event.occurredAt = Instant.now(); event.actorUserId = actorId;
        event.action = action; event.targetType = targetType; event.targetId = targetId; event.result = result;
        event.correlationId = correlationIds.currentOrCreate(); event.beforeState = beforeState; event.afterState = afterState;
        repository.saveAndFlush(event);
    }
}
