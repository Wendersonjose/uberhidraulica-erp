package br.com.uberhidraulica.erp.purchasing.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * As decisões aprovadas da `DR-0022` que mexem com dinheiro, quantidade e identidade: D1 (custo), D2
 * (rateio), D7 (conversão de unidade), D4/D11 (parcelas) e D9 (destinatário).
 */
class AcquisitionPolicyTest {

    private static BigDecimal brl(String value) { return new BigDecimal(value); }

    @Nested
    class D2Rateio {

        @Test
        void allocatesProportionallyToValueAndClosesExactly() {
            List<BigDecimal> shares = ExpenseAllocation.proportionalToValue(
                    List.of(brl("300.00"), brl("200.00"), brl("500.00")), brl("100.00"));

            assertThat(shares).containsExactly(brl("30.00"), brl("20.00"), brl("50.00"));
            assertThat(PurchaseMoney.sumCharged(shares)).isEqualByComparingTo("100.00");
        }

        @Test
        void residualCentGoesToTheHighestValueItemAndTheSumStillClosesExactly() {
            // 10,00 entre três itens iguais: 3,33 cada deixa 1 centavo de resíduo.
            List<BigDecimal> shares = ExpenseAllocation.proportionalToValue(
                    List.of(brl("100.00"), brl("100.00"), brl("100.00")), brl("10.00"));

            assertThat(PurchaseMoney.sumCharged(shares)).isEqualByComparingTo("10.00");
            assertThat(shares).containsExactly(brl("3.34"), brl("3.33"), brl("3.33"));
        }

        @Test
        void tieOnTheHighestValueIsResolvedByTheLowestIndexSoTheResultIsDeterministic() {
            List<BigDecimal> first = ExpenseAllocation.proportionalToValue(
                    List.of(brl("50.00"), brl("50.00")), brl("0.01"));
            List<BigDecimal> again = ExpenseAllocation.proportionalToValue(
                    List.of(brl("50.00"), brl("50.00")), brl("0.01"));

            assertThat(first).isEqualTo(again).containsExactly(brl("0.01"), brl("0.00"));
        }

        @Test
        void allocatingNothingGivesZeroToEveryItemInsteadOfFailing() {
            assertThat(ExpenseAllocation.proportionalToValue(List.of(brl("10.00"), brl("20.00")), BigDecimal.ZERO))
                    .containsExactly(brl("0.00"), brl("0.00"));
        }

        @Test
        void aNoteWhereEveryItemIsFreeStillClosesExactly() {
            List<BigDecimal> shares = ExpenseAllocation.proportionalToValue(
                    List.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), brl("10.00"));

            assertThat(PurchaseMoney.sumCharged(shares)).isEqualByComparingTo("10.00");
        }

        @Test
        void refusesToAllocateAnExpenseWhenThereIsNoItemToCarryIt() {
            assertThatThrownBy(() -> ExpenseAllocation.proportionalToValue(List.of(), brl("10.00")))
                    .isInstanceOf(PurchasingException.class)
                    .extracting(error -> ((PurchasingException) error).code())
                    .isEqualTo("PURCHASE_ALLOCATION_IMPOSSIBLE");
        }
    }

    @Nested
    class D7Unidade {

        @Test
        void directEquivalenceNeedsNoFactorAndChangesNoQuantity() {
            BigDecimal factor = UnitConversion.effectiveFactor("UN", "UNIDADE", null);

            assertThat(factor).isEqualByComparingTo("1");
            assertThat(UnitConversion.convertQuantity(brl("5"), factor)).isEqualByComparingTo("5.000");
            assertThat(UnitConversion.convertUnitCost(brl("10.0000"), factor)).isEqualByComparingTo("10.0000");
        }

        @Test
        void abbreviationsWithDotsAndLowercaseAreStillDirectEquivalences() {
            assertThat(UnitConversion.needsNoFactor("un.", "UNIDADE")).isTrue();
            assertThat(UnitConversion.needsNoFactor(" Lt ", "LITRO")).isTrue();
            assertThat(UnitConversion.needsNoFactor("KG", "QUILOGRAMA")).isTrue();
        }

        @Test
        void aBoxWithoutAFactorIsRefusedBecauseItWouldCreateStockThatDoesNotExist() {
            assertThatThrownBy(() -> UnitConversion.effectiveFactor("CX", "UNIDADE", null))
                    .isInstanceOf(PurchasingException.class)
                    .extracting(error -> ((PurchasingException) error).code())
                    .isEqualTo("PURCHASE_UNIT_FACTOR_REQUIRED");
        }

        @Test
        void aBoxOfTwelveBecomesTwelveUnitsAndTheUnitCostIsDividedByTheFactor() {
            BigDecimal factor = UnitConversion.effectiveFactor("CX", "UNIDADE", brl("12"));

            assertThat(UnitConversion.convertQuantity(brl("1"), factor)).isEqualByComparingTo("12.000");
            assertThat(UnitConversion.convertUnitCost(brl("120.00"), factor)).isEqualByComparingTo("10.0000");
        }

        @Test
        void aUnitThatLooksDirectButDisagreesWithTheCatalogStillDemandsAFactor() {
            // A nota diz litro e o catálogo controla o produto em balde de 20 L: não é equivalência direta.
            assertThatThrownBy(() -> UnitConversion.effectiveFactor("L", "BALDE_20L", null))
                    .isInstanceOf(PurchasingException.class)
                    .extracting(error -> ((PurchasingException) error).code())
                    .isEqualTo("PURCHASE_UNIT_FACTOR_REQUIRED");
        }

        @Test
        void zeroOrNegativeFactorIsRefused() {
            assertThatThrownBy(() -> UnitConversion.effectiveFactor("CX", "UNIDADE", BigDecimal.ZERO))
                    .isInstanceOf(PurchasingException.class)
                    .extracting(error -> ((PurchasingException) error).code())
                    .isEqualTo("PURCHASE_UNIT_FACTOR_INVALID");
        }
    }

    @Nested
    class D1Custo {

        @Test
        void defaultParametersAddIpiAndIcmsStAndLeaveIcmsNormalOut() {
            AcquisitionCost.Parameters parameters = AcquisitionCost.Parameters.approvedDefault();

            AcquisitionCost cost = AcquisitionCost.of(brl("100.00"), brl("10.00"), brl("5.00"), brl("7.00"),
                    brl("18.00"), brl("2.00"), BigDecimal.ZERO, BigDecimal.ZERO, brl("10"), parameters);

            assertThat(cost.baseAmount()).isEqualByComparingTo("90.00");
            assertThat(cost.ipiAmount()).isEqualByComparingTo("5.00");
            assertThat(cost.icmsStAmount()).isEqualByComparingTo("7.00");
            assertThat(cost.icmsNormalAmount()).as("ICMS normal é crédito, fica fora").isEqualByComparingTo("0.00");
            assertThat(cost.totalAmount()).isEqualByComparingTo("104.00");
            assertThat(cost.unitCost()).isEqualByComparingTo("10.4000");
        }

        @Test
        void theTotalIsExactlyTheSumOfTheRecordedPartsSoTheCompositionIsAuditable() {
            AcquisitionCost cost = AcquisitionCost.of(brl("33.33"), brl("0.01"), brl("1.11"), brl("2.22"),
                    brl("3.33"), brl("0.07"), brl("0.09"), brl("0.05"), brl("3"),
                    AcquisitionCost.Parameters.approvedDefault());

            BigDecimal recomputed = PurchaseMoney.sumCharged(List.of(cost.baseAmount(), cost.ipiAmount(),
                    cost.icmsStAmount(), cost.icmsNormalAmount(), cost.freightShare(), cost.insuranceShare(),
                    cost.otherShare()));
            assertThat(cost.totalAmount()).isEqualByComparingTo(recomputed);
        }

        @Test
        void turningIcmsNormalOnChangesTheCostWhichIsWhyTheParameterIsNotValidatedByDefault() {
            AcquisitionCost withCredit = AcquisitionCost.of(brl("100.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, brl("18.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, brl("1"),
                    new AcquisitionCost.Parameters(true, true, true));

            assertThat(withCredit.totalAmount()).isEqualByComparingTo("118.00");
        }

        @Test
        void aDiscountLargerThanTheProductValueIsRefusedInsteadOfProducingNegativeCost() {
            assertThatThrownBy(() -> AcquisitionCost.of(brl("10.00"), brl("11.00"), BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    brl("1"), AcquisitionCost.Parameters.approvedDefault()))
                    .isInstanceOf(PurchasingException.class);
        }

        @Test
        void unitCostKeepsFourDecimalsWhenTheDivisionDoesNotTerminate() {
            AcquisitionCost cost = AcquisitionCost.of(brl("10.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    brl("3"), AcquisitionCost.Parameters.approvedDefault());

            assertThat(cost.unitCost()).isEqualByComparingTo("3.3333");
        }
    }

    @Nested
    class D4eD11Parcelas {

        @Test
        void theThreeDuplicatesOfInvoiceTwentyOneCloseWithItsTotal() {
            // Cenário declarado pelo proprietário: NF-e 21, 3 duplicatas de 647,70 = 1.943,10.
            List<InstallmentPlan.Installment> duplicates = List.of(
                    new InstallmentPlan.Installment(1, LocalDate.of(2026, 11, 10), brl("647.70"), "001"),
                    new InstallmentPlan.Installment(2, LocalDate.of(2026, 12, 10), brl("647.70"), "002"),
                    new InstallmentPlan.Installment(3, LocalDate.of(2027, 1, 10), brl("647.70"), "003"));

            InstallmentPlan plan = InstallmentPlan.fromInvoiceDuplicates(duplicates, brl("1943.10"));

            assertThat(plan.installments()).hasSize(3);
            assertThat(PurchaseMoney.sumCharged(plan.installments().stream()
                    .map(InstallmentPlan.Installment::amount).toList())).isEqualByComparingTo("1943.10");
            assertThat(plan.installments()).extracting(InstallmentPlan.Installment::reference)
                    .containsExactly("001", "002", "003");
        }

        @Test
        void duplicatesThatDoNotCloseWithTheTotalAreRefusedSoTheDebtNeverDiffersFromTheInvoice() {
            List<InstallmentPlan.Installment> wrong = List.of(
                    new InstallmentPlan.Installment(1, LocalDate.of(2026, 11, 10), brl("647.70"), "001"),
                    new InstallmentPlan.Installment(2, LocalDate.of(2026, 12, 10), brl("647.70"), "002"));

            assertThatThrownBy(() -> InstallmentPlan.fromInvoiceDuplicates(wrong, brl("1943.10")))
                    .isInstanceOf(PurchasingException.class)
                    .extracting(error -> ((PurchasingException) error).code())
                    .isEqualTo("PURCHASE_INSTALLMENTS_DO_NOT_MATCH_TOTAL");
        }

        @Test
        void aNoteWithoutDuplicatesPresumesNothingAndDemandsTheTerms() {
            assertThatThrownBy(() -> InstallmentPlan.fromInvoiceDuplicates(List.of(), brl("100.00")))
                    .isInstanceOf(PurchasingException.class)
                    .extracting(error -> ((PurchasingException) error).code())
                    .isEqualTo("PURCHASE_INSTALLMENTS_REQUIRED");
        }

        @Test
        void informedPlanPutsTheResidualCentOnTheLastInstallmentAndStillCloses() {
            InstallmentPlan plan = InstallmentPlan.informed(brl("100.00"), 3, LocalDate.of(2026, 11, 1), 30);

            assertThat(plan.installments()).extracting(InstallmentPlan.Installment::amount)
                    .containsExactly(brl("33.33"), brl("33.33"), brl("33.34"));
            assertThat(plan.installments()).extracting(InstallmentPlan.Installment::dueDate)
                    .containsExactly(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));
        }

        @Test
        void aSingleInformedInstallmentIsTheWholeTotal() {
            InstallmentPlan plan = InstallmentPlan.informed(brl("250.55"), 1, LocalDate.of(2026, 11, 5), 0);

            assertThat(plan.installments()).singleElement()
                    .extracting(InstallmentPlan.Installment::amount).isEqualTo(brl("250.55"));
        }

        @Test
        void anInstallmentWithoutDueDateIsRefusedBecauseTheFinanceModelDemandsOne() {
            List<InstallmentPlan.Installment> noDate = List.of(
                    new InstallmentPlan.Installment(1, null, brl("100.00"), "001"));

            assertThatThrownBy(() -> InstallmentPlan.fromInvoiceDuplicates(noDate, brl("100.00")))
                    .isInstanceOf(PurchasingException.class)
                    .extracting(error -> ((PurchasingException) error).code())
                    .isEqualTo("PURCHASE_DUE_DATE_REQUIRED");
        }
    }

    @Nested
    class D9Destinatario {
        private static final String OWN = "01637003000101";

        @Test
        void theAuthorizedEstablishmentIsAcceptedWithOrWithoutMask() {
            assertThat(RecipientCheck.verify("01.637.003/0001-01", OWN)).isEqualTo(RecipientCheck.Result.AUTHORIZED);
            assertThat(RecipientCheck.verify(OWN, "01.637.003/0001-01")).isEqualTo(RecipientCheck.Result.AUTHORIZED);
        }

        @Test
        void anotherBranchOfTheSameGroupIsRefusedBecauseTheRootIsNotProofOfIdentity() {
            // Mesma raiz 01637003, filial 0002: a D9 recusa, não alerta.
            assertThat(RecipientCheck.verify("01637003000290", OWN)).isEqualTo(RecipientCheck.Result.RECIPIENT_MISMATCH);

            assertThatThrownBy(() -> RecipientCheck.requireAuthorized("01637003000290", OWN))
                    .isInstanceOf(PurchasingException.class)
                    .extracting(error -> ((PurchasingException) error).code())
                    .isEqualTo("PURCHASE_RECIPIENT_NOT_AUTHORIZED");
        }

        @Test
        void withoutOwnTaxIdConfiguredNothingConfirms() {
            assertThat(RecipientCheck.verify(OWN, null)).isEqualTo(RecipientCheck.Result.OWN_TAX_ID_NOT_CONFIGURED);
            assertThat(RecipientCheck.verify(OWN, "   ")).isEqualTo(RecipientCheck.Result.OWN_TAX_ID_NOT_CONFIGURED);

            assertThatThrownBy(() -> RecipientCheck.requireAuthorized(OWN, null))
                    .isInstanceOf(PurchasingException.class);
        }

        @Test
        void aNoteWithoutALegibleRecipientIsRefused() {
            assertThat(RecipientCheck.verify(null, OWN)).isEqualTo(RecipientCheck.Result.RECIPIENT_MISSING);
        }

        @Test
        void theConfiguredTaxIdIsNeverEchoedWhenItIsSimplyMissing() {
            assertThat(RecipientCheck.explain(RecipientCheck.Result.OWN_TAX_ID_NOT_CONFIGURED))
                    .doesNotContain(OWN).contains("OWN_TAX_ID");
        }
    }
}
