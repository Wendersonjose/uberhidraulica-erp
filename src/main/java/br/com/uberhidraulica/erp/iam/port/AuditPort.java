package br.com.uberhidraulica.erp.iam.port;

import java.util.UUID;

public interface AuditPort {
    void record(UUID actorId, String action, String targetType, String targetId,
                String result, String beforeState, String afterState);
    void recordIndependent(UUID actorId, String action, String targetType, String targetId,
                           String result, String beforeState, String afterState);
}
