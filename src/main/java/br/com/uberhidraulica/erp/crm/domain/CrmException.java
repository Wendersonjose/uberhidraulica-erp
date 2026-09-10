package br.com.uberhidraulica.erp.crm.domain;

public class CrmException extends RuntimeException {
    private final String code;
    public CrmException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
