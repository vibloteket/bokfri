package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.Metadata;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;

/** Dedicated, gated metadata-only transactions. No financial hydration, implicit selection or migration. */
public final class CustomerDocumentMetadataAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(CustomerDocumentMetadataAdapter.class);
    public enum Kind { INVOICE, CREDIT }
    public record Target(int companyId, int yearId, Kind kind, long id) { }
    public Target target() { return new Target(company, year, kind, id); }
    public record Saved(int companyId, int yearId, Kind kind, long id, Metadata values) { }
    public static final class Loaded {
        private final CustomerDocumentMetadataAdapter owner;
        private final Metadata values;
        private Saved confirmed;
        private Loaded(CustomerDocumentMetadataAdapter owner, Metadata values) { this.owner = owner; this.values = values; }
        public Metadata values() { return values; }
    }
    private final CustomerCreditDialogAdapter.Connections connections;
    private final Object gate;
    private final int company, year;
    private final long id;
    private final Kind kind;
    private final Clock clock;
    private volatile boolean blocked;

    CustomerDocumentMetadataAdapter(CustomerCreditDialogAdapter.Connections connections, Object gate,
                                    int company, int year, Kind kind, long id, Clock clock) {
        this.connections = Objects.requireNonNull(connections); this.gate = Objects.requireNonNull(gate);
        this.company = company; this.year = year; this.kind = Objects.requireNonNull(kind); this.id = id;
        this.clock = Objects.requireNonNull(clock);
    }
    public boolean isBlocked() { return blocked; }
    public Loaded load() throws SQLException {
        synchronized (gate) {
            return transaction(false, connection -> {
                var store = new NormalizedInvoiceStore(connection);
                var values = kind == Kind.INVOICE ? store.findInvoiceMetadata(company, id) : store.findCreditInvoiceMetadata(company, id);
                return new Loaded(this, values.orElseThrow(() -> new SQLException("Missing or foreign document metadata")));
            });
        }
    }
    public Saved save(Loaded loaded, Metadata replacement) throws SQLException {
        Objects.requireNonNull(replacement, "replacement");
        synchronized (gate) {
            if (loaded == null || loaded.owner != this) throw new SQLException("Foreign metadata snapshot");
            if (loaded.confirmed != null) {
                if (!loaded.confirmed.values().equals(replacement)) throw new SQLException("Metadata snapshot already saved; reload before further edits");
                return loaded.confirmed;
            }
            if (replacement.reminderCount() < 0) throw new IllegalArgumentException("Negative reminder count");
            Saved saved = transaction(true, connection -> {
                var service = new NormalizedCustomerDocumentService(connection);
                if (kind == Kind.INVOICE) service.updateInvoiceMetadata(company, id, loaded.values, replacement);
                else service.updateCreditMetadata(company, id, loaded.values, replacement);
                return new Saved(company, year, kind, id, replacement);
            });
            loaded.confirmed = saved;
            return saved;
        }
    }
    private <T> T transaction(boolean write, Work<T> work) throws SQLException {
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
                else if (committed) LOG.warn("Metadata committed but session close failed; do not retry save", close);
                else throw close;
            }
        }
    }
    @FunctionalInterface private interface Work<T> { T run(Connection connection) throws SQLException; }
}
