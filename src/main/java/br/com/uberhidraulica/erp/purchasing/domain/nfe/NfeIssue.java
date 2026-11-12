package br.com.uberhidraulica.erp.purchasing.domain.nfe;

/**
 * Achado da validação de uma NF-e. {@code BLOCKING} impede a compra efetiva; {@code WARNING} exige reconhecimento
 * explícito do usuário; {@code INFO} apenas informa (inclusive o que a validação NÃO verificou).
 */
public record NfeIssue(String code, Severity severity, String message, Integer itemNumber) {
    public enum Severity { BLOCKING, WARNING, INFO }

    public static NfeIssue blocking(String code, String message) { return new NfeIssue(code, Severity.BLOCKING, message, null); }

    public static NfeIssue blocking(String code, String message, int item) { return new NfeIssue(code, Severity.BLOCKING, message, item); }

    public static NfeIssue warning(String code, String message) { return new NfeIssue(code, Severity.WARNING, message, null); }

    public static NfeIssue warning(String code, String message, int item) { return new NfeIssue(code, Severity.WARNING, message, item); }

    public static NfeIssue info(String code, String message) { return new NfeIssue(code, Severity.INFO, message, null); }

    public static NfeIssue info(String code, String message, int item) { return new NfeIssue(code, Severity.INFO, message, item); }
}
