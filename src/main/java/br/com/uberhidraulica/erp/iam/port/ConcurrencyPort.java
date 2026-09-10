package br.com.uberhidraulica.erp.iam.port;

public interface ConcurrencyPort {
    void lockBootstrap();
    void lockActiveOwnerTransition();
    AutoCloseable lockLoginSession(String normalizedPrincipal);
}
