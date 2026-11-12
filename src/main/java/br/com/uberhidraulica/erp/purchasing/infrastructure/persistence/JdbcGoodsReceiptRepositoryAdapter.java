package br.com.uberhidraulica.erp.purchasing.infrastructure.persistence;

import br.com.uberhidraulica.erp.purchasing.domain.GoodsReceipt;
import br.com.uberhidraulica.erp.purchasing.domain.ReceivingLine;
import br.com.uberhidraulica.erp.purchasing.port.GoodsReceiptRepositoryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Recebimento em SQL explícito. Só INSERT: o recebimento é fato, e o estorno é registro próprio em outra tabela.
 * A situação por linha vem da visão {@code purchasing.purchase_item_receiving}, a única fonte de "quanto falta".
 */
@Component
class JdbcGoodsReceiptRepositoryAdapter implements GoodsReceiptRepositoryPort {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcGoodsReceiptRepositoryAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void insert(GoodsReceipt r) {
        jdbc.update("""
                insert into purchasing.goods_receipt (id, purchase_id, document_id, received_on, notes, idempotency_key,
                    request_hash, received_by, created_at)
                values (:id, :purchase, :document, :receivedOn, :notes, :key, :hash, :by, :createdAt)
                """, new MapSqlParameterSource("id", r.id()).addValue("purchase", r.purchaseId())
                .addValue("document", r.documentId()).addValue("receivedOn", r.receivedOn()).addValue("notes", r.notes())
                .addValue("key", r.idempotencyKey()).addValue("hash", r.requestHash()).addValue("by", r.receivedBy())
                .addValue("createdAt", Timestamp.from(r.createdAt())));
    }

    @Override
    public void insertItems(List<GoodsReceipt.Item> items) {
        for (GoodsReceipt.Item i : items) {
            jdbc.update("""
                    insert into purchasing.goods_receipt_item (id, receipt_id, purchase_item_id, product_id, quantity_received,
                        unit_cost, expected_quantity, divergence_type, cancelled_quantity, divergence_justification,
                        divergence_accepted_by, stock_movement_id)
                    values (:id, :receipt, :purchaseItem, :product, :quantity, :cost, :expected, :divergence, :cancelled,
                        :justification, :acceptedBy, :movement)
                    """, new MapSqlParameterSource("id", i.id()).addValue("receipt", i.receiptId())
                    .addValue("purchaseItem", i.purchaseItemId()).addValue("product", i.productId())
                    .addValue("quantity", i.quantityReceived()).addValue("cost", i.unitCost())
                    .addValue("expected", i.expectedQuantity()).addValue("divergence", i.divergence().name())
                    .addValue("cancelled", i.cancelledQuantity()).addValue("justification", i.justification())
                    .addValue("acceptedBy", i.acceptedBy()).addValue("movement", i.stockMovementId()));
        }
    }

    @Override
    public Optional<GoodsReceipt> find(UUID id) {
        return load("select * from purchasing.goods_receipt where id = :id", Map.of("id", id)).stream().findFirst();
    }

    @Override
    public Optional<GoodsReceipt> findByIdempotencyKey(String key) {
        return load("select * from purchasing.goods_receipt where idempotency_key = :key", Map.of("key", key))
                .stream().findFirst();
    }

    @Override
    public List<GoodsReceipt> receiptsOf(UUID purchaseId) {
        return load("select * from purchasing.goods_receipt where purchase_id = :id order by created_at desc, id",
                Map.of("id", purchaseId));
    }

    /** Cabeçalhos primeiro, depois itens e estornos em lote: três consultas, nunca uma por recebimento. */
    private List<GoodsReceipt> load(String sql, Map<String, ?> params) {
        record Header(UUID id, long number, UUID purchaseId, UUID documentId, LocalDate receivedOn, String notes,
                      String key, String hash, UUID by, Instant createdAt) {}
        List<Header> headers = jdbc.query(sql, params, (rs, i) -> new Header(rs.getObject("id", UUID.class),
                rs.getLong("number"), rs.getObject("purchase_id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getObject("received_on", LocalDate.class), rs.getString("notes"), rs.getString("idempotency_key"),
                rs.getString("request_hash"), rs.getObject("received_by", UUID.class), instant(rs, "created_at")));
        if (headers.isEmpty()) return List.of();

        List<UUID> ids = headers.stream().map(Header::id).toList();
        Map<UUID, List<GoodsReceipt.Item>> items = new HashMap<>();
        jdbc.query("select * from purchasing.goods_receipt_item where receipt_id in (:ids) order by id",
                Map.of("ids", ids), (ResultSet rs) -> {
                    GoodsReceipt.Item item = item(rs);
                    items.computeIfAbsent(item.receiptId(), k -> new ArrayList<>()).add(item);
                });
        Map<UUID, GoodsReceipt.Reversal> reversals = new HashMap<>();
        jdbc.query("select * from purchasing.goods_receipt_reversal where receipt_id in (:ids)",
                Map.of("ids", ids), (ResultSet rs) -> {
                    GoodsReceipt.Reversal reversal = reversal(rs);
                    reversals.put(reversal.receiptId(), reversal);
                });

        List<GoodsReceipt> result = new ArrayList<>(headers.size());
        for (Header h : headers) {
            result.add(new GoodsReceipt(h.id(), h.number(), h.purchaseId(), h.documentId(), h.receivedOn(), h.notes(),
                    h.key(), h.hash(), h.by(), h.createdAt(), items.getOrDefault(h.id(), List.of()), reversals.get(h.id())));
        }
        return result;
    }

    @Override
    public List<ReceivingLine> receiving(UUID purchaseId) {
        return jdbc.query("""
                select pi.id, pi.line_no, pi.product_id, pi.description, pi.unit_original, pi.unit_cost,
                       v.ordered_quantity, v.received_quantity, v.closed_quantity, v.pending_quantity
                from purchasing.purchase_item pi
                join purchasing.purchase_item_receiving v on v.purchase_item_id = pi.id
                where pi.purchase_id = :id order by pi.line_no
                """, Map.of("id", purchaseId), (rs, i) -> new ReceivingLine(rs.getObject("id", UUID.class),
                rs.getInt("line_no"), rs.getObject("product_id", UUID.class), rs.getString("description"),
                rs.getString("unit_original"), null, rs.getBigDecimal("ordered_quantity"),
                rs.getBigDecimal("received_quantity"), rs.getBigDecimal("closed_quantity"),
                rs.getBigDecimal("pending_quantity"), rs.getBigDecimal("unit_cost")));
    }

    @Override
    public boolean hasActiveReceipt(UUID purchaseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from purchasing.goods_receipt g
                               where g.purchase_id = :id
                                 and not exists (select 1 from purchasing.goods_receipt_reversal x where x.receipt_id = g.id))
                """, Map.of("id", purchaseId), Boolean.class));
    }

    @Override
    public void insertReversal(GoodsReceipt.Reversal r) {
        jdbc.update("""
                insert into purchasing.goods_receipt_reversal (id, receipt_id, reason, reversed_at, reversed_by, idempotency_key)
                values (:id, :receipt, :reason, :at, :by, :key)
                """, new MapSqlParameterSource("id", r.id()).addValue("receipt", r.receiptId())
                .addValue("reason", r.reason()).addValue("at", Timestamp.from(r.reversedAt()))
                .addValue("by", r.reversedBy()).addValue("key", r.idempotencyKey()));
    }

    @Override
    public Optional<GoodsReceipt.Reversal> findReversalByKey(String key) {
        return jdbc.query("select * from purchasing.goods_receipt_reversal where idempotency_key = :key",
                Map.of("key", key), (rs, i) -> reversal(rs)).stream().findFirst();
    }

    // ================================================================ mapeamento

    private static GoodsReceipt.Item item(ResultSet rs) throws SQLException {
        return new GoodsReceipt.Item(rs.getObject("id", UUID.class), rs.getObject("receipt_id", UUID.class),
                rs.getObject("purchase_item_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getBigDecimal("quantity_received"), rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("expected_quantity"), GoodsReceipt.Divergence.valueOf(rs.getString("divergence_type")),
                rs.getBigDecimal("cancelled_quantity"), rs.getString("divergence_justification"),
                rs.getObject("divergence_accepted_by", UUID.class), rs.getObject("stock_movement_id", UUID.class));
    }

    private static GoodsReceipt.Reversal reversal(ResultSet rs) throws SQLException {
        return new GoodsReceipt.Reversal(rs.getObject("id", UUID.class), rs.getObject("receipt_id", UUID.class),
                rs.getString("reason"), instant(rs, "reversed_at"), rs.getObject("reversed_by", UUID.class),
                rs.getString("idempotency_key"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
