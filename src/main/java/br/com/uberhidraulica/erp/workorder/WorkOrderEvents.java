package br.com.uberhidraulica.erp.workorder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fatos da Ordem de Serviço publicados para quem precisa reagir — o Estoque e o Financeiro.
 *
 * <p>São consumidos na mesma transação: se o consumidor recusar (saldo insuficiente, por exemplo),
 * a operação da OS não acontece. A OS não conhece estoque, saldo nem custo.</p>
 */
public final class WorkOrderEvents {
    private WorkOrderEvents() {
    }

    /** Item físico lançado na OS. */
    public record ProductLaunched(UUID workOrderId, UUID workOrderItemId, UUID productId, BigDecimal quantity) {}

    /**
     * OS finalizada, com os itens físicos que ela consome: os removidos e os rejeitados pelo cliente ficam de fora
     * (DR-0019).
     *
     * @param billingQuoteId orçamento escolhido como fonte comercial do recebível, ou nulo para a seleção
     *                       automática (DR-0015, F-02). A OS não interpreta este valor: apenas o repassa.
     */
    public record Finished(UUID workOrderId, List<ProductLine> products, UUID billingQuoteId, Instant finishedAt, UUID finishedBy) {}

    /** OS cancelada; o que ela tiver movimentado deve ser desfeito por quem movimentou. */
    public record Cancelled(UUID workOrderId) {}

    public record ProductLine(UUID workOrderItemId, UUID productId, BigDecimal quantity) {}

    /**
     * O item físico deixou de ser consumido pela OS: o cliente o rejeitou no orçamento ou ele foi removido da OS
     * (DR-0019). Quem tiver baixado estoque para ele deve devolver.
     *
     * @param reason texto para a trilha do movimento de devolução
     */
    public record ProductReleased(UUID workOrderId, UUID workOrderItemId, String reason) {}
}
