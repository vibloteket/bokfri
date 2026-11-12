package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.CustomerInvoiceStagingConverter;
import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.gui.creditinvoice.panel.NormalizedCreditRequestPanel;

import javax.swing.JButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class CustomerCreditDialogAdapterTest {
    @Test void previewIsReadOnlyAndSaveCommitsOnceWithoutChangingOriginal() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            AtomicInteger opened = new AtomicInteger(), commits = new AtomicInteger(), closes = new AtomicInteger();
            var model = model(f, () -> {
                opened.incrementAndGet();
                return wrap(f.open(), (c, name) -> {
                    if (name.equals("commit")) commits.incrementAndGet();
                    if (name.equals("close")) closes.incrementAndGet();
                });
            });
            var before = new CustomerInvoiceStagingConverter().fingerprintNormalized(f.anchor);
            var preview = model.preview(model.defaultDate(), null);
            assertEquals(0, f.count()); assertEquals(0, commits.get()); assertEquals(1, closes.get());
            assertEquals(new BigDecimal("100.00"), preview.amount());
            var created = model.save(preview);
            assertEquals(created, model.save(preview)); assertEquals(1, f.count());
            assertEquals(1, commits.get()); assertEquals(2, opened.get()); assertEquals(2, closes.get());
            assertEquals(before, new CustomerInvoiceStagingConverter().fingerprintNormalized(f.anchor));
        }
    }

    @Test void staleSourceAndForeignPreviewFailWithoutWritingAndSessionsClose() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            var model = f.adapter(); var preview = model.preview(model.defaultDate(), new BigDecimal("25"));
            assertThrows(IllegalArgumentException.class, () -> f.adapter().save(preview));
            var store = new NormalizedInvoiceStore(f.anchor);
            var old = store.findInvoiceMetadata(71, f.invoice).orElseThrow();
            store.updateInvoiceMetadata(71, f.invoice, old, new NormalizedInvoiceStore.Metadata(true, null, 0, false));
            f.anchor.commit();
            assertThrows(IllegalArgumentException.class, () -> model.save(preview)); assertEquals(0, f.count());
            model.save(model.preview(model.defaultDate(), new BigDecimal("25"))); assertEquals(1, f.count());
        }
    }

    @Test void legacyFutureAndTamperedHistoryAreRefusedWithoutMigration() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            for (int version : new int[]{2, 4}) {
                DataFormatManager.recordVersion(f.anchor, version);
                assertThrows(SQLException.class, () -> f.adapter().preview(null, null));
                assertEquals(version, DataFormatManager.detect(f.anchor)); assertEquals(0, f.count());
            }
            DataFormatManager.recordVersion(f.anchor, 3);
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate("UPDATE bokfri_schema_history SET script_sha256='tampered' WHERE version=1");
            }
            f.anchor.commit();
            assertThrows(SQLException.class, () -> f.adapter().preview(null, null));
            try (var s = f.anchor.createStatement(); var r = s.executeQuery(
                    "SELECT script_sha256 FROM bokfri_schema_history WHERE version=1")) {
                r.next(); assertEquals("tampered", r.getString(1).trim());
            }
        }
    }

    @Test void insertFailureRollsBackDedicatedSessionAndCanBeRetried() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            try (var s = f.anchor.createStatement()) {
                s.execute("ALTER TABLE customer_credit_invoice_row ADD CONSTRAINT fail_gui CHECK(row_number<>0)");
            }
            var model = f.adapter(); var preview = model.preview(null, new BigDecimal("25"));
            assertThrows(SQLException.class, () -> model.save(preview));
            assertFalse(model.hasUncertainCommit()); assertEquals(0, f.count());
            try (var s = f.anchor.createStatement()) {
                s.execute("ALTER TABLE customer_credit_invoice_row DROP CONSTRAINT fail_gui");
            }
            assertEquals(1, model.save(preview).number()); assertEquals(1, f.count());
        }
    }

    @Test void commitExceptionsBeforeOrAfterDurabilityBlockBlindRetry() throws Exception {
        for (boolean committed : new boolean[]{false, true}) {
            try (var f = new CustomerCreditGuiFixture()) {
                var model = model(f, () -> wrap(f.open(), (c, name) -> {
                    if (name.equals("commit")) {
                        if (committed) c.commit();
                        throw new SQLException("Injected unknown commit outcome");
                    }
                }));
                var preview = model.preview(null, new BigDecimal("25"));
                assertThrows(CustomerCreditDialogAdapter.CommitOutcomeUnknownException.class, () -> model.save(preview));
                assertTrue(model.hasUncertainCommit()); assertEquals(committed ? 1 : 0, f.count());
                assertThrows(SQLException.class, () -> model.save(preview));
                assertThrows(SQLException.class, () -> model.preview(null, new BigDecimal("25")));
                assertEquals(committed ? 1 : 0, f.count());
            }
        }
    }

    @Test void closeFailureAfterConfirmedCommitStillReturnsSuccessAndCachesIdentity() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            AtomicInteger opens = new AtomicInteger();
            var model = model(f, () -> {
                Connection c = f.open();
                return opens.incrementAndGet() == 1 ? c : wrap(c, (real, name) -> {
                    if (name.equals("close")) { real.close(); throw new SQLException("Injected close failure"); }
                });
            });
            var preview = model.preview(null, new BigDecimal("25"));
            var saved = model.save(preview);
            assertEquals(saved, model.save(preview)); assertFalse(model.hasUncertainCommit());
            assertEquals(1, f.count()); assertEquals(2, opens.get());
        }
    }

    @Test void sharedApplicationGateSerializesDistinctDialogWrites() throws Exception {
        try (var f = new CustomerCreditGuiFixture(); var executor = Executors.newFixedThreadPool(2)) {
            var first = f.adapter(); var second = f.adapter();
            var a = first.preview(null, new BigDecimal("75")); var b = second.preview(null, new BigDecimal("75"));
            CountDownLatch start = new CountDownLatch(1); AtomicInteger created = new AtomicInteger();
            var one = executor.submit(() -> { start.await(); try { first.save(a); created.incrementAndGet(); }
                catch (IllegalArgumentException stale) { } return null; });
            var two = executor.submit(() -> { start.await(); try { second.save(b); created.incrementAndGet(); }
                catch (IllegalArgumentException stale) { } return null; });
            start.countDown(); one.get(10, TimeUnit.SECONDS); two.get(10, TimeUnit.SECONDS);
            assertEquals(1, created.get()); assertEquals(1, f.count());
        }
    }

    @Test void requestPanelValidatesAndInvalidatesPreviewThenNotifiesAfterCommitOnEdt() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            AtomicInteger callbacks = new AtomicInteger(), closed = new AtomicInteger();
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            var model = model(f, () -> { assertFalse(SwingUtilities.isEventDispatchThread()); return f.open(); });
            AtomicReference<NormalizedCreditRequestPanel> ref = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> ref.set(new NormalizedCreditRequestPanel(model, result -> {
                try { assertTrue(SwingUtilities.isEventDispatchThread()); assertEquals(1, f.count()); }
                catch (Throwable failure) { callbackFailure.set(failure); }
                callbacks.incrementAndGet();
            }, closed::incrementAndGet)));
            var panel = ref.get();
            JTextField date = component(panel, "credit-date", JTextField.class);
            JTextField amount = component(panel, "credit-amount", JTextField.class);
            JButton preview = component(panel, "credit-preview", JButton.class);
            JButton create = component(panel, "credit-create", JButton.class);
            SwingUtilities.invokeAndWait(() -> { date.setText("invalid"); preview.doClick(); assertFalse(create.isEnabled()); });
            assertEquals(0, f.count());
            SwingUtilities.invokeAndWait(() -> { date.setText(model.defaultDate().toString()); amount.setText("25,50"); preview.doClick(); });
            waitFor(() -> create.isEnabled());
            SwingUtilities.invokeAndWait(() -> { amount.setText("30"); assertFalse(create.isEnabled()); preview.doClick(); });
            waitFor(() -> create.isEnabled());
            SwingUtilities.invokeAndWait(create::doClick);
            waitFor(() -> callbacks.get() == 1);
            assertNull(callbackFailure.get()); assertEquals(1, closed.get()); assertEquals(1, f.count());
            SwingUtilities.invokeAndWait(() -> { create.doClick(); preview.doClick(); });
            assertEquals(1, f.count()); assertEquals(1, callbacks.get());
        }
    }

    @Test void editingRequestDuringBackgroundPreviewCannotEnableAStaleSave() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var model = model(f, () -> {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new SQLException("Test wait timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new SQLException(interrupted); }
                return f.open();
            });
            AtomicReference<NormalizedCreditRequestPanel> ref = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> ref.set(new NormalizedCreditRequestPanel(model,
                    result -> fail("No creation requested"), () -> { })));
            var panel = ref.get();
            JButton preview = component(panel, "credit-preview", JButton.class);
            JButton create = component(panel, "credit-create", JButton.class);
            JTextField amount = component(panel, "credit-amount", JTextField.class);
            SwingUtilities.invokeAndWait(preview::doClick);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            try { SwingUtilities.invokeAndWait(() -> amount.setText("20")); }
            finally { release.countDown(); }
            waitFor(() -> !panel.isBusy());
            SwingUtilities.invokeAndWait(() -> assertFalse(create.isEnabled()));
            assertEquals(0, f.count());
        }
    }

    @Test void incompleteOrMissingHistoryIsNotFilledOrCreatedByPreview() throws Exception {
        try (var f = new CustomerCreditGuiFixture()) {
            try (var s = f.anchor.createStatement()) { s.executeUpdate("DELETE FROM bokfri_schema_history"); }
            f.anchor.commit();
            assertThrows(SQLException.class, () -> f.adapter().preview(null, null));
            try (var s = f.anchor.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM bokfri_schema_history")) {
                r.next(); assertEquals(0, r.getInt(1));
            }
            try (var s = f.anchor.createStatement()) { s.execute("DROP TABLE bokfri_schema_history"); }
            assertThrows(SQLException.class, () -> f.adapter().preview(null, null));
            try (var tables = f.anchor.getMetaData().getTables(null, "PUBLIC", "BOKFRI_SCHEMA_HISTORY", null)) {
                assertFalse(tables.next());
            }
            assertEquals(0, f.count());
        }
    }

    private static CustomerCreditDialogAdapter model(CustomerCreditGuiFixture f, CustomerCreditDialogAdapter.Connections c) {
        return new CustomerCreditDialogAdapter(c, f.gate, 71, 11, f.invoice, CustomerCreditGuiFixture.CLOCK);
    }
    @FunctionalInterface private interface Before { void call(Connection c, String method) throws SQLException; }
    private static Connection wrap(Connection c, Before before) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    before.call(c, method.getName());
                    try { return method.invoke(c, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }
    static <T extends Component> T component(Container container, String name, Class<T> type) {
        for (Component c : container.getComponents()) {
            if (name.equals(c.getName())) return type.cast(c);
            if (c instanceof Container nested) {
                T found = find(nested, name, type); if (found != null) return found;
            }
        }
        throw new AssertionError("Missing component " + name);
    }
    private static <T extends Component> T find(Container container, String name, Class<T> type) {
        for (Component c : container.getComponents()) {
            if (name.equals(c.getName())) return type.cast(c);
            if (c instanceof Container nested) { T found = find(nested, name, type); if (found != null) return found; }
        }
        return null;
    }
    static void waitFor(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            AtomicReference<Boolean> met = new AtomicReference<>(false);
            SwingUtilities.invokeAndWait(() -> met.set(condition.getAsBoolean()));
            if (met.get()) return; Thread.sleep(20);
        }
        throw new AssertionError("Swing operation timed out");
    }
}
