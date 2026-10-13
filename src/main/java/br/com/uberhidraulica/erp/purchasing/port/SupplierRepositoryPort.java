package br.com.uberhidraulica.erp.purchasing.port;

import br.com.uberhidraulica.erp.purchasing.domain.Supplier;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierContact;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistência de fornecedores, contatos e trilha de auditoria. Fornecedor e evento nunca são apagados. */
public interface SupplierRepositoryPort {
    /** @throws br.com.uberhidraulica.erp.purchasing.domain.PurchasingException {@code SUPPLIER_DOCUMENT_DUPLICATE} */
    void insert(Supplier supplier);

    /**
     * Grava a edição apenas se a versão persistida ainda for {@code expectedVersion}; a versão avança em um.
     *
     * @return falso quando o cadastro não existe ou já foi alterado por outra pessoa
     * @throws br.com.uberhidraulica.erp.purchasing.domain.PurchasingException {@code SUPPLIER_DOCUMENT_DUPLICATE}
     */
    boolean update(Supplier supplier, long expectedVersion);

    Optional<Supplier> find(UUID id);

    /** Leitura com bloqueio de linha: serializa edições, mudança de situação e alterações de contato do mesmo fornecedor. */
    Optional<Supplier> lock(UUID id);

    Page<Supplier> search(Filter filter, int page, int size);

    boolean documentInUse(String document, UUID excludingId);

    List<SupplierContact> contacts(UUID supplierId, boolean includeInactive);

    Optional<SupplierContact> findContact(UUID supplierId, UUID contactId);

    void insertContact(SupplierContact contact);

    void updateContact(SupplierContact contact);

    /** Deixa de ser principal o contato ativo atual, exceto o informado. */
    void clearPrimaryContact(UUID supplierId, UUID exceptContactId);

    void insertEvent(SupplierEvent event);

    Page<SupplierEvent> events(UUID supplierId, int page, int size);

    record Filter(String text, Supplier.PersonType personType, Supplier.Status status, Boolean relatedParty) {}

    record Page<T>(List<T> items, long totalItems, int page, int size) {
        public int totalPages() { return size <= 0 ? 0 : (int) ((totalItems + size - 1) / size); }
    }
}
