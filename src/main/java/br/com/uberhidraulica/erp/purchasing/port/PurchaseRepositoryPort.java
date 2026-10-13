package br.com.uberhidraulica.erp.purchasing.port;

import br.com.uberhidraulica.erp.purchasing.domain.Purchase;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseDocument;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseEvent;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseItem;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasePayableLink;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierProductLink;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistência de compra, documento fiscal, parcelas, vínculos e trilha de auditoria (`TASK-0021` I3). */
public interface PurchaseRepositoryPort {

    // ---------------------------------------------------------------- configuração

    Map<String, String> settings();

    void upsertSetting(String key, String value, UUID actorId);

    // ---------------------------------------------------------------- compra

    void insert(Purchase purchase);

    void insertItems(List<PurchaseItem> items);

    Optional<Purchase> find(UUID id);

    /**
     * Carrega a compra travando a linha ({@code SELECT … FOR UPDATE}). É o ponto de serialização de
     * cancelamento e anulação: sem ele, duas requisições simultâneas poderiam cancelar a mesma compra e
     * cancelar duas vezes as contas a pagar.
     */
    Optional<Purchase> lock(UUID id);

    /** Compra gravada com a mesma chave de idempotência, para o retry HTTP não criar compra nova. */
    Optional<Purchase> findByIdempotencyKey(String key);

    void cancel(UUID purchaseId, java.time.Instant at, UUID by, String reason);

    Page<PurchaseSummary> search(Filter filter, int page, int size);

    // ---------------------------------------------------------------- documento fiscal

    void insertDocument(PurchaseDocument document);

    /**
     * Grava o XML do documento, separado do cabeçalho.
     *
     * <p>Fica fora de {@link PurchaseDocument} de propósito: o XML inteiro não deve circular num record de
     * domínio que é passado adiante, serializado em resposta e eventualmente registrado em log. Este é o
     * único lugar onde ele é persistido, e só {@code PURCHASE_VIEW} o lê de volta.</p>
     */
    void storeXml(UUID documentId, String xml);

    List<PurchaseDocument> documents(UUID purchaseId);

    Optional<PurchaseDocument> findDocument(UUID documentId);

    /**
     * Documento <b>não anulado</b> com esta chave de acesso (`D5`). Só o não anulado importa: a linha anulada
     * permanece no histórico e a chave volta a poder ser importada.
     */
    Optional<PurchaseDocument> findActiveDocumentByAccessKey(String accessKey);

    /** Qualquer documento com esta chave, anulado ou não, para detectar XML alterado pelo hash. */
    List<PurchaseDocument> documentsByAccessKey(String accessKey);

    void voidDocument(UUID documentId, java.time.Instant at, UUID by, String reason);

    // ---------------------------------------------------------------- parcelas

    void insertPayableLinks(List<PurchasePayableLink> links);

    List<PurchasePayableLink> payableLinks(UUID purchaseId);

    // ---------------------------------------------------------------- vínculo de item e preços

    Optional<SupplierProductLink> findLink(UUID supplierId, String supplierCode);

    List<SupplierProductLink> links(UUID supplierId);

    void upsertLink(SupplierProductLink link);

    void deleteLinkIfUnused(UUID supplierId, String supplierCode);

    void insertPriceHistory(List<PriceHistoryEntry> entries);

    // ---------------------------------------------------------------- auditoria

    void insertEvent(PurchaseEvent event);

    Page<PurchaseEvent> events(UUID purchaseId, int page, int size);

    // ---------------------------------------------------------------- contratos de consulta

    record Filter(String text, UUID supplierId, Purchase.Status status, Purchase.Origin origin,
                  LocalDate issuedFrom, LocalDate issuedTo) {}

    /**
     * Resumo de compra para listagem. {@code receivedStatus} é derivado no I4; enquanto o recebimento não
     * existe, vem como {@code NONE}.
     */
    record PurchaseSummary(UUID id, long number, UUID supplierId, String supplierName, Purchase.Origin origin,
                           Purchase.Status status, BigDecimal payableTotal, String accessKey, Long invoiceNumber,
                           LocalDate issuedOn, java.time.Instant createdAt, int installmentCount) {}

    record PriceHistoryEntry(UUID id, UUID supplierId, UUID productId, BigDecimal unitPrice, BigDecimal quantity,
                             String sourceType, UUID sourceId, LocalDate occurredOn) {}

    record Page<T>(List<T> items, long totalItems, int page, int size) {
        /** Arredondamento para cima em aritmética inteira: o módulo não introduz ponto flutuante (DR-0007). */
        public int totalPages() { return size <= 0 ? 0 : (int) ((totalItems + size - 1) / size); }
    }
}
