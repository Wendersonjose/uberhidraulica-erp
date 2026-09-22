package br.com.uberhidraulica.erp.quote;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Contrato público da base comercial de faturamento de uma OS (DR-0015, F-02).
 *
 * <p>Entrega apenas o que o cliente efetivamente aprovou: por item comercial, a versão corrente — a
 * apresentada e não substituída — com decisão {@code APPROVE}. Versão rejeitada, sem decisão ou substituída
 * não entra, mesmo que uma versão anterior do mesmo item tenha sido aprovada (domínio aprovado, §§56–58).
 * Nenhum total bruto de OS é usado aqui.</p>
 */
public interface QuoteBillingQuery {

    /** Orçamentos da OS com ao menos um item aprovado na versão corrente; lista vazia quando não há base. */
    List<BillingCandidate> billingCandidates(UUID workOrderId);

    /**
     * @param approvedTotal soma dos totais já arredondados das linhas aprovadas (DR-0007)
     */
    record BillingCandidate(UUID quoteId, UUID workOrderId, BigDecimal approvedTotal, List<BillingLine> lines) {
        public BillingCandidate {
            lines = List.copyOf(lines);
        }
    }

    record BillingLine(UUID quoteItemId, UUID quoteItemRevisionId, String description, BigDecimal quantity,
                       BigDecimal unitPrice, BigDecimal discountAmount, BigDecimal totalPrice,
                       UUID workOrderServiceId, UUID workOrderProductId) {}
}
