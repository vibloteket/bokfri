package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.Metadata;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerDocumentService;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;

/** Dedicated, gated metadata-only transactions. No financial hydration, implicit selection or migration. */
public final class CustomerDocumentMetadataAdapter {
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
    private final CustomerDocumentTransactions transactions;
    private final Object gate;
    private final int company, year;
    private final long id;
    private final Kind kind;

    CustomerDocumentMetadataAdapter(CustomerCreditDialogAdapter.Connections connections, Object gate,
                                    int company, int year, Kind kind, long id, Clock clock) {
        this.transactions = new CustomerDocumentTransactions(connections, gate, company, year, clock);
        this.gate = Objects.requireNonNull(gate);
        this.company = company; this.year = year; this.kind = Objects.requireNonNull(kind); this.id = id;
    }
    public boolean isBlocked() { return transactions.isBlocked(); }
    public Loaded load() throws SQLException {
        synchronized (gate) {
            return transactions.run(false, connection -> {
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
            Saved saved = transactions.run(true, connection -> {
                var service = new NormalizedCustomerDocumentService(connection);
                if (kind == Kind.INVOICE) service.updateInvoiceMetadata(company, id, loaded.values, replacement);
                else service.updateCreditMetadata(company, id, loaded.values, replacement);
                return new Saved(company, year, kind, id, replacement);
            });
            loaded.confirmed = saved;
            return saved;
        }
    }
}
