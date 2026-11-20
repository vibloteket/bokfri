package org.fribok.bookkeeping.application;

import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedSchemaMigrations;
import org.fribok.bookkeeping.dataformat.SchemaMigrationRunner;
import org.fribok.bookkeeping.gui.creditinvoice.CustomerDocumentGuiSession;
import org.fribok.bookkeeping.gui.supplierinvoice.SupplierDocumentGuiSession;

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
        if (!selection.isActive()) {
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
