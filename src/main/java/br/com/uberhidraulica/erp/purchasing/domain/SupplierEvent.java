package br.com.uberhidraulica.erp.purchasing.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Fato imutável do histórico do fornecedor: quem fez o quê, quando, por quê e o que mudou. */
public record SupplierEvent(UUID id, UUID supplierId, Type type, UUID actorId, Instant occurredAt, String reason,
                            Map<String, Change> changes) {

    public enum Type { CREATED, UPDATED, INACTIVATED, REACTIVATED, CONTACT_ADDED, CONTACT_UPDATED, CONTACT_REMOVED }

    public record Change(String before, String after) {}

    public SupplierEvent {
        changes = changes == null ? Map.of() : Map.copyOf(changes);
    }

    public static SupplierEvent of(UUID supplierId, Type type, UUID actor, Instant now, String reason, Map<String, Change> changes) {
        return new SupplierEvent(UUID.randomUUID(), supplierId, type, actor, now, reason, changes);
    }
}
