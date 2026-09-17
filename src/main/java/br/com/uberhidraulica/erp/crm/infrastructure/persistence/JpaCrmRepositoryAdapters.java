package br.com.uberhidraulica.erp.crm.infrastructure.persistence;
import br.com.uberhidraulica.erp.crm.domain.*;
import br.com.uberhidraulica.erp.crm.port.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
class JpaCustomerRepositoryAdapter implements CustomerRepositoryPort {
    private final CustomerJpaRepository repository;
    private final NamedParameterJdbcTemplate jdbc;
    JpaCustomerRepositoryAdapter(CustomerJpaRepository repository, NamedParameterJdbcTemplate jdbc) { this.repository=repository; this.jdbc=jdbc; }

    public Customer save(Customer c) {
        CustomerEntity e=new CustomerEntity();
        e.id=c.id();e.personType=c.personType();e.name=c.name();e.document=c.document();e.phone=c.phone();e.email=c.email();
        if (c.address()!=null) { var a=c.address(); e.addressZipCode=a.zipCode();e.addressStreet=a.street();e.addressNumber=a.number();e.addressComplement=a.complement();e.addressDistrict=a.district();e.addressCity=a.city();e.addressState=a.state(); }
        e.status=c.status();e.createdAt=c.createdAt();e.updatedAt=c.updatedAt();
        return map(repository.saveAndFlush(e));
    }
    public Optional<Customer> findById(UUID id) { return repository.findById(id).map(this::map); }
    public List<Customer> findAll() { return repository.findAllByOrderByNameAscIdAsc().stream().map(this::map).toList(); }

    /** Busca paginada com SQL explícito: o casamento por placa atravessa a tabela de veículos do próprio módulo. */
    public CustomerSearch.Page<Customer> search(CustomerSearch criteria) {
        var where=new StringBuilder(" where 1=1");
        var params=new MapSqlParameterSource();
        if (criteria.personType()!=null) { where.append(" and c.person_type = :type"); params.addValue("type", criteria.personType().name()); }
        if (criteria.status()!=null) { where.append(" and c.status = :status"); params.addValue("status", criteria.status().name()); }
        if (criteria.text()!=null) {
            where.append(" and (lower(c.name) like :name");
            params.addValue("name", "%"+escape(criteria.text().toLowerCase(Locale.ROOT))+"%");
            if (criteria.digits()!=null) {
                where.append(" or c.document like :digits or c.phone like :digits");
                params.addValue("digits", "%"+criteria.digits()+"%");
            }
            if (criteria.plate()!=null) {
                where.append(" or exists (select 1 from crm.vehicle v where v.customer_id = c.id and v.plate like :plate)");
                params.addValue("plate", "%"+criteria.plate()+"%");
            }
            where.append(")");
        }
        long total=Objects.requireNonNull(jdbc.queryForObject("select count(*) from crm.customer c"+where, params, Long.class));
        params.addValue("limit", criteria.size()).addValue("offset", (long) criteria.page()*criteria.size());
        List<UUID> ids=jdbc.queryForList("select c.id from crm.customer c"+where+" order by lower(c.name), c.id limit :limit offset :offset", params, UUID.class);
        Map<UUID, CustomerEntity> byId=repository.findAllById(ids).stream().collect(Collectors.toMap(e->e.id, Function.identity()));
        return new CustomerSearch.Page<>(ids.stream().map(byId::get).map(this::map).toList(), total, criteria.page(), criteria.size());
    }

    /** Escapa curingas do LIKE; o PostgreSQL usa barra invertida como escape padrão. */
    static String escape(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }

    private Customer map(CustomerEntity e) {
        var address=new Customer.Address(e.addressZipCode,e.addressStreet,e.addressNumber,e.addressComplement,e.addressDistrict,e.addressCity,e.addressState);
        return new Customer(e.id,e.personType,e.name,e.document,e.phone,e.email,address,e.status,e.createdAt,e.updatedAt);
    }
}

