package br.com.uberhidraulica.erp.purchasing.domain;

import java.math.BigDecimal;

/**
 * Composição do custo de aquisição de um item (`DR-0022` D1, aprovada: custo auditável, parametrizável e
 * sujeita à validação tributária do contador).
 *
 * <p>Esta é a única informação que Compras entrega ao Estoque como custo, e ela entra direto na média
 * ponderada — um erro aqui contamina todo o saldo futuro do produto e não é recalculável para trás. Por isso
 * cada parcela somada fica <b>gravada</b> junto com a entrada: a conta tem de ser explicável anos depois sem
 * reabrir o XML.</p>
 *
 * @param baseAmount      valor do produto na nota, menos o desconto do item
 * @param ipiAmount       IPI somado (zero quando o parâmetro está desligado)
 * @param icmsStAmount    ICMS-ST somado (zero quando o parâmetro está desligado)
 * @param icmsNormalAmount ICMS normal somado (zero no padrão: é crédito para quem o recupera)
 * @param freightShare    frete rateado neste item (D2)
 * @param insuranceShare  seguro rateado neste item (D2)
 * @param otherShare      outras despesas rateadas neste item (D2)
 * @param totalAmount     soma das parcelas acima, já arredondada a 2 casas
 * @param quantity        quantidade na unidade do catálogo, já convertida (D7)
 * @param unitCost        {@code totalAmount / quantity}, 4 casas — o que o Estoque recebe
 */
public record AcquisitionCost(BigDecimal baseAmount, BigDecimal ipiAmount, BigDecimal icmsStAmount,
                              BigDecimal icmsNormalAmount, BigDecimal freightShare, BigDecimal insuranceShare,
                              BigDecimal otherShare, BigDecimal totalAmount, BigDecimal quantity,
                              BigDecimal unitCost) {

    /**
     * Parâmetros da composição. Nascem com os valores da alternativa A e são apresentados como <b>não
     * validados pelo contador</b>: é a condição que o proprietário anexou à decisão.
     *
     * <p>ICMS normal fora e ICMS-ST dentro é o correto para quem <b>não</b> recupera crédito. Se a oficina
     * mudar de regime, estes parâmetros mudam — e o custo já gravado <b>não</b> muda, o que é certo
     * contabilmente e gera uma quebra visível na série do custo médio.</p>
     */
    public record Parameters(boolean includeIpi, boolean includeIcmsSt, boolean includeIcmsNormal) {
        /** Padrão aprovado na D1: IPI e ICMS-ST entram; ICMS normal, PIS e COFINS ficam fora. */
        public static Parameters approvedDefault() {
            return new Parameters(true, true, false);
        }
    }

    /**
     * Calcula o custo de aquisição de um item.
     *
     * <p>Todas as parcelas são arredondadas a 2 casas e o total é a <b>soma das parcelas já arredondadas</b>
     * (`DR-0007`), de modo que o total gravado seja exatamente a soma do que fica demonstrado. O custo
     * unitário é obtido do total pela quantidade convertida, com casas extras na divisão para o
     * arredondamento final não enviesar.</p>
     *
     * @param quantityInCatalogUnit quantidade já convertida para a unidade do catálogo (D7)
     */
    public static AcquisitionCost of(BigDecimal productAmount, BigDecimal itemDiscount, BigDecimal ipi,
                                     BigDecimal icmsSt, BigDecimal icmsNormal, BigDecimal freightShare,
                                     BigDecimal insuranceShare, BigDecimal otherShare,
                                     BigDecimal quantityInCatalogUnit, Parameters parameters) {
        BigDecimal base = PurchaseMoney.charged(
                PurchaseMoney.nullToZero(productAmount).subtract(PurchaseMoney.nullToZero(itemDiscount)));
        if (base.signum() < 0) {
            throw new PurchasingException("PURCHASE_INVALID",
                    "Desconto do item maior que o valor do produto: " + productAmount + " menos " + itemDiscount);
        }
        BigDecimal ipiPart = parameters.includeIpi() ? PurchaseMoney.charged(ipi) : PurchaseMoney.charged(BigDecimal.ZERO);
        BigDecimal stPart = parameters.includeIcmsSt() ? PurchaseMoney.charged(icmsSt) : PurchaseMoney.charged(BigDecimal.ZERO);
        BigDecimal icmsPart = parameters.includeIcmsNormal()
                ? PurchaseMoney.charged(icmsNormal) : PurchaseMoney.charged(BigDecimal.ZERO);
        BigDecimal freight = PurchaseMoney.charged(freightShare);
        BigDecimal insurance = PurchaseMoney.charged(insuranceShare);
        BigDecimal other = PurchaseMoney.charged(otherShare);

        BigDecimal total = PurchaseMoney.sumCharged(
                java.util.List.of(base, ipiPart, stPart, icmsPart, freight, insurance, other));
        BigDecimal quantity = PurchaseMoney.quantity(quantityInCatalogUnit);
        PurchaseMoney.requirePositive(quantity, "Quantidade convertida do item");
        BigDecimal unitCost = PurchaseMoney.unit(total.divide(quantity, PurchaseMoney.UNIT_SCALE + 4,
                java.math.RoundingMode.HALF_UP));

        return new AcquisitionCost(base, ipiPart, stPart, icmsPart, freight, insurance, other, total, quantity, unitCost);
    }
}
