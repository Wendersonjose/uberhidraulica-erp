package br.com.uberhidraulica.erp.inventory.domain;

public class InventoryException extends RuntimeException {
    private final String code;

    public InventoryException(String code, String message) { super(message); this.code = code; }

    public String code() { return code; }
}
