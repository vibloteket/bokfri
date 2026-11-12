package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;

/** Shared dedicated-session contract for metadata and deletion; never owns application shutdown. */
final class CustomerDocumentTransactions {
    private static final Logger LOG = LoggerFactory.getLogger(CustomerDocumentTransactions.class);
    private final CustomerCreditDialogAdapter.Connections connections;
    private final Object gate;
    private final int company, year;
    private final Clock clock;
    private volatile boolean blocked;
    CustomerDocumentTransactions(CustomerCreditDialogAdapter.Connections connections, Object gate, int company, int year, Clock clock) {
        this.connections = Objects.requireNonNull(connections); this.gate = Objects.requireNonNull(gate);
        this.company = company; this.year = year; this.clock = Objects.requireNonNull(clock);
    }
    boolean isBlocked() { return blocked; }
    <T> T run(boolean write, Work<T> work) throws SQLException {
        synchronized (gate) {
            if (blocked) throw new SQLException("Previous commit outcome is unknown; refresh the application context before another request");
            Connection connection = connections.open(); boolean committed = false, committing = false;
            Throwable failure = null;
            try {
                if (!connection.getAutoCommit()) throw new SQLException("GUI factory must supply a fresh session");
                connection.setAutoCommit(false);
                CustomerCreditDialogAdapter.validateNormalizedConnection(connection, clock);
                var accounting = new NormalizedAccountingStore(connection, clock);
                var selected = accounting.getCompanies().stream().filter(c -> c.getId() == company)
                        .findFirst().orElseThrow(() -> new SQLException("Selected company no longer exists"));
                accounting.setCurrentCompany(selected);
                if (accounting.getYears().stream().noneMatch(y -> y.getId() == year))
                    throw new SQLException("Selected year does not belong to company");
                T result = work.run(connection);
                if (write) { committing = true; connection.commit(); committed = true; }
                else connection.rollback();
                return result;
            } catch (SQLException | RuntimeException | Error error) {
                failure = error;
                if (committing && !committed) blocked = true;
                try { connection.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
                throw error;
            } finally {
                try { connection.close(); }
                catch (SQLException close) {
                    if (failure != null) failure.addSuppressed(close);
                    else if (committed) LOG.warn("Document change committed but session close failed; do not retry write", close);
                    else throw close;
                }
            }
        }
    }
    @FunctionalInterface interface Work<T> { T run(Connection connection) throws SQLException; }
}
