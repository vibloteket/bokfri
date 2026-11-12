package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable GUI company/year binding with explicit dedicated-session ownership and a shared writer gate.
 * Replacing/closing the binding invalidates future leases; accepted in-flight work remains bound to
 * its original identities. No legacy selected company/year or table-row identity is consulted.
 */
public final class CustomerDocumentGuiSession implements AutoCloseable {
    private final CustomerCreditDialogAdapter.Connections connections;
    private final Object gate;
    private final Clock clock;
    private final int company, year;
    private volatile boolean active = true;

    /** Creates a selection binding without opening a database or changing application startup. */
    public CustomerDocumentGuiSession(CustomerCreditDialogAdapter.Connections connections, Object gate,
            int company, int year, Clock clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.gate = Objects.requireNonNull(gate, "shared transaction gate");
        this.clock = Objects.requireNonNull(clock, "clock"); this.company = company; this.year = year;
    }

    /** Explicit selected company migration identity. */
    public int companyId() { return company; }
    /** Explicit selected accounting-year migration identity. */
    public int yearId() { return year; }
    /** Whether this selection can start another database action. */
    public boolean isActive() { return active; }

    /** Stable source identity and immutable presentation values; never a legacy table-row object. */
    public record Source(long id, int legacyId, int number, String customer, LocalDate date) { }
    /** Freshly validated selection and booked-source list, suitable for a background GUI load. */
    public record Sources(int companyId, String companyName, int yearId, LocalDate from, LocalDate to,
                          List<Source> invoices) {
        public Sources { invoices = List.copyOf(invoices); }
    }

    /**
     * Loads unambiguously numbered booked non-cash sources in the selected company using a read-only
     * transaction. Available balance and complete source validity are rechecked by credit preview.
     */
    public Sources sources() throws SQLException {
        synchronized (gate) {
            try (Connection connection = open()) {
                if (!connection.getAutoCommit()) throw new SQLException("GUI factory must supply a fresh session");
                connection.setAutoCommit(false);
                try {
                    CustomerCreditDialogAdapter.validateNormalizedConnection(connection, clock);
                    var accounting = new NormalizedAccountingStore(connection, clock);
                    var selectedCompany = accounting.getCompanies().stream().filter(c -> c.getId() == company)
                            .findFirst().orElseThrow(() -> new SQLException("Selected company no longer exists"));
                    accounting.setCurrentCompany(selectedCompany);
                    var selectedYear = accounting.getYears().stream().filter(y -> y.getId() == year)
                            .findFirst().orElseThrow(() -> new SQLException("Selected year does not belong to company"));
                    var stored = new NormalizedInvoiceStore(connection).listInvoices(company);
                    Map<Integer, Integer> counts = new HashMap<>();
                    for (var item : stored) counts.merge(item.document().getNumber(), 1, Integer::sum);
                    List<Source> invoices = stored.stream()
                            .filter(item -> item.document().isEntered() && item.document().getNumber() != null)
                            .filter(item -> counts.get(item.document().getNumber()) == 1)
                            .filter(item -> item.document().getType() != SSInvoiceType.CASH)
                            .map(item -> new Source(item.id(), item.legacyId(), item.document().getNumber(),
                                    item.document().getCustomerName(), item.document().getLocalDate())).toList();
                    requireActive(); connection.rollback();
                    return new Sources(company, selectedCompany.getName(), year,
                            selectedYear.getLocalFrom(), selectedYear.getLocalTo(), invoices);
                } catch (SQLException | RuntimeException | Error failure) {
                    try { connection.rollback(); }
                    catch (SQLException rollback) { failure.addSuppressed(rollback); }
                    throw failure;
                }
            }
        }
    }

    /** Immutable table values; no hydrated mutable domain objects escape the session. */
    public record Document(long id, Integer number, LocalDate date, String customer, String currency,
                           java.math.BigDecimal total, java.math.BigDecimal balance,
                           boolean entered, Integer creditingNumber) { }
    public record Documents(int companyId, int yearId, String companyName, LocalDate from, LocalDate to,
                            List<Document> invoices, List<Document> credits) {
        public Documents { invoices = List.copyOf(invoices); credits = List.copyOf(credits); }
    }

    /**
     * Both company-wide document tables in one gated read transaction. The selected year supplies
     * posting context, not a document date filter. Ambiguous/null numbers have no invoice balance:
     * payments and credits cannot safely be assigned to one such document.
     */
    public Documents documents() throws SQLException {
        synchronized (gate) {
            try (Connection connection = open()) {
                if (!connection.getAutoCommit()) throw new SQLException("GUI factory must supply a fresh session");
                connection.setAutoCommit(false);
                try {
                    CustomerCreditDialogAdapter.validateNormalizedConnection(connection, clock);
                    var accounting = new NormalizedAccountingStore(connection, clock);
                    var selectedCompany = accounting.getCompanies().stream().filter(c -> c.getId() == company)
                            .findFirst().orElseThrow(() -> new SQLException("Selected company no longer exists"));
                    accounting.setCurrentCompany(selectedCompany);
                    var selectedYear = accounting.getYears().stream().filter(y -> y.getId() == year)
                            .findFirst().orElseThrow(() -> new SQLException("Selected year does not belong to company"));
                    var store = new NormalizedInvoiceStore(connection);
                    var stored = store.listInvoices(company);
                    Map<Integer, Integer> counts = new HashMap<>();
                    for (var item : stored) counts.merge(item.document().getNumber(), 1, Integer::sum);
                    List<Document> invoices = new java.util.ArrayList<>(), credits = new java.util.ArrayList<>();
                    boolean rounding = selectedCompany.isRoundingOff();
                    for (var item : stored) {
                        var invoice = item.document();
                        var total = se.swedsoft.bookkeeping.calc.math.SSSaleMath.getTotalSum(invoice, rounding);
                        java.math.BigDecimal balance = null;
                        if (invoice.getType() == SSInvoiceType.CASH) balance = java.math.BigDecimal.ZERO;
                        else if (invoice.getNumber() != null && counts.get(invoice.getNumber()) == 1)
                            balance = total.subtract(store.creditInvoiceSum(company, invoice.getNumber(), rounding))
                                    .subtract(store.inpaymentSum(company, invoice.getNumber()))
                                    .setScale(2, java.math.RoundingMode.HALF_UP);
                        invoices.add(document(item.id(), invoice, total, balance, null));
                    }
                    for (var item : store.listCreditInvoices(company)) {
                        var credit = item.document();
                        credits.add(document(item.id(), credit,
                                se.swedsoft.bookkeeping.calc.math.SSSaleMath.getTotalSum(credit, rounding),
                                null, credit.getCreditingNr()));
                    }
                    requireActive(); connection.rollback();
                    return new Documents(company, year, selectedCompany.getName(), selectedYear.getLocalFrom(),
                            selectedYear.getLocalTo(), invoices, credits);
                } catch (SQLException | RuntimeException | Error failure) {
                    try { connection.rollback(); }
                    catch (SQLException rollback) { failure.addSuppressed(rollback); }
                    throw failure;
                }
            }
        }
    }

    private static Document document(long id, se.swedsoft.bookkeeping.data.SSInvoice invoice,
                                     java.math.BigDecimal total, java.math.BigDecimal balance, Integer crediting) {
        return new Document(id, invoice.getNumber(), invoice.getLocalDate(), invoice.getCustomerName(),
                invoice.getCurrency() == null ? null : invoice.getCurrency().getName(), total, balance,
                invoice.isEntered(), crediting);
    }

    /** Explicit whole-journal planning/posting for this exact company/year selection. */
    public CustomerJournalAdapter journal(CustomerJournalAdapter.Kind kind) throws SQLException {
        requireActive();
        return new CustomerJournalAdapter(this::open, gate, company, year, kind, clock);
    }

    /** Preview/confirm deletion bound to this exact selection and stable document identity. */
    public CustomerDocumentDeletionAdapter deletion(org.fribok.bookkeeping.service.invoice.NormalizedCustomerDocumentDeletionService.Kind kind, long id)
            throws SQLException {
        requireActive();
        return new CustomerDocumentDeletionAdapter(this::open, gate, company, year, kind, id, clock);
    }

    /** Metadata-only adapter bound to this exact selection and stable document identity. */
    public CustomerDocumentMetadataAdapter metadata(CustomerDocumentMetadataAdapter.Kind kind, long id) throws SQLException {
        requireActive();
        return new CustomerDocumentMetadataAdapter(this::open, gate, company, year, kind, id, clock);
    }

    /** Binds a request adapter to a chosen SQL identity; preview still validates ownership and staleness. */
    public CustomerCreditDialogAdapter credit(long sourceId) throws SQLException {
        requireActive();
        return new CustomerCreditDialogAdapter(this::open, gate, company, year, sourceId, clock);
    }

    private Connection open() throws SQLException {
        requireActive();
        Connection connection = connections.open();
        if (!active) {
            connection.close();
            throw new SQLException("GUI selection was replaced or closed");
        }
        return connection;
    }

    private void requireActive() throws SQLException {
        if (!active) throw new SQLException("GUI selection was replaced or closed");
    }

    /** Invalidates future requests only; never closes someone else's connection or shuts down the database. */
    @Override public void close() { active = false; }
}
