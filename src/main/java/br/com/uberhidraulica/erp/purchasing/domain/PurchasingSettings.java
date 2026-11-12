package br.com.uberhidraulica.erp.purchasing.domain;

import br.com.uberhidraulica.erp.purchasing.domain.nfe.BrazilianTaxId;

/**
 * Configuração do módulo Compras (`DR-0022` D1 e D9).
 *
 * <p>Os três parâmetros de custo nascem na alternativa A da D1 e são apresentados como <b>não validados pelo
 * contador</b> — condição que o proprietário anexou à aprovação. Alterá-los <b>não</b> recalcula custo já
 * gravado: o custo histórico é o que foi efetivamente pago, e mudança de regime tributário não reescreve o
 * passado.</p>
 *
 * @param ownTaxId           CNPJ completo do estabelecimento autorizado; vazio significa não configurado
 * @param freightAllocation  método de rateio (D2); hoje sempre {@code VALUE}, parametrizável por decisão
 */
public record PurchasingSettings(String ownTaxId, AcquisitionCost.Parameters costParameters,
                                 FreightAllocation freightAllocation) {

    /** Chaves aceitas em {@code purchasing.settings}; o banco tem o mesmo CHECK. */
    public static final String KEY_OWN_TAX_ID = "OWN_TAX_ID";
    public static final String KEY_INCLUDE_IPI = "COST_INCLUDE_IPI";
    public static final String KEY_INCLUDE_ICMS_ST = "COST_INCLUDE_ICMS_ST";
    public static final String KEY_INCLUDE_ICMS_NORMAL = "COST_INCLUDE_ICMS_NORMAL";
    public static final String KEY_FREIGHT_ALLOCATION = "FREIGHT_ALLOCATION";

    /** Método de rateio de frete e despesas (D2). {@code QUANTITY} fica para quando o peso explicar melhor. */
    public enum FreightAllocation { VALUE, QUANTITY }

    public PurchasingSettings {
        ownTaxId = ownTaxId == null ? "" : ownTaxId;
        if (costParameters == null) costParameters = AcquisitionCost.Parameters.approvedDefault();
        if (freightAllocation == null) freightAllocation = FreightAllocation.VALUE;
    }

    /** Verdadeiro quando o CNPJ da oficina está configurado — pré-requisito para confirmar qualquer nota. */
    public boolean ownTaxIdConfigured() {
        return !ownTaxId.isBlank();
    }

    /**
     * Valida o valor de uma chave antes de gravar. O banco também valida; aqui a mensagem é legível para quem
     * está na tela de configuração.
     */
    public static String validate(String key, String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        return switch (key) {
            case KEY_OWN_TAX_ID -> {
                String digits = BrazilianTaxId.normalize(value);
                if (digits == null || digits.length() != 14 || !BrazilianTaxId.isValidCnpj(digits)) {
                    // A D9 proíbe aceitar a raiz: 8 dígitos cairiam aqui, como deve ser.
                    throw new PurchasingException("PURCHASE_SETTING_INVALID",
                            "OWN_TAX_ID deve ser o CNPJ completo e válido do estabelecimento, com 14 posições");
                }
                yield digits;
            }
            case KEY_INCLUDE_IPI, KEY_INCLUDE_ICMS_ST, KEY_INCLUDE_ICMS_NORMAL -> {
                if (!"true".equals(value) && !"false".equals(value)) {
                    throw new PurchasingException("PURCHASE_SETTING_INVALID", key + " aceita apenas true ou false");
                }
                yield value;
            }
            case KEY_FREIGHT_ALLOCATION -> {
                if (!"VALUE".equals(value) && !"QUANTITY".equals(value)) {
                    throw new PurchasingException("PURCHASE_SETTING_INVALID",
                            "FREIGHT_ALLOCATION aceita apenas VALUE ou QUANTITY");
                }
                yield value;
            }
            default -> throw new PurchasingException("PURCHASE_SETTING_UNKNOWN", "Configuração desconhecida: " + key);
        };
    }

    /** Monta a configuração a partir dos pares chave/valor gravados, usando o padrão aprovado no que faltar. */
    public static PurchasingSettings from(java.util.Map<String, String> stored) {
        return new PurchasingSettings(
                stored.getOrDefault(KEY_OWN_TAX_ID, ""),
                new AcquisitionCost.Parameters(
                        flag(stored, KEY_INCLUDE_IPI, true),
                        flag(stored, KEY_INCLUDE_ICMS_ST, true),
                        flag(stored, KEY_INCLUDE_ICMS_NORMAL, false)),
                FreightAllocation.valueOf(stored.getOrDefault(KEY_FREIGHT_ALLOCATION, "VALUE")));
    }

    private static boolean flag(java.util.Map<String, String> stored, String key, boolean fallback) {
        String value = stored.get(key);
        return value == null ? fallback : "true".equals(value);
    }
}
