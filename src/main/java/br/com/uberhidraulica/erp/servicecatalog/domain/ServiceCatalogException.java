package br.com.uberhidraulica.erp.servicecatalog.domain;

public class ServiceCatalogException extends RuntimeException {
    private final String code;

    public ServiceCatalogException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
