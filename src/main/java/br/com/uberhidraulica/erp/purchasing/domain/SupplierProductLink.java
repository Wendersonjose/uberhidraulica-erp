package br.com.uberhidraulica.erp.purchasing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Vínculo "aprendido" entre o item de um fornecedor e um produto do catálogo (`D6` e `D7`).
 *
 * <p>A chave é <b>fornecedor + código do fornecedor</b>, nunca a descrição: casar por descrição erra em
 * silêncio e só aparece no inventário. Confirmar uma vez grava o vínculo com o fator de conversão, e da
 * segunda nota em diante a importação já vem convertida.</p>
 *
 * <p>Por isso o vínculo precisa ser visível e removível na interface: um vínculo errado confirmado uma vez se
 * repetiria para sempre sem ninguém perceber.</p>
 */
public record SupplierProductLink(UUID id, UUID supplierId, String supplierCode, String gtin, UUID productId,
                                  String unitOriginal, BigDecimal unitConversionFactor, Instant confirmedAt,
                                  UUID confirmedBy, Instant updatedAt, UUID updatedBy) {

    public SupplierProductLink {
        if (supplierCode == null || supplierCode.isBlank()) {
            throw new PurchasingException("PURCHASE_LINK_INVALID",
                    "O vínculo exige o código do item no fornecedor: descrição não serve como chave");
        }
        if (productId == null) {
            throw new PurchasingException("PURCHASE_LINK_INVALID", "O vínculo exige o produto do catálogo");
        }
        if (unitConversionFactor == null || unitConversionFactor.signum() <= 0) {
            throw new PurchasingException("PURCHASE_UNIT_FACTOR_INVALID", "Fator de conversão deve ser maior que zero");
        }
        unitConversionFactor = PurchaseMoney.unit(unitConversionFactor);
    }
}
