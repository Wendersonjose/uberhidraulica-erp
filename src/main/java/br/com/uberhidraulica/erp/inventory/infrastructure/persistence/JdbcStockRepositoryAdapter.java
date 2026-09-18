package br.com.uberhidraulica.erp.inventory.infrastructure.persistence;

import br.com.uberhidraulica.erp.inventory.domain.InventoryException;
import br.com.uberhidraulica.erp.inventory.domain.Stock;
import br.com.uberhidraulica.erp.inventory.domain.StockMovement;
import br.com.uberhidraulica.erp.inventory.port.StockRepositoryPort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.*;

/** Persistência do estoque em SQL explícito: saldo bloqueado por linha e movimentação imutável. */
@Component
class JdbcStockRepositoryAdapter implements StockRepositoryPort {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcStockRepositoryAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Stock> BALANCE = (rs, i) -> new Stock(rs.getObject("product_id", UUID.class),
            rs.getBigDecimal("quantity"), rs.getBigDecimal("average_cost"), rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<StockMovement> MOVEMENT = (rs, i) -> new StockMovement(
            rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
            StockMovement.Type.valueOf(rs.getString("movement_type")), rs.getBigDecimal("quantity"),
            rs.getBigDecimal("unit_cost"), rs.getBigDecimal("balance_after"), rs.getBigDecimal("average_cost_after"),
            rs.getString("reason"), StockMovement.Source.valueOf(rs.getString("source_type")),
            rs.getObject("work_order_id", UUID.class), rs.getObject("work_order_item_id", UUID.class),
            rs.getObject("reverses_movement_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
            rs.getObject("recorded_by", UUID.class), "IN".equals(rs.getString("direction")));

    @Override
    public Stock lockBalance(UUID productId) {
        // A linha do saldo é o ponto de serialização: duas baixas do mesmo produto esperam uma pela outra.
        jdbc.update("insert into inventory.stock_balance (product_id, quantity, average_cost, updated_at) values (:id, 0, null, now())"
                + " on conflict (product_id) do nothing", Map.of("id", productId));
        return jdbc.query("select * from inventory.stock_balance where product_id = :id for update", Map.of("id", productId), BALANCE)
                .stream().findFirst().orElseThrow(() -> new InventoryException("PRODUCT_NOT_FOUND", "Produto não encontrado"));
    }

    @Override
    public Map<UUID, Stock> balances() {
        Map<UUID, Stock> result = new HashMap<>();
        jdbc.query("select * from inventory.stock_balance", rs -> {
            result.put(rs.getObject("product_id", UUID.class), new Stock(rs.getObject("product_id", UUID.class),
                    rs.getBigDecimal("quantity"), rs.getBigDecimal("average_cost"), rs.getTimestamp("updated_at").toInstant()));
        });
        return result;
    }

    @Override
    public void saveBalance(Stock stock) {
        jdbc.update("update inventory.stock_balance set quantity = :quantity, average_cost = :cost, updated_at = :now where product_id = :id",
                new MapSqlParameterSource().addValue("quantity", stock.quantity()).addValue("cost", stock.averageCost())
                        .addValue("now", Timestamp.from(stock.updatedAt())).addValue("id", stock.productId()));
    }

    @Override
    public StockMovement record(StockMovement m) {
        jdbc.update("""
                insert into inventory.stock_movement (id, product_id, movement_type, direction, quantity, unit_cost, balance_after,
                    average_cost_after, reason, source_type, work_order_id, work_order_item_id, reverses_movement_id, occurred_at, recorded_by)
                values (:id, :productId, :type, :direction, :quantity, :unitCost, :balanceAfter, :averageCostAfter, :reason, :source,
                    :workOrderId, :workOrderItemId, :reverses, :occurredAt, :recordedBy)""",
                new MapSqlParameterSource().addValue("id", m.id()).addValue("productId", m.productId())
                        .addValue("type", m.type().name()).addValue("direction", m.incoming() ? "IN" : "OUT")
                        .addValue("quantity", m.quantity()).addValue("unitCost", m.unitCost())
                        .addValue("balanceAfter", m.balanceAfter()).addValue("averageCostAfter", m.averageCostAfter())
                        .addValue("reason", m.reason()).addValue("source", m.source().name())
                        .addValue("workOrderId", m.workOrderId()).addValue("workOrderItemId", m.workOrderItemId())
                        .addValue("reverses", m.reversesMovementId()).addValue("occurredAt", Timestamp.from(m.occurredAt()))
                        .addValue("recordedBy", m.recordedBy()));
        return m;
    }

    @Override
    public Optional<StockMovement> findMovement(UUID id) {
        return jdbc.query("select * from inventory.stock_movement where id = :id", Map.of("id", id), MOVEMENT).stream().findFirst();
    }

    @Override
    public List<StockMovement> movementsOfProduct(UUID productId, int limit, int offset) {
        return jdbc.query("select * from inventory.stock_movement where product_id = :id order by occurred_at desc, id limit :limit offset :offset",
                new MapSqlParameterSource().addValue("id", productId).addValue("limit", limit).addValue("offset", offset), MOVEMENT);
    }

    @Override
    public long countMovementsOfProduct(UUID productId) {
        return Objects.requireNonNull(jdbc.queryForObject("select count(*) from inventory.stock_movement where product_id = :id",
                Map.of("id", productId), Long.class));
    }

    @Override
    public List<StockMovement> pendingWorkOrderExits(UUID workOrderId) {
        return jdbc.query("""
                select m.* from inventory.stock_movement m
                where m.work_order_id = :id and m.movement_type = 'WORK_ORDER_OUT'
                  and not exists (select 1 from inventory.stock_movement r where r.reverses_movement_id = m.id)
                order by m.occurred_at, m.id""", Map.of("id", workOrderId), MOVEMENT);
    }

    @Override
    public boolean workOrderExitExists(UUID workOrderItemId) {
        if (workOrderItemId == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from inventory.stock_movement where work_order_item_id = :id and movement_type = 'WORK_ORDER_OUT')",
                Map.of("id", workOrderItemId), Boolean.class));
    }

    @Override
    public String setting(String key) {
        return jdbc.queryForList("select value from inventory.settings where key = :key", Map.of("key", key), String.class)
                .stream().findFirst().orElseThrow(() -> new InventoryException("SETTING_NOT_FOUND", "Configuração não encontrada"));
    }

    @Override
    public void saveSetting(String key, String value) {
        if (jdbc.update("update inventory.settings set value = :value, updated_at = now() where key = :key",
                Map.of("key", key, "value", value)) == 0)
            throw new InventoryException("SETTING_NOT_FOUND", "Configuração não encontrada");
    }
}
