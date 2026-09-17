package br.com.uberhidraulica.erp.crm.port;
import br.com.uberhidraulica.erp.crm.domain.Customer;
import br.com.uberhidraulica.erp.crm.domain.CustomerSearch;
import java.util.*;
public interface CustomerRepositoryPort {
    Customer save(Customer customer);
    Optional<Customer> findById(UUID id);
    List<Customer> findAll();
    CustomerSearch.Page<Customer> search(CustomerSearch criteria);
}
