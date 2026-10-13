package br.com.uberhidraulica.erp.purchasing.domain.nfe;

import br.com.uberhidraulica.erp.purchasing.domain.PurchasingException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NfeAccessKeyTest {
    /** Chave da NF-e de referência da TASK-0021 (número 21, série 1, emitente 67.757.350/0001-78). */
    private static final String REFERENCE_KEY = "31261067757350000178550010000000211010202158";

    @Test
    void decodesTheReferenceInvoiceKey() {
        NfeAccessKey key = NfeAccessKey.parse(REFERENCE_KEY);
        assertThat(key.stateCode()).isEqualTo("31");
        assertThat(key.yearMonth()).isEqualTo("2610");
        assertThat(key.issuerTaxId()).isEqualTo("67757350000178");
        assertThat(key.model()).isEqualTo("55");
        assertThat(key.series()).isEqualTo("001");
        assertThat(key.number()).isEqualTo(21);
        assertThat(key.emissionType()).isEqualTo("1");
        assertThat(key.numericCode()).isEqualTo("01020215");
        assertThat(key.checkDigit()).isEqualTo(8);
    }

    @Test
    void toleratesWhitespaceInThePastedKey() {
        assertThat(NfeAccessKey.parse("3126 1067 7573 5000 0178 5500 1000 0000 2110 1020 2158").value()).isEqualTo(REFERENCE_KEY);
    }

    @Test
    void refusesWrongCheckDigitLengthAndNonDigits() {
        assertThatThrownBy(() -> NfeAccessKey.parse("31261067757350000178550010000000211010202159"))
                .isInstanceOf(PurchasingException.class).hasMessageContaining("verificador");
        assertThat(NfeAccessKey.isValid("3126106775735000017855001000000021101020215")).isFalse();
        assertThat(NfeAccessKey.isValid("3126106775735000017855001000000021101020215A")).isFalse();
        assertThat(NfeAccessKey.isValid(null)).isFalse();
    }

    @Test
    void acceptsAnAlphanumericCnpjSegmentWithTheAsciiMinus48CheckDigit() {
        // cUF(2) AAMM(4) CNPJ(14) mod(2) série(3) nNF(9) tpEmis(1) cNF(8) = 43 posições; o fixture pondera por (c - '0').
        String base = "312610" + "12ABC34501DE35" + "55" + "001" + "000000021" + "1" + "01020215";
        assertThat(base).hasSize(43);
        String key = base + NfeFixtures.checkDigit(base);
        assertThat(NfeAccessKey.isValid(key)).isTrue();
        assertThat(NfeAccessKey.parse(key).issuerTaxId()).isEqualTo("12ABC34501DE35");
        assertThat(NfeAccessKey.isValid(base + (Character.digit(key.charAt(43), 10) + 1) % 10)).isFalse();
    }
}
