package br.com.uberhidraulica.erp.crm.domain;

import java.util.List;
import java.util.Locale;

/**
 * Critério de busca de clientes.
 *
 * <p>O texto livre casa com nome/razão social (sem diferenciar maiúsculas) e com placa de veículo
 * vinculado; quando não contém letras, também com CPF/CNPJ e telefone.</p>
 */
public record CustomerSearch(String text, Customer.PersonType personType, Customer.Status status, int page, int size) {
    public static final int MAX_SIZE = 100;

    public CustomerSearch {
        text = text == null || text.isBlank() ? null : text.trim();
        if (page < 0) throw Customer.invalid("Página inválida");
        if (size < 1 || size > MAX_SIZE) throw Customer.invalid("Tamanho de página deve estar entre 1 e " + MAX_SIZE);
    }

    /** Dígitos para CPF/CNPJ e telefone; texto com letras (nome, placa) não busca por documento. */
    public String digits() {
        if (text == null || text.codePoints().anyMatch(Character::isLetter)) return null;
        return emptyToNull(text.replaceAll("[^0-9]", ""));
    }

    public String plate() { return text == null ? null : emptyToNull(text.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT)); }

    private static String emptyToNull(String value) { return value.isEmpty() ? null : value; }

    public record Page<T>(List<T> items, long totalItems, int page, int size) {
        public Page { items = List.copyOf(items); }
        public int totalPages() { return (int) ((totalItems + size - 1) / size); }
    }
}
