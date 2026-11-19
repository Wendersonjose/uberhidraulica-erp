package br.com.uberhidraulica.erp.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Conta a receber: da OS (DR-0015) ou avulsa (DR-0024, Q2).
 *
 * <p>{@code originalAmount} é congelado na geração e, no recebível da OS, conferido contra as linhas aprovadas do
 * orçamento de faturamento ao carregar. Desconto, acréscimo, total recebido, saldo e saldo de cada parcela são
 * sempre derivados dos lançamentos — nunca gravados, nunca sobrescrevem o original.</p>
 *
 * <p>Parcelas (DR-0024, Q1): o plano corrente divide o valor original. Recebimentos e descontos quitam primeiro
 * as parcelas mais antigas; o acréscimo é saldo à parte, pago depois do principal. Sem plano explícito, o
 * recebível tem uma parcela implícita com o valor original e o vencimento do próprio recebível.</p>
 */
public record Receivable(UUID id, Origin origin, UUID workOrderId, Long workOrderNumber, UUID customerId, UUID billingQuoteId,
                         String description, UUID revenueCategoryId, String revenueCategoryName, String notes, String idempotencyKey,
                         BigDecimal originalAmount, LocalDate issuedOn, LocalDate dueDate, Instant createdAt, UUID createdBy,
                         Instant cancelledAt, UUID cancelledBy, String cancellationReason,
                         List<Line> lines, List<Adjustment> adjustments, List<DueDateChange> dueDateChanges,
                         List<Settlement> receipts, InstallmentPlan plan) {

    public static final int MAX_INSTALLMENTS = 60;

    public enum Origin { WORK_ORDER, MANUAL }

    public enum AdjustmentType { DISCOUNT, SURCHARGE }

    /** De onde veio o ajuste: manual, com motivo livre, ou juros e multa por atraso confirmados (DR-0024, Q3). */
    public enum AdjustmentOrigin { MANUAL, LATE_CHARGE }

    /** Linha aprovada do orçamento, copiada no instante da geração. */
    public record Line(UUID id, UUID quoteItemRevisionId, String description, BigDecimal quantity, BigDecimal unitPrice,
                       BigDecimal discountAmount, BigDecimal totalAmount, int displayOrder) {}

    /**
     * Desconto ou acréscimo financeiro; imutável. Correção é estorno total próprio (DR-0017, opção A):
     * o ajuste estornado deixa de compor descontos ou acréscimos, e o histórico mostra os dois registros.
     */
    public record Adjustment(UUID id, AdjustmentType type, BigDecimal amount, String reason, Instant recordedAt, UUID recordedBy,
                             String idempotencyKey, Settlement.Reversal reversal, AdjustmentOrigin origin) {
        public boolean active() { return reversal == null; }

        public Adjustment(UUID id, AdjustmentType type, BigDecimal amount, String reason, Instant recordedAt, UUID recordedBy,
                          String idempotencyKey, Settlement.Reversal reversal) {
            this(id, type, amount, reason, recordedAt, recordedBy, idempotencyKey, reversal, AdjustmentOrigin.MANUAL);
        }

        public Adjustment {
            if (id == null || type == null || recordedAt == null || recordedBy == null || idempotencyKey == null)
                throw new FinanceException("INVALID_FINANCE_ENTRY", "Dados obrigatórios do ajuste ausentes");
            amount = Money.positive(amount, "Valor do ajuste");
            reason = Money.requiredText(reason, "Motivo do ajuste", 500);
            if (origin == null) origin = AdjustmentOrigin.MANUAL;
            if (origin == AdjustmentOrigin.LATE_CHARGE && type != AdjustmentType.SURCHARGE)
                throw new FinanceException("INVALID_FINANCE_ENTRY", "Juros e multa são sempre acréscimo");
        }
    }

    /** Mudança de vencimento; {@code installmentNumber} é nulo no histórico anterior às parcelas. */
    public record DueDateChange(UUID id, LocalDate previousDueDate, LocalDate newDueDate, String reason, Instant changedAt, UUID changedBy,
                                String idempotencyKey, Integer installmentNumber) {
        public DueDateChange(UUID id, LocalDate previousDueDate, LocalDate newDueDate, String reason, Instant changedAt, UUID changedBy,
                             String idempotencyKey) {
            this(id, previousDueDate, newDueDate, reason, changedAt, changedBy, idempotencyKey, null);
        }
    }

    /** Parcela de um plano: valor e número imutáveis; o vencimento muda só com histórico. */
    public record Installment(UUID id, int number, BigDecimal amount, LocalDate dueDate) {
        public Installment {
            if (number < 1 || dueDate == null || amount == null || amount.signum() < 0)
                throw new FinanceException("INVALID_INSTALLMENT", "Parcela inválida");
        }
    }

    /**
     * Plano de parcelas. Versão 0 é o plano implícito (uma parcela, valor original, vencimento do recebível), que
     * não está gravado; planos gravados começam na versão 1, e um plano novo não apaga o anterior.
     */
    public record InstallmentPlan(int version, String reason, Instant createdAt, UUID createdBy, List<Installment> installments) {
        public InstallmentPlan {
            installments = List.copyOf(installments).stream().sorted(Comparator.comparingInt(Installment::number)).toList();
            if (installments.isEmpty()) throw new FinanceException("INVALID_INSTALLMENT", "Plano sem parcela");
        }

        public boolean implicit() { return version == 0; }
    }

    /** Posição derivada de uma parcela: quanto do principal dela continua em aberto. */
    public record InstallmentPosition(int number, BigDecimal amount, LocalDate dueDate, BigDecimal openPrincipal) {
        public boolean open() { return openPrincipal.signum() > 0; }
    }

    /** Recebível da OS sem plano explícito: a forma usada pela geração na finalização (DR-0015). */
    public Receivable(UUID id, UUID workOrderId, long workOrderNumber, UUID customerId, UUID billingQuoteId,
                      BigDecimal originalAmount, LocalDate issuedOn, LocalDate dueDate, Instant createdAt, UUID createdBy,
                      Instant cancelledAt, UUID cancelledBy, String cancellationReason,
                      List<Line> lines, List<Adjustment> adjustments, List<DueDateChange> dueDateChanges, List<Settlement> receipts) {
        this(id, Origin.WORK_ORDER, workOrderId, workOrderNumber, customerId, billingQuoteId, null, null, null, null, null,
                originalAmount, issuedOn, dueDate, createdAt, createdBy, cancelledAt, cancelledBy, cancellationReason,
                lines, adjustments, dueDateChanges, receipts, null);
    }

    public Receivable {
        if (id == null || origin == null || customerId == null || issuedOn == null || dueDate == null || createdAt == null)
            throw new FinanceException("INVALID_RECEIVABLE", "Dados obrigatórios do recebível ausentes");
        lines = List.copyOf(lines == null ? List.of() : lines).stream().sorted(Comparator.comparingInt(Line::displayOrder)).toList();
        adjustments = List.copyOf(adjustments == null ? List.of() : adjustments);
        dueDateChanges = List.copyOf(dueDateChanges == null ? List.of() : dueDateChanges);
        receipts = List.copyOf(receipts == null ? List.of() : receipts);
        if (originalAmount == null || originalAmount.signum() < 0)
            throw new FinanceException("INVALID_RECEIVABLE", "Valor original inválido");
        notes = Money.optionalText(notes, "Observação", 500);
        if (origin == Origin.WORK_ORDER) {
            if (workOrderId == null || workOrderNumber == null || billingQuoteId == null)
                throw new FinanceException("INVALID_RECEIVABLE", "Recebível da OS sem OS ou orçamento de faturamento");
            if (lines.isEmpty()) throw new FinanceException("INVALID_RECEIVABLE", "Recebível sem linha aprovada");
            // O valor congelado é conferido, não recalculado: divergência fica visível em vez de corrigida em silêncio.
            if (Money.sum(lines.stream().map(Line::totalAmount).toList()).compareTo(originalAmount) != 0)
                throw new FinanceException("RECEIVABLE_TOTAL_MISMATCH", "Valor original diverge das linhas aprovadas");
        } else {
            if (workOrderId != null || workOrderNumber != null || billingQuoteId != null || !lines.isEmpty())
                throw new FinanceException("INVALID_RECEIVABLE", "Recebível avulso não tem OS, orçamento nem linha aprovada");
            description = Money.requiredText(description, "Descrição", 200);
            if (revenueCategoryId == null) throw new FinanceException("INVALID_RECEIVABLE", "Categoria de receita é obrigatória");
            if (originalAmount.signum() == 0) throw new FinanceException("INVALID_FINANCE_ENTRY", "Valor deve ser positivo");
        }
        originalAmount = originalAmount.setScale(Money.SCALE, java.math.RoundingMode.UNNECESSARY);
        if (plan == null)
            plan = new InstallmentPlan(0, null, createdAt, createdBy, List.of(new Installment(null, 1, originalAmount, dueDate)));
        else if (Money.sum(plan.installments().stream().map(Installment::amount).toList()).compareTo(originalAmount) != 0)
            throw new FinanceException("INSTALLMENT_TOTAL_MISMATCH", "A soma das parcelas diverge do valor original");
    }

    public boolean manual() { return origin == Origin.MANUAL; }

    // ------------------------------------------------------------------ derivados

    public BigDecimal discountAmount() { return adjustmentTotal(AdjustmentType.DISCOUNT); }

    public BigDecimal surchargeAmount() { return adjustmentTotal(AdjustmentType.SURCHARGE); }

    public BigDecimal adjustedAmount() { return originalAmount.subtract(discountAmount()).add(surchargeAmount()); }

    public BigDecimal receivedAmount() {
        return Money.sum(receipts.stream().filter(Settlement::active).map(Settlement::amount).toList());
    }

    public BigDecimal outstandingBalance() { return adjustedAmount().subtract(receivedAmount()); }

    /** Principal ainda em aberto: original menos descontos e recebimentos, nunca negativo. */
    public BigDecimal principalOutstanding() {
        BigDecimal value = originalAmount.subtract(discountAmount()).subtract(receivedAmount());
        return value.signum() < 0 ? Money.zero() : value;
    }

    /** Acréscimos ainda não pagos; o recebimento só os alcança depois de quitar o principal. */
    public BigDecimal surchargeOutstanding() { return outstandingBalance().subtract(principalOutstanding()); }

    /** Saldo de cada parcela: o principal em aberto fica nas parcelas mais novas, porque as antigas são quitadas antes. */
    public List<InstallmentPosition> installmentPositions() {
        List<Installment> installments = plan.installments();
        BigDecimal remaining = principalOutstanding();
        InstallmentPosition[] positions = new InstallmentPosition[installments.size()];
        for (int i = installments.size() - 1; i >= 0; i--) {
            Installment installment = installments.get(i);
            BigDecimal open = installment.amount().min(remaining);
            remaining = remaining.subtract(open);
            positions[i] = new InstallmentPosition(installment.number(), installment.amount(), installment.dueDate(),
                    open.setScale(Money.SCALE, java.math.RoundingMode.UNNECESSARY));
        }
        return List.of(positions);
    }

    /**
     * Vencimento corrente: o da primeira parcela com principal em aberto. Sem principal em aberto, o da última
     * parcela — o que sobra é acréscimo, e ele vence com o fim do plano.
     */
    public LocalDate currentDueDate() {
        List<InstallmentPosition> positions = installmentPositions();
        return positions.stream().filter(InstallmentPosition::open).map(InstallmentPosition::dueDate).findFirst()
                .orElse(positions.get(positions.size() - 1).dueDate());
    }

    public boolean cancelled() { return cancelledAt != null; }

    public FinancialStatus status(LocalDate today) {
        return FinancialStatus.derive(cancelled(), receivedAmount(), outstandingBalance(), currentDueDate(), today);
    }

    private BigDecimal adjustmentTotal(AdjustmentType type) {
        return Money.sum(adjustments.stream().filter(adjustment -> adjustment.active() && adjustment.type() == type)
                .map(Adjustment::amount).toList());
    }

    private boolean hasActiveReceipt() { return receipts.stream().anyMatch(Settlement::active); }

    // ------------------------------------------------------------------ regras

    /** DR-0015, F-05: parcial permitido; acima do saldo, recusado — sem troco, crédito ou saldo avulso. */
    public void checkReceipt(BigDecimal amount) {
        requireNotCancelled();
        if (amount.compareTo(outstandingBalance()) > 0)
            throw new FinanceException("AMOUNT_EXCEEDS_BALANCE", "Valor maior que o saldo em aberto de " + outstandingBalance().toPlainString());
    }

    /** DR-0015, F-06: desconto limitado ao saldo, para que valor ajustado e saldo nunca fiquem negativos. */
    public void checkAdjustment(AdjustmentType type, BigDecimal amount) {
        requireNotCancelled();
        if (type == AdjustmentType.DISCOUNT && amount.compareTo(outstandingBalance()) > 0)
            throw new FinanceException("AMOUNT_EXCEEDS_BALANCE", "Desconto maior que o saldo em aberto de " + outstandingBalance().toPlainString());
    }

    /**
     * DR-0017: estorno total e único do ajuste. Estornar desconto só aumenta o saldo. Estornar acréscimo reduz o
     * valor ajustado, e é recusado se o recebido passaria a exceder o novo valor: nada de saldo negativo ou crédito.
     */
    public void checkAdjustmentReversal(UUID adjustmentId) {
        requireNotCancelled();
        Adjustment adjustment = adjustments.stream().filter(candidate -> candidate.id().equals(adjustmentId)).findFirst()
                .orElseThrow(() -> new FinanceException("ADJUSTMENT_NOT_FOUND", "Ajuste não encontrado"));
        if (!adjustment.active()) throw new FinanceException("ADJUSTMENT_ALREADY_REVERSED", "Este ajuste já foi estornado");
        if (adjustment.type() == AdjustmentType.SURCHARGE
                && receivedAmount().compareTo(adjustedAmount().subtract(adjustment.amount())) > 0)
            throw new FinanceException("ADJUSTMENT_REVERSAL_EXCEEDS_RECEIVED",
                    "Estornar este acréscimo deixaria o recebido acima do valor devido; estorne antes os recebimentos excedentes");
    }

    /**
     * DR-0015, F-04, por parcela (DR-0024, Q1): só parcela com principal em aberto tem vencimento a alterar. Num
     * plano de parcela única, vale enquanto houver saldo, como antes das parcelas.
     */
    public Installment checkDueDateChange(Integer installmentNumber, LocalDate newDueDate) {
        requireNotCancelled();
        if (outstandingBalance().signum() == 0)
            throw new FinanceException("RECEIVABLE_NOT_OPEN", "Recebível quitado não tem vencimento a alterar");
        if (newDueDate == null) throw new FinanceException("INVALID_FINANCE_ENTRY", "Novo vencimento é obrigatório");
        List<InstallmentPosition> positions = installmentPositions();
        int number = installmentNumber != null ? installmentNumber
                : positions.stream().filter(InstallmentPosition::open).mapToInt(InstallmentPosition::number).findFirst()
                .orElse(positions.get(positions.size() - 1).number());
        Installment installment = plan.installments().stream().filter(candidate -> candidate.number() == number).findFirst()
                .orElseThrow(() -> new FinanceException("INSTALLMENT_NOT_FOUND", "Parcela não encontrada"));
        boolean open = positions.stream().anyMatch(position -> position.number() == number && position.open());
        if (!open && positions.size() > 1)
            throw new FinanceException("INSTALLMENT_NOT_OPEN", "Parcela quitada não tem vencimento a alterar");
        if (newDueDate.equals(installment.dueDate())) throw new FinanceException("INVALID_FINANCE_ENTRY", "O vencimento informado já é o atual");
        return installment;
    }

    /**
     * DR-0024, Q1: novo plano só enquanto nenhum recebimento estiver ativo. A soma tem de ser o valor original
     * exato, e os vencimentos seguem a ordem das parcelas.
     */
    public List<Installment> checkInstallmentPlan(List<Installment> proposed) {
        requireNotCancelled();
        if (hasActiveReceipt())
            throw new FinanceException("RECEIVABLE_HAS_RECEIPTS", "O recebível já tem recebimento; estorne-o antes de refazer as parcelas");
        if (proposed == null || proposed.isEmpty() || proposed.size() > MAX_INSTALLMENTS)
            throw new FinanceException("INVALID_INSTALLMENT", "Informe de 1 a " + MAX_INSTALLMENTS + " parcelas");
        List<Installment> normalized = new ArrayList<>();
        LocalDate previous = null;
        for (int i = 0; i < proposed.size(); i++) {
            Installment installment = proposed.get(i);
            if (installment.number() != i + 1)
                throw new FinanceException("INVALID_INSTALLMENT", "As parcelas devem ser numeradas de 1 em diante, sem lacuna");
            if (installment.dueDate().isBefore(issuedOn))
                throw new FinanceException("INVALID_INSTALLMENT", "Parcela não pode vencer antes da emissão");
            if (previous != null && installment.dueDate().isBefore(previous))
                throw new FinanceException("INVALID_INSTALLMENT", "Os vencimentos devem seguir a ordem das parcelas");
            previous = installment.dueDate();
            normalized.add(new Installment(installment.id(), installment.number(), Money.positive(installment.amount(), "Valor da parcela"),
                    installment.dueDate()));
        }
        if (Money.sum(normalized.stream().map(Installment::amount).toList()).compareTo(originalAmount) != 0)
            throw new FinanceException("INSTALLMENT_TOTAL_MISMATCH", "A soma das parcelas deve ser exatamente " + originalAmount.toPlainString());
        return normalized;
    }

    /** DR-0015, F-08: dinheiro recebido não some por mudança de status da OS; vale também para o avulso. */
    public void checkCancellation() {
        requireNotCancelled();
        if (hasActiveReceipt())
            throw new FinanceException("RECEIVABLE_HAS_RECEIPTS",
                    manual() ? "A conta tem recebimentos; estorne-os antes de cancelar"
                            : "O recebível da OS tem recebimentos; estorne-os antes de cancelar a OS");
    }

    private void requireNotCancelled() {
        if (cancelled()) throw new FinanceException("RECEIVABLE_CANCELLED", "Recebível cancelado não aceita lançamentos");
    }

    /**
     * Divide um valor em parcelas iguais; os centavos que sobram vão para a última (DR-0024, Q1 e DR-0007). As
     * datas avançam um mês por parcela a partir do primeiro vencimento.
     */
    public static List<Installment> equalSplit(BigDecimal total, int count, LocalDate firstDueDate) {
        if (count < 1 || count > MAX_INSTALLMENTS) throw new FinanceException("INVALID_INSTALLMENT", "Informe de 1 a " + MAX_INSTALLMENTS + " parcelas");
        BigDecimal each = total.divide(BigDecimal.valueOf(count), Money.SCALE, java.math.RoundingMode.DOWN);
        List<Installment> result = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            BigDecimal amount = i < count ? each : total.subtract(each.multiply(BigDecimal.valueOf(count - 1)));
            result.add(new Installment(null, i, amount, firstDueDate.plusMonths(i - 1)));
        }
        return result;
    }
}
