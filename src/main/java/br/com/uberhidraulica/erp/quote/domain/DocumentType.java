package br.com.uberhidraulica.erp.quote.domain;

public enum DocumentType {
    CPF(11), CNPJ(14);

    private final int digits;

    DocumentType(int digits) { this.digits = digits; }

    public int digits() { return digits; }
}
