package br.com.uberhidraulica.erp.inventory;

/**
 * Recusa do Estoque a uma entrada ou estorno de compra, com código estável. Existe para que Compras nunca precise
 * conhecer a exceção interna do Estoque, que não faz parte do contrato público.
 */
public class PurchaseStockException extends RuntimeException {
    private final String code;

    public PurchaseStockException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
