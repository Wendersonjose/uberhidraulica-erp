package br.com.uberhidraulica.erp.purchasing.domain.nfe;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NfeXmlParserTest {

    @Test
    void readsTheSyntheticInvoiceShapedLikeTheReferenceNote() {
        NfeFixtures fixture = NfeFixtures.valid();
        NfeDocument d = NfeXmlParser.parse(fixture.xml());

        assertThat(d.accessKeyDeclared()).isEqualTo(fixture.accessKey());
        assertThat(d.layoutVersion()).isEqualTo("4.00");
        assertThat(d.identification().model()).isEqualTo("55");
        assertThat(d.identification().series()).isEqualTo("1");
        assertThat(d.identification().number()).isEqualTo(21L);
        assertThat(d.identification().issuedOn()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(d.issuer().taxId()).isEqualTo("67757350000178");
        assertThat(d.recipient().taxId()).isEqualTo("01637003000101");
        assertThat(d.items()).hasSize(12);
        assertThat(d.items().get(0).supplierCode()).isEqualTo("S-001");
        assertThat(d.items().get(0).quantity()).isEqualByComparingTo("2");
        assertThat(d.items().get(0).icmsCsosn()).isEqualTo("102");
        assertThat(d.items().get(0).gtin()).isNull();
        assertThat(d.totals().invoiceAmount()).isEqualByComparingTo("1943.10");
        assertThat(d.billing().installments()).hasSize(3);
        assertThat(d.billing().installments().get(2).dueDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(d.billing().installments()).allSatisfy(i -> assertThat(i.amount()).isEqualByComparingTo("647.70"));
        assertThat(d.protocol().statusCode()).isEqualTo("100");
        assertThat(d.hasSignature()).isTrue();
        assertThat(d.xmlSha256()).hasSize(64);
    }

    @Test
    void sumsOfTheSyntheticItemsAndInstallmentsEqualTheInvoiceAmountWithoutRoundingDrift() {
        NfeDocument d = NfeFixtures.valid().parse();
        BigDecimal items = d.items().stream().map(NfeDocument.Item::grossTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal dups = d.billing().installments().stream().map(NfeDocument.Installment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(items).isEqualByComparingTo("1943.10");
        assertThat(dups).isEqualByComparingTo(d.totals().invoiceAmount());
    }

    @Test
    void parsesABareNfeWithoutProtocolAndKeepsTheBytesHashStable() {
        NfeFixtures fixture = NfeFixtures.valid();
        fixture.protocol = false;
        NfeDocument first = NfeXmlParser.parse(fixture.xml().getBytes(StandardCharsets.UTF_8));
        NfeDocument second = NfeXmlParser.parse(fixture.xml().getBytes(StandardCharsets.UTF_8));
        assertThat(first.protocol()).isNull();
        assertThat(first.xmlSha256()).isEqualTo(second.xmlSha256());
    }

    @Test
    void refusesDoctypeAndExternalEntitiesToPreventXxe() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE nfeProc [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<nfeProc xmlns=\"http://www.portalfiscal.inf.br/nfe\"><NFe><infNFe Id=\"NFe1\">&x;</infNFe></NFe></nfeProc>";
        assertThatThrownBy(() -> NfeXmlParser.parse(xxe))
                .isInstanceOf(NfeParseException.class).extracting("code").isEqualTo("NFE_XML_MALFORMED");
    }

    @Test
    void refusesEmptyMalformedOversizedAndNonNfeDocuments() {
        assertThatThrownBy(() -> NfeXmlParser.parse("  ")).extracting("code").isEqualTo("NFE_XML_EMPTY");
        assertThatThrownBy(() -> NfeXmlParser.parse("<nfeProc><NFe>")).extracting("code").isEqualTo("NFE_XML_MALFORMED");
        assertThatThrownBy(() -> NfeXmlParser.parse(new byte[NfeXmlParser.MAX_BYTES + 1])).extracting("code").isEqualTo("NFE_XML_TOO_LARGE");
        assertThatThrownBy(() -> NfeXmlParser.parse("<html><body/></html>")).extracting("code").isEqualTo("NFE_XML_NOT_NFE");
        assertThatThrownBy(() -> NfeXmlParser.parse("<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\"><infNFe Id=\"NFe1\"/></NFe>"))
                .extracting("code").isEqualTo("NFE_XML_INCOMPLETE");
    }

    @Test
    void refusesNumbersInScientificNotationInsteadOfSilentlyAcceptingThem() {
        NfeFixtures fixture = NfeFixtures.valid();
        fixture.lines.set(0, new NfeFixtures.Line("S-001", "x", "2.0000", "1.5E2", "300.00"));
        assertThatThrownBy(fixture::parse).extracting("code").isEqualTo("NFE_XML_INVALID_NUMBER");
    }

    @Test
    void doesNotTreatAnInvalidGtinAsAProductIdentifier() {
        String xml = NfeFixtures.valid().xml().replaceFirst("<cEAN>SEM GTIN</cEAN>", "<cEAN>7891000100104</cEAN>");
        NfeDocument.Item item = NfeXmlParser.parse(xml).items().get(0);
        assertThat(item.gtin()).isNull();
        assertThat(item.gtinDeclared()).isEqualTo("7891000100104");
        String ok = NfeFixtures.valid().xml().replaceFirst("<cEAN>SEM GTIN</cEAN>", "<cEAN>7891000100103</cEAN>");
        assertThat(NfeXmlParser.parse(ok).items().get(0).gtin()).isEqualTo("7891000100103");
    }
}
