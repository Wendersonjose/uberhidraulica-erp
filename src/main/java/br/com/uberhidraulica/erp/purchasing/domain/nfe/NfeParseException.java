package br.com.uberhidraulica.erp.purchasing.domain.nfe;

import br.com.uberhidraulica.erp.purchasing.domain.PurchasingException;

/** O texto recebido não é uma NF-e legível: XML malformado, inseguro, grande demais ou sem as tags mínimas. */
public class NfeParseException extends PurchasingException {
    public NfeParseException(String code, String message) {
        super(code, message);
    }
}
