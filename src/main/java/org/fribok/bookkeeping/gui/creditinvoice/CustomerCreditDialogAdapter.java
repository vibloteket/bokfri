package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.NormalizedSchemaMigrations;
import org.fribok.bookkeeping.dataformat.SchemaMigrationRunner;
import org.fribok.bookkeeping.service.creditinvoice.NormalizedCustomerCreditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * GUI-facing credit request boundary with explicit selection and dedicated short-lived sessions.
 * Does not enable normalized GUI startup, open legacy storage or implicitly migrate a schema.
 */
public final class CustomerCreditDialogAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(CustomerCreditDialogAdapter.class);

    /** Supplies a fresh, exclusively owned JDBC connection in auto-commit mode for each action. */
    @FunctionalInterface
    public interface Connections { Connection open() throws SQLException; }

    private final Connections connections;
    private final Object transactionGate;
    private final int company, year;
    private final long invoiceId;
    private final Clock clock;
    private volatile boolean uncertainCommit;

    /**
     * Binds explicit identities and the application-owned gate shared by every writer for this database.
     * The gate is not a concurrent database allocator; all application writes must obey it.
     */
    public CustomerCreditDialogAdapter(Connections connections, Object transactionGate,
            int company, int year, long invoiceId, Clock clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.transactionGate = Objects.requireNonNull(transactionGate, "transaction gate");
        this.company = company; this.year = year; this.invoiceId = invoiceId;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Immutable presentation data plus a private workflow token; cannot carry arbitrary row edits. */
    public static final class Preview {
        private final CustomerCreditDialogAdapter owner;
        private final NormalizedCustomerCreditService.Plan plan;
        private final BigDecimal amount;
        private Created created;
        private Preview(CustomerCreditDialogAdapter owner,
                NormalizedCustomerCreditService.Plan plan, BigDecimal amount) {
            this.owner = owner; this.plan = plan; this.amount = amount;
        }
        /** Original invoice's unambiguous business number. */
        public int invoiceNumber() { return plan.credit().getCreditingNr(); }
        /** Proposed credit number, revalidated at save. */
        public int creditNumber() { return plan.credit().getNumber(); }
        /** Requested date. */
        public LocalDate date() { return plan.credit().getLocalDate(); }
        /** Requested value at existing invoice-balance precision. */
        public BigDecimal amount() { return amount; }
        /** Available balance at preview time. */
        public BigDecimal balance() { return plan.remainingBalance(); }
    }

    /** Committed identity for explicit caller-owned refresh/notification, never a legacy cache mutation. */
    public record Created(long id, int legacyId, int number) { }

    /** A commit exception has an unknown durable outcome; close and refresh, never blindly retry. */
    public static final class CommitOutcomeUnknownException extends SQLException {
        private CommitOutcomeUnknownException(SQLException cause) {
            super("Credit commit could not be confirmed; close and refresh before another request", cause);
        }
    }

    /** Default request date from the explicit clock in the application's Swedish business zone. */
    public LocalDate defaultDate() { return LocalDate.now(clock.withZone(ZoneId.of("Europe/Stockholm"))); }

    /** Whether a failed commit requires a new externally refreshed context instead of automatic retries. */
    public boolean hasUncertainCommit() { return uncertainCommit; }

    /** Read-only preview in a fresh session; null amount explicitly requests the remaining balance. */
    public Preview preview(LocalDate date, BigDecimal amount) throws SQLException {
        synchronized (transactionGate) {
            requireKnownOutcome();
            return session(false, connection -> {
                var plan = new NormalizedCustomerCreditService(connection, clock)
                        .plan(company, year, invoiceId, date, amount);
                return new Preview(this, plan, (amount == null ? plan.remainingBalance() : amount)
                        .setScale(2, RoundingMode.HALF_UP));
            });
        }
    }

    /**
     * Commits a fresh service result once. Repeated saves of the same preview return the committed
     * identity without another database write; stale foreign-context previews are not accepted.
     */
    public Created save(Preview preview) throws SQLException {
        synchronized (transactionGate) {
            Objects.requireNonNull(preview, "preview");
            if (preview.owner != this) throw new IllegalArgumentException("Preview belongs to another dialog context");
            if (preview.created != null) return preview.created;
            requireKnownOutcome();
            Created created = session(true, connection -> {
                var result = new NormalizedCustomerCreditService(connection, clock).create(preview.plan);
                return new Created(result.id(), result.legacyId(), result.credit().getNumber());
            });
            preview.created = created;
            return created;
        }
    }

    private void requireKnownOutcome() throws SQLException {
        if (uncertainCommit) throw new SQLException("Previous commit outcome is unknown; refresh the owning context");
    }

    @FunctionalInterface
    private interface Action<T> { T run(Connection connection) throws SQLException; }

    /** Called only with the shared gate held; no second lock ordering is introduced. */
    private <T> T session(boolean write, Action<T> action) throws SQLException {
        Connection connection = connections.open();
        boolean transaction = false;
        T result;
        try {
            if (!connection.getAutoCommit()) {
                throw new SQLException("GUI factory must supply a fresh auto-commit session");
            }
            connection.setAutoCommit(false); transaction = true;
            validateNormalizedConnection(connection, clock);
            result = action.run(connection);
            if (write) {
                try { connection.commit(); }
                catch (SQLException failure) {
                    uncertainCommit = true;
                    throw new CommitOutcomeUnknownException(failure);
                }
            } else connection.rollback();
        } catch (SQLException | RuntimeException | Error failure) {
            if (transaction) {
                try { connection.rollback(); }
                catch (SQLException rollback) { failure.addSuppressed(rollback); }
            }
            try { connection.close(); }
            catch (SQLException close) { failure.addSuppressed(close); }
            throw failure;
        }
        try { connection.close(); }
        catch (SQLException close) {
            // A committed write must not be presented as failed or retried because cleanup failed.
            if (write) LOG.warn("Credit committed but dedicated GUI session close failed", close);
            else throw close;
        }
        return result;
    }
    /** Shared read-only guard for explicitly owned normalized GUI sessions; never migrates. */
    static void validateNormalizedConnection(Connection connection, Clock clock) throws SQLException {
        if (DataFormatManager.detect(connection) != DataFormatManager.NORMALIZED_DATA_FORMAT_VERSION) {
            throw new SQLException("Credit dialog requires normalized format 3; no migration is performed");
        }
        final java.util.List<Integer> installed;
        try {
            installed = new SchemaMigrationRunner(clock)
                    .validateInstalled(connection, NormalizedSchemaMigrations.load());
        } catch (java.io.IOException resources) {
            throw new SQLException("Cannot verify packaged normalized schema resources", resources);
        }
        if (!installed.contains(12)) {
            throw new SQLException("Credit workflow requires normalized schema V12 or later");
        }
    }

}
