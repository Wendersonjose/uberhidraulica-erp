package br.com.uberhidraulica.erp.quote.domain;

/**
 * Se uma condição comercial pode receber decisão do cliente, e por que não pode quando é o caso.
 *
 * <p>Inteiramente derivado: nenhuma coluna guarda este valor, conforme a proibição de {@code is_stale}
 * no modelo de dados aprovado.</p>
 */
public enum DecisionAvailability {
    /** Apresentada, não substituída e dentro da validade comercial. */
    AVAILABLE,
    /** Existe versão posterior do mesmo item efetivamente apresentada (RN-20). */
    SUPERSEDED,
    /** A apresentação que a contém passou da validade comercial (RN-12). */
    EXPIRED,
    /** Existe apenas em rascunho: o cliente nunca a recebeu (RN-19). */
    NOT_PRESENTED
}
