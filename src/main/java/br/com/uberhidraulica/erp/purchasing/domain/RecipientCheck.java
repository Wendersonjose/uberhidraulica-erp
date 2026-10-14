package br.com.uberhidraulica.erp.purchasing.domain;

import br.com.uberhidraulica.erp.purchasing.domain.nfe.BrazilianTaxId;

/**
 * Verificação do destinatário da NF-e contra o CNPJ do estabelecimento autorizado (`DR-0022` D9).
 *
 * <p>O proprietário endureceu esta decisão: a confirmação é <b>impedida</b> enquanto o CNPJ destinatário não
 * corresponder, e a <b>raiz</b> do CNPJ não serve como prova de identidade. A comparação é portanto por CNPJ
 * completo, 14 dígitos. Nota emitida para outra filial do mesmo grupo é recusada, não alertada.</p>
 *
 * <p>Consequência registrada na DR: {@code OWN_TAX_ID} é pré-requisito de operação. Sem ele configurado,
 * nenhuma nota confirma — a prévia continua funcionando, porque ler não movimenta nada.</p>
 */
public final class RecipientCheck {

    /** Resultado da verificação. {@code AUTHORIZED} é o único que permite confirmar. */
    public enum Result {
        /** Destinatário é o estabelecimento autorizado. */
        AUTHORIZED,
        /** {@code OWN_TAX_ID} não configurado: não há com o que comparar. */
        OWN_TAX_ID_NOT_CONFIGURED,
        /** Destinatário é outro CNPJ, inclusive filial do mesmo grupo. */
        RECIPIENT_MISMATCH,
        /** A nota não traz destinatário legível. */
        RECIPIENT_MISSING
    }

    private RecipientCheck() {}

    private static String digitsOrEmpty(String raw) {
        String normalized = BrazilianTaxId.normalize(raw);
        return normalized == null ? "" : normalized;
    }

    /**
     * @param recipientTaxId CNPJ/CPF do destinatário como veio na nota, com ou sem máscara
     * @param ownTaxId       CNPJ do estabelecimento autorizado ({@code OWN_TAX_ID}), nulo quando não configurado
     */
    public static Result verify(String recipientTaxId, String ownTaxId) {
        // `BrazilianTaxId.normalize` preserva null; aqui null e vazio significam a mesma coisa (não informado).
        String own = digitsOrEmpty(ownTaxId);
        if (own.isEmpty()) return Result.OWN_TAX_ID_NOT_CONFIGURED;
        String recipient = digitsOrEmpty(recipientTaxId);
        if (recipient.isEmpty()) return Result.RECIPIENT_MISSING;
        // Comparação por CNPJ completo. Comparar raiz seria aceitar outra filial como prova de identidade,
        // que é justamente o que a D9 proíbe.
        return own.equals(recipient) ? Result.AUTHORIZED : Result.RECIPIENT_MISMATCH;
    }

    /** Mensagem para o operador, sem expor o CNPJ configurado quando o problema é ausência de configuração. */
    public static String explain(Result result) {
        return switch (result) {
            case AUTHORIZED -> "Destinatário confere com o estabelecimento autorizado";
            case OWN_TAX_ID_NOT_CONFIGURED -> "CNPJ da oficina não configurado: configure OWN_TAX_ID antes de importar";
            case RECIPIENT_MISMATCH -> "Nota destinada a outro CNPJ: não pertence ao estabelecimento autorizado";
            case RECIPIENT_MISSING -> "Nota sem CNPJ de destinatário legível";
        };
    }

    /**
     * Impede a confirmação quando o destinatário não é o estabelecimento autorizado.
     *
     * @throws PurchasingException {@code PURCHASE_RECIPIENT_NOT_AUTHORIZED} em qualquer resultado diferente de
     *                             {@link Result#AUTHORIZED}
     */
    public static void requireAuthorized(String recipientTaxId, String ownTaxId) {
        Result result = verify(recipientTaxId, ownTaxId);
        if (result != Result.AUTHORIZED) {
            throw new PurchasingException("PURCHASE_RECIPIENT_NOT_AUTHORIZED", explain(result));
        }
    }
}
