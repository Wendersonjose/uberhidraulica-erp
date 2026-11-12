package br.com.uberhidraulica.erp.purchasing.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Item da compra, já convertido para a unidade do catálogo (`D7`) e com a composição do custo de aquisição
 * gravada parcela por parcela (`D1`).
 *
 * <p>A unidade e a quantidade <b>originais</b> da nota ficam preservadas: sem elas, uma conversão errada não
 * é auditável — ninguém saberia que a nota dizia 1 CX e o estoque recebeu 12 UN.</p>
 *
 * @param unitPrice preço unitário na unidade do catálogo, 4 casas
 * @param unitCost  custo unitário de aquisição, 4 casas — é o único número que o Estoque recebe como custo
 */
public record PurchaseItem(UUID id, UUID purchaseId, int lineNo, UUID productId, String supplierCode,
                           String description, String unitOriginal, BigDecimal quantityOriginal,
                           BigDecimal unitConversionFactor, BigDecimal quantity, BigDecimal unitPrice,
                           BigDecimal unitCost, AcquisitionCost cost, BigDecimal discount, BigDecimal lineTotal,
                           String ncm, String cfop, String gtin, UUID workOrderId, UUID workOrderItemId) {

    public PurchaseItem {
        if (lineNo < 1) throw new PurchasingException("PURCHASE_INVALID", "Número de linha do item deve ser positivo");
        PurchaseMoney.requirePositive(quantity, "Quantidade do item");
        if (unitConversionFactor == null || unitConversionFactor.signum() <= 0) {
            throw new PurchasingException("PURCHASE_UNIT_FACTOR_INVALID", "Fator de conversão deve ser maior que zero");
        }
    }

    /** Verdadeiro quando o item ainda não está vinculado a um produto do catálogo — não pode ser confirmado. */
    public boolean unlinked() {
        return productId == null;
    }
}
