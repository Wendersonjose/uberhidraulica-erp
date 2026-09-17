package br.com.uberhidraulica.erp.servicecatalog.infrastructure.persistence;

import br.com.uberhidraulica.erp.servicecatalog.domain.CatalogService;
import br.com.uberhidraulica.erp.servicecatalog.domain.CatalogSetup;
import br.com.uberhidraulica.erp.servicecatalog.port.CatalogSetupRepositoryPort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Persistência dos cadastros de apoio do catálogo com SQL explícito sobre o PostgreSQL. */
@Component
class JdbcCatalogSetupRepositoryAdapter implements CatalogSetupRepositoryPort {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcCatalogSetupRepositoryAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<CatalogSetup.Category> CATEGORY = (rs, i) -> new CatalogSetup.Category(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("active"), instant(rs, "created_at"), instant(rs, "updated_at"));

    private static final RowMapper<CatalogSetup.VehicleGroup> GROUP = (rs, i) -> new CatalogSetup.VehicleGroup(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"), rs.getBoolean("active"),
            instant(rs, "created_at"), instant(rs, "updated_at"));

    private static final RowMapper<CatalogSetup.ServicePrice> PRICE = (rs, i) -> new CatalogSetup.ServicePrice(
            rs.getObject("id", UUID.class), rs.getObject("service_id", UUID.class), rs.getObject("vehicle_id", UUID.class),
            rs.getObject("vehicle_group_id", UUID.class), rs.getBigDecimal("price"), instant(rs, "created_at"), instant(rs, "updated_at"));

    private static final RowMapper<CatalogService> SERVICE = (rs, i) -> new CatalogService(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"), rs.getObject("category_id", UUID.class),
            rs.getBigDecimal("base_price"), rs.getInt("default_warranty_days"), rs.getBoolean("active"),
            instant(rs, "created_at"), instant(rs, "updated_at"));

    @Override
    public CatalogSetup.Category saveCategory(CatalogSetup.Category c) {
        jdbc.update("""
                insert into servicecatalog.service_category (id, name, active, created_at, updated_at)
                values (:id, :name, :active, :createdAt, :updatedAt)
                on conflict (id) do update set name = excluded.name, active = excluded.active, updated_at = excluded.updated_at""",
                new MapSqlParameterSource().addValue("id", c.id()).addValue("name", c.name()).addValue("active", c.active())
                        .addValue("createdAt", ts(c.createdAt())).addValue("updatedAt", ts(c.updatedAt())));
        return c;
    }

    @Override
    public Optional<CatalogSetup.Category> findCategory(UUID id) {
        return jdbc.query("select * from servicecatalog.service_category where id = :id", Map.of("id", id), CATEGORY).stream().findFirst();
    }

    @Override
    public List<CatalogSetup.Category> listCategories() {
        return jdbc.query("select * from servicecatalog.service_category order by lower(name), id", CATEGORY);
    }

    @Override
    public CatalogSetup.VehicleGroup saveGroup(CatalogSetup.VehicleGroup g) {
        jdbc.update("""
                insert into servicecatalog.vehicle_group (id, name, description, active, created_at, updated_at)
                values (:id, :name, :description, :active, :createdAt, :updatedAt)
                on conflict (id) do update set name = excluded.name, description = excluded.description,
                    active = excluded.active, updated_at = excluded.updated_at""",
                new MapSqlParameterSource().addValue("id", g.id()).addValue("name", g.name()).addValue("description", g.description())
                        .addValue("active", g.active()).addValue("createdAt", ts(g.createdAt())).addValue("updatedAt", ts(g.updatedAt())));
        return g;
    }

    @Override
    public Optional<CatalogSetup.VehicleGroup> findGroup(UUID id) {
        return jdbc.query("select * from servicecatalog.vehicle_group where id = :id", Map.of("id", id), GROUP).stream().findFirst();
    }

    @Override
    public List<CatalogSetup.VehicleGroup> listGroups() {
        return jdbc.query("select * from servicecatalog.vehicle_group order by lower(name), id", GROUP);
    }

    @Override
    public List<UUID> groupVehicles(UUID groupId) {
        return jdbc.queryForList("select vehicle_id from servicecatalog.vehicle_group_member where group_id = :id order by added_at, vehicle_id",
                Map.of("id", groupId), UUID.class);
    }

    @Override
    public Map<UUID, Long> groupVehicleCounts() {
        Map<UUID, Long> counts = new HashMap<>();
        jdbc.query("select group_id, count(*) from servicecatalog.vehicle_group_member group by group_id",
                rs -> { counts.put(rs.getObject(1, UUID.class), rs.getLong(2)); });
        return counts;
    }

    @Override
    public Optional<UUID> groupOfVehicle(UUID vehicleId) {
        return jdbc.queryForList("select group_id from servicecatalog.vehicle_group_member where vehicle_id = :id", Map.of("id", vehicleId), UUID.class)
                .stream().findFirst();
    }

    @Override
    public void assignVehicle(UUID groupId, UUID vehicleId) {
        jdbc.update("""
                insert into servicecatalog.vehicle_group_member (vehicle_id, group_id, added_at) values (:vehicleId, :groupId, now())
                on conflict (vehicle_id) do update set group_id = excluded.group_id, added_at = excluded.added_at""",
                Map.of("vehicleId", vehicleId, "groupId", groupId));
    }

    @Override
    public boolean removeVehicle(UUID groupId, UUID vehicleId) {
        return jdbc.update("delete from servicecatalog.vehicle_group_member where group_id = :groupId and vehicle_id = :vehicleId",
                Map.of("vehicleId", vehicleId, "groupId", groupId)) > 0;
    }

    @Override
    public CatalogSetup.ServicePrice savePrice(CatalogSetup.ServicePrice p) {
        jdbc.update("""
                insert into servicecatalog.service_price (id, service_id, vehicle_id, vehicle_group_id, price, created_at, updated_at)
                values (:id, :serviceId, :vehicleId, :groupId, :price, :createdAt, :updatedAt)
                on conflict (id) do update set price = excluded.price, updated_at = excluded.updated_at""",
                new MapSqlParameterSource().addValue("id", p.id()).addValue("serviceId", p.serviceId()).addValue("vehicleId", p.vehicleId())
                        .addValue("groupId", p.vehicleGroupId()).addValue("price", p.price())
                        .addValue("createdAt", ts(p.createdAt())).addValue("updatedAt", ts(p.updatedAt())));
        return p;
    }

    @Override
    public List<CatalogSetup.ServicePrice> listPrices(UUID serviceId) {
        return jdbc.query("select * from servicecatalog.service_price where service_id = :id order by created_at, id", Map.of("id", serviceId), PRICE);
    }

    @Override
    public Optional<CatalogSetup.ServicePrice> findVehiclePrice(UUID serviceId, UUID vehicleId) {
        return jdbc.query("select * from servicecatalog.service_price where service_id = :s and vehicle_id = :v",
                Map.of("s", serviceId, "v", vehicleId), PRICE).stream().findFirst();
    }

    @Override
    public Optional<CatalogSetup.ServicePrice> findGroupPrice(UUID serviceId, UUID groupId) {
        return jdbc.query("select * from servicecatalog.service_price where service_id = :s and vehicle_group_id = :g",
                Map.of("s", serviceId, "g", groupId), PRICE).stream().findFirst();
    }

    @Override
    public boolean deletePrice(UUID serviceId, UUID priceId) {
        return jdbc.update("delete from servicecatalog.service_price where service_id = :s and id = :id", Map.of("s", serviceId, "id", priceId)) > 0;
    }

    @Override
    public ServicePage searchServices(String text, UUID categoryId, Boolean active, int page, int size) {
        var where = new StringBuilder(" where 1=1");
        var params = new MapSqlParameterSource();
        if (categoryId != null) { where.append(" and category_id = :categoryId"); params.addValue("categoryId", categoryId); }
        if (active != null) { where.append(" and active = :active"); params.addValue("active", active); }
        if (text != null) {
            where.append(" and (lower(name) like :text or lower(coalesce(description, '')) like :text)");
            String escaped = text.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            params.addValue("text", "%" + escaped + "%");
        }
        long total = Objects.requireNonNull(jdbc.queryForObject("select count(*) from servicecatalog.service" + where, params, Long.class));
        params.addValue("limit", size).addValue("offset", (long) page * size);
        return new ServicePage(jdbc.query("select * from servicecatalog.service" + where + " order by lower(name), id limit :limit offset :offset",
                params, SERVICE), total);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
