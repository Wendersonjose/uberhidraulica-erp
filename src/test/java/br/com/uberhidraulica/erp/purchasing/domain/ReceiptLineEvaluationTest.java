package br.com.uberhidraulica.erp.purchasing.domain;

import br.com.uberhidraulica.erp.purchasing.domain.GoodsReceipt.Divergence;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** `DR-0022` D3: o estoque recebe a quantidade física; divergência é só a que o operador declara ou o excesso. */
class ReceiptLineEvaluationTest {
    private static BigDecimal n(String value) { return new BigDecimal(value); }

    @Test
    void receivingExactlyThePendingQuantityIsNotADivergence() {
        var result = ReceiptLineEvaluation.evaluate(n("10"), n("10"), false);
        assertThat(result.divergence()).isEqualTo(Divergence.NONE);
        assertThat(result.expected()).isEqualByComparingTo("10");
        assertThat(result.cancelled()).isEqualByComparingTo("0");
        assertThat(result.divergent()).isFalse();
    }

    @Test
    void receivingLessIsAPartialReceiptAndNeedsNoAuthorization() {
        // AG-05 §57: pedido 10, chegaram 6, pendem 4. Não é divergência: o resto ainda vem.
        var result = ReceiptLineEvaluation.evaluate(n("10"), n("6"), false);
        assertThat(result.divergence()).isEqualTo(Divergence.NONE);
        assertThat(result.divergent()).isFalse();
        assertThat(result.expected()).isEqualByComparingTo("10");
    }

    @Test
    void receivingLessAndDeclaringTheRestWillNotComeIsAShortCloseWithTheCancelledQuantityRecorded() {
        var result = ReceiptLineEvaluation.evaluate(n("10"), n("8"), true);
        assertThat(result.divergence()).isEqualTo(Divergence.SHORT_CLOSED);
        assertThat(result.cancelled()).isEqualByComparingTo("2");
        assertThat(result.expected()).isEqualByComparingTo("10");
        assertThat(result.divergent()).isTrue();
    }

    @Test
    void closingTheRemainderWhenTheWholePendingArrivedIsNothingToClose() {
        var result = ReceiptLineEvaluation.evaluate(n("4"), n("4"), true);
        assertThat(result.divergence()).isEqualTo(Divergence.NONE);
        assertThat(result.cancelled()).isEqualByComparingTo("0");
    }

    @Test
    void receivingMoreThanThePendingIsAnOverReceiptThatEntersStockByWhatPhysicallyArrived() {
        var result = ReceiptLineEvaluation.evaluate(n("4"), n("5"), false);
        assertThat(result.divergence()).isEqualTo(Divergence.OVER);
        assertThat(result.expected()).isEqualByComparingTo("4");
        assertThat(result.cancelled()).isEqualByComparingTo("0");
        // closeRemaining não muda o excesso: não há resto a encerrar.
        assertThat(ReceiptLineEvaluation.evaluate(n("4"), n("5"), true).divergence()).isEqualTo(Divergence.OVER);
    }

    @Test
    void receivingAnythingOnALineAlreadySettledIsAnOverReceiptWithZeroExpected() {
        // Linha já resolvida (pendente 0) ou já recebida acima (pendente negativo): qualquer quantidade é excesso.
        var settled = ReceiptLineEvaluation.evaluate(n("0"), n("1"), false);
        assertThat(settled.divergence()).isEqualTo(Divergence.OVER);
        assertThat(settled.expected()).isEqualByComparingTo("0");

        var alreadyOver = ReceiptLineEvaluation.evaluate(n("-2"), n("1"), false);
        assertThat(alreadyOver.divergence()).isEqualTo(Divergence.OVER);
        assertThat(alreadyOver.expected()).isEqualByComparingTo("0");
    }

    @Test
    void fractionalQuantitiesKeepThreeDecimalsWithoutBinaryDrift() {
        var result = ReceiptLineEvaluation.evaluate(n("12.500"), n("7.250"), true);
        assertThat(result.divergence()).isEqualTo(Divergence.SHORT_CLOSED);
        assertThat(result.cancelled()).isEqualByComparingTo("5.250");
        assertThat(result.cancelled().scale()).isEqualTo(3);
    }

    @Test
    void anEmptyOrNegativeReceiptQuantityIsRefused() {
        assertThatThrownBy(() -> ReceiptLineEvaluation.evaluate(n("4"), n("0"), false)).isInstanceOf(PurchasingException.class);
        assertThatThrownBy(() -> ReceiptLineEvaluation.evaluate(n("4"), n("-1"), false)).isInstanceOf(PurchasingException.class);
    }

    // ------------------------------------------------------------------ situação derivada

    private static ReceivingLine line(String ordered, String received, String closed) {
        BigDecimal pending = n(ordered).subtract(n(received)).subtract(n(closed));
        return new ReceivingLine(UUID.randomUUID(), 1, UUID.randomUUID(), "x", "UN", "UNIDADE", n(ordered), n(received),
                n(closed), pending, n("1"));
    }

    @Test
    void theReceivingStatusIsDerivedFromReceivedAgainstOrdered() {
        assertThat(ReceivingLine.statusOf(List.of(line("4", "0", "0"), line("2", "0", "0")))).isEqualTo(GoodsReceipt.Status.NONE);
        assertThat(ReceivingLine.statusOf(List.of(line("4", "1", "0"), line("2", "0", "0")))).isEqualTo(GoodsReceipt.Status.PARTIAL);
        assertThat(ReceivingLine.statusOf(List.of(line("4", "4", "0"), line("2", "0", "0")))).isEqualTo(GoodsReceipt.Status.PARTIAL);
        assertThat(ReceivingLine.statusOf(List.of(line("4", "4", "0"), line("2", "2", "0")))).isEqualTo(GoodsReceipt.Status.COMPLETE);
    }

    @Test
    void aLineClosedShortCountsAsSettledButAReceiptIsStillNeededForTheStatusToLeaveNone() {
        // 4 pedidos, 3 recebidos e 1 encerrado: a linha não espera mais nada.
        assertThat(ReceivingLine.statusOf(List.of(line("4", "3", "1")))).isEqualTo(GoodsReceipt.Status.COMPLETE);
        // Nada recebido não vira completo só por não haver pendência: sem recebimento a situação é NONE.
        assertThat(ReceivingLine.statusOf(List.of(line("0", "0", "0")))).isEqualTo(GoodsReceipt.Status.NONE);
        assertThat(ReceivingLine.statusOf(List.of())).isEqualTo(GoodsReceipt.Status.NONE);
        // Excesso aceito: pendente negativo continua sendo resolvido.
        assertThat(ReceivingLine.statusOf(List.of(line("4", "5", "0")))).isEqualTo(GoodsReceipt.Status.COMPLETE);
    }

    @Test
    void aDivergentReceiptItemCannotBeBuiltWithoutJustificationAndAuthorizer() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new GoodsReceipt.Item(id, id, id, id, n("5"), n("1"), n("4"), Divergence.OVER, n("0"), null, null, null))
                .isInstanceOf(PurchasingException.class).extracting("code").isEqualTo("PURCHASE_DIVERGENCE_JUSTIFICATION_REQUIRED");
        assertThatThrownBy(() -> new GoodsReceipt.Item(id, id, id, id, n("5"), n("1"), n("4"), Divergence.OVER, n("0"), "chegou a mais", null, null))
                .isInstanceOf(PurchasingException.class);
        assertThat(new GoodsReceipt.Item(id, id, id, id, n("5"), n("1"), n("4"), Divergence.OVER, n("0"), "chegou a mais", id, null)
                .divergence()).isEqualTo(Divergence.OVER);
        // Item sem divergência não exige nada.
        assertThat(new GoodsReceipt.Item(id, id, id, id, n("4"), n("1"), n("4"), Divergence.NONE, n("0"), null, null, null)
                .quantityReceived()).isEqualByComparingTo("4");
    }
}
