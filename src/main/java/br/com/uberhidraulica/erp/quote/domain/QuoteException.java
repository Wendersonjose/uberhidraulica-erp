package br.com.uberhidraulica.erp.quote.domain;

public class QuoteException extends RuntimeException {
    private final String code;

    public QuoteException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
