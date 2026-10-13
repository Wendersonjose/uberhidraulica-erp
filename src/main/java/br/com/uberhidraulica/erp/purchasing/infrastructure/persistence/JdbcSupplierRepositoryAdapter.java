package br.com.uberhidraulica.erp.purchasing.infrastructure.persistence;

import br.com.uberhidraulica.erp.purchasing.domain.PurchasingException;
import br.com.uberhidraulica.erp.purchasing.domain.Supplier;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierContact;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierEvent;
import br.com.uberhidraulica.erp.purchasing.port.SupplierRepositoryPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistência de fornecedores em SQL explícito. Não há DELETE: o banco também o recusa. */
@Component
class JdbcSupplierRepositoryAdapter implements SupplierRepositoryPort {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NamedParameterJdbcTemplate jdbc;

    JdbcSupplierRepositoryAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ================================================================ fornecedor

    @Override
    public void insert(Supplier s) {
        try {
            jdbc.update("""
                    insert into purchasing.supplier (
                        id, person_type, legal_name, trade_name, document, state_registration, phone, email,
                        address_zip_code, address_street, address_number, address_complement, address_district, address_city, address_state,
                        payment_terms, preferred_payment_method, usual_due_day, credit_limit, commercial_notes,
                        related_party, related_party_note, status, inactivated_at, inactivated_by, inactivation_reason,
                        version, created_at, created_by, updated_at, updated_by)
                    values (
                        :id, :personType, :legalName, :tradeName, :document, :stateRegistration, :phone, :email,
                        :zipCode, :street, :number, :complement, :district, :city, :state,
                        :paymentTerms, :preferredPaymentMethod, :usualDueDay, :creditLimit, :commercialNotes,
                        :relatedParty, :relatedPartyNote, :status, :inactivatedAt, :inactivatedBy, :inactivationReason,
                        0, :createdAt, :createdBy, :updatedAt, :updatedBy)
                    """, params(s));
        } catch (DuplicateKeyException e) {
            throw duplicateDocument();
        }
    }

    @Override
    public boolean update(Supplier s, long expectedVersion) {
        try {
            return jdbc.update("""
                    update purchasing.supplier set
                        person_type = :personType, legal_name = :legalName, trade_name = :tradeName, document = :document,
                        state_registration = :stateRegistration, phone = :phone, email = :email,
                        address_zip_code = :zipCode, address_street = :street, address_number = :number,
                        address_complement = :complement, address_district = :district, address_city = :city, address_state = :state,
                        payment_terms = :paymentTerms, preferred_payment_method = :preferredPaymentMethod, usual_due_day = :usualDueDay,
                        credit_limit = :creditLimit, commercial_notes = :commercialNotes,
                        related_party = :relatedParty, related_party_note = :relatedPartyNote,
                        status = :status, inactivated_at = :inactivatedAt, inactivated_by = :inactivatedBy,
                        inactivation_reason = :inactivationReason,
                        updated_at = :updatedAt, updated_by = :updatedBy, version = version + 1
                    where id = :id and version = :expectedVersion
                    """, params(s).addValue("expectedVersion", expectedVersion)) == 1;
        } catch (DuplicateKeyException e) {
            throw duplicateDocument();
        }
    }

    @Override
    public Optional<Supplier> find(UUID id) {
        return jdbc.query("select * from purchasing.supplier where id = :id", Map.of("id", id), (rs, i) -> supplier(rs)).stream().findFirst();
    }

    @Override
    public Optional<Supplier> lock(UUID id) {
        return jdbc.query("select * from purchasing.supplier where id = :id for update", Map.of("id", id), (rs, i) -> supplier(rs)).stream().findFirst();
    }

    @Override
    public Page<Supplier> search(Filter filter, int page, int size) {
        StringBuilder where = new StringBuilder(" where true");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (filter.personType() != null) { where.append(" and person_type = :personType"); p.addValue("personType", filter.personType().name()); }
        if (filter.status() != null) { where.append(" and status = :status"); p.addValue("status", filter.status().name()); }
        if (filter.relatedParty() != null) { where.append(" and related_party = :relatedParty"); p.addValue("relatedParty", filter.relatedParty()); }
        if (filter.text() != null) {
            String alnum = filter.text().replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
            where.append(" and (lower(legal_name) like :text or lower(coalesce(trade_name, '')) like :text");
            p.addValue("text", "%" + escapeLike(filter.text().toLowerCase(Locale.ROOT)) + "%");
            if (alnum.length() >= 3) { where.append(" or coalesce(document, '') like :document"); p.addValue("document", "%" + alnum + "%"); }
            where.append(")");
        }
        Long total = jdbc.queryForObject("select count(*) from purchasing.supplier" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<Supplier> items = jdbc.query("select * from purchasing.supplier" + where
                + " order by lower(legal_name), id limit :limit offset :offset", p, (rs, i) -> supplier(rs));
        return new Page<>(items, total == null ? 0 : total, page, size);
    }

    @Override
    public Optional<Supplier> findByDocument(String document) {
        if (document == null || document.isBlank()) return Optional.empty();
        return jdbc.query("select * from purchasing.supplier where document = :document",
                Map.of("document", document.trim()), (rs, i) -> supplier(rs)).stream().findFirst();
    }

    @Override
    public boolean documentInUse(String document, UUID excludingId) {
        Boolean exists = jdbc.queryForObject("""
                select exists (select 1 from purchasing.supplier where document = :document and (cast(:excluding as uuid) is null or id <> :excluding))
                """, new MapSqlParameterSource("document", document).addValue("excluding", excludingId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    private static MapSqlParameterSource params(Supplier s) {
        Supplier.Address a = s.address();
        Supplier.CommercialTerms t = s.terms();
        return new MapSqlParameterSource()
                .addValue("id", s.id()).addValue("personType", s.personType().name()).addValue("legalName", s.legalName())
                .addValue("tradeName", s.tradeName()).addValue("document", s.document()).addValue("stateRegistration", s.stateRegistration())
                .addValue("phone", s.phone()).addValue("email", s.email())
                .addValue("zipCode", a.zipCode()).addValue("street", a.street()).addValue("number", a.number())
                .addValue("complement", a.complement()).addValue("district", a.district()).addValue("city", a.city()).addValue("state", a.state())
                .addValue("paymentTerms", t.paymentTerms()).addValue("preferredPaymentMethod", t.preferredPaymentMethod())
                .addValue("usualDueDay", t.usualDueDay()).addValue("creditLimit", t.creditLimit()).addValue("commercialNotes", t.notes())
                .addValue("relatedParty", s.relatedParty()).addValue("relatedPartyNote", s.relatedPartyNote())
                .addValue("status", s.status().name()).addValue("inactivatedAt", ts(s.inactivatedAt()))
                .addValue("inactivatedBy", s.inactivatedBy()).addValue("inactivationReason", s.inactivationReason())
                .addValue("createdAt", ts(s.createdAt())).addValue("createdBy", s.createdBy())
                .addValue("updatedAt", ts(s.updatedAt())).addValue("updatedBy", s.updatedBy());
    }

    private static Supplier supplier(ResultSet rs) throws SQLException {
        Supplier.Address address = new Supplier.Address(rs.getString("address_zip_code"), rs.getString("address_street"),
                rs.getString("address_number"), rs.getString("address_complement"), rs.getString("address_district"),
                rs.getString("address_city"), rs.getString("address_state"));
        Supplier.CommercialTerms terms = new Supplier.CommercialTerms(rs.getString("payment_terms"), rs.getString("preferred_payment_method"),
                rs.getObject("usual_due_day", Integer.class), rs.getBigDecimal("credit_limit"), rs.getString("commercial_notes"));
        return new Supplier(rs.getObject("id", UUID.class), Supplier.PersonType.valueOf(rs.getString("person_type")), rs.getString("legal_name"),
                rs.getString("trade_name"), rs.getString("document"), rs.getString("state_registration"), rs.getString("phone"), rs.getString("email"),
                address, terms, rs.getBoolean("related_party"), rs.getString("related_party_note"), Supplier.Status.valueOf(rs.getString("status")),
                instant(rs, "inactivated_at"), rs.getObject("inactivated_by", UUID.class), rs.getString("inactivation_reason"), rs.getLong("version"),
                instant(rs, "created_at"), rs.getObject("created_by", UUID.class), instant(rs, "updated_at"), rs.getObject("updated_by", UUID.class));
    }

    // ================================================================ contatos

    @Override
    public List<SupplierContact> contacts(UUID supplierId, boolean includeInactive) {
        return jdbc.query("select * from purchasing.supplier_contact where supplier_id = :id"
                + (includeInactive ? "" : " and active") + " order by active desc, primary_contact desc, lower(name), id",
                Map.of("id", supplierId), (rs, i) -> contact(rs));
    }

    @Override
    public Optional<SupplierContact> findContact(UUID supplierId, UUID contactId) {
        return jdbc.query("select * from purchasing.supplier_contact where supplier_id = :supplier and id = :id",
                Map.of("supplier", supplierId, "id", contactId), (rs, i) -> contact(rs)).stream().findFirst();
    }

    @Override
    public void insertContact(SupplierContact c) {
        try {
            jdbc.update("""
                    insert into purchasing.supplier_contact (id, supplier_id, name, role, phone, email, primary_contact, active, created_at, created_by, updated_at)
                    values (:id, :supplierId, :name, :role, :phone, :email, :primary, :active, :createdAt, :createdBy, :updatedAt)
                    """, contactParams(c));
        } catch (DuplicateKeyException e) {
            throw primaryContactConflict();
        }
    }

    @Override
    public void updateContact(SupplierContact c) {
        try {
            jdbc.update("""
                    update purchasing.supplier_contact set name = :name, role = :role, phone = :phone, email = :email,
                        primary_contact = :primary, active = :active, updated_at = :updatedAt
                    where id = :id and supplier_id = :supplierId
                    """, contactParams(c));
        } catch (DuplicateKeyException e) {
            throw primaryContactConflict();
        }
    }

    @Override
    public void clearPrimaryContact(UUID supplierId, UUID exceptContactId) {
        jdbc.update("update purchasing.supplier_contact set primary_contact = false where supplier_id = :supplier and primary_contact and id <> :except",
                Map.of("supplier", supplierId, "except", exceptContactId));
    }

    private static MapSqlParameterSource contactParams(SupplierContact c) {
        return new MapSqlParameterSource().addValue("id", c.id()).addValue("supplierId", c.supplierId()).addValue("name", c.name())
                .addValue("role", c.role()).addValue("phone", c.phone()).addValue("email", c.email()).addValue("primary", c.primary())
                .addValue("active", c.active()).addValue("createdAt", ts(c.createdAt())).addValue("createdBy", c.createdBy())
                .addValue("updatedAt", ts(c.updatedAt()));
    }

    private static SupplierContact contact(ResultSet rs) throws SQLException {
        return new SupplierContact(rs.getObject("id", UUID.class), rs.getObject("supplier_id", UUID.class), rs.getString("name"),
                rs.getString("role"), rs.getString("phone"), rs.getString("email"), rs.getBoolean("primary_contact"), rs.getBoolean("active"),
                instant(rs, "created_at"), rs.getObject("created_by", UUID.class), instant(rs, "updated_at"));
    }

    // ================================================================ eventos

    @Override
    public void insertEvent(SupplierEvent e) {
        jdbc.update("""
                insert into purchasing.supplier_event (id, supplier_id, event_type, actor_id, occurred_at, reason, changes)
                values (:id, :supplierId, :type, :actor, :occurredAt, :reason, cast(:changes as jsonb))
                """, new MapSqlParameterSource().addValue("id", e.id()).addValue("supplierId", e.supplierId()).addValue("type", e.type().name())
                .addValue("actor", e.actorId()).addValue("occurredAt", ts(e.occurredAt())).addValue("reason", e.reason())
                .addValue("changes", e.changes().isEmpty() ? null : changesJson(e.changes())));
    }

    @Override
    public Page<SupplierEvent> events(UUID supplierId, int page, int size) {
        Long total = jdbc.queryForObject("select count(*) from purchasing.supplier_event where supplier_id = :id", Map.of("id", supplierId), Long.class);
        List<SupplierEvent> items = jdbc.query("""
                select id, supplier_id, event_type, actor_id, occurred_at, reason, changes::text as changes
                from purchasing.supplier_event where supplier_id = :id
                order by occurred_at desc, id desc limit :limit offset :offset
                """, new MapSqlParameterSource("id", supplierId).addValue("limit", size).addValue("offset", (long) page * size),
                (rs, i) -> new SupplierEvent(rs.getObject("id", UUID.class), rs.getObject("supplier_id", UUID.class),
                        SupplierEvent.Type.valueOf(rs.getString("event_type")), rs.getObject("actor_id", UUID.class),
                        instant(rs, "occurred_at"), rs.getString("reason"), parseChanges(rs.getString("changes"))));
        return new Page<>(items, total == null ? 0 : total, page, size);
    }

    private static String changesJson(Map<String, SupplierEvent.Change> changes) {
        Map<String, Map<String, String>> tree = new LinkedHashMap<>();
        changes.forEach((field, change) -> {
            Map<String, String> pair = new LinkedHashMap<>();
            pair.put("before", change.before());
            pair.put("after", change.after());
            tree.put(field, pair);
        });
        return JSON.writeValueAsString(tree);
    }

    private static Map<String, SupplierEvent.Change> parseChanges(String json) {
        if (json == null) return Map.of();
        Map<String, SupplierEvent.Change> changes = new LinkedHashMap<>();
        JsonNode root = JSON.readTree(json);
        root.properties().forEach(entry -> changes.put(entry.getKey(),
                new SupplierEvent.Change(text(entry.getValue().get("before")), text(entry.getValue().get("after")))));
        return changes;
    }

    private static String text(JsonNode node) { return node == null || node.isNull() ? null : node.asString(); }

    // ================================================================ utilidades

    private static PurchasingException duplicateDocument() {
        return new PurchasingException("SUPPLIER_DOCUMENT_DUPLICATE", "Já existe fornecedor com este CPF/CNPJ");
    }

    private static PurchasingException primaryContactConflict() {
        return new PurchasingException("SUPPLIER_CONTACT_PRIMARY_CONFLICT", "Outra pessoa definiu o contato principal ao mesmo tempo; tente novamente");
    }

    private static String escapeLike(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }

    private static Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
