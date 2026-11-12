package br.com.uberhidraulica.erp.finance;

/**
 * Falha do contrato {@link PurchasePayables}, com código estável.
 *
 * <p>Existe no pacote público do Financeiro de propósito: o tipo de exceção interno do módulo não é exposto,
 * então quem chama de fora não consegue — nem deveria — depender dele. Assim a fronteira continua
 * verificável pelo {@code ApplicationModules.verify()}, e o chamador ainda recebe um erro de cliente legível
 * em vez de um 500.</p>
 */
public class PurchasePayableException extends RuntimeException {
    private final String code;

    public PurchasePayableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
