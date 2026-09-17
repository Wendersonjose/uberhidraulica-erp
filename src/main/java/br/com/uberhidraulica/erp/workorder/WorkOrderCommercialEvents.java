package br.com.uberhidraulica.erp.workorder;

import java.util.UUID;

/**
 * Contrato público pelo qual o orçamento informa a OS sobre fatos comerciais (DR-0013).
 *
 * <p>A OS decide, pelas suas regras automáticas, se e para qual status se move. Quem informa não
 * conhece status, etapas nem histórico da OS.</p>
 */
public interface WorkOrderCommercialEvents {
    void quotePresented(UUID workOrderId);

    /**
     * Decisão consolidada sobre uma apresentação.
     *
     * @param anyApproved      ao menos um item da apresentação está aprovado
     * @param allRejected      todos os itens da apresentação foram decididos e nenhum foi aprovado
     */
    void quoteDecided(UUID workOrderId, boolean anyApproved, boolean allRejected);
}
