package br.com.uberhidraulica.erp.inventory.application;

import br.com.uberhidraulica.erp.workorder.WorkOrderEvents;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reage aos fatos da OS conforme o modo de baixa configurado (DR-0014).
 *
 * <p>Os ouvintes rodam na transação de quem publicou: recusar por saldo insuficiente desfaz o
 * lançamento ou a finalização da OS, em vez de deixar estoque e OS discordando.</p>
 */
@Component
class WorkOrderStockListener {
    private final InventoryApplicationService inventory;

    WorkOrderStockListener(InventoryApplicationService inventory) { this.inventory = inventory; }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onProductLaunched(WorkOrderEvents.ProductLaunched event) {
        if (inventory.writeOffMode() != InventoryApplicationService.WriteOffMode.ITEM_LAUNCH) return;
        inventory.writeOffForWorkOrder(event.workOrderId(), event.workOrderItemId(), event.productId(), event.quantity());
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onFinished(WorkOrderEvents.Finished event) {
        if (inventory.writeOffMode() != InventoryApplicationService.WriteOffMode.WORK_ORDER_FINISH) return;
        for (WorkOrderEvents.ProductLine line : event.products())
            inventory.writeOffForWorkOrder(event.workOrderId(), line.workOrderItemId(), line.productId(), line.quantity());
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onCancelled(WorkOrderEvents.Cancelled event) {
        inventory.returnWorkOrderStock(event.workOrderId());
    }
}
