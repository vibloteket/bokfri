package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.Metadata;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CustomerDocumentMetadataAdapterTest {
    @Test void invoiceAndCreditMetadataAreReadOnlyUntilSaveAndNeverChangeFinancialTables() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            var creditAdapter = f.adapter(); long credit = creditAdapter.save(creditAdapter.preview(null, java.math.BigDecimal.TEN)).id();
            for (var kind : CustomerDocumentMetadataAdapter.Kind.values()) {
                long id = kind == CustomerDocumentMetadataAdapter.Kind.INVOICE ? f.invoice : credit;
                AtomicInteger opens = new AtomicInteger();
                var adapter = adapter(f, kind, id, () -> { opens.incrementAndGet(); return f.open(); });
                var before = snapshot(f.anchor, false);
                var loaded = adapter.load(); assertEquals(before, snapshot(f.anchor, false));
                assertNull(loaded.values().ocrNumber());
                var financial = snapshot(f.anchor, true);
                var replacement = new Metadata(true, "", 3, true);
                var saved = adapter.save(loaded, replacement);
                assertEquals(71, saved.companyId()); assertEquals(11, saved.yearId()); assertEquals(kind, saved.kind()); assertEquals(id, saved.id());
                assertEquals(replacement, read(f, kind, id)); assertEquals(financial, snapshot(f.anchor, true));
                assertSame(saved, adapter.save(loaded, replacement)); assertEquals(2, opens.get());
                assertThrows(SQLException.class, () -> adapter.save(loaded, new Metadata(false, "different", 0, false)));
            }
        }
    }

    @Test void staleForeignAndClosedSelectionsCannotSaveAndReloadSupportsNullVersusEmptyOcr() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            var first = adapter(f, CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice, f::open);
            var second = adapter(f, CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice, f::open);
            var a = first.load(); var b = second.load();
            assertThrows(SQLException.class, () -> second.save(a, new Metadata(true, null, 0, false)));
            first.save(a, new Metadata(true, "", 1, false));
            assertThrows(SQLException.class, () -> second.save(b, new Metadata(false, "stale", 2, false)));
            second.save(second.load(), new Metadata(false, null, 2, true));
            assertNull(read(f, CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice).ocrNumber());
            var session = new CustomerDocumentGuiSession(f::open, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            var leased = session.metadata(CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice); var loaded = leased.load(); session.close();
            assertThrows(SQLException.class, leased::load);
            assertThrows(SQLException.class, () -> leased.save(loaded, new Metadata(true, null, 3, true)));
            assertThrows(SQLException.class, () -> session.metadata(CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice));
        }
    }

    @Test void lateUpdateFailureRollsBackAndAllowsRetry() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            AtomicBoolean fail = new AtomicBoolean(true);
            var adapter = adapter(f, CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice, () -> {
                Connection c = f.open();
                return wrap(c, Connection.class, (method, args) -> {
                    if (method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("UPDATE customer_invoice SET printed=")) {
                        PreparedStatement statement = (PreparedStatement) invoke(c, method, args);
                        return wrap(statement, PreparedStatement.class, (m, a) -> {
                            Object result = invoke(statement, m, a);
                            if (m.getName().equals("executeUpdate") && fail.getAndSet(false)) throw new SQLException("Injected late update failure");
                            return result;
                        });
                    }
                    return invoke(c, method, args);
                });
            });
            var loaded = adapter.load(); var before = snapshot(f.anchor, false); var next = new Metadata(true, "retry", 1, true);
            assertThrows(SQLException.class, () -> adapter.save(loaded, next)); assertFalse(adapter.isBlocked());
            assertEquals(before, snapshot(f.anchor, false)); adapter.save(loaded, next); assertEquals(next, read(f, CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice));
        }
    }

    @Test void uncertainCommitBlocksFurtherRequestsOnEitherSideOfDurability() throws Exception {
        for (boolean durable : List.of(false, true)) try (var f = new CustomerCreditGuiFixture()) {
            var adapter = adapter(f, CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice, () -> {
                Connection c = f.open();
                return wrap(c, Connection.class, (method, args) -> {
                    if (method.getName().equals("commit")) {
                        if (durable) c.commit(); throw new SQLException("Injected uncertain commit");
                    }
                    return invoke(c, method, args);
                });
            });
            var loaded = adapter.load(); var next = new Metadata(true, "commit", 1, false);
            assertThrows(SQLException.class, () -> adapter.save(loaded, next)); assertTrue(adapter.isBlocked());
            assertThrows(SQLException.class, adapter::load); assertThrows(SQLException.class, () -> adapter.save(loaded, next));
            assertEquals(durable, read(f, CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice).printed());
        }
    }

    @Test void postCommitCloseFailureRemainsConfirmedAndReplaySafe() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            AtomicInteger opens = new AtomicInteger();
            var adapter = adapter(f, CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice, () -> {
                int number = opens.incrementAndGet(); Connection c = f.open();
                return wrap(c, Connection.class, (method, args) -> {
                    Object result = invoke(c, method, args);
                    if (method.getName().equals("close") && number == 2) throw new SQLException("Injected close failure");
                    return result;
                });
            });
            var loaded = adapter.load(); var next = new Metadata(true, null, 0, true);
            var saved = adapter.save(loaded, next); assertSame(saved, adapter.save(loaded, next));
            assertFalse(adapter.isBlocked()); assertEquals(2, opens.get()); assertEquals(next, read(f, CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice));
        }
    }

    @Test void missingForeignYearLegacyAndIncompleteFormatNeverMigrateOrWrite() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            assertThrows(SQLException.class, () -> adapter(f, CustomerDocumentMetadataAdapter.Kind.INVOICE, -1, f::open).load());
            assertThrows(SQLException.class, () -> new CustomerDocumentMetadataAdapter(f::open, f.gate, 72, 11,
                    CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice, CustomerCreditGuiFixture.CLOCK).load());
            assertThrows(SQLException.class, () -> new CustomerDocumentMetadataAdapter(f::open, f.gate, 71, 999,
                    CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice, CustomerCreditGuiFixture.CLOCK).load());
            DataFormatManager.recordVersion(f.anchor, 2); f.anchor.commit();
            assertThrows(SQLException.class, () -> adapter(f, CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice, f::open).load());
            assertEquals(2, DataFormatManager.detect(f.anchor));
            DataFormatManager.recordVersion(f.anchor, 3);
            try (var s = f.anchor.createStatement()) { s.executeUpdate("DELETE FROM bokfri_schema_history"); } f.anchor.commit();
            assertThrows(SQLException.class, () -> adapter(f, CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice, f::open).load());
        }
    }

    @Test void metadataAndCreditRequestsShareTheSameGate() throws Exception {
        try (var f = new CustomerCreditGuiFixture(); var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
            var writer = adapter(f, CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice, () -> {
                Connection c = f.open(); return wrap(c, Connection.class, (method, args) -> {
                    if (method.getName().equals("commit")) {
                        entered.countDown();
                        if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new SQLException("Test timeout");
                    }
                    return invoke(c, method, args);
                });
            });
            AtomicInteger creditOpens = new AtomicInteger();
            var credit = new CustomerCreditDialogAdapter(() -> { creditOpens.incrementAndGet(); return f.open(); },
                    f.gate, 71, 11, f.invoice, CustomerCreditGuiFixture.CLOCK);
            var loaded = writer.load();
            var saving = executor.submit(() -> writer.save(loaded, new Metadata(true, "gated", 1, false)));
            try {
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
                var started = new java.util.concurrent.CountDownLatch(1);
                var previewing = executor.submit(() -> { started.countDown(); return credit.preview(null, java.math.BigDecimal.ONE); });
                assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> previewing.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
                assertEquals(0, creditOpens.get()); release.countDown();
                saving.get(10, java.util.concurrent.TimeUnit.SECONDS); previewing.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(1, creditOpens.get());
            } finally { release.countDown(); }
        }
    }

    private static CustomerDocumentMetadataAdapter adapter(CustomerCreditGuiFixture f, CustomerDocumentMetadataAdapter.Kind kind,
                                                          long id, CustomerCreditDialogAdapter.Connections connections) {
        return new CustomerDocumentMetadataAdapter(connections, f.gate, 71, 11, kind, id, CustomerCreditGuiFixture.CLOCK);
    }
    private static Metadata read(CustomerCreditGuiFixture f, CustomerDocumentMetadataAdapter.Kind kind, long id) throws Exception {
        var store = new NormalizedInvoiceStore(f.anchor);
        return (kind == CustomerDocumentMetadataAdapter.Kind.INVOICE ? store.findInvoiceMetadata(71, id) : store.findCreditInvoiceMetadata(71, id)).orElseThrow();
    }
    private static Map<String, List<String>> snapshot(Connection c, boolean omitMetadata) throws Exception {
        Map<String, List<String>> tables = new TreeMap<>();
        try (var rs = c.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME"); List<String> rows = new ArrayList<>();
                try (var statement = c.createStatement(); var values = statement.executeQuery("SELECT * FROM " + table)) {
                    var metadata = values.getMetaData();
                    while (values.next()) {
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            String column = metadata.getColumnName(i);
                            if (omitMetadata && Set.of("CUSTOMER_INVOICE", "CUSTOMER_CREDIT_INVOICE").contains(table)
                                    && Set.of("PRINTED", "OCR_NUMBER", "REMINDER_COUNT", "INTEREST_INVOICED").contains(column)) continue;
                            Object value = values.getObject(i); String text = String.valueOf(value);
                            row.append(column).append(':').append(value == null ? "NULL" : value.getClass().getName())
                                    .append(':').append(text.length()).append(':').append(text).append(';');
                        }
                        rows.add(row.toString());
                    }
                }
                Collections.sort(rows); tables.put(table, rows);
            }
        }
        assertFalse(tables.isEmpty()); return tables;
    }
    private interface Call { Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable; }
    private static <T> T wrap(T target, Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> call.invoke(m, a)));
    }
    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); } catch (InvocationTargetException error) { throw error.getCause(); }
    }
}
