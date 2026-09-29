package br.com.uberhidraulica.erp.inventory;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.UUID;

/**
 * Contrato público do custo histórico de peças baixadas em Ordens de Serviço.
 *
 * <p>Sempre o custo unitário efetivamente registrado na movimentação no instante da baixa — nunca o custo
 * atual do catálogo. Usado pelo resumo financeiro gerencial para compor o custo direto conhecido.</p>
 */
public interface InventoryCostQuery {

    /**
     * Soma o custo das baixas ({@code WORK_ORDER_OUT}) menos as devoluções ({@code WORK_ORDER_RETURN}) das
     * Ordens de Serviço informadas. Zero quando a coleção é vazia ou nenhuma delas tem movimentação.
     */
    BigDecimal partsCostForWorkOrders(Collection<UUID> workOrderIds);
}
