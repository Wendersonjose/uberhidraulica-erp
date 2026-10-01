package br.com.uberhidraulica.erp.purchasing.port;

import br.com.uberhidraulica.erp.purchasing.domain.Supplier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupplierRepositoryPort {
    Supplier save(Supplier supplier);
    Optional<Supplier> findById(UUID id);
    Page<Supplier> search(String text, Supplier.PersonType personType, Supplier.Status status, int page, int size);
    boolean existsByDocumentExcludingId(String document, UUID excludedId);

    record Page<T>(List<T> items, long totalItems, int page, int size) {
        public int totalPages() { return size == 0 ? 0 : (int) Math.ceil((double) totalItems / size); }
    }
}
