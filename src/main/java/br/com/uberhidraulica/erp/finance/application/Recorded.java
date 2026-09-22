package br.com.uberhidraulica.erp.finance.application;

/**
 * Resultado de uma operação idempotente.
 *
 * @param replayed verdadeiro quando a chave já tinha sido usada para o mesmo pedido e nada foi gravado de novo
 */
public record Recorded<T>(T value, boolean replayed) {}
