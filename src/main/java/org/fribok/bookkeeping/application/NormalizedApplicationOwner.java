package org.fribok.bookkeeping.application;

import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.NormalizedAccountPlanCatalogStore;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedSchemaMigrations;
import org.fribok.bookkeeping.dataformat.SchemaMigrationRunner;
import org.fribok.bookkeeping.gui.creditinvoice.CustomerDocumentGuiSession;
import org.fribok.bookkeeping.gui.supplierinvoice.SupplierDocumentGuiSession;
import org.fribok.bookkeeping.service.year.AccountingYearService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explicit normalized application ownership behind the production startup guard. Owns no long-lived
 * connection and never initializes, migrates, seeds or persists a legacy/global selection. Database
 * operations must run off the EDT. All leases share one writer gate and uncertain-commit boundary.
 */
public final class NormalizedApplicationOwner implements AutoCloseable {
    /** Supplies a fresh, exclusively owned auto-commit connection for each action. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    /** Immutable accounting-year identity and presentation. */
    public record Year(int id, LocalDate from, LocalDate to) {}

    /** Immutable company identity and its scoped accounting years. */
    public record Company(int id, String name, List<Year> years) {
        public Company {
            years = List.copyOf(years);
        }
    }

    /** One explicit, immutable company/year lease shared by customer and supplier screens. */
    public final class Selection {
        private final Company company;
        private final Year year;
        private final CustomerDocumentGuiSession customers;
        private final SupplierDocumentGuiSession suppliers;

        private Selection(Company company, Year year) {
            this.company = company;
            this.year = year;
            customers =
                    new CustomerDocumentGuiSession(
                            () -> openLease(this), gate, company.id(), year.id(), clock);
            suppliers =
                    new SupplierDocumentGuiSession(
                            () -> openLease(this), gate, company.id(), year.id(), clock);
        }

        public Company company() {
            return company;
        }

        public Year year() {
            return year;
        }

        public CustomerDocumentGuiSession customers() {
            return customers;
        }

        public SupplierDocumentGuiSession suppliers() {
            return suppliers;
        }

        public boolean isActive() {
            return !closed && current == this && customers.isActive() && suppliers.isActive();
        }

        public boolean isBlocked() {
            return NormalizedApplicationOwner.this.isBlocked();
        }

        private void retire() {
            customers.close();
            suppliers.close();
        }
    }

    /** Immutable explicit template choice; duplicate names are distinguished by identity. */
    public record AccountPlan(int id, String name) {}

    /**
     * Opaque owner-bound confirmation token with an explicit target and, for additional years, a
     * document lease.
     */
    public final class YearPreview {
        private final NormalizedApplicationOwner owner = NormalizedApplicationOwner.this;
        private final Selection selection;
        private final Company company;
        private final LocalDate from;
        private final LocalDate to;
        private final AccountPlan plan;
        private final List<List<Object>> snapshot;
        private boolean consumed;

        private YearPreview(
                Selection selection,
                Company company,
                LocalDate from,
                LocalDate to,
                AccountPlan plan,
                List<List<Object>> snapshot) {
            this.selection = selection;
            this.company = company;
            this.from = from;
            this.to = to;
            this.plan = plan;
            this.snapshot = snapshot;
        }

        public Company company() {
            return company;
        }

        public LocalDate from() {
            return from;
        }

        public LocalDate to() {
            return to;
        }

        public AccountPlan plan() {
            return plan;
        }
    }

    /** Reads templates only for the current, explicit application lease. */
    public List<AccountPlan> accountPlans(Selection selection) throws SQLException {
        return accountPlans(selection, selection.company.id());
    }

    /**
     * Lists templates for explicit first-year creation; refuses a company that already has years.
     */
    public List<AccountPlan> firstYearAccountPlans(int companyId) throws SQLException {
        return accountPlans(null, companyId);
    }

    private List<AccountPlan> accountPlans(Selection selection, int companyId) throws SQLException {
        synchronized (gate) {
            requireYearCommand(selection);
            try (var connection = openValidated()) {
                connection.setAutoCommit(false);
                try {
                    if (selection == null) yearAccounting(connection, null, companyId);
                    var result =
                            new NormalizedAccountPlanCatalogStore(connection)
                                    .getAccountPlans().stream()
                                            .map(
                                                    plan ->
                                                            new AccountPlan(
                                                                    plan.getId(), plan.getName()))
                                            .toList();
                    connection.rollback();
                    requireYearCommand(selection);
                    return result;
                } catch (SQLException | RuntimeException | Error failure) {
                    rollback(connection, failure);
                    throw failure;
                }
            }
        }
    }

    /** Validates explicit dates/template and captures the exact SQL inputs without writing. */
    public YearPreview previewYear(Selection selection, LocalDate from, LocalDate to, int planId)
            throws SQLException {
        return previewYear(selection, selection.company.id(), from, to, planId);
    }

    /**
     * Previews a first empty year for an explicit existing company, without changing document
     * context.
     */
    public YearPreview previewFirstYear(int companyId, LocalDate from, LocalDate to, int planId)
            throws SQLException {
        return previewYear(null, companyId, from, to, planId);
    }

    private YearPreview previewYear(
            Selection selection, int companyId, LocalDate from, LocalDate to, int planId)
            throws SQLException {
        synchronized (gate) {
            requireYearCommand(selection);
            try (var connection = openValidated()) {
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                connection.setAutoCommit(false);
                try {
                    var accounting = yearAccounting(connection, selection, companyId);
                    var plan =
                            new NormalizedAccountPlanCatalogStore(connection)
                                    .getAccountPlans().stream()
                                            .filter(value -> value.getId() == planId)
                                            .findFirst()
                                            .orElseThrow(
                                                    () ->
                                                            new SQLException(
                                                                    "Account plan no longer"
                                                                            + " exists"));
                    AccountingYearService.prepare(from, to, plan, accounting.getYears());
                    var result =
                            new YearPreview(
                                    selection,
                                    selection == null
                                            ? new Company(
                                                    companyId,
                                                    accounting.getCurrentCompany().getName(),
                                                    List.of())
                                            : selection.company(),
                                    from,
                                    to,
                                    new AccountPlan(plan.getId(), plan.getName()),
                                    yearInputs(connection, companyId, planId));
                    connection.rollback();
                    requireYearCommand(selection);
                    return result;
                } catch (SQLException | RuntimeException | Error failure) {
                    rollback(connection, failure);
                    throw failure;
                }
            }
        }
    }

    /**
     * Confirms a single-use preview in one transaction, rechecking scope, dates and exact SQL
     * inputs. Creates an empty independent year, without carry-forward or changing the current
     * lease.
     */
    public Year createYear(YearPreview preview) throws SQLException {
        Objects.requireNonNull(preview, "preview");
        synchronized (gate) {
            if (preview.owner != this) {
                throw new SQLException("Year preview belongs to another owner");
            }
            requireYearCommand(preview.selection);
            if (preview.consumed) throw new SQLException("Year preview was already consumed");
            preview.consumed = true;
            var connection = openValidated();
            boolean committing = false;
            boolean committed = false;
            Throwable failure = null;
            try {
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                connection.setAutoCommit(false);
                if (!preview.snapshot.equals(
                        yearInputs(connection, preview.company.id(), preview.plan.id()))) {
                    throw new SQLException("Year creation inputs changed; preview again");
                }
                var accounting =
                        yearAccounting(connection, preview.selection, preview.company.id());
                var plan =
                        new NormalizedAccountPlanCatalogStore(connection)
                                .getAccountPlans().stream()
                                        .filter(value -> value.getId() == preview.plan.id())
                                        .findFirst()
                                        .orElseThrow(
                                                () ->
                                                        new SQLException(
                                                                "Account plan no longer exists"));
                var year =
                        AccountingYearService.prepare(
                                preview.from, preview.to, plan, accounting.getYears());
                year.setId(accounting.nextAccountingYearId());
                year.setVouchers(new ArrayList<>());
                accounting.addAccountingYear(year);
                // Closing retires future work; this accepted transaction retains its original
                // company.
                committing = true;
                connection.commit();
                committed = true;
                return new Year(year.getId(), year.getLocalFrom(), year.getLocalTo());
            } catch (SQLException | RuntimeException | Error error) {
                failure = error;
                if (committing && !committed) blocked = true;
                rollback(connection, error);
                throw error;
            } finally {
                try {
                    connection.close();
                } catch (SQLException close) {
                    if (failure != null) failure.addSuppressed(close);
                    else if (committed) {
                        org.slf4j.LoggerFactory.getLogger(NormalizedApplicationOwner.class)
                                .warn(
                                        "Year creation committed but connection close failed; do"
                                                + " not retry",
                                        close);
                    } else throw close;
                }
            }
        }
    }

    private void requireYearCommand(Selection selection) throws SQLException {
        if (selection == null) requireAvailable();
        else requireLease(selection);
    }

    private NormalizedAccountingStore yearAccounting(
            Connection connection, Selection selection, int companyId) throws SQLException {
        var accounting = new NormalizedAccountingStore(connection, clock);
        var company =
                accounting.getCompanies().stream()
                        .filter(value -> value.getId() == companyId)
                        .findFirst()
                        .orElseThrow(() -> new SQLException("Selected company no longer exists"));
        accounting.setCurrentCompany(company);
        if (selection == null) {
            if (!accounting.getYears().isEmpty()) {
                throw new SQLException(
                        "Company already has accounting years; select a year explicitly");
            }
            return accounting;
        }
        if (!Objects.equals(company.getName(), selection.company.name())) {
            throw new SQLException("Selected company changed; select it again");
        }
        if (accounting.getYears().stream()
                .noneMatch(
                        value ->
                                value.getId() == selection.year.id()
                                        && Objects.equals(
                                                value.getLocalFrom(), selection.year.from())
                                        && Objects.equals(
                                                value.getLocalTo(), selection.year.to()))) {
            throw new SQLException("Selected accounting year no longer exists");
        }
        return accounting;
    }

    private static List<List<Object>> yearInputs(Connection connection, int companyId, int planId)
            throws SQLException {
        List<List<Object>> result = new ArrayList<>();
        String[] queries = {
            "SELECT * FROM company WHERE legacy_id = ?",
            "SELECT * FROM accounting_year WHERE company_id = "
                    + "(SELECT id FROM company WHERE legacy_id = ?) ORDER BY legacy_id",
            "SELECT * FROM account_plan_catalog WHERE legacy_id = ?",
            "SELECT * FROM account_plan_catalog_account WHERE plan_legacy_id = ? ORDER BY"
                    + " row_number"
        };
        for (int index = 0; index < queries.length; index++) {
            try (var statement = connection.prepareStatement(queries[index])) {
                statement.setInt(1, index < 2 ? companyId : planId);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        List<Object> row = new ArrayList<>();
                        row.add(index);
                        for (int column = 1;
                                column <= rows.getMetaData().getColumnCount();
                                column++) {
                            row.add(rows.getObject(column));
                        }
                        result.add(row);
                    }
                }
            }
        }
        return result;
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollback) {
            failure.addSuppressed(rollback);
        }
    }

    private final Connections connections;
    private final Clock clock;
    private final Object gate = new Object();
    private final Object state = new Object();
    private volatile Selection current;
    private volatile boolean closed;
    private volatile boolean blocked;

    /** Constructs without opening a database or changing normal application startup. */
    public NormalizedApplicationOwner(Connections connections, Clock clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns immutable company/year choices from one read-only normalized transaction. */
    public List<Company> companies() throws SQLException {
        requireAvailable();
        synchronized (gate) {
            requireAvailable();
            try (var connection = openValidated()) {
                connection.setAutoCommit(false);
                try {
                    var accounting = new NormalizedAccountingStore(connection, clock);
                    List<Company> result = new ArrayList<>();
                    for (var company : accounting.getCompanies()) {
                        accounting.setCurrentCompany(company);
                        var years =
                                accounting.getYears().stream()
                                        .map(
                                                year ->
                                                        new Year(
                                                                year.getId(),
                                                                year.getLocalFrom(),
                                                                year.getLocalTo()))
                                        .toList();
                        result.add(new Company(company.getId(), company.getName(), years));
                    }
                    connection.rollback();
                    requireAvailable();
                    return List.copyOf(result);
                } catch (SQLException | RuntimeException | Error failure) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollback) {
                        failure.addSuppressed(rollback);
                    }
                    throw failure;
                }
            }
        }
    }

    /**
     * Validates both identities before replacing the current lease. Failed selection keeps the old
     * lease usable. Previously accepted writes finish on their original identities; replacement
     * waits for their transaction gate. No saved database/UI selection is modified.
     */
    public Selection select(int companyId, int yearId) throws SQLException {
        requireAvailable();
        synchronized (gate) {
            var company =
                    companies().stream()
                            .filter(value -> value.id() == companyId)
                            .findFirst()
                            .orElseThrow(
                                    () -> new SQLException("Selected company no longer exists"));
            var year =
                    company.years().stream()
                            .filter(value -> value.id() == yearId)
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new SQLException(
                                                    "Selected year does not belong to company"));
            synchronized (state) {
                requireAvailable();
                var replacement = new Selection(company, year);
                var previous = current;
                current = replacement;
                if (previous != null) previous.retire();
                return replacement;
            }
        }
    }

    /**
     * An uncertain commit blocks all families and selection changes until external reconciliation.
     */
    public boolean isBlocked() {
        return blocked;
    }

    private void requireAvailable() throws SQLException {
        if (closed) {
            throw new SQLException("Normalized application owner is closed");
        }
        if (blocked) {
            throw new SQLException(
                    "Commit outcome is unknown; reconcile externally before opening a new owner");
        }
    }

    private void requireLease(Selection selection) throws SQLException {
        requireAvailable();
        if (current != selection || !selection.isActive()) {
            throw new SQLException("Application selection was replaced or closed");
        }
    }

    private Connection openLease(Selection selection) throws SQLException {
        synchronized (state) {
            requireLease(selection);
        }
        var connection = openValidated();
        try {
            synchronized (state) {
                requireLease(selection);
            }
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, args) -> {
                                if (method.getDeclaringClass() == Object.class) {
                                    return switch (method.getName()) {
                                        case "equals" -> proxy == args[0];
                                        case "hashCode" -> System.identityHashCode(proxy);
                                        default -> "Normalized application connection";
                                    };
                                }
                                // Never expose an unguarded connection through JDBC unwrap.
                                if (method.getName().equals("unwrap")) {
                                    if (args[0] == Connection.class) {
                                        return proxy;
                                    }
                                    throw new SQLException(
                                            "Driver-specific connection access is not available"
                                                    + " from an application lease");
                                }
                                if (method.getName().equals("isWrapperFor")) {
                                    return args[0] == Connection.class;
                                }
                                try {
                                    return method.invoke(connection, args);
                                } catch (InvocationTargetException failure) {
                                    if (method.getName().equals("commit")) {
                                        blocked = true;
                                    }
                                    throw failure.getCause();
                                }
                            });
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.close();
            } catch (SQLException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }

    private Connection openValidated() throws SQLException {
        var connection = connections.open();
        try {
            if (!connection.getAutoCommit()) {
                throw new SQLException("Application factory must supply a fresh session");
            }
            connection.setAutoCommit(false);
            if (DataFormatManager.detect(connection)
                    != DataFormatManager.NORMALIZED_DATA_FORMAT_VERSION) {
                throw new SQLException("Application owner requires normalized format 3");
            }
            try {
                var migrations = NormalizedSchemaMigrations.load();
                if (new SchemaMigrationRunner(clock)
                                .validateInstalled(connection, migrations)
                                .size()
                        != migrations.size()) {
                    throw new SQLException(
                            "Complete current normalized schema required; migrate explicitly"
                                    + " first");
                }
            } catch (java.io.IOException failure) {
                throw new SQLException("Cannot load packaged normalized schema history", failure);
            }
            connection.rollback();
            connection.setAutoCommit(true);
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            try {
                connection.close();
            } catch (SQLException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }

    /**
     * Invalidates future leases without waiting on accepted writes or closing their connections.
     */
    @Override
    public void close() {
        synchronized (state) {
            closed = true;
            var selection = current;
            current = null;
            if (selection != null) selection.retire();
        }
    }
}
