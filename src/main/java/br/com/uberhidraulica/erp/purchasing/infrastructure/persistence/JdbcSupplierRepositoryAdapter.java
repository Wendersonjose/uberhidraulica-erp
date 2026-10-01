package br.com.uberhidraulica.erp.purchasing.infrastructure.persistence;

import br.com.uberhidraulica.erp.purchasing.domain.Supplier;
import br.com.uberhidraulica.erp.purchasing.port.SupplierRepositoryPort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Component
class JdbcSupplierRepositoryAdapter implements SupplierRepositoryPort {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcSupplierRepositoryAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Supplier save(Supplier s) {
        var p = params(s);
        int updated = jdbc.update("""
                update purchasing.supplier set
                    person_type=:personType, legal_name=:legalName, trade_name=:tradeName, document=:document,
                    phone=:phone, email=:email, address_zip_code=:zipCode, address_street=:street,
                    address_number=:number, address_complement=:complement, address_district=:district,
                    address_city=:city, address_state=:state, payment_terms=:paymentTerms,
                    preferred_payment_method=:preferredPaymentMethod, usual_due_day=:usualDueDay,
                    credit_limit=:creditLimit, commercial_notes=:commercialNotes, status=:status,
                    updated_at=:updatedAt
                where id=:id
                """, p);
        if (updated == 0) {
            jdbc.update("""
                    insert into purchasing.supplier (
                        id, person_type, legal_name, trade_name, document, phone, email,
                        address_zip_code, address_street, address_number, address_complement, address_district,
                        address_city, address_state, payment_terms, preferred_payment_method, usual_due_day,
                        credit_limit, commercial_notes, status, created_at, updated_at)
                    values (:id,:personType,:legalName,:tradeName,:document,:phone,:email,
                        :zipCode,:street,:number,:complement,:district,:city,:state,:paymentTerms,
                        :preferredPaymentMethod,:usualDueDay,:creditLimit,:commercialNotes,:status,:createdAt,:updatedAt)
                    """, p);
        }
        return findById(s.id()).orElseThrow();
    }

    @Override
    public Optional<Supplier> findById(UUID id) {
        return jdbc.query("select * from purchasing.supplier where id=:id", new MapSqlParameterSource("id", id), MAPPER)
                .stream().findFirst();
    }

    @Override
    public Page<Supplier> search(String text, Supplier.PersonType personType, Supplier.Status status, int page, int size) {
        var where = new StringBuilder(" where 1=1");
        var p = new MapSqlParameterSource();
        if (personType != null) { where.append(" and person_type=:personType"); p.addValue("personType", personType.name()); }
        if (status != null) { where.append(" and status=:status"); p.addValue("status", status.name()); }
        if (text != null) {
            where.append(" and (lower(legal_name) like :text or lower(coalesce(trade_name,'')) like :text or coalesce(document,'') like :digits)");
            p.addValue("text", "%" + escape(text.toLowerCase(Locale.ROOT)) + "%");
            p.addValue("digits", "%" + text.replaceAll("\\D", "") + "%");
        }
        Long total = jdbc.queryForObject("select count(*) from purchasing.supplier" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<Supplier> items = jdbc.query("select * from purchasing.supplier" + where +
                " order by lower(legal_name), id limit :limit offset :offset", p, MAPPER);
        return new Page<>(items, total == null ? 0 : total, page, size);
    }

    @Override
    public boolean existsByDocumentExcludingId(String document, UUID excludedId) {
        var p = new MapSqlParameterSource("document", document).addValue("excludedId", excludedId);
        Integer count = jdbc.queryForObject("""
                select count(*) from purchasing.supplier
                where document=:document and (:excludedId is null or id<>:excludedId)
                """, p, Integer.class);
        return count != null && count > 0;
    }

    private static MapSqlParameterSource params(Supplier s) {
        Supplier.Address a = s.address();
        Supplier.CommercialTerms c = s.commercialTerms();
        return new MapSqlParameterSource()
                .addValue("id", s.id()).addValue("personType", s.personType().name())
                .addValue("legalName", s.legalName()).addValue("tradeName", s.tradeName())
                .addValue("document", s.document()).addValue("phone", s.phone()).addValue("email", s.email())
                .addValue("zipCode", a == null ? null : a.zipCode()).addValue("street", a == null ? null : a.street())
                .addValue("number", a == null ? null : a.number()).addValue("complement", a == null ? null : a.complement())
                .addValue("district", a == null ? null : a.district()).addValue("city", a == null ? null : a.city())
                .addValue("state", a == null ? null : a.state())
                .addValue("paymentTerms", c.paymentTerms()).addValue("preferredPaymentMethod", c.preferredPaymentMethod())
                .addValue("usualDueDay", c.usualDueDay()).addValue("creditLimit", c.creditLimit())
                .addValue("commercialNotes", c.notes()).addValue("status", s.status().name())
                .addValue("createdAt", s.createdAt()).addValue("updatedAt", s.updatedAt());
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }

    private static final RowMapper<Supplier> MAPPER = (rs, rowNum) -> map(rs);
    private static Supplier map(ResultSet rs) throws SQLException {
        var address = new Supplier.Address(rs.getString("address_zip_code"), rs.getString("address_street"),
                rs.getString("address_number"), rs.getString("address_complement"), rs.getString("address_district"),
                rs.getString("address_city"), rs.getString("address_state"));
        Integer dueDay = (Integer) rs.getObject("usual_due_day");
        var terms = new Supplier.CommercialTerms(rs.getString("payment_terms"), rs.getString("preferred_payment_method"),
                dueDay, rs.getBigDecimal("credit_limit"), rs.getString("commercial_notes"));
        return new Supplier(rs.getObject("id", UUID.class), Supplier.PersonType.valueOf(rs.getString("person_type")),
                rs.getString("legal_name"), rs.getString("trade_name"), rs.getString("document"), rs.getString("phone"),
                rs.getString("email"), address, terms, Supplier.Status.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
