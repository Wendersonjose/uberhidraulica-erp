package br.com.uberhidraulica.erp.quote.domain;

/**
 * Estados técnicos da apresentação global.
 *
 * <p>{@code EXPIRED} não existe aqui de propósito: expiração é derivada de {@code validUntil}
 * com o relógio do servidor, e persistir o estado exigiria um processo para mantê-lo correto.</p>
 */
public enum RevisionStatus { DRAFT, PRESENTED }
