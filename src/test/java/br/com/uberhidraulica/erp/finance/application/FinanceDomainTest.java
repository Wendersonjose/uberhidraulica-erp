package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.finance.domain.FinanceException;
import br.com.uberhidraulica.erp.finance.domain.FinancialStatus;
import br.com.uberhidraulica.erp.finance.domain.Money;
import br.com.uberhidraulica.erp.finance.domain.Payable;
import br.com.uberhidraulica.erp.finance.domain.PaymentMethod;
import br.com.uberhidraulica.erp.finance.domain.Receivable;
import br.com.uberhidraulica.erp.finance.domain.Settlement;
import br.com.uberhidraulica.erp.quote.QuoteBillingQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regras do Financeiro que não dependem de banco (DR-0015). */
class FinanceDomainTest {
    private static final Instant NOW = Instant.parse("2026-09-22T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);
    private static final UUID USER = UUID.randomUUID();

    private static BigDecimal money(String value) { return new BigDecimal(value); }

    private static Receivable receivable(String original, LocalDate due, List<Receivable.Adjustment> adjustments, List<Settlement> receipts) {
        UUID id = UUID.randomUUID();
        return new Receivable(id, UUID.randomUUID(), 7, UUID.randomUUID(), UUID.randomUUID(), money(original), TODAY, due, NOW, USER,
                null, null, null, List.of(new Receivable.Line(UUID.randomUUID(), UUID.randomUUID(), "Serviço", money("1"), money(original),
                money("0"), money(original), 1)), adjustments, List.of(), receipts);
    }

    private static Settlement receipt(String amount, boolean reversed) {
        return new Settlement(UUID.randomUUID(), UUID.randomUUID(), money(amount), UUID.randomUUID(), "PIX", TODAY, null, NOW, USER,
                UUID.randomUUID().toString(), reversed ? new Settlement.Reversal(UUID.randomUUID(), "Erro", NOW, USER, "k") : null);
    }

    private static Receivable.Adjustment adjustment(Receivable.AdjustmentType type, String amount) {
        return new Receivable.Adjustment(UUID.randomUUID(), type, money(amount), "Negociação", NOW, USER, UUID.randomUUID().toString());
    }

    @Test
    void derivedAmountsNeverOverwriteTheOriginalAndIgnoreReversedReceipts() {
        Receivable r = receivable("1000.00", TODAY, List.of(adjustment(Receivable.AdjustmentType.DISCOUNT, "100.00"),
                adjustment(Receivable.AdjustmentType.SURCHARGE, "25.50")), List.of(receipt("300.00", false), receipt("200.00", true)));
        assertThat(r.originalAmount()).isEqualByComparingTo("1000.00");
        assertThat(r.discountAmount()).isEqualByComparingTo("100.00");
        assertThat(r.surchargeAmount()).isEqualByComparingTo("25.50");
        assertThat(r.adjustedAmount()).isEqualByComparingTo("925.50");
        assertThat(r.receivedAmount()).isEqualByComparingTo("300.00");
        assertThat(r.outstandingBalance()).isEqualByComparingTo("625.50");
        assertThat(r.status(TODAY)).isEqualTo(FinancialStatus.PARCIAL);
    }

    @Test
    void receiptAboveTheBalanceIsRefusedAndPartialIsAccepted() {
        Receivable r = receivable("500.00", TODAY, List.of(), List.of(receipt("200.00", false)));
        r.checkReceipt(money("300.00"));
        assertThatThrownBy(() -> r.checkReceipt(money("300.01"))).isInstanceOf(FinanceException.class)
                .extracting("code").isEqualTo("AMOUNT_EXCEEDS_BALANCE");
    }

    @Test
    void discountCannotMakeTheBalanceNegativeButSurchargeIsFree() {
        Receivable r = receivable("500.00", TODAY, List.of(), List.of(receipt("450.00", false)));
        r.checkAdjustment(Receivable.AdjustmentType.DISCOUNT, money("50.00"));
        assertThatThrownBy(() -> r.checkAdjustment(Receivable.AdjustmentType.DISCOUNT, money("50.01")))
                .extracting("code").isEqualTo("AMOUNT_EXCEEDS_BALANCE");
        r.checkAdjustment(Receivable.AdjustmentType.SURCHARGE, money("1000.00"));
    }

    @Test
    void cancellationIsRefusedWhileAnyReceiptIsActive() {
        assertThatThrownBy(() -> receivable("500.00", TODAY, List.of(), List.of(receipt("10.00", false))).checkCancellation())
                .extracting("code").isEqualTo("RECEIVABLE_HAS_RECEIPTS");
        receivable("500.00", TODAY, List.of(), List.of(receipt("10.00", true))).checkCancellation();
    }

    @Test
    void dueDateChangeOnlyWhileOpenAndToADifferentDate() {
        Receivable open = receivable("100.00", TODAY, List.of(), List.of());
        open.checkDueDateChange(TODAY.plusDays(10));
        assertThatThrownBy(() -> open.checkDueDateChange(TODAY)).extracting("code").isEqualTo("INVALID_FINANCE_ENTRY");
        Receivable paid = receivable("100.00", TODAY, List.of(), List.of(receipt("100.00", false)));
        assertThatThrownBy(() -> paid.checkDueDateChange(TODAY.plusDays(1))).extracting("code").isEqualTo("RECEIVABLE_NOT_OPEN");
    }

    @Test
    void statusIsDerivedWithOverdueOnlyWhenBalanceRemains() {
        assertThat(receivable("100.00", TODAY.minusDays(1), List.of(), List.of()).status(TODAY)).isEqualTo(FinancialStatus.VENCIDO);
        assertThat(receivable("100.00", TODAY, List.of(), List.of()).status(TODAY)).isEqualTo(FinancialStatus.ABERTO);
        assertThat(receivable("100.00", TODAY.minusDays(9), List.of(), List.of(receipt("100.00", false))).status(TODAY))
                .isEqualTo(FinancialStatus.QUITADO);
        assertThat(receivable("0.00", TODAY, List.of(), List.of()).status(TODAY)).isEqualTo(FinancialStatus.QUITADO);
    }

    @Test
    void frozenOriginalAmountMustMatchTheApprovedLines() {
        assertThatThrownBy(() -> new Receivable(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(),
                money("99.00"), TODAY, TODAY, NOW, USER, null, null, null,
                List.of(new Receivable.Line(UUID.randomUUID(), UUID.randomUUID(), "A", money("1"), money("100"), money("0"), money("100.00"), 1)),
                List.of(), List.of(), List.of())).extracting("code").isEqualTo("RECEIVABLE_TOTAL_MISMATCH");
    }

    @Test
    void cashIsRefusedUntilACashSessionExistsAndInactiveMethodsToo() {
        PaymentMethod cash = new PaymentMethod(UUID.randomUUID(), "DINHEIRO", "Dinheiro", true, true, NOW, NOW);
        assertThatThrownBy(cash::requireUsable).extracting("code").isEqualTo("CASH_SESSION_REQUIRED");
        PaymentMethod inactive = new PaymentMethod(UUID.randomUUID(), "PIX", "PIX", false, false, NOW, NOW);
        assertThatThrownBy(inactive::requireUsable).extracting("code").isEqualTo("PAYMENT_METHOD_INACTIVE");
        new PaymentMethod(UUID.randomUUID(), "PIX", "PIX", true, false, NOW, NOW).requireUsable();
    }

    @Test
    void amountsKeepTwoDecimalsAndEffectiveDateIsNeverInTheFuture() {
        assertThat(Money.positive(money("10.5"), "Valor")).isEqualByComparingTo("10.50");
        assertThatThrownBy(() -> Money.positive(money("10.505"), "Valor")).extracting("code").isEqualTo("INVALID_FINANCE_ENTRY");
        assertThatThrownBy(() -> Money.positive(money("0"), "Valor")).extracting("code").isEqualTo("INVALID_FINANCE_ENTRY");
        assertThat(Money.effectiveDate(null, TODAY)).isEqualTo(TODAY);
        assertThat(Money.effectiveDate(TODAY.minusDays(3), TODAY)).isEqualTo(TODAY.minusDays(3));
        assertThatThrownBy(() -> Money.effectiveDate(TODAY.plusDays(1), TODAY)).extracting("code").isEqualTo("INVALID_FINANCE_ENTRY");
    }

    @Test
    void payableRequiresDueDateRefusesOverpaymentAndCancellationWithPayments() {
        assertThatThrownBy(() -> new Payable(UUID.randomUUID(), "Aluguel", null, UUID.randomUUID(), "Fixos", money("100.00"), null,
                null, NOW, USER, "k", null, null, null, List.of())).extracting("code").isEqualTo("INVALID_FINANCE_ENTRY");
        Payable payable = new Payable(UUID.randomUUID(), "Aluguel", "Imobiliária", UUID.randomUUID(), "Fixos", money("100.00"), TODAY,
                null, NOW, USER, "k", null, null, null, List.of(receipt("40.00", false)));
        payable.checkPayment(money("60.00"));
        assertThatThrownBy(() -> payable.checkPayment(money("60.01"))).extracting("code").isEqualTo("AMOUNT_EXCEEDS_BALANCE");
        assertThatThrownBy(payable::checkCancellation).extracting("code").isEqualTo("PAYABLE_HAS_PAYMENTS");
        assertThat(payable.status(TODAY)).isEqualTo(FinancialStatus.PARCIAL);
    }

    // ------------------------------------------------------------------ seleção do orçamento de faturamento (F-02)

    private static QuoteBillingQuery.BillingCandidate candidate() {
        return new QuoteBillingQuery.BillingCandidate(UUID.randomUUID(), UUID.randomUUID(), money("100.00"), List.of());
    }

    @Test
    void singleCandidateIsSelectedAutomatically() {
        var only = candidate();
        assertThat(ReceivableService.select(List.of(only), null)).isEqualTo(only);
    }

    @Test
    void severalCandidatesRequireExplicitSelectionAndNoneMeansNoBillingBasis() {
        var first = candidate();
        var second = candidate();
        assertThatThrownBy(() -> ReceivableService.select(List.of(first, second), null))
                .extracting("code").isEqualTo("BILLING_QUOTE_SELECTION_REQUIRED");
        assertThat(ReceivableService.select(List.of(first, second), second.quoteId())).isEqualTo(second);
        assertThatThrownBy(() -> ReceivableService.select(List.of(), null)).extracting("code").isEqualTo("WORK_ORDER_WITHOUT_BILLING_BASIS");
        assertThatThrownBy(() -> ReceivableService.select(List.of(first), UUID.randomUUID()))
                .extracting("code").isEqualTo("BILLING_QUOTE_NOT_ELIGIBLE");
        assertThatThrownBy(() -> ReceivableService.select(List.of(), UUID.randomUUID()))
                .extracting("code").isEqualTo("BILLING_QUOTE_NOT_ELIGIBLE");
    }
}
