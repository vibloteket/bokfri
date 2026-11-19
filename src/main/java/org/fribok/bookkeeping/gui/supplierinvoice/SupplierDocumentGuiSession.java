package org.fribok.bookkeeping.gui.supplierinvoice;

import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedOutpaymentStore;
import org.fribok.bookkeeping.dataformat.NormalizedSchemaMigrations;
import org.fribok.bookkeeping.dataformat.NormalizedSupplierInvoiceStore;
import org.fribok.bookkeeping.dataformat.SchemaMigrationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit normalized supplier company/year binding with dedicated sessions and a shared writer
 * gate. Does not change GUI startup, own application shutdown, or consult legacy selected objects.
 */
public final class SupplierDocumentGuiSession implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SupplierDocumentGuiSession.class);

    /** Supplies a fresh, exclusively owned auto-commit connection for each action. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    /** Immutable selected-company/year presentation, loaded without changing stored selection. */
    public record Selection(
            int companyId, String companyName, int yearId, LocalDate from, LocalDate to) {}

    final Object gate;
    final Clock clock;
    private final Connections connections;
    private final int company;
    private final int year;
    private volatile boolean active = true;
    private volatile boolean blocked;

    /**
     * Binds explicit identities to the application-owned gate obeyed by every writer for this
     * database. Construction opens no database. A commit error blocks all adapters leased from this
     * selection.
     */
    public SupplierDocumentGuiSession(
            Connections connections, Object gate, int company, int year, Clock clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.gate = Objects.requireNonNull(gate, "shared transaction gate");
        this.company = company;
        this.year = year;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Explicit company migration identity, never a legacy table-row position. */
    public int companyId() {
        return company;
    }

    /** Explicit accounting-year migration identity. */
    public int yearId() {
        return year;
    }

    /** Whether this selection may start another request. */
    public boolean isActive() {
        return active;
    }

    /** An unknown commit outcome requires externally refreshed context, never blind retry. */
    public boolean isBlocked() {
        return blocked;
    }

    /** Loads company/year labels and initial journal dates in a fresh read-only transaction. */
    public Selection selection() throws SQLException {
        return run(
                false,
                (connection, selected, selectedYear) ->
                        new Selection(
                                company,
                                selected.getName(),
                                year,
                                selectedYear.getLocalFrom(),
                                selectedYear.getLocalTo()));
    }

    /** Immutable company-wide supplier document presentation, keyed only by stable SQL identity. */
    public record Document(
            long id,
            Integer number,
            LocalDate date,
            String supplier,
            String currency,
            BigDecimal total,
            BigDecimal balance,
            boolean entered,
            boolean bankExported,
            Integer creditingNumber) {}

    /**
     * Both document families from one selected-company read transaction; year supplies posting
     * context.
     */
    public record Documents(
            int companyId,
            int yearId,
            String companyName,
            LocalDate from,
            LocalDate to,
            List<Document> invoices,
            List<Document> credits) {
        public Documents {
            invoices = List.copyOf(invoices);
            credits = List.copyOf(credits);
        }
    }

    /**
     * Loads company-wide invoice/credit tables without legacy caches or stored-selection changes.
     * Ambiguous or absent invoice numbers have no balance: payments/credits cannot safely be
     * assigned.
     */
    public Documents documents() throws SQLException {
        return run(
                false,
                (connection, selected, selectedYear) -> {
                    var store = new NormalizedSupplierInvoiceStore(connection);
                    var payments = new NormalizedOutpaymentStore(connection);
                    var stored = store.listSupplierInvoices(company);
                    Map<Integer, Integer> counts = new HashMap<>();
                    for (var item : stored) {
                        counts.merge(item.document().getNumber(), 1, Integer::sum);
                    }
                    List<Document> invoices = new ArrayList<>();
                    List<Document> credits = new ArrayList<>();
                    for (var item : stored) {
                        var invoice = item.document();
                        BigDecimal total = SSSupplierInvoiceMath.getTotalSum(invoice);
                        BigDecimal balance = null;
                        if (invoice.getNumber() != null && counts.get(invoice.getNumber()) == 1) {
                            balance =
                                    total.subtract(
                                                    store.supplierCreditInvoiceSum(
                                                            company, invoice.getNumber()))
                                            .subtract(
                                                    payments.outpaymentSum(
                                                            company, invoice.getNumber()));
                        }
                        invoices.add(document(item.id(), invoice, total, balance, null));
                    }
                    for (var item : store.listSupplierCreditInvoices(company)) {
                        var credit = item.document();
                        credits.add(
                                document(
                                        item.id(),
                                        credit,
                                        SSSupplierInvoiceMath.getTotalSum(credit),
                                        null,
                                        credit.getCreditingNr()));
                    }
                    return new Documents(
                            company,
                            year,
                            selected.getName(),
                            selectedYear.getLocalFrom(),
                            selectedYear.getLocalTo(),
                            invoices,
                            credits);
                });
    }

    private static Document document(
            long id,
            SSSupplierInvoice invoice,
            BigDecimal total,
            BigDecimal balance,
            Integer crediting) {
        return new Document(
                id,
                invoice.getNumber(),
                invoice.getLocalDate(),
                invoice.getSupplierName(),
                invoice.getCurrency() == null ? null : invoice.getCurrency().getName(),
                total,
                balance,
                invoice.isEntered(),
                invoice.isBGCEntered(),
                crediting);
    }

    /**
     * Leases existing-row amount editing for this exact selection, family and stable SQL identity.
     */
    public SupplierRowAmountsAdapter rowAmounts(
            org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierRowAmountsService.Kind
                    kind,
            long id)
            throws SQLException {
        requireAvailable();
        return new SupplierRowAmountsAdapter(this, kind, id);
    }

    /** Leases description-only editing for existing supplier rows in this exact selection. */
    public SupplierRowDescriptionAdapter rowDescriptions(
            org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService
                            .Kind
                    kind,
            long id)
            throws SQLException {
        requireAvailable();
        return new SupplierRowDescriptionAdapter(this, kind, id);
    }

    /** Leases payment-term-only editing; stored dates are never recalculated. */
    public SupplierPaymentTermAdapter paymentTerm(
            org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDatesService.Kind kind,
            long id)
            throws SQLException {
        requireAvailable();
        return new SupplierPaymentTermAdapter(this, kind, id);
    }

    /** Leases row allocation editing with this selected year and company catalogue. */
    public SupplierRowAccountingAdapter rowAccounting(
            org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService
                            .Kind
                    kind,
            long id)
            throws SQLException {
        requireAvailable();
        return new SupplierRowAccountingAdapter(this, kind, id);
    }

    /** Leases explicit supplier header date editing for this exact selection and SQL identity. */
    public SupplierDatesAdapter dates(
            org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDatesService.Kind kind,
            long id)
            throws SQLException {
        requireAvailable();
        return new SupplierDatesAdapter(this, kind, id);
    }

    /** Leases explicit supplier VAT/rounding editing for this selection and SQL identity. */
    public SupplierTaxRoundingAdapter taxRounding(
            org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierTaxRoundingService.Kind
                    kind,
            long id)
            throws SQLException {
        requireAvailable();
        return new SupplierTaxRoundingAdapter(this, kind, id);
    }

    /** Leases explicit stored exchange-rate editing for this selection and SQL identity. */
    public SupplierExchangeRateAdapter exchangeRate(
            org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierExchangeRateService
                            .Kind
                    kind,
            long id)
            throws SQLException {
        requireAvailable();
        return new SupplierExchangeRateAdapter(this, kind, id);
    }

    /** Leases explicit supplier currency-code/rate editing for this selection and SQL identity. */
    public SupplierCurrencyAdapter currency(
            org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierExchangeRateService
                            .Kind
                    kind,
            long id)
            throws SQLException {
        requireAvailable();
        return new SupplierCurrencyAdapter(this, kind, id);
    }

    /** Leases bounded metadata editing for this exact selection, family and stable SQL identity. */
    public SupplierDocumentMetadataAdapter metadata(
            org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService
                            .Kind
                    kind,
            long id)
            throws SQLException {
        requireAvailable();
        return new SupplierDocumentMetadataAdapter(this, kind, id);
    }

    /** Leases confirmed deletion for this exact selection, family and stable SQL identity. */
    public SupplierDocumentDeletionAdapter deletion(
            org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentDeletionService
                            .Kind
                    kind,
            long id)
            throws SQLException {
        requireAvailable();
        return new SupplierDocumentDeletionAdapter(this, kind, id);
    }

    /** Leases invoice or credit journal planning/posting for this exact selection. */
    public SupplierJournalAdapter journal(SupplierJournalAdapter.Kind kind) throws SQLException {
        requireAvailable();
        return new SupplierJournalAdapter(this, kind);
    }

    void requireAvailable() throws SQLException {
        if (!active) {
            throw new SQLException("GUI selection was replaced or closed");
        }
        if (blocked) {
            throw new SQLException(
                    "Previous commit outcome is unknown; refresh the application context before"
                            + " another request");
        }
    }

    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection, SSNewCompany selected, SSNewAccountingYear selectedYear)
                throws SQLException;
    }

    <T> T run(boolean write, Work<T> work) throws SQLException {
        synchronized (gate) {
            requireAvailable();
            Connection connection = connections.open();
            boolean transaction = false;
            boolean committing = false;
            boolean committed = false;
            Throwable failure = null;
            try {
                requireAvailable();
                if (!connection.getAutoCommit()) {
                    throw new SQLException("GUI factory must supply a fresh auto-commit session");
                }
                connection.setAutoCommit(false);
                transaction = true;
                validate(connection);
                var accounting = new NormalizedAccountingStore(connection, clock);
                var selected =
                        accounting.getCompanies().stream()
                                .filter(c -> c.getId() == company)
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new SQLException(
                                                        "Selected company no longer exists"));
                accounting.setCurrentCompany(selected);
                var selectedYear =
                        accounting.getYears().stream()
                                .filter(y -> y.getId() == year)
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new SQLException(
                                                        "Selected year does not belong to"
                                                                + " company"));
                T result = work.run(connection, selected, selectedYear);
                if (write) {
                    // Once accepted, this write remains bound to the original identities even if
                    // selection closes.
                    committing = true;
                    connection.commit();
                    committed = true;
                } else {
                    requireAvailable();
                    connection.rollback();
                }
                return result;
            } catch (SQLException | RuntimeException | Error error) {
                failure = error;
                if (committing && !committed) {
                    blocked = true;
                }
                if (transaction) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollback) {
                        error.addSuppressed(rollback);
                    }
                }
                throw error;
            } finally {
                try {
                    connection.close();
                } catch (SQLException close) {
                    if (failure != null) {
                        failure.addSuppressed(close);
                    } else if (committed) {
                        LOG.warn(
                                "Supplier document change committed but session close failed; do"
                                        + " not retry",
                                close);
                    } else {
                        throw close;
                    }
                }
            }
        }
    }

    private void validate(Connection connection) throws SQLException {
        if (DataFormatManager.detect(connection)
                != DataFormatManager.NORMALIZED_DATA_FORMAT_VERSION) {
            throw new SQLException(
                    "Supplier dialog requires normalized format 3; no migration is performed");
        }
        try {
            var migrations = NormalizedSchemaMigrations.load();
            var installed =
                    new SchemaMigrationRunner(clock).validateInstalled(connection, migrations);
            if (installed.size() != migrations.size() || !installed.contains(21)) {
                throw new SQLException(
                        "Supplier dialog requires the complete current normalized schema, including"
                                + " V21");
            }
        } catch (java.io.IOException resources) {
            throw new SQLException("Cannot verify packaged normalized schema resources", resources);
        }
    }

    /**
     * Invalidates future leases/actions; never shuts down the database or cancels accepted writes.
     */
    @Override
    public void close() {
        active = false;
    }
}
