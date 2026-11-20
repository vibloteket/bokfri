package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.*;

import org.fribok.bookkeeping.dataformat.*;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.Metadata;
import org.fribok.bookkeeping.gui.creditinvoice.CustomerDocumentMetadataAdapter;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.Kind;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.data.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class NormalizedApplicationOwnerTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T14:00:00Z"), ZoneOffset.UTC);

    @Test
    void constructionIsLazyAndCatalogueIsImmutableReadOnlyScopedAndDetached() throws Exception {
        try (var f = new Fixture();
                var owner = new NormalizedApplicationOwner(f::open, CLOCK)) {
            assertEquals(0, f.opens.get());
            var before = f.snapshot();
            var companies = owner.companies();
            assertEquals(2, companies.size());
            assertThrows(UnsupportedOperationException.class, () -> companies.clear());
            var first = companies.stream().filter(c -> c.id() == 71).findFirst().orElseThrow();
            assertEquals(
                    List.of(11),
                    first.years().stream().map(NormalizedApplicationOwner.Year::id).toList());
            assertThrows(UnsupportedOperationException.class, () -> first.years().clear());
            assertEquals(before, f.snapshot());
            assertEquals(f.opens.get(), f.closes.get());
        }
    }

    @Test
    void bothDocumentFamiliesShareSelectionAndNoDatabaseOrLegacySelectionIsWritten()
            throws Exception {
        try (var f = new Fixture();
                var owner = new NormalizedApplicationOwner(f::open, CLOCK);
                var guard = new LegacyGuard()) {
            var before = f.snapshot();
            var selected = owner.select(71, 11);
            assertEquals(71, selected.company().id());
            assertEquals(11, selected.year().id());
            assertEquals(
                    List.of(f.customer),
                    selected.customers().documents().invoices().stream().map(d -> d.id()).toList());
            assertEquals(
                    List.of(f.supplier),
                    selected.suppliers().documents().invoices().stream().map(d -> d.id()).toList());
            assertEquals(before, f.snapshot());
            assertEquals(f.opens.get(), f.closes.get());
        }
    }

    @Test
    void invalidSelectionRetainsOldLeaseAndSuccessfulReplacementInvalidatesAllOldFactories()
            throws Exception {
        try (var f = new Fixture();
                var owner = new NormalizedApplicationOwner(f::open, CLOCK)) {
            var old = owner.select(71, 11);
            var adapter = old.suppliers().metadata(Kind.INVOICE, f.supplier);
            var loaded = adapter.load();
            assertThrows(SQLException.class, () -> owner.select(71, 12));
            assertThrows(SQLException.class, () -> owner.select(-1, 11));
            assertTrue(old.isActive());
            old.customers().documents();
            var next = owner.select(72, 12);
            assertFalse(old.isActive());
            assertTrue(next.isActive());
            int opens = f.opens.get();
            var before = f.snapshot();
            assertThrows(SQLException.class, old.customers()::documents);
            assertThrows(SQLException.class, old.suppliers()::documents);
            assertThrows(SQLException.class, () -> adapter.save(loaded, "Wrong old write", null));
            assertEquals(opens, f.opens.get());
            assertEquals(before, f.snapshot());
            assertTrue(next.customers().documents().invoices().isEmpty());
            assertTrue(next.suppliers().documents().invoices().isEmpty());
        }
    }

    @Test
    void uncertainCommitsFromEitherFamilyBlockBothFamiliesAndSelectionOnBothDurabilitySides()
            throws Exception {
        for (boolean supplier : List.of(false, true))
            for (boolean durable : List.of(false, true))
                try (var f = new Fixture();
                        var owner = new NormalizedApplicationOwner(f::open, CLOCK)) {
                    var selected = owner.select(71, 11);
                    var customers =
                            selected.customers()
                                    .metadata(
                                            CustomerDocumentMetadataAdapter.Kind.INVOICE,
                                            f.customer);
                    var supplierAdapter = selected.suppliers().metadata(Kind.INVOICE, f.supplier);
                    var customerLoaded = customers.load();
                    var supplierLoaded = supplierAdapter.load();
                    f.failure.set(durable ? "after" : "before");
                    if (supplier)
                        assertThrows(
                                SQLException.class,
                                () -> supplierAdapter.save(supplierLoaded, "Changed", null));
                    else
                        assertThrows(
                                SQLException.class,
                                () ->
                                        customers.save(
                                                customerLoaded,
                                                new Metadata(true, "changed", 2, true)));
                    assertTrue(owner.isBlocked());
                    assertTrue(selected.isBlocked());
                    int count = f.opens.get();
                    assertThrows(SQLException.class, selected.customers()::documents);
                    assertThrows(SQLException.class, selected.suppliers()::documents);
                    assertThrows(SQLException.class, customers::load);
                    assertThrows(SQLException.class, supplierAdapter::load);
                    assertThrows(SQLException.class, owner::companies);
                    assertThrows(SQLException.class, () -> owner.select(72, 12));
                    assertEquals(count, f.opens.get());
                    if (supplier)
                        assertEquals(
                                durable ? "Changed" : "Stored supplier",
                                f.value(
                                        "SELECT supplier_name FROM supplier_invoice WHERE id="
                                                + f.supplier));
                    else
                        assertEquals(
                                durable ? "changed" : null,
                                f.value(
                                        "SELECT ocr_number FROM customer_invoice WHERE id="
                                                + f.customer));
                }
    }

    @Test
    void knownCommitCloseFailureDoesNotBlockOtherFamilyOrRepeatWrites() throws Exception {
        try (var f = new Fixture();
                var owner = new NormalizedApplicationOwner(f::open, CLOCK)) {
            var selected = owner.select(71, 11);
            var adapter = selected.suppliers().metadata(Kind.INVOICE, f.supplier);
            var loaded = adapter.load();
            f.failure.set("close");
            var saved = adapter.save(loaded, "Committed", null);
            int opens = f.opens.get();
            assertSame(saved, adapter.save(loaded, "Committed", null));
            assertEquals(opens, f.opens.get());
            assertFalse(owner.isBlocked());
            selected.customers().documents();
            assertEquals(
                    "Committed",
                    f.value("SELECT supplier_name FROM supplier_invoice WHERE id=" + f.supplier));
        }
    }

    @Test
    void ordinaryWriteFailureRollsBackAndDoesNotPoisonOwner() throws Exception {
        try (var f = new Fixture();
                var owner = new NormalizedApplicationOwner(f::open, CLOCK)) {
            var selected = owner.select(71, 11);
            var adapter = selected.suppliers().metadata(Kind.INVOICE, f.supplier);
            var loaded = adapter.load();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> adapter.save(loaded, "x".repeat(256), null));
            assertFalse(owner.isBlocked());
            selected.customers().documents();
            selected.suppliers().documents();
        }
    }

    @Test
    void closeInvalidatesBothFamiliesWithoutOpeningOrShuttingDownDatabase() throws Exception {
        try (var f = new Fixture()) {
            var owner = new NormalizedApplicationOwner(f::open, CLOCK);
            var selected = owner.select(71, 11);
            int opens = f.opens.get();
            owner.close();
            owner.close();
            assertFalse(selected.isActive());
            assertThrows(SQLException.class, owner::companies);
            assertThrows(SQLException.class, () -> owner.select(71, 11));
            assertThrows(SQLException.class, selected.customers()::documents);
            assertThrows(SQLException.class, selected.suppliers()::documents);
            assertEquals(opens, f.opens.get());
            assertEquals("2", f.value("SELECT COUNT(*) FROM company"));
        }
    }

    @Test
    void formatAndIncompleteOrTamperedSchemaAreRejectedWithoutRepairOrMutation() throws Exception {
        for (String mode : List.of("format", "missing", "checksum"))
            try (var f = new Fixture();
                    var owner = new NormalizedApplicationOwner(f::open, CLOCK)) {
                if (mode.equals("format")) DataFormatManager.recordVersion(f.anchor, 2);
                else
                    try (var s = f.anchor.createStatement()) {
                        s.executeUpdate(
                                mode.equals("missing")
                                        ? "DELETE FROM BOKFRI_SCHEMA_HISTORY WHERE VERSION=21"
                                        : "UPDATE BOKFRI_SCHEMA_HISTORY SET SCRIPT_SHA256='bad'"
                                                + " WHERE VERSION=21");
                    }
                f.anchor.commit();
                var before = f.snapshot();
                assertThrows(SQLException.class, owner::companies);
                assertThrows(SQLException.class, () -> owner.select(71, 11));
                assertEquals(before, f.snapshot());
                assertFalse(owner.isBlocked());
            }
    }

    @Test
    void everyLeaseRevalidatesFullCurrentSchemaEvenForCustomerOnlyAction() throws Exception {
        try (var f = new Fixture();
                var owner = new NormalizedApplicationOwner(f::open, CLOCK)) {
            var selected = owner.select(71, 11);
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate("DELETE FROM BOKFRI_SCHEMA_HISTORY WHERE VERSION=21");
            }
            f.anchor.commit();
            var before = f.snapshot();
            assertThrows(SQLException.class, selected.customers()::documents);
            assertThrows(SQLException.class, selected.suppliers()::documents);
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void closeDoesNotWaitForAcceptedCommitAndAcceptedWriteKeepsOriginalIdentity() throws Exception {
        try (var f = new Fixture();
                var owner = new NormalizedApplicationOwner(f::open, CLOCK)) {
            var selected = owner.select(71, 11);
            var adapter = selected.suppliers().metadata(Kind.INVOICE, f.supplier);
            var loaded = adapter.load();
            f.entered = new CountDownLatch(1);
            f.release = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var save = executor.submit(() -> adapter.save(loaded, "Accepted original", null));
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                owner.close();
                assertFalse(selected.isActive());
                assertThrows(SQLException.class, () -> owner.select(72, 12));
                f.release.countDown();
                assertEquals(71, save.get(5, TimeUnit.SECONDS).companyId());
                assertEquals(
                        "Accepted original",
                        f.value(
                                "SELECT supplier_name FROM supplier_invoice WHERE id="
                                        + f.supplier));
            } finally {
                f.release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void replacementWaitsForAcceptedWriteAndRetiresBothOldFamiliesAfterCommit() throws Exception {
        try (var f = new Fixture();
                var owner = new NormalizedApplicationOwner(f::open, CLOCK)) {
            var old = owner.select(71, 11);
            var adapter = old.suppliers().metadata(Kind.INVOICE, f.supplier);
            var loaded = adapter.load();
            f.entered = new CountDownLatch(1);
            f.release = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var save = executor.submit(() -> adapter.save(loaded, "Original context", null));
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                var selecting = new CountDownLatch(1);
                var next =
                        executor.submit(
                                () -> {
                                    selecting.countDown();
                                    return owner.select(72, 12);
                                });
                assertTrue(selecting.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> next.get(100, TimeUnit.MILLISECONDS));
                f.release.countDown();
                assertEquals(71, save.get(5, TimeUnit.SECONDS).companyId());
                assertEquals(72, next.get(5, TimeUnit.SECONDS).company().id());
                assertFalse(old.isActive());
                assertFalse(old.customers().isActive());
                assertFalse(old.suppliers().isActive());
                assertEquals(
                        "Original context",
                        f.value(
                                "SELECT supplier_name FROM supplier_invoice WHERE id="
                                        + f.supplier));
            } finally {
                f.release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void uncheckedCommitFailureAlsoBlocksOtherFamilyWithoutBlindRetry() throws Exception {
        try (var f = new Fixture();
                var owner = new NormalizedApplicationOwner(f::open, CLOCK)) {
            var selected = owner.select(71, 11);
            var adapter = selected.suppliers().metadata(Kind.INVOICE, f.supplier);
            var loaded = adapter.load();
            f.failure.set("runtime");
            assertThrows(IllegalStateException.class, () -> adapter.save(loaded, "Unknown", null));
            assertTrue(owner.isBlocked());
            int opens = f.opens.get();
            assertThrows(SQLException.class, selected.customers()::documents);
            assertEquals(opens, f.opens.get());
        }
    }

    @Test
    void factoryMustProvideFreshAutoCommitConnectionsAndRejectedSessionsAreClosed()
            throws Exception {
        try (var f = new Fixture();
                var owner =
                        new NormalizedApplicationOwner(
                                () -> {
                                    var connection = f.open();
                                    connection.setAutoCommit(false);
                                    return connection;
                                },
                                CLOCK)) {
            var before = f.snapshot();
            assertThrows(SQLException.class, owner::companies);
            assertEquals(f.opens.get(), f.closes.get());
            assertEquals(before, f.snapshot());
            assertFalse(owner.isBlocked());
        }
    }

    static final class LegacyGuard implements AutoCloseable {
        final java.lang.reflect.Field field;
        final Object previous;

        LegacyGuard() throws Exception {
            field = se.swedsoft.bookkeeping.data.system.SSDB.class.getDeclaredField("iConnection");
            field.setAccessible(true);
            previous = field.get(se.swedsoft.bookkeeping.data.system.SSDB.getInstance());
            field.set(
                    se.swedsoft.bookkeeping.data.system.SSDB.getInstance(),
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (p, m, a) -> {
                                throw new AssertionError("Legacy JDBC " + m.getName());
                            }));
        }

        public void close() throws Exception {
            field.set(se.swedsoft.bookkeeping.data.system.SSDB.getInstance(), previous);
        }
    }

    static final class Fixture implements AutoCloseable {
        final String url = "jdbc:hsqldb:mem:application_owner_" + System.nanoTime();
        final Connection anchor;
        final AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger();
        final AtomicReference<String> failure = new AtomicReference<>("");
        volatile CountDownLatch entered, release;
        final long customer, supplier;

        Fixture() throws Exception {
            anchor = DriverManager.getConnection(url, "SA", "");
            anchor.setAutoCommit(false);
            new SchemaMigrationRunner(CLOCK).migrate(anchor, NormalizedSchemaMigrations.load());
            DataFormatManager.recordVersion(anchor, 3);
            var writer = new NormalizedAccountingWriter(CLOCK);
            for (int id : List.of(71, 72)) {
                var company = new SSNewCompany();
                company.setId(id);
                company.setName("Company " + id);
                writer.addCompany(anchor, company);
                var year = new SSNewAccountingYear();
                year.setId(id == 71 ? 11 : 12);
                year.setLocalFrom(LocalDate.of(2026, 1, 1));
                year.setLocalTo(LocalDate.of(2026, 12, 31));
                var plan = new SSAccountPlan();
                plan.setAccounts(
                        new ArrayList<>(
                                List.of(
                                        new SSAccount(1510),
                                        new SSAccount(3010),
                                        new SSAccount(2440),
                                        new SSAccount(4010))));
                year.setAccountPlan(plan);
                writer.addAccountingYear(anchor, id, year);
            }
            var invoice = SSInvoice.forPersistence();
            invoice.setNumber(1);
            invoice.setCustomerName("Stored customer");
            invoice.setLocalDate(LocalDate.of(2026, 2, 2));
            invoice.setCurrencyRate(BigDecimal.ONE);
            invoice.setTaxFree(true);
            var row = new se.swedsoft.bookkeeping.data.base.SSSaleRow();
            row.setQuantity(BigDecimal.ONE);
            row.setUnitprice(new BigDecimal("100"));
            row.setAccountNr(3010);
            invoice.getRows().add(row);
            customer = new NormalizedInvoiceStore(anchor).addInvoice(71, invoice);
            var sinvoice = SSSupplierInvoice.forPersistence();
            sinvoice.setNumber(1);
            sinvoice.setSupplierName("Stored supplier");
            sinvoice.setLocalDate(LocalDate.of(2026, 2, 2));
            sinvoice.setCurrencyRate(BigDecimal.ONE);
            sinvoice.setTaxSum(BigDecimal.ZERO);
            var srow = new SSSupplierInvoiceRow();
            srow.setQuantity(1);
            srow.setUnitprice(new BigDecimal("100"));
            srow.setAccountNr(4010);
            sinvoice.getRows().add(srow);
            supplier = new NormalizedSupplierInvoiceStore(anchor).addSupplierInvoice(71, sinvoice);
            anchor.commit();
        }

        Connection open() throws SQLException {
            opens.incrementAndGet();
            var c = DriverManager.getConnection(url, "SA", "");
            var committed = new AtomicBoolean();
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (p, m, a) -> {
                                try {
                                    if (m.getName().equals("commit")) {
                                        if (entered != null) {
                                            entered.countDown();
                                            if (!release.await(5, TimeUnit.SECONDS))
                                                throw new SQLException("Timed out");
                                        }
                                        if (failure.get().equals("runtime"))
                                            throw new IllegalStateException(
                                                    "Unchecked commit failure");
                                        if (failure.get().equals("before"))
                                            throw new SQLException(
                                                    "Unknown commit before durability");
                                        c.commit();
                                        committed.set(true);
                                        if (failure.get().equals("after"))
                                            throw new SQLException(
                                                    "Unknown commit after durability");
                                        return null;
                                    }
                                    Object result = m.invoke(c, a);
                                    if (m.getName().equals("close")) {
                                        closes.incrementAndGet();
                                        if (committed.get() && failure.get().equals("close"))
                                            throw new SQLException("Confirmed close failure");
                                    }
                                    return result;
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
        }

        String value(String sql) throws SQLException {
            try (var s = anchor.createStatement();
                    var r = s.executeQuery(sql)) {
                r.next();
                return r.getString(1);
            } finally {
                anchor.rollback();
            }
        }

        Map<String, List<String>> snapshot() throws SQLException {
            var result = new TreeMap<String, List<String>>();
            try (var tables =
                    anchor.getMetaData().getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
                while (tables.next()) {
                    String t = tables.getString("TABLE_NAME");
                    var values = new ArrayList<String>();
                    try (var s = anchor.createStatement();
                            var rows = s.executeQuery("SELECT * FROM " + t)) {
                        while (rows.next()) {
                            var v = new ArrayList<String>();
                            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
                                v.add(rows.getString(i));
                            values.add(v.toString());
                        }
                    }
                    Collections.sort(values);
                    result.put(t, values);
                }
            } finally {
                anchor.rollback();
            }
            return result;
        }

        public void close() throws SQLException {
            anchor.rollback();
            try (var s = anchor.createStatement()) {
                s.execute("SHUTDOWN");
            }
            anchor.close();
        }
    }
}
