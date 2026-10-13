package br.com.uberhidraulica.erp.purchasing.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Trilha de auditoria da compra: append-only, recusada pelo banco a UPDATE e DELETE.
 *
 * <p>Registra também os atos que atravessam fronteira de módulo — {@code PAYABLES_REGISTERED} e
 * {@code PAYABLES_CANCELLED} —, porque a importação cria conta a pagar com a permissão de Compras e não com
 * a do Financeiro. Essa escolha é deliberada (`AG-09`) e precisa ficar rastreável.</p>
 *
 * @param payload JSON com o detalhe do ato; nunca contém o XML, que fica só em purchase_document
 */
public record PurchaseEvent(UUID id, UUID purchaseId, Type type, UUID actorId, Instant occurredAt, String reason,
                            String payload) {

    public enum Type {
        IMPORTED, CREATED, CANCELLED, DOCUMENT_VOIDED, PAYABLES_REGISTERED, PAYABLES_CANCELLED,
        ITEM_LINK_CONFIRMED, SUPPLIER_CREATED_FROM_NFE
    }

    public static PurchaseEvent of(UUID purchaseId, Type type, UUID actorId, Instant at, String reason, String payload) {
        return new PurchaseEvent(UUID.randomUUID(), purchaseId, type, actorId, at, reason, payload);
    }
}
