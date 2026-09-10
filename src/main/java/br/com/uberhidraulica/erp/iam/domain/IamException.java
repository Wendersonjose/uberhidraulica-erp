package br.com.uberhidraulica.erp.iam.domain;

public class IamException extends RuntimeException {
    private final String code;

    public IamException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
