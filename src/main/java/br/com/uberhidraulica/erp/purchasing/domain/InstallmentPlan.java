package br.com.uberhidraulica.erp.purchasing.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Plano de parcelas da compra (`DR-0022` D4 e D11, aprovadas).
 *
 * <p>D4: as obrigações nascem na confirmação da compra, uma conta a pagar por duplicata, e a soma das
 * parcelas tem de fechar com o total a pagar — é a proteção direta contra duplicar contas a pagar. D11: nota
 * sem duplicata não presume nada; vencimento e condições são informados por quem confirma.</p>
 *
 * <p>O cálculo é em centavos inteiros e o resíduo vai para a <b>última</b> parcela. Dividir em
 * {@code BigDecimal} e arredondar parcela a parcela deixaria sobra ou falta de centavos contra o total da
 * nota, que é exatamente o erro que a conferência de fechamento existe para impedir.</p>
 */
public record InstallmentPlan(List<Installment> installments, BigDecimal total) {

    /**
     * @param number    número da parcela, a partir de 1
     * @param dueDate   vencimento
     * @param amount    valor da parcela, 2 casas
     * @param reference identificação da duplicata na nota, quando veio dela; nulo quando foi informada
     */
    public record Installment(int number, LocalDate dueDate, BigDecimal amount, String reference) {}

    public InstallmentPlan {
        if (installments == null || installments.isEmpty()) {
            throw new PurchasingException("PURCHASE_INSTALLMENTS_REQUIRED", "A compra precisa de ao menos uma parcela");
        }
        installments = List.copyOf(installments);
    }

    /**
     * Plano a partir das duplicatas da NF-e. Mantém o vencimento e o valor de cada duplicata como estão: a
     * nota é o fato comercial, não cabe recalcular. Apenas confere que fecham com o total a pagar.
     *
     * @throws PurchasingException {@code PURCHASE_INSTALLMENTS_DO_NOT_MATCH_TOTAL} quando a soma não fecha
     */
    public static InstallmentPlan fromInvoiceDuplicates(List<Installment> duplicates, BigDecimal payableTotal) {
        if (duplicates == null || duplicates.isEmpty()) {
            throw new PurchasingException("PURCHASE_INSTALLMENTS_REQUIRED",
                    "Nota sem duplicata: informe vencimento e condições de pagamento");
        }
        List<Installment> normalized = new ArrayList<>(duplicates.size());
        for (int i = 0; i < duplicates.size(); i++) {
            Installment source = duplicates.get(i);
            requireDueDate(source.dueDate());
            PurchaseMoney.requirePositive(source.amount(), "Valor da parcela " + (i + 1));
            normalized.add(new Installment(i + 1, source.dueDate(), PurchaseMoney.charged(source.amount()),
                    source.reference()));
        }
        requireClosure(normalized, payableTotal);
        return new InstallmentPlan(normalized, PurchaseMoney.charged(payableTotal));
    }

    /**
     * Plano informado por quem confirma, para nota sem duplicata (D11). Divide o total em parcelas iguais a
     * partir do primeiro vencimento, com intervalo em dias, e o resíduo de centavos na última parcela.
     *
     * @param count          número de parcelas, ao menos 1
     * @param firstDueDate   vencimento da primeira parcela
     * @param intervalDays   dias entre parcelas; irrelevante quando {@code count} é 1
     */
    public static InstallmentPlan informed(BigDecimal payableTotal, int count, LocalDate firstDueDate, int intervalDays) {
        if (count < 1) {
            throw new PurchasingException("PURCHASE_INSTALLMENTS_REQUIRED", "Número de parcelas deve ser ao menos 1");
        }
        if (count > 1 && intervalDays < 1) {
            throw new PurchasingException("PURCHASE_INVALID", "Intervalo entre parcelas deve ser de ao menos 1 dia");
        }
        requireDueDate(firstDueDate);
        PurchaseMoney.requirePositive(payableTotal, "Total a pagar");

        long totalCents = PurchaseMoney.cents(payableTotal);
        if (totalCents < count) {
            throw new PurchasingException("PURCHASE_INVALID",
                    "Total de " + PurchaseMoney.charged(payableTotal) + " não se divide em " + count + " parcelas");
        }
        long each = totalCents / count;
        List<Installment> result = new ArrayList<>(count);
        long distributed = 0L;
        for (int i = 1; i <= count; i++) {
            long cents = i == count ? totalCents - distributed : each;
            distributed += cents;
            result.add(new Installment(i, firstDueDate.plusDays((long) intervalDays * (i - 1)),
                    PurchaseMoney.fromCents(cents), null));
        }
        requireClosure(result, payableTotal);
        return new InstallmentPlan(result, PurchaseMoney.charged(payableTotal));
    }

    private static void requireDueDate(LocalDate dueDate) {
        if (dueDate == null) {
            throw new PurchasingException("PURCHASE_DUE_DATE_REQUIRED", "Vencimento é obrigatório em toda parcela");
        }
    }

    /**
     * Σ parcelas = total a pagar, exatamente. Recusar a confirmação quando não fecha é o que impede a compra
     * de gerar obrigação diferente da nota.
     */
    private static void requireClosure(List<Installment> installments, BigDecimal payableTotal) {
        BigDecimal sum = PurchaseMoney.sumCharged(installments.stream().map(Installment::amount).toList());
        BigDecimal expected = PurchaseMoney.charged(payableTotal);
        if (sum.compareTo(expected) != 0) {
            throw new PurchasingException("PURCHASE_INSTALLMENTS_DO_NOT_MATCH_TOTAL",
                    "Soma das parcelas (" + sum + ") difere do total a pagar (" + expected + ")");
        }
    }
}
