package br.com.uberhidraulica.erp.purchasing.domain.nfe;

import br.com.uberhidraulica.erp.purchasing.domain.PurchasingException;

import java.util.Locale;

/**
 * Chave de acesso da NF-e: 44 posições {@code cUF(2) AAMM(4) CNPJ(14) mod(2) série(3) nNF(9) tpEmis(1) cNF(8) cDV(1)}.
 * Com CNPJ alfanumérico (NT Conjunta 2025.001) a estrutura é {@code [0-9]{6}[A-Z0-9]{12}[0-9]{26}} e o dígito
 * verificador usa o código ASCII menos 48 de cada posição; para chaves numéricas o resultado é o módulo 11 clássico.
 */
public record NfeAccessKey(String value) {
    private static final String PATTERN = "[0-9]{6}[A-Z0-9]{12}[0-9]{26}";

    public NfeAccessKey {
        value = clean(value);
        if (value == null || !value.matches(PATTERN))
            throw new PurchasingException("NFE_ACCESS_KEY_INVALID", "Chave de acesso deve ter 44 posições");
        if (expectedCheckDigit(value) != value.charAt(43) - '0')
            throw new PurchasingException("NFE_ACCESS_KEY_INVALID", "Dígito verificador da chave de acesso não confere");
    }

    public static NfeAccessKey parse(String raw) { return new NfeAccessKey(raw); }

    /** Verdadeiro quando o texto tem formato e dígito verificador válidos, sem lançar exceção. */
    public static boolean isValid(String raw) {
        String cleaned = clean(raw);
        return cleaned != null && cleaned.matches(PATTERN) && expectedCheckDigit(cleaned) == cleaned.charAt(43) - '0';
    }

    private static String clean(String raw) {
        return raw == null ? null : raw.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }

    private static int expectedCheckDigit(String key) {
        int sum = 0;
        int weight = 2;
        for (int i = 42; i >= 0; i--) {
            sum += (key.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

    public String stateCode() { return value.substring(0, 2); }

    /** {@code AAMM}, por exemplo {@code 2610} para outubro de 2026. */
    public String yearMonth() { return value.substring(2, 6); }

    public String issuerTaxId() { return value.substring(6, 20); }

    public String model() { return value.substring(20, 22); }

    public String series() { return value.substring(22, 25); }

    public long number() { return Long.parseLong(value.substring(25, 34)); }

    public String emissionType() { return value.substring(34, 35); }

    public String numericCode() { return value.substring(35, 43); }

    public int checkDigit() { return value.charAt(43) - '0'; }
}
