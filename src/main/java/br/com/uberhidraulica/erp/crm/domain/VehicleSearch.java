package br.com.uberhidraulica.erp.crm.domain;

import java.util.Locale;
import java.util.UUID;

/** Critério de busca de veículos por placa, marca, modelo ou nome do proprietário atual. */
public record VehicleSearch(String text, UUID customerId, Boolean active, int page, int size) {
    public VehicleSearch {
        text = text == null || text.isBlank() ? null : text.trim();
        if (page < 0) throw new CrmException("INVALID_VEHICLE", "Página inválida");
        if (size < 1 || size > CustomerSearch.MAX_SIZE)
            throw new CrmException("INVALID_VEHICLE", "Tamanho de página deve estar entre 1 e " + CustomerSearch.MAX_SIZE);
    }

    public String plate() {
        if (text == null) return null;
        String plate = text.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return plate.isEmpty() ? null : plate;
    }

    /** Linha da listagem: veículo com o nome do proprietário atual. */
    public record Row(Vehicle vehicle, String customerName) {}
}
