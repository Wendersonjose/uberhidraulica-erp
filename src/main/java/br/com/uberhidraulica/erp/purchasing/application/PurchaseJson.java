package br.com.uberhidraulica.erp.purchasing.application;

import br.com.uberhidraulica.erp.purchasing.domain.Supplier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON mínimo para a trilha de auditoria e para o retrato comercial do fornecedor.
 *
 * <p>É um mapa de texto, escrito à mão de propósito: o payload da auditoria nunca carrega o XML nem objetos de
 * domínio, e não vale puxar um serializador para isso.</p>
 */
final class PurchaseJson {
    private PurchaseJson() {}

    static String of(Map<String, String> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(values).entrySet()) {
            if (!first) out.append(',');
            out.append('"').append(escape(entry.getKey())).append("\":\"").append(escape(entry.getValue())).append('"');
            first = false;
        }
        return out.append('}').toString();
    }

    /** Condições comerciais congeladas no instante da compra, para o histórico não mudar com o cadastro. */
    static String commercialSnapshot(Supplier supplier) {
        Supplier.CommercialTerms terms = supplier.terms();
        if (terms == null) return null;
        return of(Map.of("paymentTerms", String.valueOf(terms.paymentTerms()),
                "preferredPaymentMethod", String.valueOf(terms.preferredPaymentMethod()),
                "usualDueDay", String.valueOf(terms.usualDueDay())));
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
