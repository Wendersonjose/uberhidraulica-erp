package br.com.uberhidraulica.erp.purchasing.domain.nfe;

import java.util.Locale;

/**
 * CPF e CNPJ. O CNPJ aceita o formato alfanumérico (NT Conjunta 2025.001): 12 posições alfanuméricas seguidas de
 * dois dígitos verificadores numéricos, calculados com o mesmo módulo 11 usando como valor de cada caractere o
 * código ASCII menos 48 — para números o valor é o próprio dígito, então CNPJ numérico segue idêntico.
 */
public final class BrazilianTaxId {
    private static final int[] CNPJ_WEIGHTS_1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] CNPJ_WEIGHTS_2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    private BrazilianTaxId() {}

    /** Remove máscara e normaliza para maiúsculas; null permanece null. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        return raw.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
    }

    public static boolean isValidCnpj(String value) {
        String cnpj = normalize(value);
        if (cnpj == null || !cnpj.matches("[0-9A-Z]{12}[0-9]{2}")) return false;
        if (cnpj.chars().distinct().count() == 1) return false;
        int first = checkDigit(cnpj, 12, CNPJ_WEIGHTS_1);
        int second = checkDigit(cnpj, 13, CNPJ_WEIGHTS_2);
        return cnpj.charAt(12) - '0' == first && cnpj.charAt(13) - '0' == second;
    }

    public static boolean isValidCpf(String value) {
        String cpf = normalize(value);
        if (cpf == null || !cpf.matches("[0-9]{11}")) return false;
        if (cpf.chars().distinct().count() == 1) return false;
        int first = cpfDigit(cpf, 9);
        int second = cpfDigit(cpf, 10);
        return cpf.charAt(9) - '0' == first && cpf.charAt(10) - '0' == second;
    }

    /** CNPJ (14 posições) ou CPF (11 dígitos) válido. */
    public static boolean isValid(String value) { return isValidCnpj(value) || isValidCpf(value); }

    public static String format(String value) {
        String id = normalize(value);
        if (id != null && id.length() == 14)
            return id.substring(0, 2) + "." + id.substring(2, 5) + "." + id.substring(5, 8) + "/" + id.substring(8, 12) + "-" + id.substring(12);
        if (id != null && id.length() == 11)
            return id.substring(0, 3) + "." + id.substring(3, 6) + "." + id.substring(6, 9) + "-" + id.substring(9);
        return id;
    }

    private static int checkDigit(String cnpj, int length, int[] weights) {
        int sum = 0;
        for (int i = 0; i < length; i++) sum += (cnpj.charAt(i) - '0') * weights[i];
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

    private static int cpfDigit(String cpf, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) sum += (cpf.charAt(i) - '0') * (length + 1 - i);
        int remainder = (sum * 10) % 11;
        return remainder == 10 ? 0 : remainder;
    }
}
