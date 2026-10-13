package br.com.uberhidraulica.erp.purchasing.domain.nfe;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrazilianTaxIdAndGtinTest {

    @Test
    void validatesTheCnpjsOfTheReferenceInvoiceWithAndWithoutMask() {
        assertThat(BrazilianTaxId.isValidCnpj("67757350000178")).isTrue();
        assertThat(BrazilianTaxId.isValidCnpj("67.757.350/0001-78")).isTrue();
        assertThat(BrazilianTaxId.isValidCnpj("01.637.003/0001-01")).isTrue();
        assertThat(BrazilianTaxId.format("67757350000178")).isEqualTo("67.757.350/0001-78");
    }

    @Test
    void refusesWrongCheckDigitsRepeatedDigitsAndBadLength() {
        assertThat(BrazilianTaxId.isValidCnpj("67757350000179")).isFalse();
        assertThat(BrazilianTaxId.isValidCnpj("00000000000000")).isFalse();
        assertThat(BrazilianTaxId.isValidCnpj("6775735000017")).isFalse();
        assertThat(BrazilianTaxId.isValidCnpj(null)).isFalse();
    }

    @Test
    void acceptsTheOfficialAlphanumericCnpjExample() {
        // Exemplo publicado na documentação da Receita Federal para o CNPJ alfanumérico: 12.ABC.345/01DE-35.
        assertThat(BrazilianTaxId.isValidCnpj("12.ABC.345/01DE-35")).isTrue();
        assertThat(BrazilianTaxId.isValidCnpj("12ABC34501DE36")).isFalse();
    }

    @Test
    void validatesCpf() {
        assertThat(BrazilianTaxId.isValidCpf("529.982.247-25")).isTrue();
        assertThat(BrazilianTaxId.isValidCpf("52998224726")).isFalse();
        assertThat(BrazilianTaxId.isValidCpf("11111111111")).isFalse();
        assertThat(BrazilianTaxId.isValid("52998224725")).isTrue();
    }

    @Test
    void validatesGtinCheckDigit() {
        assertThat(Gtin.isValid("7891000100103")).isTrue();
        assertThat(Gtin.isValid("4006381333931")).isTrue();
        assertThat(Gtin.isValid("7891000100104")).isFalse();
        assertThat(Gtin.isValid("SEM GTIN")).isFalse();
        assertThat(Gtin.isValid(null)).isFalse();
        assertThat(Gtin.isValid("123")).isFalse();
    }
}
