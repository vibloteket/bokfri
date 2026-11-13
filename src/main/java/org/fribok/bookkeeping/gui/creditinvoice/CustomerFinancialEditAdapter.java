package org.fribok.bookkeeping.gui.creditinvoice;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;

import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.FinancialValues;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerFinancialEditService;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerFinancialEditService.Kind;

/**
 * Selection-bound load/edit/preview/confirm flow; the GUI cannot mutate a persisted domain
 * snapshot.
 */
public final class CustomerFinancialEditAdapter {
    /**
     * Explicit UI target.
     *
     * @param companyId company migration identity
     * @param yearId selected year migration identity
     * @param kind document family
     * @param id stable SQL identity
     */
    public record Target(int companyId, int yearId, Kind kind, long id) {}

    /** Owner-bound original snapshot, never a mutable domain object. */
    public static final class Loaded {
        private final CustomerFinancialEditAdapter owner;
        private final NormalizedCustomerFinancialEditService.Snapshot snapshot;

        private Loaded(
                CustomerFinancialEditAdapter owner,
                NormalizedCustomerFinancialEditService.Snapshot snapshot) {
            this.owner = owner;
            this.snapshot = snapshot;
        }

        /**
         * @return immutable original display values
         */
        public NormalizedCustomerFinancialEditService.Data data() {
            return snapshot.data();
        }
    }

    /** Owner-bound, immutable proposed values; construction is private. */
    public static final class Preview {
        private final CustomerFinancialEditAdapter owner;
        private final NormalizedCustomerFinancialEditService.Plan plan;
        private Saved confirmed;

        private Preview(
                CustomerFinancialEditAdapter owner,
                NormalizedCustomerFinancialEditService.Plan plan) {
            this.owner = owner;
            this.plan = plan;
        }

        /**
         * @return original total
         */
        public BigDecimal before() {
            return plan.original().total();
        }

        /**
         * @return proposed total
         */
        public BigDecimal after() {
            return plan.total();
        }

        /**
         * @return immutable proposed dates and row amounts
         */
        public FinancialValues values() {
            return plan.proposed();
        }
    }

    /**
     * Confirmed commit notification, safe to replay without writing again.
     *
     * @param companyId company migration identity
     * @param yearId selected year migration identity
     * @param kind document family
     * @param id stable SQL identity
     * @param total verified stored total
     */
    public record Saved(int companyId, int yearId, Kind kind, long id, BigDecimal total) {}

    private final int company, year;
    private final Kind kind;
    private final long id;
    private final Object gate;
    private final Clock clock;
    private final CustomerDocumentTransactions transactions;

    CustomerFinancialEditAdapter(
            CustomerCreditDialogAdapter.Connections connections,
            Object gate,
            int company,
            int year,
            Kind kind,
            long id,
            Clock clock) {
        this.company = company;
        this.year = year;
        this.kind = java.util.Objects.requireNonNull(kind);
        this.id = id;
        this.gate = gate;
        this.clock = clock;
        this.transactions =
                new CustomerDocumentTransactions(connections, gate, company, year, clock);
    }

    /**
     * @return the immutable selection-qualified target
     */
    public Target target() {
        return new Target(company, year, kind, id);
    }

    /**
     * @return whether an unknown commit outcome blocks further requests
     */
    public boolean isBlocked() {
        return transactions.isBlocked();
    }

    /**
     * Loads original values in a dedicated read transaction.
     *
     * @return owner-bound original values
     * @throws SQLException for an invalid lease, target, format or storage error
     */
    public Loaded load() throws SQLException {
        synchronized (gate) {
            return transactions.run(
                    false,
                    c ->
                            new Loaded(
                                    this,
                                    new NormalizedCustomerFinancialEditService(c, clock)
                                            .load(company, year, kind, id)));
        }
    }

    /**
     * Validates a draft and calculates totals without writes.
     *
     * @param loaded original values from this adapter
     * @param values complete proposed dates and row amounts
     * @return owner-bound preview
     * @throws SQLException for foreign tokens, invalid context or storage errors
     * @throws IllegalArgumentException for stale or unrepresentable draft values
     */
    public Preview preview(Loaded loaded, FinancialValues values) throws SQLException {
        synchronized (gate) {
            if (loaded == null || loaded.owner != this)
                throw new SQLException("Foreign financial snapshot");
            return transactions.run(
                    false,
                    c ->
                            new Preview(
                                    this,
                                    new NormalizedCustomerFinancialEditService(c, clock)
                                            .plan(loaded.snapshot, values)));
        }
    }

    /**
     * Commits one confirmed preview; confirmed replay performs no further writes.
     *
     * @param preview preview from this adapter
     * @return confirmed selection-qualified result
     * @throws SQLException for invalid context, persistence or unknown-commit errors
     * @throws IllegalArgumentException if the source became stale
     */
    public Saved save(Preview preview) throws SQLException {
        synchronized (gate) {
            if (preview == null || preview.owner != this)
                throw new SQLException("Foreign financial preview");
            if (preview.confirmed != null) return preview.confirmed;
            var result =
                    transactions.run(
                            true,
                            c ->
                                    new NormalizedCustomerFinancialEditService(c, clock)
                                            .save(preview.plan));
            preview.confirmed =
                    new Saved(result.companyId(), year, result.kind(), result.id(), result.total());
            return preview.confirmed;
        }
    }
}
