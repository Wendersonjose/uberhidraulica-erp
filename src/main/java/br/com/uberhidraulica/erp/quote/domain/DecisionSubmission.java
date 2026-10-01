package br.com.uberhidraulica.erp.quote.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Submissão pública atômica de decisões.
 *
 * <p>Guarda a identidade **declarada** pelo cliente no momento da decisão, e não um cadastro: alterar
 * o cliente no CRM depois não pode reescrever o que ele afirmou ao aceitar.</p>
 */
public record DecisionSubmission(UUID id, UUID publicQuoteAccessId, UUID quoteRevisionId, UUID quoteId,
                                 String requestId, byte[] payloadDigest, String customerName,
                                 DocumentType documentType, String documentNumber, boolean explicitAcceptance,
                                 Instant occurredAt, String ipAddress, String userAgent,
                                 List<Decision> decisions) {

    /** Decisão individual consolidada sobre uma condição comercial apresentada. */
    public record Decision(UUID id, UUID quoteItemRevisionId, DecisionType decisionType, Instant occurredAt) {}

    public DecisionSubmission {
        if (id == null || publicQuoteAccessId == null || quoteRevisionId == null || quoteId == null
                || occurredAt == null)
            throw invalid("Dados obrigatórios ausentes");
        requestId = required(requestId, "requestId", 100);
        customerName = required(customerName, "nome", 200);
        if (documentType == null) throw invalid("Tipo de documento é obrigatório");
        documentNumber = normalizedDocument(documentType, documentNumber);
        if (!explicitAcceptance)
            throw new QuoteException("EXPLICIT_ACCEPTANCE_REQUIRED",
                    "A decisão exige aceite explícito do cliente");
        if (payloadDigest == null || payloadDigest.length != PublicAccessToken.digestLength())
            throw invalid("Digest do conteúdo inválido");
        if (ipAddress == null || ipAddress.isBlank()) throw invalid("Endereço de origem ausente");
        userAgent = userAgent == null || userAgent.isBlank() ? null
                : userAgent.substring(0, Math.min(userAgent.length(), 1024));
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        if (decisions.isEmpty()) throw invalid("A submissão precisa conter ao menos uma decisão");
        payloadDigest = payloadDigest.clone();
    }

    @Override
    public byte[] payloadDigest() { return payloadDigest.clone(); }

    /**
     * Documento normalizado para somente dígitos.
     *
     * <p>Comprimento é conferido; dígito verificador **não**. A revisão de segurança aprovada é
     * explícita em não inventar política de rejeição aqui: recusar um documento válido para a
     * Receita por conta de uma regra não aprovada impediria uma aprovação legítima.</p>
     */
    private static String normalizedDocument(DocumentType type, String value) {
        if (value == null) throw invalid("Documento é obrigatório");
        if (value.codePoints().anyMatch(Character::isLetter))
            throw invalid("Documento deve conter somente dígitos e formatação");
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() != type.digits())
            throw invalid("Documento incompatível com o tipo informado");
        return digits;
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw invalid(label + " inválido");
        return value.trim();
    }

    private static QuoteException invalid(String message) {
        return new QuoteException("VALIDATION_ERROR", message);
    }
}
