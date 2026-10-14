package br.com.uberhidraulica.erp.purchasing.domain.nfe;

import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeIssue.Severity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NfeValidatorTest {
    private static final NfeValidator.Context THIS_COMPANY = new NfeValidator.Context(NfeFixtures.RECIPIENT, LocalDate.of(2026, 10, 13));

    private static List<NfeIssue> validate(NfeFixtures fixture) {
        return NfeValidator.validate(fixture.parse(), THIS_COMPANY);
    }

    private static List<String> codes(List<NfeIssue> issues, Severity severity) {
        return issues.stream().filter(i -> i.severity() == severity).map(NfeIssue::code).toList();
    }

    @Test
    void acceptsAConsistentInvoiceFromARelatedLegalEntityAndStillReportsWhatWasNotVerified() {
        List<NfeIssue> issues = validate(NfeFixtures.valid());

        assertThat(NfeValidator.hasBlocking(issues)).isFalse();
        assertThat(codes(issues, Severity.WARNING)).isEmpty();
        assertThat(codes(issues, Severity.INFO)).contains("NFE_DISTINCT_LEGAL_ENTITY", "NFE_SIGNATURE_NOT_VERIFIED",
                "NFE_OFFICIAL_VALIDATION_NOT_PERFORMED");
    }

    @Test
    void blocksAnInvoiceAddressedToAnotherCompany() {
        NfeFixtures fixture = NfeFixtures.valid();
        fixture.recipient = "11222333000181";
        assertThat(codes(validate(fixture), Severity.BLOCKING)).contains("NFE_RECIPIENT_NOT_THIS_COMPANY");
    }

    @Test
    void blocksAnInvoiceIssuedByThisSameCompany() {
        NfeFixtures fixture = NfeFixtures.valid();
        fixture.issuer = NfeFixtures.RECIPIENT;
        fixture.recipient = NfeFixtures.ISSUER;
        List<String> blocking = codes(validate(fixture), Severity.BLOCKING);
        assertThat(blocking).contains("NFE_RECIPIENT_NOT_THIS_COMPANY");
        fixture.recipient = NfeFixtures.RECIPIENT;
        assertThat(codes(validate(fixture), Severity.BLOCKING)).contains("NFE_ISSUER_IS_THIS_COMPANY");
    }

    @Test
    void warnsWhenIssuerSharesTheCnpjRootWithThisCompany() {
        NfeFixtures fixture = NfeFixtures.valid();
        fixture.issuer = "01637003000286";
        List<NfeIssue> issues = validate(fixture);
        assertThat(codes(issues, Severity.WARNING)).contains("NFE_SAME_LEGAL_ENTITY_ROOT");
    }

    @Test
    void warnsWhenTheCompanyCnpjIsNotConfiguredInsteadOfAssumingTheRecipientIsRight() {
        List<NfeIssue> issues = NfeValidator.validate(NfeFixtures.valid().parse(), new NfeValidator.Context(null, LocalDate.of(2026, 10, 13)));
        assertThat(codes(issues, Severity.WARNING)).contains("NFE_OWN_TAX_ID_NOT_CONFIGURED");
        assertThat(NfeValidator.hasBlocking(issues)).isFalse();
    }

    @Test
    void blocksAnythingThatIsNotAnAuthorizedModel55Invoice() {
        NfeFixtures model65 = NfeFixtures.valid();
        model65.model = "65";
        assertThat(codes(validate(model65), Severity.BLOCKING)).contains("NFE_MODEL_NOT_55");

        NfeFixtures denied = NfeFixtures.valid();
        denied.status = "110";
        assertThat(codes(validate(denied), Severity.BLOCKING)).contains("NFE_NOT_AUTHORIZED");

        NfeFixtures cancelled = NfeFixtures.valid();
        cancelled.status = "101";
        assertThat(codes(validate(cancelled), Severity.BLOCKING)).contains("NFE_NOT_AUTHORIZED");

        NfeFixtures homologation = NfeFixtures.valid();
        homologation.environment = "2";
        assertThat(codes(validate(homologation), Severity.BLOCKING)).contains("NFE_HOMOLOGATION");

        NfeFixtures noProtocol = NfeFixtures.valid();
        noProtocol.protocol = false;
        assertThat(codes(validate(noProtocol), Severity.BLOCKING)).contains("NFE_PROTOCOL_MISSING");
    }

    @Test
    void acceptsAuthorizedOutsideDeadlineStatus150() {
        NfeFixtures late = NfeFixtures.valid();
        late.status = "150";
        assertThat(NfeValidator.hasBlocking(validate(late))).isFalse();
    }

    @Test
    void blocksAProtocolThatBelongsToAnotherKeyAndAKeyThatDoesNotMatchTheContent() {
        NfeFixtures otherProtocol = NfeFixtures.valid();
        otherProtocol.protocolKeyOverride = "31261067757350000178550010000000221010202154";
        assertThat(codes(validate(otherProtocol), Severity.BLOCKING)).contains("NFE_PROTOCOL_KEY_MISMATCH");

        // Chave válida (DV correto) de outra nota: número 22 no lugar de 21.
        NfeFixtures wrongKey = NfeFixtures.valid();
        String base = "3126106775735000017855001000000022" + "1" + "01020215";
        wrongKey.keyOverride = base + NfeFixtures.checkDigit(base);
        wrongKey.protocolKeyOverride = wrongKey.keyOverride;
        assertThat(codes(validate(wrongKey), Severity.BLOCKING)).contains("NFE_KEY_MISMATCH");

        NfeFixtures badDigit = NfeFixtures.valid();
        badDigit.keyOverride = "31261067757350000178550010000000211010202159";
        badDigit.protocolKeyOverride = badDigit.keyOverride;
        assertThat(codes(validate(badDigit), Severity.BLOCKING)).contains("NFE_KEY_INVALID");
    }

    @Test
    void blocksWhenTheItemsDoNotAddUpToTheDeclaredProductTotal() {
        NfeFixtures fixture = NfeFixtures.valid();
        fixture.productsAmountOverride = "1943.11";
        assertThat(codes(validate(fixture), Severity.BLOCKING)).contains("NFE_TOTAL_PRODUCTS_MISMATCH");
    }

    @Test
    void asksForAcknowledgementWhenInvoiceAmountAndInstallmentsDisagree() {
        NfeFixtures fixture = NfeFixtures.valid();
        fixture.installments.set(2, new String[]{"003", "2026-11-06", "647.69"});
        List<NfeIssue> issues = validate(fixture);
        assertThat(codes(issues, Severity.WARNING)).contains("NFE_INSTALLMENTS_SUM_MISMATCH", "NFE_BILLING_NET_MISMATCH");
        assertThat(NfeValidator.hasBlocking(issues)).isFalse();

        NfeFixtures formula = NfeFixtures.valid();
        formula.invoiceAmount = "1943.20";
        assertThat(codes(validate(formula), Severity.WARNING)).contains("NFE_TOTAL_INVOICE_FORMULA_MISMATCH");
    }

    @Test
    void reportsInvoicesWithoutBillingAsInformationOnly() {
        NfeFixtures fixture = NfeFixtures.valid();
        fixture.installments.clear();
        List<NfeIssue> issues = validate(fixture);
        assertThat(codes(issues, Severity.INFO)).contains("NFE_NO_BILLING_INSTALLMENTS");
        assertThat(NfeValidator.hasBlocking(issues)).isFalse();
    }

    @Test
    void flagsItemLevelProblemsWithTheItemNumber() {
        NfeFixtures fixture = NfeFixtures.valid();
        fixture.lines.set(3, new NfeFixtures.Line("S-004", "Item sintetico 04", "0.0000", "12.35", "123.50"));
        String xml = fixture.xml().replaceFirst("<NCM>84133090</NCM>", "<NCM>8413</NCM>").replace("<CFOP>5102</CFOP>", "<CFOP>1102</CFOP>");
        List<NfeIssue> issues = NfeValidator.validate(NfeXmlParser.parse(xml), THIS_COMPANY);

        assertThat(issues).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo("NFE_ITEM_QUANTITY_INVALID");
            assertThat(i.severity()).isEqualTo(Severity.BLOCKING);
            assertThat(i.itemNumber()).isEqualTo(4);
        });
        assertThat(codes(issues, Severity.WARNING)).contains("NFE_ITEM_NCM_INVALID", "NFE_ITEM_CFOP_UNEXPECTED");
    }

    @Test
    void warnsAboutMissingSignatureAndFutureIssueDate() {
        NfeFixtures fixture = NfeFixtures.valid();
        fixture.signature = false;
        fixture.issuedAt = "2026-12-01T10:15:00-03:00";
        List<String> warnings = codes(validate(fixture), Severity.WARNING);
        assertThat(warnings).contains("NFE_SIGNATURE_ABSENT", "NFE_ISSUED_IN_FUTURE");
    }
}
