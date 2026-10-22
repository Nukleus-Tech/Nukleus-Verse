package com.nukleus.vrmeeting.accountdeletion;

import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;
import java.util.Optional;

@Component
public class AccountDeletionWorkerLock {
    private final DataSource dataSource;
    public AccountDeletionWorkerLock(DataSource dataSource) { this.dataSource = dataSource; }

    // Dedicated connection in autocommit mode: session lock, NOT a long-running transaction.
    // Reserve this advisory-key namespace for account deletion in all deployed services.
    public Optional<Handle> tryAcquire(long requestId) throws SQLException {
        Connection connection = dataSource.getConnection();
        long key = requestId ^ 0x4E554B0000000000L;
        try {
            connection.setAutoCommit(true);
            try (PreparedStatement s = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                s.setQueryTimeout(10); s.setLong(1, key);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next() && r.getBoolean(1)) return Optional.of(new Handle(connection, key));
                }
            }
            connection.close();
            return Optional.empty();
        } catch (SQLException e) {
            try { connection.abort(Runnable::run); } finally { connection.close(); }
            throw e;
        }
    }
    public static class Handle implements AutoCloseable {
        private final Connection connection;
        private final long key;
        Handle(Connection connection, long key) { this.connection = connection; this.key = key; }
        public void assertLive() throws SQLException {
            if (!connection.isValid(5)) throw new SQLException("Worker lock connection lost");
        }
        @Override public void close() throws SQLException {
            try (PreparedStatement s = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                s.setQueryTimeout(10); s.setLong(1, key);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next() || !r.getBoolean(1)) throw new SQLException("Worker lock lost");
                }
            } catch (SQLException e) {
                // Never return a session with a possibly held lock to the pool.
                connection.abort(Runnable::run);
                throw e;
            } finally { connection.close(); }
        }
    }
}
