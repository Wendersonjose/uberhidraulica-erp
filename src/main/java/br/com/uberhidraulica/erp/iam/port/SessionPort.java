package br.com.uberhidraulica.erp.iam.port;

public interface SessionPort {
    void invalidateAll(String normalizedEmail);
    void invalidateOthers(String normalizedEmail, String currentSessionId);
}
