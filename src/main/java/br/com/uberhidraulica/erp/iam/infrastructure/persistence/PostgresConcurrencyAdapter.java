package br.com.uberhidraulica.erp.iam.infrastructure.persistence;

import br.com.uberhidraulica.erp.iam.port.ConcurrencyPort;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;

@Component
public class PostgresConcurrencyAdapter implements ConcurrencyPort {
    private static final long BOOTSTRAP_LOCK = 8_364_001L;
    private static final long ACTIVE_OWNER_LOCK = 8_364_002L;
    private final EntityManager entityManager;
    private final DataSource dataSource;

    public PostgresConcurrencyAdapter(EntityManager entityManager, DataSource dataSource) {
        this.entityManager = entityManager;
        this.dataSource = dataSource;
    }
    @Override public void lockBootstrap() { lock(BOOTSTRAP_LOCK); }
    @Override public void lockActiveOwnerTransition() { lock(ACTIVE_OWNER_LOCK); }
    @Override public AutoCloseable lockLoginSession(String normalizedPrincipal) {
        try {
            Connection connection = dataSource.getConnection();
            try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_lock(hashtextextended(?, 8364003))")) {
                statement.setString(1, normalizedPrincipal);
                statement.execute();
            }
            return () -> unlockLogin(connection, normalizedPrincipal);
        } catch (SQLException exception) {
            throw new IllegalStateException("Não foi possível serializar a substituição da sessão", exception);
        }
    }
    private void lock(long key) { entityManager.createNativeQuery("select pg_advisory_xact_lock(:key)").setParameter("key", key).getSingleResult(); }

    private static void unlockLogin(Connection connection, String principal) throws SQLException {
        try (connection; PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(hashtextextended(?, 8364003))")) {
            statement.setString(1, principal);
            statement.execute();
        }
    }
}
