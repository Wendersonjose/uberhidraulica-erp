package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.finance.domain.FinancialStatus;
import br.com.uberhidraulica.erp.finance.domain.Receivable;
import br.com.uberhidraulica.erp.finance.domain.Settlement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DR-0024, Q1 e Q2: parcelas do recebível e recebível avulso, sem banco. */
class ReceivableInstallmentDomainTest {
    private static final Instant NOW = Instant.parse("2026-11-18T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);
    private static final UUID USER = UUID.randomUUID();

    private static BigDecimal money(String value) { return new BigDecimal(value); }

    private static Receivable manual(String amount, Receivable.InstallmentPlan plan, List<Receivable.Adjustment> adjustments,
                                     List<Settlement> receipts) {
        return new Receivable(UUID.randomUUID(), Receivable.Origin.MANUAL, null, null, UUID.randomUUID(), null, "Venda de balcão",
                UUID.randomUUID(), "Balcão", null, "k-" + UUID.randomUUID(), money(amount), TODAY, TODAY, NOW, USER, null, null, null,
                List.of(), adjustments, List.of(), receipts, plan);
    }

    private static Receivable.InstallmentPlan plan(String... amountsAndDays) {
        List<Receivable.Installment> installments = new java.util.ArrayList<>();
        for (int i = 0; i < amountsAndDays.length; i += 2)
            installments.add(new Receivable.Installment(UUID.randomUUID(), i / 2 + 1, money(amountsAndDays[i]),
                    TODAY.plusDays(Long.parseLong(amountsAndDays[i + 1]))));
        return new Receivable.InstallmentPlan(1, "Parcelado", NOW, USER, installments);
    }

    private static Settlement receipt(String amount) {
        return new Settlement(UUID.randomUUID(), UUID.randomUUID(), money(amount), UUID.randomUUID(), "PIX", TODAY, null, NOW, USER,
                UUID.randomUUID().toString(), null);
    }

    private static Receivable.Adjustment adjustment(Receivable.AdjustmentType type, String amount) {
        return new Receivable.Adjustment(UUID.randomUUID(), type, money(amount), "Negociação", NOW, USER, UUID.randomUUID().toString(), null);
    }

    @Test
    void equalSplitPutsTheRemainingCentsOnTheLastInstallment() {
        List<Receivable.Installment> split = Receivable.equalSplit(money("100.00"), 3, TODAY);
        assertThat(split).extracting(Receivable.Installment::amount).map(BigDecimal::toPlainString)
                .containsExactly("33.33", "33.33", "33.34");
        assertThat(split).extracting(Receivable.Installment::dueDate)
                .containsExactly(TODAY, TODAY.plusMonths(1), TODAY.plusMonths(2));
    }

    @Test
    void withoutAPlanTheReceivableHasOneImplicitInstallment() {
        Receivable r = manual("250.00", null, List.of(), List.of());
        assertThat(r.plan().implicit()).isTrue();
        assertThat(r.installmentPositions()).singleElement().satisfies(p -> {
            assertThat(p.amount()).isEqualByComparingTo("250.00");
            assertThat(p.openPrincipal()).isEqualByComparingTo("250.00");
            assertThat(p.dueDate()).isEqualTo(TODAY);
        });
    }

    @Test
    void receiptsSettleTheOldestInstallmentsFirstAndMoveTheCurrentDueDate() {
        Receivable r = manual("300.00", plan("100.00", "-10", "100.00", "20", "100.00", "50"), List.of(), List.of(receipt("150.00")));
        assertThat(r.installmentPositions()).extracting(p -> p.openPrincipal().toPlainString()).containsExactly("0.00", "50.00", "100.00");
        assertThat(r.currentDueDate()).isEqualTo(TODAY.plusDays(20));
        // A primeira parcela, vencida, foi quitada: o recebível não está vencido.
        assertThat(r.status(TODAY)).isEqualTo(FinancialStatus.PARCIAL);
        assertThat(manual("300.00", plan("100.00", "-10", "100.00", "20", "100.00", "50"), List.of(), List.of(receipt("50.00")))
                .status(TODAY)).isEqualTo(FinancialStatus.VENCIDO);
    }

    @Test
    void surchargeIsPaidAfterThePrincipalAndKeptApartFromTheInstallments() {
        Receivable r = manual("200.00", plan("100.00", "-5", "100.00", "25"),
                List.of(adjustment(Receivable.AdjustmentType.SURCHARGE, "10.00")), List.of(receipt("150.00")));
        assertThat(r.principalOutstanding()).isEqualByComparingTo("50.00");
        assertThat(r.surchargeOutstanding()).isEqualByComparingTo("10.00");
        assertThat(r.outstandingBalance()).isEqualByComparingTo("60.00");
        assertThat(r.installmentPositions()).extracting(p -> p.openPrincipal().toPlainString()).containsExactly("0.00", "50.00");

        Receivable onlySurcharge = manual("200.00", plan("100.00", "-5", "100.00", "-1"),
                List.of(adjustment(Receivable.AdjustmentType.SURCHARGE, "10.00")), List.of(receipt("200.00")));
        assertThat(onlySurcharge.principalOutstanding()).isEqualByComparingTo("0.00");
        assertThat(onlySurcharge.outstandingBalance()).isEqualByComparingTo("10.00");
        assertThat(onlySurcharge.currentDueDate()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void discountReducesThePrincipalOfTheOldestInstallmentsFirst() {
        Receivable r = manual("200.00", plan("100.00", "0", "100.00", "30"), List.of(adjustment(Receivable.AdjustmentType.DISCOUNT, "120.00")),
                List.of());
        assertThat(r.installmentPositions()).extracting(p -> p.openPrincipal().toPlainString()).containsExactly("0.00", "80.00");
    }

    @Test
    void newPlanMustMatchTheOriginalAmountKeepOrderAndHaveNoActiveReceipt() {
        Receivable r = manual("100.00", null, List.of(), List.of());
        List<Receivable.Installment> ok = r.checkInstallmentPlan(Receivable.equalSplit(money("100.00"), 2, TODAY));
        assertThat(ok).hasSize(2);
        assertThatThrownBy(() -> r.checkInstallmentPlan(Receivable.equalSplit(money("99.99"), 2, TODAY)))
                .extracting("code").isEqualTo("INSTALLMENT_TOTAL_MISMATCH");
        assertThatThrownBy(() -> r.checkInstallmentPlan(List.of(
                new Receivable.Installment(null, 1, money("50.00"), TODAY.plusDays(10)),
                new Receivable.Installment(null, 2, money("50.00"), TODAY.plusDays(5))))).extracting("code").isEqualTo("INVALID_INSTALLMENT");
        assertThatThrownBy(() -> r.checkInstallmentPlan(List.of(new Receivable.Installment(null, 1, money("100.00"), TODAY.minusDays(1)))))
                .extracting("code").isEqualTo("INVALID_INSTALLMENT");
        Receivable received = manual("100.00", null, List.of(), List.of(receipt("10.00")));
        assertThatThrownBy(() -> received.checkInstallmentPlan(Receivable.equalSplit(money("100.00"), 2, TODAY)))
                .extracting("code").isEqualTo("RECEIVABLE_HAS_RECEIPTS");
    }

    @Test
    void dueDateChangeTargetsAnOpenInstallment() {
        Receivable r = manual("200.00", plan("100.00", "0", "100.00", "30"), List.of(), List.of(receipt("100.00")));
        assertThat(r.checkDueDateChange(null, TODAY.plusDays(40)).number()).isEqualTo(2);
        assertThatThrownBy(() -> r.checkDueDateChange(1, TODAY.plusDays(5))).extracting("code").isEqualTo("INSTALLMENT_NOT_OPEN");
        assertThatThrownBy(() -> r.checkDueDateChange(3, TODAY.plusDays(5))).extracting("code").isEqualTo("INSTALLMENT_NOT_FOUND");
    }

    @Test
    void manualReceivableHasNoWorkOrderAndRequiresDescriptionAndCategory() {
        assertThatThrownBy(() -> new Receivable(UUID.randomUUID(), Receivable.Origin.MANUAL, UUID.randomUUID(), 3L, UUID.randomUUID(), null,
                "Venda", UUID.randomUUID(), null, null, "k", money("10.00"), TODAY, TODAY, NOW, USER, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null)).extracting("code").isEqualTo("INVALID_RECEIVABLE");
        assertThatThrownBy(() -> new Receivable(UUID.randomUUID(), Receivable.Origin.MANUAL, null, null, UUID.randomUUID(), null,
                " ", UUID.randomUUID(), null, null, "k", money("10.00"), TODAY, TODAY, NOW, USER, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null)).extracting("code").isEqualTo("INVALID_FINANCE_ENTRY");
        assertThatThrownBy(() -> new Receivable(UUID.randomUUID(), Receivable.Origin.MANUAL, null, null, UUID.randomUUID(), null,
                "Venda", null, null, null, "k", money("10.00"), TODAY, TODAY, NOW, USER, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null)).extracting("code").isEqualTo("INVALID_RECEIVABLE");
    }
}
