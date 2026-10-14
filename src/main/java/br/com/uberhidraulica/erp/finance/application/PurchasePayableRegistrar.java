package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.finance.PurchasePayableException;
import br.com.uberhidraulica.erp.finance.PurchasePayables;
import br.com.uberhidraulica.erp.finance.domain.FinanceException;
import br.com.uberhidraulica.erp.finance.domain.Payable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Implementação do contrato público {@link PurchasePayables}.
 *
 * <p>Delega ao {@link PayableService}, que é onde a regra do Financeiro vive — este adaptador não reimplementa
 * nada. O que ele faz de essencial é <b>traduzir</b> a falha interna ({@link FinanceException}) para
 * {@link PurchasePayableException}: o tipo interno não é exposto pelo módulo, e deixá-lo escapar para um
 * controller de Compras daria 500 em vez do erro de cliente que o caso realmente é.</p>
 */
@Service
class PurchasePayableRegistrar implements PurchasePayables {
    private final PayableService payables;

    PurchasePayableRegistrar(PayableService payables) {
        this.payables = payables;
    }

    @Override
    @Transactional
    public List<Registered> register(Request request) {
        if (request == null || request.installments() == null || request.installments().isEmpty()) {
            throw new PurchasePayableException("PURCHASE_PAYABLES_EMPTY", "Nenhuma parcela a registrar");
        }
        if (request.idempotencyPrefix() == null || request.idempotencyPrefix().isBlank()) {
            throw new PurchasePayableException("PURCHASE_PAYABLES_KEY_REQUIRED",
                    "Prefixo de idempotência é obrigatório para não duplicar conta a pagar");
        }
        List<Registered> result = new ArrayList<>(request.installments().size());
        for (Installment installment : request.installments()) {
            String description = request.installments().size() == 1
                    ? request.description()
                    : request.description() + " — parcela " + installment.number() + "/" + request.installments().size();
            try {
                Recorded<Payable> recorded = payables.create(description, request.supplierName(),
                        request.expenseCategoryId(), installment.amount(), installment.dueDate(), request.notes(),
                        key(request.idempotencyPrefix(), installment.number()));
                result.add(new Registered(recorded.value().id(), installment.number(), installment.dueDate(),
                        installment.amount(), installment.reference(), recorded.replayed()));
            } catch (FinanceException internal) {
                throw translate(internal);
            }
        }
        return List.copyOf(result);
    }

    @Override
    @Transactional
    public void cancel(List<UUID> payableIds, String reason) {
        if (payableIds == null || payableIds.isEmpty()) return;
        for (UUID payableId : payableIds) {
            try {
                payables.cancel(payableId, reason);
            } catch (FinanceException internal) {
                // Conta já paga não é cancelada em silêncio: a falha sobe e o caso de uso de quem chamou
                // desfaz a transação inteira, em vez de deixar metade das obrigações canceladas.
                throw translate(internal);
            }
        }
    }

    /**
     * Chave por parcela, derivada do prefixo estável do pedido. Na importação o prefixo é a chave de acesso da
     * NF-e, então reimportar a mesma nota reencontra as mesmas contas em vez de criar novas (`D4`/`D5`).
     */
    private static String key(String prefix, int installmentNumber) {
        return prefix + ":" + installmentNumber;
    }

    private static PurchasePayableException translate(FinanceException internal) {
        return new PurchasePayableException(internal.code(), internal.getMessage());
    }
}
