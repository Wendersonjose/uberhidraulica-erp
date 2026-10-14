package br.com.uberhidraulica.erp.purchasing.infrastructure.persistence;

import br.com.uberhidraulica.erp.purchasing.domain.AcquisitionCost;
import br.com.uberhidraulica.erp.purchasing.domain.Purchase;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseDocument;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseEvent;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseItem;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasePayableLink;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasingException;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierProductLink;
import br.com.uberhidraulica.erp.purchasing.port.PurchaseRepositoryPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistência de compra em SQL explícito. Não há DELETE: o banco também o recusa por trigger.
 *
 * <p>Correção é cancelamento ou anulação, nunca reescrita — por isso os únicos UPDATE aqui são os que marcam
 * compra cancelada e documento anulado.</p>
 */
@Component
class JdbcPurchaseRepositoryAdapter implements PurchaseRepositoryPort {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcPurchaseRepositoryAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ================================================================ configuração

    @Override
    public Map<String, String> settings() {
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.query("select key, value from purchasing.settings", rs -> {
            result.put(rs.getString("key"), rs.getString("value"));
        });
        return result;
    }

    @Override
    public void upsertSetting(String key, String value, UUID actorId) {
        jdbc.update("""
                insert into purchasing.settings (key, value, updated_at, updated_by)
                values (:key, :value, now(), :actor)
                on conflict (key) do update set value = :value, updated_at = now(), updated_by = :actor
                """, new MapSqlParameterSource("key", key).addValue("value", value).addValue("actor", actorId));
    }

    // ================================================================ compra

    @Override
    public void insert(Purchase p) {
        try {
            jdbc.update("""
                    insert into purchasing.purchase (id, supplier_id, origin, status, goods_total, discount_total,
                        freight_total, insurance_total, other_total, tax_added_total, payable_total, expense_category_id,
                        commercial_snapshot, work_order_id, notes, idempotency_key, created_at, created_by)
                    values (:id, :supplier, :origin, :status, :goods, :discount, :freight, :insurance, :other, :tax,
                        :payable, :category, cast(:snapshot as jsonb), :workOrder, :notes, :key, :createdAt, :createdBy)
                    """, new MapSqlParameterSource("id", p.id())
                    .addValue("supplier", p.supplierId()).addValue("origin", p.origin().name())
                    .addValue("status", p.status().name()).addValue("goods", p.goodsTotal())
                    .addValue("discount", p.discountTotal()).addValue("freight", p.freightTotal())
                    .addValue("insurance", p.insuranceTotal()).addValue("other", p.otherTotal())
                    .addValue("tax", p.taxAddedTotal()).addValue("payable", p.payableTotal())
                    .addValue("category", p.expenseCategoryId()).addValue("snapshot", p.commercialSnapshot())
                    .addValue("workOrder", p.workOrderId()).addValue("notes", p.notes())
                    .addValue("key", p.idempotencyKey()).addValue("createdAt", Timestamp.from(p.createdAt()))
                    .addValue("createdBy", p.createdBy()));
        } catch (DuplicateKeyException collision) {
            // Retry HTTP com a mesma chave: quem chamou relê e devolve o resultado anterior.
            throw new PurchasingException("PURCHASE_IDEMPOTENCY_KEY_REUSED", "Esta compra já foi registrada");
        }
    }

    @Override
    public void insertItems(List<PurchaseItem> items) {
        for (PurchaseItem i : items) {
            AcquisitionCost c = i.cost();
            jdbc.update("""
                    insert into purchasing.purchase_item (id, purchase_id, line_no, product_id, supplier_code, description,
                        unit_original, quantity_original, unit_conversion_factor, quantity, unit_price, unit_cost,
                        cost_base_amount, cost_ipi_amount, cost_icms_st_amount, cost_icms_normal_amount,
                        cost_freight_share, cost_insurance_share, cost_other_share, acquisition_total,
                        discount, line_total, ncm, cfop, gtin, work_order_id, work_order_item_id)
                    values (:id, :purchase, :lineNo, :product, :supplierCode, :description, :unitOriginal,
                        :quantityOriginal, :factor, :quantity, :unitPrice, :unitCost, :base, :ipi, :icmsSt, :icmsNormal,
                        :freight, :insurance, :other, :total, :discount, :lineTotal, :ncm, :cfop, :gtin,
                        :workOrder, :workOrderItem)
                    """, new MapSqlParameterSource("id", i.id())
                    .addValue("purchase", i.purchaseId()).addValue("lineNo", i.lineNo())
                    .addValue("product", i.productId()).addValue("supplierCode", i.supplierCode())
                    .addValue("description", i.description()).addValue("unitOriginal", i.unitOriginal())
                    .addValue("quantityOriginal", i.quantityOriginal()).addValue("factor", i.unitConversionFactor())
                    .addValue("quantity", i.quantity()).addValue("unitPrice", i.unitPrice())
                    .addValue("unitCost", i.unitCost())
                    .addValue("base", c.baseAmount()).addValue("ipi", c.ipiAmount())
                    .addValue("icmsSt", c.icmsStAmount()).addValue("icmsNormal", c.icmsNormalAmount())
                    .addValue("freight", c.freightShare()).addValue("insurance", c.insuranceShare())
                    .addValue("other", c.otherShare()).addValue("total", c.totalAmount())
                    .addValue("discount", i.discount()).addValue("lineTotal", i.lineTotal())
                    .addValue("ncm", i.ncm()).addValue("cfop", i.cfop()).addValue("gtin", i.gtin())
                    .addValue("workOrder", i.workOrderId()).addValue("workOrderItem", i.workOrderItemId()));
        }
    }

    @Override
    public Optional<Purchase> find(UUID id) { return load(id, false); }

    @Override
    public Optional<Purchase> lock(UUID id) { return load(id, true); }

    private Optional<Purchase> load(UUID id, boolean forUpdate) {
        Optional<Purchase> header = jdbc.query("select * from purchasing.purchase where id = :id"
                        + (forUpdate ? " for update" : ""),
                Map.of("id", id), (rs, i) -> purchase(rs)).stream().findFirst();
        return header.map(p -> withItems(p, items(p.id())));
    }

    @Override
    public Optional<Purchase> findByIdempotencyKey(String key) {
        return jdbc.query("select * from purchasing.purchase where idempotency_key = :key", Map.of("key", key),
                        (rs, i) -> purchase(rs)).stream().findFirst()
                .map(p -> withItems(p, items(p.id())));
    }

    @Override
    public void cancel(UUID purchaseId, Instant at, UUID by, String reason) {
        jdbc.update("""
                update purchasing.purchase set status = 'CANCELLED', cancelled_at = :at, cancelled_by = :by,
                    cancellation_reason = :reason
                where id = :id and cancelled_at is null
                """, new MapSqlParameterSource("id", purchaseId).addValue("at", Timestamp.from(at))
                .addValue("by", by).addValue("reason", reason));
    }

    @Override
    public Page<PurchaseSummary> search(Filter filter, int page, int size) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (filter.supplierId() != null) { where.append(" and p.supplier_id = :supplier"); p.addValue("supplier", filter.supplierId()); }
        if (filter.status() != null) { where.append(" and p.status = :status"); p.addValue("status", filter.status().name()); }
        if (filter.origin() != null) { where.append(" and p.origin = :origin"); p.addValue("origin", filter.origin().name()); }
        if (filter.issuedFrom() != null) { where.append(" and d.issued_at >= :from"); p.addValue("from", filter.issuedFrom()); }
        if (filter.issuedTo() != null) { where.append(" and d.issued_at < :to"); p.addValue("to", filter.issuedTo().plusDays(1)); }
        if (filter.text() != null && !filter.text().isBlank()) {
            String text = filter.text().trim();
            String alnum = text.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
            where.append(" and (lower(s.legal_name) like :text or lower(coalesce(s.trade_name, '')) like :text");
            p.addValue("text", "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%");
            if (!alnum.isEmpty()) {
                // Busca por chave de acesso e por número da nota, que é como o operador procura na prática.
                where.append(" or coalesce(d.access_key, '') like :alnum or cast(d.number as text) like :alnum");
                p.addValue("alnum", "%" + alnum + "%");
            }
            where.append(")");
        }
        // O documento é opcional (compra direta não tem nota), por isso LEFT JOIN; e um único documento ativo
        // por compra, garantido na aplicação, por isso não há risco de duplicar linha.
        String from = """
                 from purchasing.purchase p
                 join purchasing.supplier s on s.id = p.supplier_id
                 left join purchasing.purchase_document d on d.purchase_id = p.id and d.voided_at is null
                """;
        Long total = jdbc.queryForObject("select count(*)" + from + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<PurchaseSummary> items = jdbc.query("""
                select p.id, p.number, p.supplier_id, s.legal_name, p.origin, p.status, p.payable_total,
                       d.access_key, d.number as invoice_number, d.issued_at, p.created_at,
                       (select count(*) from purchasing.purchase_payable pp where pp.purchase_id = p.id) as installments,
                       (select case when not coalesce(bool_or(v.received_quantity > 0), false) then 'NONE'
                                    when bool_and(v.pending_quantity <= 0) then 'COMPLETE' else 'PARTIAL' end
                        from purchasing.purchase_item_receiving v where v.purchase_id = p.id) as receipt_status
                """ + from + where + " order by p.created_at desc, p.id limit :limit offset :offset", p,
                (rs, i) -> new PurchaseSummary(rs.getObject("id", UUID.class), rs.getLong("number"),
                        rs.getObject("supplier_id", UUID.class), rs.getString("legal_name"),
                        Purchase.Origin.valueOf(rs.getString("origin")), Purchase.Status.valueOf(rs.getString("status")),
                        rs.getBigDecimal("payable_total"), rs.getString("access_key"),
                        (Long) rs.getObject("invoice_number"), localDate(rs, "issued_at"),
                        instant(rs, "created_at"), rs.getInt("installments"),
                        rs.getString("receipt_status") == null ? br.com.uberhidraulica.erp.purchasing.domain.GoodsReceipt.Status.NONE
                                : br.com.uberhidraulica.erp.purchasing.domain.GoodsReceipt.Status.valueOf(rs.getString("receipt_status"))));
        return new Page<>(items, total == null ? 0 : total, page, size);
    }

    // ================================================================ pedido (I5)

    @Override
    public void updateDraft(Purchase p, Instant at, UUID by) {
        int rows = jdbc.update("""
                update purchasing.purchase set supplier_id = :supplier, goods_total = :goods,
                    discount_total = :discount, freight_total = :freight, insurance_total = :insurance,
                    other_total = :other, tax_added_total = :tax, payable_total = :payable,
                    commercial_snapshot = cast(:snapshot as jsonb), work_order_id = :workOrder, notes = :notes,
                    version = version + 1, updated_at = :at, updated_by = :by
                where id = :id and status = DRAFT
                """, new MapSqlParameterSource("id", p.id()).addValue("supplier", p.supplierId())
                .addValue("goods", p.goodsTotal()).addValue("discount", p.discountTotal())
                .addValue("freight", p.freightTotal()).addValue("insurance", p.insuranceTotal())
                .addValue("other", p.otherTotal()).addValue("tax", p.taxAddedTotal())
                .addValue("payable", p.payableTotal()).addValue("snapshot", p.commercialSnapshot())
                .addValue("workOrder", p.workOrderId()).addValue("notes", p.notes())
                .addValue("at", Timestamp.from(at)).addValue("by", by));
        if (rows == 0) {
            throw new PurchasingException("PURCHASE_ORDER_NOT_EDITABLE", "Só o rascunho pode ser alterado");
        }
    }

    @Override
    public void replaceItems(UUID purchaseId, List<PurchaseItem> items) {
        jdbc.update("delete from purchasing.purchase_item where purchase_id = :id", Map.of("id", purchaseId));
        insertItems(items);
    }

    @Override
    public void transition(UUID purchaseId, Purchase.Status from, Purchase.Status to, Instant at, UUID by) {
        int rows = jdbc.update("""
                update purchasing.purchase set status = :to, version = version + 1, updated_at = :at, updated_by = :by,
                    approved_at = case when :to = APPROVED then cast(:at as timestamptz) else approved_at end,
                    approved_by = case when :to = APPROVED then cast(:by as uuid) else approved_by end
                where id = :id and status = :from
                """, new MapSqlParameterSource("id", purchaseId).addValue("from", from.name())
                .addValue("to", to.name()).addValue("at", Timestamp.from(at)).addValue("by", by));
        if (rows == 0) {
            throw new PurchasingException("PURCHASE_ORDER_STATE_CHANGED",
                    "A situação da compra mudou enquanto a operação era processada: recarregue e tente de novo");
        }
    }

    // ================================================================ documento fiscal

    @Override
    public void insertDocument(PurchaseDocument d) {
        try {
            jdbc.update("""
                    insert into purchasing.purchase_document (id, purchase_id, kind, access_key, series, number,
                        issued_at, issuer_document, recipient_document, total_amount, protocol_number, protocol_status,
                        xml_sha256, xml_content, created_at, created_by)
                    values (:id, :purchase, :kind, :accessKey, :series, :number, :issuedAt, :issuer, :recipient,
                        :total, :protocolNumber, :protocolStatus, :sha256, :xml, :createdAt, :createdBy)
                    """, new MapSqlParameterSource("id", d.id())
                    .addValue("purchase", d.purchaseId()).addValue("kind", d.kind().name())
                    .addValue("accessKey", d.accessKey()).addValue("series", d.series()).addValue("number", d.number())
                    .addValue("issuedAt", d.issuedAt() == null ? null : Timestamp.from(d.issuedAt()))
                    .addValue("issuer", d.issuerDocument()).addValue("recipient", d.recipientDocument())
                    .addValue("total", d.totalAmount()).addValue("protocolNumber", d.protocolNumber())
                    .addValue("protocolStatus", d.protocolStatus()).addValue("sha256", d.xmlSha256())
                    .addValue("xml", null).addValue("createdAt", Timestamp.from(d.createdAt()))
                    .addValue("createdBy", d.createdBy()));
        } catch (DuplicateKeyException collision) {
            // D5: o índice único parcial resolve a corrida de duas importações simultâneas da mesma nota.
            throw new PurchasingException("PURCHASE_DUPLICATE_NFE",
                    "Esta NF-e já foi importada e a compra está ativa");
        }
    }

    @Override
    public void storeXml(UUID documentId, String xml) {
        jdbc.update("update purchasing.purchase_document set xml_content = :xml where id = :id",
                new MapSqlParameterSource("id", documentId).addValue("xml", xml));
    }

    @Override
    public List<PurchaseDocument> documents(UUID purchaseId) {
        return jdbc.query("select * from purchasing.purchase_document where purchase_id = :id order by created_at",
                Map.of("id", purchaseId), (rs, i) -> document(rs));
    }

    @Override
    public Optional<PurchaseDocument> findDocument(UUID documentId) {
        return jdbc.query("select * from purchasing.purchase_document where id = :id", Map.of("id", documentId),
                (rs, i) -> document(rs)).stream().findFirst();
    }

    @Override
    public Optional<PurchaseDocument> findActiveDocumentByAccessKey(String accessKey) {
        return jdbc.query("""
                select * from purchasing.purchase_document where access_key = :key and voided_at is null
                """, Map.of("key", accessKey), (rs, i) -> document(rs)).stream().findFirst();
    }

    @Override
    public List<PurchaseDocument> documentsByAccessKey(String accessKey) {
        return jdbc.query("select * from purchasing.purchase_document where access_key = :key order by created_at",
                Map.of("key", accessKey), (rs, i) -> document(rs));
    }

    @Override
    public void voidDocument(UUID documentId, Instant at, UUID by, String reason) {
        jdbc.update("""
                update purchasing.purchase_document set voided_at = :at, voided_by = :by, void_reason = :reason
                where id = :id and voided_at is null
                """, new MapSqlParameterSource("id", documentId).addValue("at", Timestamp.from(at))
                .addValue("by", by).addValue("reason", reason));
    }

    // ================================================================ parcelas

    @Override
    public void insertPayableLinks(List<PurchasePayableLink> links) {
        for (PurchasePayableLink l : links) {
            jdbc.update("""
                    insert into purchasing.purchase_payable (id, purchase_id, payable_id, installment_no, due_date,
                        amount, invoice_reference, created_at)
                    values (:id, :purchase, :payable, :no, :dueDate, :amount, :reference, :createdAt)
                    """, new MapSqlParameterSource("id", l.id())
                    .addValue("purchase", l.purchaseId()).addValue("payable", l.payableId())
                    .addValue("no", l.installmentNo()).addValue("dueDate", l.dueDate())
                    .addValue("amount", l.amount()).addValue("reference", l.invoiceReference())
                    .addValue("createdAt", Timestamp.from(l.createdAt())));
        }
    }

    @Override
    public List<PurchasePayableLink> payableLinks(UUID purchaseId) {
        return jdbc.query("""
                select * from purchasing.purchase_payable where purchase_id = :id order by installment_no
                """, Map.of("id", purchaseId), (rs, i) -> new PurchasePayableLink(
                rs.getObject("id", UUID.class), rs.getObject("purchase_id", UUID.class),
                rs.getObject("payable_id", UUID.class), rs.getInt("installment_no"),
                rs.getObject("due_date", LocalDate.class), rs.getBigDecimal("amount"),
                rs.getString("invoice_reference"), instant(rs, "created_at")));
    }

    // ================================================================ vínculo e preços

    @Override
    public Optional<SupplierProductLink> findLink(UUID supplierId, String supplierCode) {
        if (supplierCode == null || supplierCode.isBlank()) return Optional.empty();
        return jdbc.query("""
                select * from purchasing.supplier_product_link where supplier_id = :supplier and supplier_code = :code
                """, Map.of("supplier", supplierId, "code", supplierCode.trim()), (rs, i) -> link(rs))
                .stream().findFirst();
    }

    @Override
    public List<SupplierProductLink> links(UUID supplierId) {
        return jdbc.query("""
                select * from purchasing.supplier_product_link where supplier_id = :supplier order by supplier_code
                """, Map.of("supplier", supplierId), (rs, i) -> link(rs));
    }

    @Override
    public void upsertLink(SupplierProductLink l) {
        jdbc.update("""
                insert into purchasing.supplier_product_link (id, supplier_id, supplier_code, gtin, product_id,
                    unit_original, unit_conversion_factor, confirmed_at, confirmed_by)
                values (:id, :supplier, :code, :gtin, :product, :unitOriginal, :factor, :confirmedAt, :confirmedBy)
                on conflict (supplier_id, supplier_code) do update set product_id = :product, gtin = :gtin,
                    unit_original = :unitOriginal, unit_conversion_factor = :factor,
                    updated_at = :confirmedAt, updated_by = :confirmedBy
                """, new MapSqlParameterSource("id", l.id())
                .addValue("supplier", l.supplierId()).addValue("code", l.supplierCode()).addValue("gtin", l.gtin())
                .addValue("product", l.productId()).addValue("unitOriginal", l.unitOriginal())
                .addValue("factor", l.unitConversionFactor())
                .addValue("confirmedAt", Timestamp.from(l.confirmedAt())).addValue("confirmedBy", l.confirmedBy()));
    }

    @Override
    public void deleteLinkIfUnused(UUID supplierId, String supplierCode) {
        // O vínculo é aprendizado, não histórico: pode ser removido para corrigir um erro de casamento. O que
        // já foi comprado não muda, porque o item da compra guarda o product_id usado na época.
        jdbc.update("""
                delete from purchasing.supplier_product_link where supplier_id = :supplier and supplier_code = :code
                """, Map.of("supplier", supplierId, "code", supplierCode));
    }

    @Override
    public void insertPriceHistory(List<PriceHistoryEntry> entries) {
        for (PriceHistoryEntry e : entries) {
            jdbc.update("""
                    insert into purchasing.supplier_price_history (id, supplier_id, product_id, unit_price, quantity,
                        source_type, source_id, occurred_on, created_at)
                    values (:id, :supplier, :product, :price, :quantity, :sourceType, :sourceId, :occurredOn, now())
                    """, new MapSqlParameterSource("id", e.id())
                    .addValue("supplier", e.supplierId()).addValue("product", e.productId())
                    .addValue("price", e.unitPrice()).addValue("quantity", e.quantity())
                    .addValue("sourceType", e.sourceType()).addValue("sourceId", e.sourceId())
                    .addValue("occurredOn", e.occurredOn()));
        }
    }

    // ================================================================ auditoria

    @Override
    public void insertEvent(PurchaseEvent e) {
        jdbc.update("""
                insert into purchasing.purchase_event (id, purchase_id, event_type, actor_id, occurred_at, reason, payload)
                values (:id, :purchase, :type, :actor, :occurredAt, :reason, cast(:payload as jsonb))
                """, new MapSqlParameterSource("id", e.id())
                .addValue("purchase", e.purchaseId()).addValue("type", e.type().name())
                .addValue("actor", e.actorId()).addValue("occurredAt", Timestamp.from(e.occurredAt()))
                .addValue("reason", e.reason()).addValue("payload", e.payload()));
    }

    @Override
    public Page<PurchaseEvent> events(UUID purchaseId, int page, int size) {
        Long total = jdbc.queryForObject("select count(*) from purchasing.purchase_event where purchase_id = :id",
                Map.of("id", purchaseId), Long.class);
        List<PurchaseEvent> items = jdbc.query("""
                select * from purchasing.purchase_event where purchase_id = :id
                order by occurred_at desc, id limit :limit offset :offset
                """, new MapSqlParameterSource("id", purchaseId).addValue("limit", size)
                .addValue("offset", (long) page * size),
                (rs, i) -> new PurchaseEvent(rs.getObject("id", UUID.class), rs.getObject("purchase_id", UUID.class),
                        PurchaseEvent.Type.valueOf(rs.getString("event_type")), rs.getObject("actor_id", UUID.class),
                        instant(rs, "occurred_at"), rs.getString("reason"), rs.getString("payload")));
        return new Page<>(items, total == null ? 0 : total, page, size);
    }

    // ================================================================ mapeamento

    private List<PurchaseItem> items(UUID purchaseId) {
        return jdbc.query("select * from purchasing.purchase_item where purchase_id = :id order by line_no",
                Map.of("id", purchaseId), (rs, i) -> item(rs));
    }

    private static Purchase withItems(Purchase p, List<PurchaseItem> items) {
        return new Purchase(p.id(), p.number(), p.supplierId(), p.origin(), p.status(), p.goodsTotal(),
                p.discountTotal(), p.freightTotal(), p.insuranceTotal(), p.otherTotal(), p.taxAddedTotal(),
                p.payableTotal(), p.expenseCategoryId(), p.commercialSnapshot(), p.workOrderId(), p.notes(),
                p.idempotencyKey(), p.createdAt(), p.createdBy(), p.cancelledAt(), p.cancelledBy(),
                p.cancellationReason(), p.version(), p.updatedAt(), p.updatedBy(), p.approvedAt(), p.approvedBy(),
                items);
    }

    private static Purchase purchase(ResultSet rs) throws SQLException {
        return new Purchase(rs.getObject("id", UUID.class), rs.getLong("number"),
                rs.getObject("supplier_id", UUID.class), Purchase.Origin.valueOf(rs.getString("origin")),
                Purchase.Status.valueOf(rs.getString("status")), rs.getBigDecimal("goods_total"),
                rs.getBigDecimal("discount_total"), rs.getBigDecimal("freight_total"),
                rs.getBigDecimal("insurance_total"), rs.getBigDecimal("other_total"),
                rs.getBigDecimal("tax_added_total"), rs.getBigDecimal("payable_total"),
                rs.getObject("expense_category_id", UUID.class), rs.getString("commercial_snapshot"),
                rs.getObject("work_order_id", UUID.class), rs.getString("notes"), rs.getString("idempotency_key"),
                instant(rs, "created_at"), rs.getObject("created_by", UUID.class), instant(rs, "cancelled_at"),
                rs.getObject("cancelled_by", UUID.class), rs.getString("cancellation_reason"), rs.getLong("version"),
                instant(rs, "updated_at"), rs.getObject("updated_by", UUID.class), instant(rs, "approved_at"),
                rs.getObject("approved_by", UUID.class), List.of());
    }

    private static PurchaseItem item(ResultSet rs) throws SQLException {
        AcquisitionCost cost = new AcquisitionCost(rs.getBigDecimal("cost_base_amount"),
                rs.getBigDecimal("cost_ipi_amount"), rs.getBigDecimal("cost_icms_st_amount"),
                rs.getBigDecimal("cost_icms_normal_amount"), rs.getBigDecimal("cost_freight_share"),
                rs.getBigDecimal("cost_insurance_share"), rs.getBigDecimal("cost_other_share"),
                rs.getBigDecimal("acquisition_total"), rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_cost"));
        return new PurchaseItem(rs.getObject("id", UUID.class), rs.getObject("purchase_id", UUID.class),
                rs.getInt("line_no"), rs.getObject("product_id", UUID.class), rs.getString("supplier_code"),
                rs.getString("description"), rs.getString("unit_original"), rs.getBigDecimal("quantity_original"),
                rs.getBigDecimal("unit_conversion_factor"), rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_price"), rs.getBigDecimal("unit_cost"), cost, rs.getBigDecimal("discount"),
                rs.getBigDecimal("line_total"), rs.getString("ncm"), rs.getString("cfop"), rs.getString("gtin"),
                rs.getObject("work_order_id", UUID.class), rs.getObject("work_order_item_id", UUID.class));
    }

    private static PurchaseDocument document(ResultSet rs) throws SQLException {
        return new PurchaseDocument(rs.getObject("id", UUID.class), rs.getObject("purchase_id", UUID.class),
                PurchaseDocument.Kind.valueOf(rs.getString("kind")), rs.getString("access_key"),
                rs.getString("series"), (Long) rs.getObject("number"), instant(rs, "issued_at"),
                rs.getString("issuer_document"), rs.getString("recipient_document"), rs.getBigDecimal("total_amount"),
                rs.getString("protocol_number"), rs.getString("protocol_status"), rs.getString("xml_sha256"),
                instant(rs, "voided_at"), rs.getObject("voided_by", UUID.class), rs.getString("void_reason"),
                instant(rs, "created_at"), rs.getObject("created_by", UUID.class));
    }

    private static SupplierProductLink link(ResultSet rs) throws SQLException {
        return new SupplierProductLink(rs.getObject("id", UUID.class), rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_code"), rs.getString("gtin"), rs.getObject("product_id", UUID.class),
                rs.getString("unit_original"), rs.getBigDecimal("unit_conversion_factor"),
                instant(rs, "confirmed_at"), rs.getObject("confirmed_by", UUID.class), instant(rs, "updated_at"),
                rs.getObject("updated_by", UUID.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant().atZone(java.time.ZoneId.of("America/Sao_Paulo")).toLocalDate();
    }

    /** Escapa o curinga do LIKE para que "%" digitado na busca seja texto, não curinga. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
