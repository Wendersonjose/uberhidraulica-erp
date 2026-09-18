package br.com.uberhidraulica.erp.productcatalog.domain;

/**
 * Unidade-base de estoque do item (AG-04, seções 21 e 22).
 *
 * <p>Toda quantidade — de catálogo, de OS e de movimentação — é expressa nesta unidade, de modo que
 * não existe soma entre unidades diferentes. Se a quantidade admite fração, e com que granularidade
 * por unidade, é a pergunta em aberto da {@code DR-0006} e não é decidida aqui.</p>
 */
public enum ProductUnit {
    UNIDADE, LITRO, METRO, QUILOGRAMA,
    /** Embalagens fechadas de fluido, para quem controla por galão ou balde (DR-0014). */
    GALAO_5L, BALDE_20L
}
