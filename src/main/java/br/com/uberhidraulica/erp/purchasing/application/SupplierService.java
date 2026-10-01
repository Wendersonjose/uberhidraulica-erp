package br.com.uberhidraulica.erp.purchasing.application;

import br.com.uberhidraulica.erp.purchasing.domain.Supplier;
import br.com.uberhidraulica.erp.purchasing.port.SupplierRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class SupplierService {
    private final SupplierRepositoryPort repository;
    private final Clock clock;

    public SupplierService(SupplierRepositoryPort repository) {
        this(repository, Clock.systemUTC());
    }

    SupplierService(SupplierRepositoryPort repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public Supplier create(Supplier.PersonType type, String legalName, String tradeName, String document,
                           String phone, String email, Supplier.Address address, Supplier.CommercialTerms terms) {
        String normalizedDocument = normalizeDocument(document);
        ensureDocumentAvailable(normalizedDocument, null);
        return repository.save(Supplier.create(type, legalName, tradeName, normalizedDocument, phone, email, address, terms, now()));
    }

    @Transactional(readOnly = true)
    public Supplier get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new SupplierNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public SupplierRepositoryPort.Page<Supplier> search(String text, Supplier.PersonType personType,
                                                         Supplier.Status status, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return repository.search(clean(text), personType, status, safePage, safeSize);
    }

    @Transactional
    public Supplier update(UUID id, Supplier.PersonType type, String legalName, String tradeName, String document,
                           String phone, String email, Supplier.Address address, Supplier.CommercialTerms terms) {
        Supplier current = get(id);
        String normalizedDocument = normalizeDocument(document);
        ensureDocumentAvailable(normalizedDocument, id);
        return repository.save(current.update(type, legalName, tradeName, normalizedDocument, phone, email, address, terms, now()));
    }

    @Transactional
    public Supplier inactivate(UUID id) { return repository.save(get(id).inactivate(now())); }

    @Transactional
    public Supplier reactivate(UUID id) { return repository.save(get(id).reactivate(now())); }

    private void ensureDocumentAvailable(String document, UUID excludedId) {
        if (document != null && repository.existsByDocumentExcludingId(document, excludedId))
            throw new DuplicateSupplierDocumentException(document);
    }

    private Instant now() { return Instant.now(clock); }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String normalizeDocument(String value) {
        if (value == null || value.isBlank()) return null;
        String digits = value.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    public static final class SupplierNotFoundException extends RuntimeException {
        public SupplierNotFoundException(UUID id) { super("Fornecedor não encontrado: " + id); }
    }
    public static final class DuplicateSupplierDocumentException extends RuntimeException {
        public DuplicateSupplierDocumentException(String document) { super("Já existe fornecedor com este CPF/CNPJ: " + document); }
    }
}
