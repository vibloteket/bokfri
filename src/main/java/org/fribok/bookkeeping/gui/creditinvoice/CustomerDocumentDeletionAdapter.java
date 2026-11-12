package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.service.invoice.NormalizedCustomerDocumentDeletionService;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerDocumentDeletionService.Kind;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerDocumentDeletionService.Plan;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

/** Selection-bound preview/confirm deletion with dedicated sessions and confirmed-result replay. */
public final class CustomerDocumentDeletionAdapter {
    public static final class Preview {
        private final CustomerDocumentDeletionAdapter owner;
        private final Plan plan;
        private Saved confirmed;
        private Preview(CustomerDocumentDeletionAdapter owner, Plan plan) { this.owner = owner; this.plan = plan; }
        public Plan plan() { return plan; }
    }
    public record Saved(int companyId, int yearId, Kind kind, long id, List<Long> detachedOrderIds) {
        public Saved { detachedOrderIds = List.copyOf(detachedOrderIds); }
    }
    private final int company, year;
    private final long id;
    private final Kind kind;
    private final Object gate;
    private final CustomerDocumentTransactions transactions;
    CustomerDocumentDeletionAdapter(CustomerCreditDialogAdapter.Connections connections, Object gate,
                                    int company, int year, Kind kind, long id, Clock clock) {
        this.company = company; this.year = year; this.kind = java.util.Objects.requireNonNull(kind); this.id = id;
        this.gate = gate; transactions = new CustomerDocumentTransactions(connections, gate, company, year, clock);
    }
    public int yearId() { return year; }
    public boolean isBlocked() { return transactions.isBlocked(); }
    public Preview preview() throws SQLException {
        synchronized (gate) {
            return transactions.run(false, connection -> new Preview(this,
                    new NormalizedCustomerDocumentDeletionService(connection).plan(company, kind, id)));
        }
    }
    public Saved delete(Preview preview) throws SQLException {
        synchronized (gate) {
            if (preview == null || preview.owner != this) throw new SQLException("Foreign deletion preview");
            if (preview.confirmed != null) return preview.confirmed;
            var deleted = transactions.run(true, connection -> new NormalizedCustomerDocumentDeletionService(connection).delete(preview.plan));
            preview.confirmed = new Saved(deleted.companyId(), year, deleted.kind(), deleted.id(), deleted.detachedOrderIds());
            return preview.confirmed;
        }
    }
}
