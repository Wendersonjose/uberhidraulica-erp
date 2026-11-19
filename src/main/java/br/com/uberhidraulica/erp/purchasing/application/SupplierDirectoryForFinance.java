package br.com.uberhidraulica.erp.purchasing.application;

import br.com.uberhidraulica.erp.finance.FinanceSupplierDirectory;
import br.com.uberhidraulica.erp.purchasing.domain.Supplier;
import br.com.uberhidraulica.erp.purchasing.port.SupplierRepositoryPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Implementação, por Compras, do contrato de fornecedores que o Financeiro declara (DR-0024, Q6). */
@Component
class SupplierDirectoryForFinance implements FinanceSupplierDirectory {
    private final SupplierRepositoryPort suppliers;

    SupplierDirectoryForFinance(SupplierRepositoryPort suppliers) { this.suppliers = suppliers; }

    @Override
    @Transactional(readOnly = true)
    public Optional<SupplierReference> supplier(UUID id) {
        if (id == null) return Optional.empty();
        return suppliers.find(id).map(s -> new SupplierReference(s.id(),
                s.tradeName() != null && !s.tradeName().isBlank() ? s.tradeName() : s.legalName(), s.status() == Supplier.Status.ACTIVE));
    }
}
