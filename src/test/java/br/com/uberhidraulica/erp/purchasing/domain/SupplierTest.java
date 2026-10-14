package br.com.uberhidraulica.erp.purchasing.domain;

import br.com.uberhidraulica.erp.purchasing.domain.Supplier.Address;
import br.com.uberhidraulica.erp.purchasing.domain.Supplier.CommercialTerms;
import br.com.uberhidraulica.erp.purchasing.domain.Supplier.Draft;
import br.com.uberhidraulica.erp.purchasing.domain.Supplier.PersonType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

    private static Draft pj(String document) {
        return new Draft(PersonType.PJ, "  Fornecedor Sintético Ltda ", " Sintético ", document, null, "(34) 3333-4444", "vendas@exemplo.test",
                new Address("38400-000", "Rua A", "10", null, "Centro", "Uberlândia", "mg"), CommercialTerms.EMPTY, false, null);
    }

    private static Draft pf(String document) {
        return new Draft(PersonType.PF, "João Autônomo", null, document, null, null, null, null, null, false, null);
    }

    private static Supplier register(Draft draft) { return Supplier.register(draft, ACTOR, NOW); }

    @Test
    void normalizesAndValidatesALegalEntity() {
        Supplier supplier = register(pj("67.757.350/0001-78"));

        assertThat(supplier.document()).isEqualTo("67757350000178");
        assertThat(supplier.legalName()).isEqualTo("Fornecedor Sintético Ltda");
        assertThat(supplier.tradeName()).isEqualTo("Sintético");
        assertThat(supplier.address().zipCode()).isEqualTo("38400000");
        assertThat(supplier.address().state()).isEqualTo("MG");
        assertThat(supplier.status()).isEqualTo(Supplier.Status.ACTIVE);
        assertThat(supplier.version()).isZero();
        assertThat(supplier.createdBy()).isEqualTo(ACTOR);
    }

    @Test
    void acceptsTheAlphanumericCnpjForALegalEntity() {
        assertThat(register(pj("12.ABC.345/01DE-35")).document()).isEqualTo("12ABC34501DE35");
    }

    @Test
    void acceptsANaturalPersonWithValidCpfAndASupplierWithoutDocument() {
        assertThat(register(pf("529.982.247-25")).document()).isEqualTo("52998224725");
        assertThat(register(pf(null)).document()).isNull();
        assertThat(register(pf("   ")).document()).isNull();
    }

    @Test
    void refusesDocumentsThatDoNotMatchThePersonTypeOrHaveWrongCheckDigits() {
        assertThatThrownBy(() -> register(pf("67757350000178"))).isInstanceOf(PurchasingException.class)
                .extracting("code").isEqualTo("SUPPLIER_DOCUMENT_INVALID");
        assertThatThrownBy(() -> register(pj("52998224725"))).extracting("code").isEqualTo("SUPPLIER_DOCUMENT_INVALID");
        assertThatThrownBy(() -> register(pj("67757350000179"))).extracting("code").isEqualTo("SUPPLIER_DOCUMENT_INVALID");
        assertThatThrownBy(() -> register(pf("52998224726"))).extracting("code").isEqualTo("SUPPLIER_DOCUMENT_INVALID");
    }

    @Test
    void refusesInvalidContactAndAddressData() {
        assertThatThrownBy(() -> register(new Draft(PersonType.PJ, "X Ltda", null, null, null, null, "sem-arroba", null, null, false, null)))
                .hasMessageContaining("E-mail");
        assertThatThrownBy(() -> register(new Draft(PersonType.PJ, "X Ltda", null, null, null, null, null,
                new Address("123", null, null, null, null, null, null), null, false, null))).hasMessageContaining("CEP");
        assertThatThrownBy(() -> register(new Draft(PersonType.PJ, "X Ltda", null, null, null, null, null,
                new Address(null, null, null, null, null, null, "XX"), null, false, null))).hasMessageContaining("UF");
        assertThatThrownBy(() -> register(new Draft(PersonType.PJ, "  ", null, null, null, null, null, null, null, false, null)))
                .hasMessageContaining("Razão social");
        assertThatThrownBy(() -> register(new Draft(null, "X", null, null, null, null, null, null, null, false, null)))
                .hasMessageContaining("Tipo de pessoa");
    }

    @Test
    void refusesInvalidCommercialTerms() {
        assertThatThrownBy(() -> register(withTerms(new CommercialTerms(null, null, 0, null, null)))).hasMessageContaining("vencimento");
        assertThatThrownBy(() -> register(withTerms(new CommercialTerms(null, null, 32, null, null)))).hasMessageContaining("vencimento");
        assertThatThrownBy(() -> register(withTerms(new CommercialTerms(null, null, null, new BigDecimal("-1"), null)))).hasMessageContaining("negativo");
        assertThatThrownBy(() -> register(withTerms(new CommercialTerms(null, null, null, new BigDecimal("10.005"), null)))).hasMessageContaining("duas casas");
        assertThat(register(withTerms(new CommercialTerms(" 30 dias ", "PIX", 10, new BigDecimal("1500.5"), null))).terms().creditLimit())
                .isEqualByComparingTo("1500.50");
    }

    private static Draft withTerms(CommercialTerms terms) {
        return new Draft(PersonType.PJ, "X Ltda", null, null, null, null, null, null, terms, false, null);
    }

    @Test
    void relatedPartyNoteRequiresTheRelatedPartyFlag() {
        assertThatThrownBy(() -> register(new Draft(PersonType.PJ, "X Ltda", null, null, null, null, null, null, null, false, "mesma família")))
                .hasMessageContaining("relacionad");
        Supplier related = register(new Draft(PersonType.PJ, "X Ltda", null, "67757350000178", null, null, null, null, null, true, "empresa do sócio"));
        assertThat(related.relatedParty()).isTrue();
        assertThat(related.relatedPartyNote()).isEqualTo("empresa do sócio");
    }

    @Test
    void inactivationNeedsAReasonKeepsTheDataAndReactivationClearsTheMark() {
        Supplier supplier = register(pj("67757350000178"));
        assertThatThrownBy(() -> supplier.inactivate("  ", ACTOR, NOW)).hasMessageContaining("Motivo");

        Supplier inactive = supplier.inactivate(" parou de vender ", ACTOR, NOW.plusSeconds(60));
        assertThat(inactive.status()).isEqualTo(Supplier.Status.INACTIVE);
        assertThat(inactive.inactivationReason()).isEqualTo("parou de vender");
        assertThat(inactive.document()).isEqualTo(supplier.document());
        assertThatThrownBy(() -> inactive.inactivate("de novo", ACTOR, NOW)).extracting("code").isEqualTo("SUPPLIER_ALREADY_INACTIVE");

        Supplier active = inactive.reactivate(ACTOR, NOW.plusSeconds(120));
        assertThat(active.status()).isEqualTo(Supplier.Status.ACTIVE);
        assertThat(active.inactivationReason()).isNull();
        assertThat(active.inactivatedAt()).isNull();
        assertThatThrownBy(() -> active.reactivate(ACTOR, NOW)).extracting("code").isEqualTo("SUPPLIER_ALREADY_ACTIVE");
    }

    @Test
    void personalDocumentIsMaskedForViewersButCompanyDocumentIsNot() {
        Supplier person = register(pf("52998224725"));
        assertThat(person.documentFor(true)).isEqualTo("52998224725");
        assertThat(person.documentFor(false)).isEqualTo("*********25");
        assertThat(register(pj("67757350000178")).documentFor(false)).isEqualTo("67757350000178");
        assertThat(register(pf(null)).documentFor(false)).isNull();
    }

    @Test
    void auditDiffListsOnlyChangedFieldsAndNeverRecordsAFullCpf() {
        Supplier before = register(pf("52998224725"));
        Supplier after = before.revise(new Draft(PersonType.PF, "João Autônomo Silva", null, "39053344705", null, "34999990000", null, null,
                new CommercialTerms(null, null, null, new BigDecimal("100"), null), false, null), ACTOR, NOW.plusSeconds(5));

        var changes = after.changesFrom(before);
        assertThat(changes).containsOnlyKeys("legalName", "document", "phone", "terms.creditLimit");
        assertThat(changes.get("legalName").before()).isEqualTo("João Autônomo");
        assertThat(changes.get("document").before()).isEqualTo("*********25");
        assertThat(changes.get("document").after()).isEqualTo("*********05");
        assertThat(changes.get("terms.creditLimit").after()).isEqualTo("100.00");
        assertThat(after.changesFrom(after)).isEmpty();
    }
}
