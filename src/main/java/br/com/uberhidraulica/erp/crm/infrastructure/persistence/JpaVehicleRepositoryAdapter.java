package br.com.uberhidraulica.erp.crm.infrastructure.persistence;

import br.com.uberhidraulica.erp.crm.domain.CustomerSearch;
import br.com.uberhidraulica.erp.crm.domain.Vehicle;
import br.com.uberhidraulica.erp.crm.domain.VehicleSearch;
import br.com.uberhidraulica.erp.crm.port.VehicleRepositoryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
class JpaVehicleRepositoryAdapter implements VehicleRepositoryPort {
    private final VehicleJpaRepository repository;
    private final VehicleOwnershipJpaRepository ownerships;
    private final NamedParameterJdbcTemplate jdbc;

    JpaVehicleRepositoryAdapter(VehicleJpaRepository repository, VehicleOwnershipJpaRepository ownerships, NamedParameterJdbcTemplate jdbc) {
        this.repository = repository;
        this.ownerships = ownerships;
        this.jdbc = jdbc;
    }

    @Override
    public Vehicle save(Vehicle v) {
        VehicleEntity e = new VehicleEntity();
        e.id = v.id(); e.customerId = v.customerId(); e.plate = v.plate(); e.manufacturer = v.manufacturer(); e.model = v.model();
        e.modelYear = v.modelYear(); e.mileage = v.mileage(); e.steeringGearManufacturer = v.steeringGearManufacturer();
        e.color = v.color(); e.notes = v.notes(); e.active = v.active(); e.createdAt = v.createdAt(); e.updatedAt = v.updatedAt();
        return map(repository.saveAndFlush(e));
    }

    @Override public Optional<Vehicle> findById(UUID id) { return repository.findById(id).map(this::map); }
    @Override public Optional<Vehicle> findByIdForUpdate(UUID id) { return repository.lockById(id).map(this::map); }
    @Override public List<Vehicle> findByCustomerId(UUID id) { return repository.findByCustomerIdOrderByModelAscIdAsc(id).stream().map(this::map).toList(); }

    @Override
    public CustomerSearch.Page<VehicleSearch.Row> search(VehicleSearch criteria) {
        var where = new StringBuilder(" where 1=1");
        var params = new MapSqlParameterSource();
        if (criteria.customerId() != null) { where.append(" and v.customer_id = :customerId"); params.addValue("customerId", criteria.customerId()); }
        if (criteria.active() != null) { where.append(" and v.active = :active"); params.addValue("active", criteria.active()); }
        if (criteria.text() != null) {
            where.append(" and (lower(v.manufacturer) like :text or lower(v.model) like :text or lower(c.name) like :text");
            params.addValue("text", "%" + JpaCustomerRepositoryAdapter.escape(criteria.text().toLowerCase(Locale.ROOT)) + "%");
            if (criteria.plate() != null) { where.append(" or v.plate like :plate"); params.addValue("plate", "%" + criteria.plate() + "%"); }
            where.append(")");
        }
        String from = " from crm.vehicle v join crm.customer c on c.id = v.customer_id";
        long total = Objects.requireNonNull(jdbc.queryForObject("select count(*)" + from + where, params, Long.class));
        params.addValue("limit", criteria.size()).addValue("offset", (long) criteria.page() * criteria.size());
        List<Map.Entry<UUID, String>> rows = jdbc.query("select v.id, c.name" + from + where + " order by v.plate, v.id limit :limit offset :offset",
                params, (rs, i) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2)));
        Map<UUID, VehicleEntity> byId = repository.findAllById(rows.stream().map(Map.Entry::getKey).toList()).stream()
                .collect(Collectors.toMap(e -> e.id, Function.identity()));
        return new CustomerSearch.Page<>(rows.stream().map(r -> new VehicleSearch.Row(map(byId.get(r.getKey())), r.getValue())).toList(),
                total, criteria.page(), criteria.size());
    }

    @Override
    public void openOwnership(Vehicle.Ownership o) {
        var e = new VehicleOwnershipEntity();
        e.id = o.id(); e.vehicleId = o.vehicleId(); e.customerId = o.customerId(); e.startedAt = o.startedAt(); e.endedAt = o.endedAt(); e.changedBy = o.changedBy();
        ownerships.saveAndFlush(e);
    }

    @Override public void closeCurrentOwnership(UUID vehicleId, Instant endedAt) { ownerships.closeCurrent(vehicleId, endedAt); }

    @Override
    public List<Vehicle.Ownership> ownershipHistory(UUID vehicleId) {
        return ownerships.findByVehicleIdOrderByStartedAtAscIdAsc(vehicleId).stream()
                .map(e -> new Vehicle.Ownership(e.id, e.vehicleId, e.customerId, e.startedAt, e.endedAt, e.changedBy)).toList();
    }

    private Vehicle map(VehicleEntity e) {
        return new Vehicle(e.id, e.customerId, e.plate, e.manufacturer, e.model, e.modelYear, e.mileage, e.steeringGearManufacturer,
                e.color, e.notes, e.active, e.createdAt, e.updatedAt);
    }
}
