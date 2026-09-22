package br.com.uberhidraulica.erp.finance.domain;

/** Recusa de regra financeira, identificada por código estável para a API. */
public class FinanceException extends RuntimeException {
    private final String code;

    public FinanceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
