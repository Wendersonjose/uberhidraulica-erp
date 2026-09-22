package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.workorder.WorkOrderEvents;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reage à finalização e ao cancelamento da OS dentro da transação dela (mesmo desenho do Estoque): uma
 * recusa do Financeiro desfaz a operação da OS, e uma recusa de outro módulo desfaz o recebível.
 */
@Component
class WorkOrderFinanceListener {
    private final ReceivableService receivables;

    WorkOrderFinanceListener(ReceivableService receivables) { this.receivables = receivables; }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onFinished(WorkOrderEvents.Finished event) {
        receivables.generateForFinishedWorkOrder(event.workOrderId(), event.billingQuoteId(), event.finishedAt(), event.finishedBy());
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onCancelled(WorkOrderEvents.Cancelled event) {
        receivables.cancelForWorkOrder(event.workOrderId(), "Cancelamento da OS");
    }
}
