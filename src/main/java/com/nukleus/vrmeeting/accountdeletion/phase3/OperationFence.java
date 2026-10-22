package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.nukleus.vrmeeting.accountdeletion.AccountDeletionException;
import org.springframework.http.HttpStatus;
import javax.sql.DataSource;
import java.sql.*;

/**
 * Session-level PG lock, NOT a transaction across cloud I/O.
 * All synchronous HTTP ingress takes shared; request/cancel and permanent cleanup take exclusive.
 * Deliberately coarse: shared-record writers/admin routes also participate. Busy => retry, not bypass.
 */
public final class OperationFence {
    private static final long KEY = 0x4e5644454c455433L;
    private final DataSource dataSource;
    private final Phase3Properties properties;
    private final ThreadLocal<Lease> held = new ThreadLocal<>();
    public OperationFence(DataSource dataSource, Phase3Properties properties) {
        this.dataSource = dataSource; this.properties = properties;
    }
    public Lease acquire(boolean exclusive) {
        if (!properties.isEnabled() || held.get() != null) throw AccountDeletionException.integration();
        Connection c = null;
        try {
            c = dataSource.getConnection();
            c.setAutoCommit(true);
            try (var statement = c.prepareStatement("select pg_try_advisory_lock" +
                    (exclusive ? "" : "_shared") + "(?)")) {
                statement.setLong(1, KEY);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        throw new AccountDeletionException(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_OPERATION_BUSY");
                    }
                }
            }
            var lease = new Lease(c, exclusive);
            held.set(lease);
            return lease;
        } catch (SQLException e) {
            if (c != null) discard(c);
            throw AccountDeletionException.integration();
        } catch (RuntimeException e) {
            if (c != null) discard(c);
            throw e;
        }
    }
    public void requireHeld(boolean exclusive) {
        Lease lease = held.get();
        if (lease == null || (exclusive && !lease.exclusive)) throw AccountDeletionException.integration();
        lease.assertLive();
    }
    private static void discard(Connection c) {
        try { c.abort(Runnable::run); } catch (SQLException ignored) {}
        try { c.close(); } catch (SQLException ignored) {}
    }
    public final class Lease implements AutoCloseable {
        private final Connection connection;
        private final boolean exclusive;
        private boolean closed;
        private Lease(Connection connection, boolean exclusive) {
            this.connection = connection; this.exclusive = exclusive;
        }
        public void assertLive() {
            try {
                if (closed || !connection.isValid(2)) throw AccountDeletionException.integration();
            } catch (SQLException e) { throw AccountDeletionException.integration(); }
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            held.remove();
            try (var statement = connection.prepareStatement("select pg_advisory_unlock" +
                    (exclusive ? "" : "_shared") + "(?)")) {
                statement.setLong(1, KEY);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) throw new SQLException("Fence lost");
                }
            } catch (SQLException e) { discard(connection); return; }
            try { connection.close(); } catch (SQLException e) { discard(connection); }
        }
    }
}

