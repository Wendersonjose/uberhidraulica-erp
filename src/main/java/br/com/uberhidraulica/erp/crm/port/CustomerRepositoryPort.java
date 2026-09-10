package br.com.uberhidraulica.erp.crm.port;
import br.com.uberhidraulica.erp.crm.domain.Customer;
import java.util.*;
public interface CustomerRepositoryPort {
    Customer save(Customer customer);
    Optional<Customer> findById(UUID id);
    List<Customer> findAll();
}
