package br.com.uberhidraulica.erp.purchasing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Unidade da NF-e × unidade do catálogo (`DR-0022` D7, aprovada: exigir fator de conversão válido antes de
 * movimentar estoque).
 *
 * <p>Equivalências diretas são as que não mudam quantidade nenhuma. Qualquer outra unidade (CX, PAR, JG, KIT)
 * exige fator informado e gravado no vínculo do item: uma caixa de 12 lançada como 1 unidade criaria saldo de
 * 1 onde há 12 e custo unitário 12 vezes maior. Sem fator, a linha não confirma — é a trava da decisão.</p>
 */
public final class UnitConversion {
    /** Unidades do catálogo, conforme {@code productcatalog.product.unit}. */
    public enum CatalogUnit { UNIDADE, LITRO, METRO, QUILOGRAMA, GALAO_5L, BALDE_20L }

    /** Equivalências diretas: fator 1, nenhuma conversão de quantidade. */
    private static final Map<String, CatalogUnit> DIRECT = Map.ofEntries(
            Map.entry("UN", CatalogUnit.UNIDADE), Map.entry("UND", CatalogUnit.UNIDADE),
            Map.entry("UNID", CatalogUnit.UNIDADE), Map.entry("UNIDADE", CatalogUnit.UNIDADE),
            Map.entry("PC", CatalogUnit.UNIDADE), Map.entry("PÇ", CatalogUnit.UNIDADE),
            Map.entry("PCS", CatalogUnit.UNIDADE), Map.entry("PEC", CatalogUnit.UNIDADE),
            Map.entry("L", CatalogUnit.LITRO), Map.entry("LT", CatalogUnit.LITRO),
            Map.entry("LTS", CatalogUnit.LITRO), Map.entry("LITRO", CatalogUnit.LITRO),
            Map.entry("M", CatalogUnit.METRO), Map.entry("MT", CatalogUnit.METRO),
            Map.entry("MTS", CatalogUnit.METRO), Map.entry("METRO", CatalogUnit.METRO),
            Map.entry("KG", CatalogUnit.QUILOGRAMA), Map.entry("QUILO", CatalogUnit.QUILOGRAMA),
            Map.entry("QUILOGRAMA", CatalogUnit.QUILOGRAMA));

    private UnitConversion() {}

    /** Normaliza a unidade da nota: maiúsculas, sem espaços e sem ponto final ("UN." vira "UN"). */
    public static String normalize(String unitFromInvoice) {
        if (unitFromInvoice == null) return "";
        return unitFromInvoice.trim().toUpperCase(Locale.ROOT).replace(".", "");
    }

    /** Unidade do catálogo equivalente, quando a unidade da nota dispensa fator. */
    public static Optional<CatalogUnit> directEquivalent(String unitFromInvoice) {
        return Optional.ofNullable(DIRECT.get(normalize(unitFromInvoice)));
    }

    /**
     * Verdadeiro quando a unidade da nota equivale diretamente à unidade do produto no catálogo — único caso em
     * que a linha confirma sem fator informado.
     */
    public static boolean needsNoFactor(String unitFromInvoice, String catalogUnit) {
        Optional<CatalogUnit> equivalent = directEquivalent(unitFromInvoice);
        return equivalent.isPresent() && catalogUnit != null
                && equivalent.get().name().equals(catalogUnit.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Fator efetivo da linha. Quando a unidade equivale diretamente à do catálogo, é 1. Fora disso, o fator
     * informado é obrigatório e tem de ser positivo.
     *
     * @throws PurchasingException {@code PURCHASE_UNIT_FACTOR_REQUIRED} quando falta fator para unidade que exige
     */
    public static BigDecimal effectiveFactor(String unitFromInvoice, String catalogUnit, BigDecimal informedFactor) {
        if (informedFactor != null) {
            if (informedFactor.signum() <= 0) {
                throw new PurchasingException("PURCHASE_UNIT_FACTOR_INVALID",
                        "Fator de conversão deve ser maior que zero");
            }
            return PurchaseMoney.unit(informedFactor);
        }
        if (needsNoFactor(unitFromInvoice, catalogUnit)) return BigDecimal.ONE.setScale(PurchaseMoney.UNIT_SCALE);
        throw new PurchasingException("PURCHASE_UNIT_FACTOR_REQUIRED",
                "Unidade " + normalize(unitFromInvoice) + " da nota não equivale a " + catalogUnit
                        + " no catálogo: informe o fator de conversão");
    }

    /** Quantidade no catálogo: quantidade da nota multiplicada pelo fator (1 CX × 12 = 12 UNIDADE). */
    public static BigDecimal convertQuantity(BigDecimal quantityFromInvoice, BigDecimal factor) {
        PurchaseMoney.requirePositive(quantityFromInvoice, "Quantidade da nota");
        return PurchaseMoney.quantity(quantityFromInvoice.multiply(factor));
    }

    /**
     * Custo unitário no catálogo: custo da unidade da nota dividido pelo fator (R$ 120,00 a caixa de 12 vira
     * R$ 10,00 a unidade). Divisão com casas extras antes do arredondamento final, para o fator não introduzir
     * erro sistemático — operando nunca é arredondado antes da conta (`DR-0007`).
     */
    public static BigDecimal convertUnitCost(BigDecimal unitCostFromInvoice, BigDecimal factor) {
        if (factor == null || factor.signum() <= 0) {
            throw new PurchasingException("PURCHASE_UNIT_FACTOR_INVALID", "Fator de conversão deve ser maior que zero");
        }
        return PurchaseMoney.unit(PurchaseMoney.nullToZero(unitCostFromInvoice)
                .divide(factor, PurchaseMoney.UNIT_SCALE + 4, RoundingMode.HALF_UP));
    }
}
