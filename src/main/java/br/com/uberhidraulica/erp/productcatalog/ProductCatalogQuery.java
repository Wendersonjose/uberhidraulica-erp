package br.com.uberhidraulica.erp.productcatalog;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Contrato público do catálogo de produtos físicos.
 *
 * <p>Expõe somente o que outros módulos precisam para referenciar e tirar snapshot de um item
 * físico. Saldo de estoque não pertence a este contrato.</p>
 */
public interface ProductCatalogQuery {
    Optional<ProductReference> product(UUID id);

    /** {@code salePrice} é nulo enquanto o produto não tiver preço de venda definido. */
    record ProductReference(UUID id, String description, String internalCode, String unit,
                            BigDecimal salePrice, boolean active) {}
}
