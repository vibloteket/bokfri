package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.*;

import org.fribok.bookkeeping.dataformat.NormalizedAccountPlanCatalogStore;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedSchemaMigrations;
import org.fribok.bookkeeping.dataformat.SchemaMigrationRunner;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class NormalizedApplicationYearCreationTest {
    private static final LocalDate FROM = LocalDate.of(2027, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 12, 31);

    @Test
    void previewDoesNotWriteAndCreateKeepsLeaseAndOtherCompany() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner();
                var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
            var lease = owner.select(71, 11);
            var before = f.snapshot();
            assertEquals(2, owner.accountPlans(lease).size()); // Duplicate names remain distinct.
            var preview = owner.previewYear(lease, FROM, TO, f.planId);
            assertEquals(before, f.snapshot());
            var year = owner.createYear(preview);
            assertTrue(
                    f.snapshot().containsAll(before),
                    "Every pre-existing SQL row must remain unchanged");
            assertTrue(lease.isActive());
            assertEquals(11, lease.year().id());
            assertEquals(2, owner.companies().get(0).years().size());
            assertEquals(1, owner.companies().get(1).years().size());
            try (var c = f.open()) {
                var accounting = new NormalizedAccountingStore(c, Clock.systemUTC());
                accounting.setCurrentCompany(accounting.getCompanies().get(0));
                var stored =
                        accounting.getYears().stream()
                                .filter(y -> y.getId() == year.id())
                                .findFirst()
                                .orElseThrow();
                accounting.setCurrentYear(stored);
                assertTrue(accounting.getVouchers().isEmpty());
                assertEquals(1, accounting.getAccounts().size());
                assertEquals(1910, accounting.getAccounts().get(0).getNumber());
                assertTrue(accounting.getOpeningBalances(stored).isEmpty());
                assertEquals(
                        "Cash",
                        new NormalizedAccountPlanCatalogStore(c)
                                .getAccountPlans()
                                .get(0)
                                .getAccounts()
                                .get(0)
                                .getDescription());
            }
            assertThrows(SQLException.class, () -> owner.createYear(preview));
        }
    }

    @Test
    void invalidDatesOverlapMissingPlanAndUnavailableLeaseNeverWrite() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            var lease = owner.select(71, 11);
            var before = f.snapshot();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> owner.previewYear(lease, null, TO, f.planId));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> owner.previewYear(lease, TO, FROM, f.planId));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> owner.previewYear(lease, FROM.minusDays(1), TO, f.planId));
            assertThrows(SQLException.class, () -> owner.previewYear(lease, FROM, TO, -1));
            assertEquals(before, f.snapshot());
            owner.close();
            int opens = f.opens.get();
            assertThrows(SQLException.class, () -> owner.accountPlans(lease));
            assertThrows(SQLException.class, () -> owner.previewYear(lease, FROM, TO, f.planId));
            assertEquals(opens, f.opens.get());
        }
    }

    @Test
    void changedInputsInvalidateConfirmationWithoutAdditionalWrites() throws Exception {
        for (var sql :
                List.of(
                        "UPDATE account_plan_catalog_account SET description = 'changed'",
                        "UPDATE accounting_year SET ends_on = DATE '2026-12-30' WHERE legacy_id ="
                                + " 11",
                        "UPDATE company SET name = 'renamed' WHERE legacy_id = 71")) {
            try (var f = new Fixture();
                    var owner = f.owner()) {
                var lease = owner.select(71, 11);
                var preview = owner.previewYear(lease, FROM, TO, f.planId);
                try (var c = f.open();
                        var s = c.createStatement()) {
                    s.executeUpdate(sql);
                }
                var before = f.snapshot();
                assertThrows(SQLException.class, () -> owner.createYear(preview));
                assertEquals(before, f.snapshot());
                assertFalse(owner.isBlocked());
                assertTrue(lease.isActive());
            }
        }
    }

    @Test
    void foreignAndReplacedLeaseCannotConfirmOrConsumeOriginalPreview() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner();
                var foreign = f.owner()) {
            var lease = owner.select(71, 11);
            var preview = owner.previewYear(lease, FROM, TO, f.planId);
            int opens = f.opens.get();
            assertThrows(SQLException.class, () -> foreign.createYear(preview));
            assertEquals(opens, f.opens.get());
            owner.select(72, 12);
            opens = f.opens.get();
            assertThrows(SQLException.class, () -> owner.createYear(preview));
            assertEquals(opens, f.opens.get());
        }
    }

    @Test
    void commitFailuresBlockAllCommandsIncludingDocuments() throws Exception {
        for (var failure : List.of("before", "after", "runtime")) {
            try (var f = new Fixture();
                    var owner = f.owner()) {
                var lease = owner.select(71, 11);
                var preview = owner.previewYear(lease, FROM, TO, f.planId);
                f.failure = failure;
                assertThrows(Exception.class, () -> owner.createYear(preview));
                assertTrue(owner.isBlocked());
                int opens = f.opens.get();
                assertThrows(SQLException.class, () -> owner.accountPlans(lease));
                assertThrows(SQLException.class, () -> owner.createYear(preview));
                assertThrows(SQLException.class, () -> lease.suppliers().documents());
                assertThrows(SQLException.class, () -> lease.customers().documents());
                assertEquals(opens, f.opens.get());
                try (var fresh = f.owner()) {
                    assertEquals(
                            failure.equals("after") ? 2 : 1,
                            fresh.companies().get(0).years().size());
                }
            }
        }
    }

    @Test
    void lateAccountInsertFailureRollsBackWholeYearAndFreshPreviewCanRetry() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            var lease = owner.select(71, 11);
            var preview = owner.previewYear(lease, FROM, TO, f.planId);
            var before = f.snapshot();
            f.failure = "account";
            assertThrows(SQLException.class, () -> owner.createYear(preview));
            assertEquals(before, f.snapshot());
            assertFalse(owner.isBlocked());
            assertTrue(lease.isActive());
            assertNotNull(owner.createYear(owner.previewYear(lease, FROM, TO, f.planId)));
        }
    }

    @Test
    void closeFailureAfterCommitDoesNotBlockOrAllowRetry() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            var lease = owner.select(71, 11);
            var preview = owner.previewYear(lease, FROM, TO, f.planId);
            f.failure = "close";
            assertNotNull(owner.createYear(preview));
            assertFalse(owner.isBlocked());
            assertThrows(SQLException.class, () -> owner.createYear(preview));
            assertEquals(2, owner.companies().get(0).years().size());
        }
    }

    @Test
    void acceptedCreationFinishesOriginalCompanyAfterOwnerClose() throws Exception {
        try (var f = new Fixture();
                var owner = f.owner()) {
            var preview = owner.previewYear(owner.select(71, 11), FROM, TO, f.planId);
            f.entered = new CountDownLatch(1);
            f.release = new CountDownLatch(1);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var pending = pool.submit(() -> owner.createYear(preview));
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                owner.close();
                f.release.countDown();
                assertNotNull(pending.get(5, TimeUnit.SECONDS));
                try (var fresh = f.owner()) {
                    assertEquals(2, fresh.companies().get(0).years().size());
                }
            } finally {
                f.release.countDown();
                pool.shutdownNow();
            }
        }
    }

    static final class Fixture implements AutoCloseable {
        final String url = "jdbc:hsqldb:mem:yearcreate-" + UUID.randomUUID();
        final AtomicInteger opens = new AtomicInteger();
        int planId;
        volatile String failure = "";
        volatile CountDownLatch entered;
        volatile CountDownLatch release;

        Fixture() throws Exception {
            this(true);
        }

        Fixture(boolean createYears) throws Exception {
            try (var c = open()) {
                new SchemaMigrationRunner(Clock.systemUTC())
                        .migrate(c, NormalizedSchemaMigrations.load());
                org.fribok.bookkeeping.dataformat.DataFormatManager.recordVersion(c, 3);
                c.setAutoCommit(false);
                var accounting = new NormalizedAccountingStore(c, Clock.systemUTC());
                for (int i = 0; i < 2; i++) {
                    var company = new SSNewCompany();
                    company.setId(71 + i);
                    company.setName("Company " + i);
                    accounting.addCompany(company);
                    accounting.setCurrentCompany(company);
                    var year = new SSNewAccountingYear();
                    year.setId(11 + i);
                    year.setLocalFrom(LocalDate.of(2026, 1, 1));
                    year.setLocalTo(LocalDate.of(2026, 12, 31));
                    var plan = new SSAccountPlan();
                    plan.setName("Template");
                    var account = new SSAccount(1910);
                    account.setDescription("Cash");
                    plan.setAccounts(new ArrayList<>(List.of(account)));
                    year.setAccountPlan(plan);
                    if (createYears) accounting.addAccountingYear(year);
                    new NormalizedAccountPlanCatalogStore(c).addAccountPlan(plan);
                    if (i == 0) planId = plan.getId();
                }
                c.commit();
            }
        }

        Connection open() throws SQLException {
            return DriverManager.getConnection(url, "SA", "");
        }

        NormalizedApplicationOwner owner() {
            return new NormalizedApplicationOwner(
                    () -> {
                        assertFalse(
                                javax.swing.SwingUtilities.isEventDispatchThread(),
                                "JDBC must run off EDT");
                        opens.incrementAndGet();
                        var c = open();
                        var mode = failure;
                        failure = "";
                        return (Connection)
                                Proxy.newProxyInstance(
                                        Connection.class.getClassLoader(),
                                        new Class<?>[] {Connection.class},
                                        (proxy, method, args) -> {
                                            if (mode.equals("account")
                                                    && method.getName().equals("prepareStatement")
                                                    && ((String) args[0])
                                                            .startsWith("INSERT INTO account (")) {
                                                throw new SQLException(
                                                        "late account insert failure");
                                            }
                                            if (method.getName().equals("commit")) {
                                                if (entered != null) {
                                                    entered.countDown();
                                                    if (!release.await(5, TimeUnit.SECONDS))
                                                        throw new SQLException("timeout");
                                                }
                                                if (mode.equals("before"))
                                                    throw new SQLException("before");
                                                if (mode.equals("runtime"))
                                                    throw new IllegalStateException("runtime");
                                                if (mode.equals("after")) {
                                                    c.commit();
                                                    throw new SQLException("after");
                                                }
                                            }
                                            if (method.getName().equals("close")
                                                    && mode.equals("close")) {
                                                c.close();
                                                throw new SQLException("close");
                                            }
                                            try {
                                                return method.invoke(c, args);
                                            } catch (InvocationTargetException e) {
                                                throw e.getCause();
                                            }
                                        });
                    },
                    Clock.systemUTC());
        }

        List<String> snapshot() throws Exception {
            List<String> result = new ArrayList<>();
            try (var c = open();
                    var tables =
                            c.getMetaData()
                                    .getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
                while (tables.next()) {
                    String table = tables.getString("TABLE_NAME");
                    try (var s = c.createStatement();
                            var rows = s.executeQuery("SELECT * FROM " + table)) {
                        while (rows.next()) {
                            StringBuilder row = new StringBuilder(table);
                            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
                                row.append('|').append(rows.getString(i));
                            result.add(row.toString());
                        }
                    }
                }
            }
            result.sort(String::compareTo);
            return result;
        }

        public void close() throws Exception {
            try (var c = open()) {
                c.createStatement().execute("SHUTDOWN");
            }
        }
    }
}
