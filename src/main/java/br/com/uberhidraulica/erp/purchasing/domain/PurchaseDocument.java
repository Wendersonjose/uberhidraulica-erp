package br.com.uberhidraulica.erp.purchasing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Documento fiscal da compra (`D5`).
 *
 * <p>O hash do XML é <b>sempre</b> gravado. Mesma chave de acesso com hash diferente é nota alterada: o caso
 * de uso bloqueia e alerta, e em nenhuma hipótese sobrescreve. Anular mantém a linha no histórico e libera a
 * chave para nova importação — é o que o índice único parcial do banco expressa.</p>
 */
public record PurchaseDocument(UUID id, UUID purchaseId, Kind kind, String accessKey, String series, Long number,
                               Instant issuedAt, String issuerDocument, String recipientDocument,
                               BigDecimal totalAmount, String protocolNumber, String protocolStatus,
                               String xmlSha256, Instant voidedAt, UUID voidedBy, String voidReason,
                               Instant createdAt, UUID createdBy) {

    public enum Kind { NFE, PROVISIONAL, OTHER }

    public PurchaseDocument {
        if (kind == Kind.NFE && (accessKey == null || accessKey.length() != 44)) {
            throw new PurchasingException("PURCHASE_DOCUMENT_INVALID", "NF-e exige chave de acesso de 44 posições");
        }
        if (kind != Kind.NFE && accessKey != null) {
            throw new PurchasingException("PURCHASE_DOCUMENT_INVALID", "Somente NF-e tem chave de acesso");
        }
        if (kind == Kind.NFE && (xmlSha256 == null || xmlSha256.length() != 64)) {
            throw new PurchasingException("PURCHASE_DOCUMENT_INVALID", "NF-e exige o hash SHA-256 do XML");
        }
    }

    public boolean voided() {
        return voidedAt != null;
    }

    /** Recusa anular o que já está anulado; o resto das travas da `D5` é do caso de uso. */
    public void checkVoid() {
        if (voided()) {
            throw new PurchasingException("PURCHASE_DOCUMENT_ALREADY_VOIDED", "Documento já está anulado");
        }
    }
}
