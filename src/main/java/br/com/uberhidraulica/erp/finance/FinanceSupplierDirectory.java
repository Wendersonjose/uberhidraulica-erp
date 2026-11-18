package br.com.uberhidraulica.erp.finance;

import java.util.Optional;
import java.util.UUID;

/**
 * Cadastro de fornecedores, como o Financeiro precisa dele para vincular a conta a pagar (DR-0024, Q6).
 *
 * <p>O Financeiro declara o contrato e Compras o implementa: Compras já depende do Financeiro
 * ({@link PurchasePayables}), e o inverso criaria um ciclo entre módulos. O Financeiro guarda só o id e um snapshot
 * do nome; nunca lê tabela de Compras.</p>
 */
public interface FinanceSupplierDirectory {

    Optional<SupplierReference> supplier(UUID id);

    /** Nome de exibição (fantasia, ou razão social) e situação do cadastro. */
    record SupplierReference(UUID id, String name, boolean active) {}
}
