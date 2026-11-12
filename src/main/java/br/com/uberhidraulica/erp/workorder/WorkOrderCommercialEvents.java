package br.com.uberhidraulica.erp.workorder;

import java.util.UUID;

/**
 * Contrato público pelo qual o orçamento informa a OS sobre fatos comerciais (DR-0013).
 *
 * <p>A OS decide, pelas suas regras automáticas, se e para qual status se move. Quem informa não
 * conhece status, etapas nem histórico da OS.</p>
 */
public interface WorkOrderCommercialEvents {
    /**
     * Bloqueia a linha da OS até o fim da transação de quem chama.
     *
     * <p>Apresentação e decisão chamam isto antes de ler o orçamento. A finalização também começa bloqueando a
     * OS, então as duas disputam os bloqueios na mesma ordem (OS, depois orçamento) — sem deadlock — e a
     * base comercial do recebível nunca é lida no meio de uma alteração comercial (revisão TASK-0015, F1).</p>
     *
     * @return {@code true} se a OS ainda está em atendimento e aceita mudança comercial; {@code false} se está
     *         finalizada, entregue ou cancelada, quando criar, apresentar ou decidir orçamento já não faz sentido
     *         (DR-0012 item 9: OS encerrada não aceita alterações). Quem chama decide como recusar.
     */
    boolean lockForCommercialChange(UUID workOrderId);

    void quotePresented(UUID workOrderId);

    /**
     * Decisão consolidada sobre uma apresentação.
     *
     * @param anyApproved      ao menos um item da apresentação está aprovado
     * @param allRejected      todos os itens da apresentação foram decididos e nenhum foi aprovado
     */
    void quoteDecided(UUID workOrderId, boolean anyApproved, boolean allRejected);

    /**
     * Decisões do cliente sobre itens de orçamento ligados a itens físicos da OS (DR-0019, opção A).
     *
     * <p>A OS guarda a decisão no item e, para o rejeitado, publica a liberação para o Estoque devolver a baixa.</p>
     *
     * @param approvedByProductItem id do item físico da OS → {@code true} aprovado, {@code false} rejeitado
     * @return itens <b>aprovados</b> que já foram removidos da OS. Aprovar o que a OS não tem mais faturaria um item
     *         inexistente; quem chama deve recusar a decisão inteira. Rejeitar item removido é inofensivo.
     */
    java.util.Set<UUID> productItemsDecided(UUID workOrderId, java.util.Map<UUID, Boolean> approvedByProductItem);
}
