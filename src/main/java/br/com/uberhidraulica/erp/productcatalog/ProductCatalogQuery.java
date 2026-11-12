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

    /** Catálogo inteiro, para quem precisa cruzar com dados próprios (saldo de estoque, por exemplo). */
    java.util.List<ProductReference> products();

    /**
     * Produto pelo GTIN. É o critério 2 de casamento de item na importação de NF-e (`DR-0022` D6): vem depois
     * do vínculo já confirmado e antes das sugestões por descrição.
     */
    Optional<ProductReference> productByGtin(String gtin);

    /**
     * Grava a identidade fiscal do produto quando ela ainda não é conhecida, do mesmo jeito que a `D6` trata o
     * vínculo: aprendida na primeira confirmação e reaproveitada depois.
     *
     * <p><b>Nunca sobrescreve</b> valor já gravado. GTIN é identidade do produto, e deixar uma nota alterá-lo
     * em silêncio permitiria que um XML errado repontasse o produto para outro código sem ninguém ver —
     * corrigir um GTIN existente é operação de catálogo, não efeito colateral de importação.</p>
     *
     * @return verdadeiro quando algo foi efetivamente gravado
     */
    boolean learnFiscalIdentity(UUID productId, String gtin, String ncm);

    /** {@code salePrice} é nulo enquanto o produto não tiver preço de venda definido. */
    record ProductReference(UUID id, String description, String internalCode, String unit,
                            BigDecimal salePrice, boolean active, String category, BigDecimal minimumStock,
                            String gtin, String ncm) {}
}
