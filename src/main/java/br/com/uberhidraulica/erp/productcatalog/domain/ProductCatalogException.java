package br.com.uberhidraulica.erp.productcatalog.domain;

public class ProductCatalogException extends RuntimeException {
    private final String code;

    public ProductCatalogException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
