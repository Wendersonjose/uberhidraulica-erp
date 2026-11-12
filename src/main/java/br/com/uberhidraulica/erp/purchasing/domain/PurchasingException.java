package br.com.uberhidraulica.erp.purchasing.domain;

/** Recusa de regra de Compras, identificada por código estável para a API. */
public class PurchasingException extends RuntimeException {
    private final String code;

    public PurchasingException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
