package br.com.uberhidraulica.erp.purchasing.domain.nfe;

/** GTIN (GS1) de 8, 12, 13 ou 14 dígitos com dígito verificador. "SEM GTIN" e vazio não são códigos. */
public final class Gtin {
    private Gtin() {}

    public static boolean isValid(String value) {
        if (value == null || !value.matches("[0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14}")) return false;
        int sum = 0;
        for (int i = value.length() - 2, weight = 3; i >= 0; i--, weight = weight == 3 ? 1 : 3)
            sum += (value.charAt(i) - '0') * weight;
        int check = (10 - sum % 10) % 10;
        return value.charAt(value.length() - 1) - '0' == check;
    }
}
