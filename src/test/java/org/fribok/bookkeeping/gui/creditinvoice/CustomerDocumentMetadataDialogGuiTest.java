package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.Metadata;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@Tag("integration")
class CustomerDocumentMetadataDialogGuiTest {
    @Test void viewRoutesSelectedSqlIdentityAndRefreshesOnlyAfterMetadataCommitWithoutLegacyJdbc() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture(); var ignored = new LegacyGuard()) {
            AtomicInteger opens = new AtomicInteger(), refreshes = new AtomicInteger();
            var session = new CustomerDocumentGuiSession(() -> {
                assertFalse(SwingUtilities.isEventDispatchThread()); opens.incrementAndGet(); return f.open();
            }, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            AtomicReference<JFrame> owner = new AtomicReference<>(); AtomicReference<CustomerDocumentView> view = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame(); owner.set(frame); var panel = new CustomerDocumentView(frame, session); view.set(panel);
                frame.add(panel); frame.setSize(1100, 400); frame.setVisible(true);
            });
            try {
                JTable table = component(view.get(), "normalized-invoices", JTable.class);
                CustomerCreditDialogAdapterTest.waitFor(() -> table.getRowCount() == 1);
                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(component(view.get(), "invoice-metadata", JButton.class).isEnabled());
                    assertFalse(component(view.get(), "credit-metadata", JButton.class).isEnabled());
                    table.getRowSorter().toggleSortOrder(1); table.setRowSelectionInterval(0, 0);
                    table.getModel().addTableModelListener(event -> { if (table.getRowCount() > 0) { assertTrue(SwingUtilities.isEventDispatchThread()); refreshes.incrementAndGet(); } });
                });
                SwingUtilities.invokeLater(() -> component(view.get(), "invoice-metadata", JButton.class).doClick());
                var dialog = showing(); JButton save = component(dialog, "metadata-save", JButton.class);
                CustomerCreditDialogAdapterTest.waitFor(save::isEnabled);
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(dialog.getTitle().contains("SQL-ID " + f.invoice));
                    assertTrue(component(dialog, "metadata-missing-ocr", JCheckBox.class).isSelected());
                    assertFalse(component(dialog, "metadata-ocr", JTextField.class).isEnabled());
                    component(dialog, "metadata-printed", JCheckBox.class).setSelected(true);
                    component(dialog, "metadata-interest", JCheckBox.class).setSelected(true);
                    component(dialog, "metadata-reminders", JSpinner.class).setValue(2);
                    component(dialog, "metadata-missing-ocr", JCheckBox.class).doClick();
                });
                assertEquals(0, refreshes.get()); SwingUtilities.invokeAndWait(save::doClick);
                CustomerCreditDialogAdapterTest.waitFor(() -> refreshes.get() == 1);
                var store = new NormalizedInvoiceStore(f.anchor);
                assertEquals(new Metadata(true, "", 2, true), store.findInvoiceMetadata(71, f.invoice).orElseThrow());
                assertTrue(store.findInvoice(71, f.invoice).orElseThrow().document().isEntered());
                assertEquals(4, opens.get());
            } finally { SwingUtilities.invokeAndWait(() -> { view.get().close(); owner.get().dispose(); }); }
        }
    }

    @Test void busyLoadBlocksCloseAndStaleSaveRequiresReloadBeforeRetry() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger opens = new AtomicInteger(), callbacks = new AtomicInteger();
            var adapter = new CustomerDocumentMetadataAdapter(() -> {
                assertFalse(SwingUtilities.isEventDispatchThread());
                if (opens.incrementAndGet() == 1) { entered.countDown(); await(release); }
                return f.open();
            }, f.gate, 71, 11, CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice, CustomerCreditGuiFixture.CLOCK);
            AtomicReference<CustomerDocumentMetadataDialog> ref = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> ref.set(new CustomerDocumentMetadataDialog(null, adapter, saved -> callbacks.incrementAndGet())));
            var dialog = ref.get();
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true)); assertTrue(entered.await(10, TimeUnit.SECONDS));
                CustomerCreditDialogAdapterTest.waitFor(dialog::isShowing);
                SwingUtilities.invokeAndWait(() -> {
                    dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                    assertTrue(dialog.isShowing()); assertFalse(component(dialog, "metadata-cancel", JButton.class).isEnabled());
                });
                release.countDown(); JButton save = component(dialog, "metadata-save", JButton.class);
                CustomerCreditDialogAdapterTest.waitFor(save::isEnabled);
                var store = new NormalizedInvoiceStore(f.anchor); var old = store.findInvoiceMetadata(71, f.invoice).orElseThrow();
                store.updateInvoiceMetadata(71, f.invoice, old, new Metadata(false, "concurrent", 1, false)); f.anchor.commit();
                SwingUtilities.invokeAndWait(save::doClick);
                JLabel status = component(dialog, "metadata-status", JLabel.class);
                CustomerCreditDialogAdapterTest.waitFor(() -> status.getText().contains("stale")); assertEquals(0, callbacks.get());
                SwingUtilities.invokeAndWait(() -> component(dialog, "metadata-reload", JButton.class).doClick());
                CustomerCreditDialogAdapterTest.waitFor(save::isEnabled);
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals("concurrent", component(dialog, "metadata-ocr", JTextField.class).getText());
                    component(dialog, "metadata-missing-ocr", JCheckBox.class).doClick();
                    component(dialog, "metadata-reminders", JSpinner.class).setValue(-1); save.doClick();
                    assertTrue(dialog.isShowing()); assertTrue(save.isEnabled()); assertTrue(status.getText().startsWith("Ange"));
                    component(dialog, "metadata-reminders", JSpinner.class).setValue(3); save.doClick();
                });
                try { CustomerCreditDialogAdapterTest.waitFor(() -> !dialog.isDisplayable()); }
                catch (AssertionError error) { throw new AssertionError(status.getText() + " / opens=" + opens.get(), error); }
                assertEquals(1, callbacks.get());
                var persisted = store.findInvoiceMetadata(71, f.invoice).orElseThrow();
                assertNull(persisted.ocrNumber()); assertEquals(3, persisted.reminderCount());
            } finally { release.countDown(); SwingUtilities.invokeAndWait(dialog::dispose); }
        }
    }

    @Test void selectionInvalidationDuringAcceptedSaveFinishesOriginalCommitWithoutRefresh() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture()) {
            CountDownLatch committing = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            var session = new CustomerDocumentGuiSession(() -> {
                Connection c = f.open();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("commit")) { committing.countDown(); await(release); }
                    try { return method.invoke(c, args); } catch (InvocationTargetException error) { throw error.getCause(); }
                });
            }, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            AtomicReference<CustomerDocumentMetadataDialog> ref = new AtomicReference<>();
            var metadataAdapter = session.metadata(CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice);
            SwingUtilities.invokeAndWait(() -> ref.set(new CustomerDocumentMetadataDialog(null,
                    metadataAdapter, saved -> callbacks.incrementAndGet())));
            var dialog = ref.get();
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true)); JButton save = component(dialog, "metadata-save", JButton.class);
                CustomerCreditDialogAdapterTest.waitFor(save::isEnabled);
                SwingUtilities.invokeAndWait(() -> { component(dialog, "metadata-printed", JCheckBox.class).setSelected(true); save.doClick(); });
                assertTrue(committing.await(10, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(() -> { session.close(); dialog.invalidateSelection(); assertTrue(dialog.isDisplayable()); });
                release.countDown(); CustomerCreditDialogAdapterTest.waitFor(() -> !dialog.isDisplayable());
                assertEquals(0, callbacks.get()); assertTrue(new NormalizedInvoiceStore(f.anchor).findInvoiceMetadata(71, f.invoice).orElseThrow().printed());
            } finally { release.countDown(); SwingUtilities.invokeAndWait(dialog::dispose); }
        }
    }
    @Test void creditTableMetadataActionDoesNotEditInvoiceWithSameSqlId() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture(); var ignored = new LegacyGuard()) {
            var creator = f.adapter(); long credit = creator.save(creator.preview(null, java.math.BigDecimal.TEN)).id();
            var session = new CustomerDocumentGuiSession(f::open, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            AtomicReference<JFrame> owner = new AtomicReference<>(); AtomicReference<CustomerDocumentView> view = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame(); owner.set(frame); var panel = new CustomerDocumentView(frame, session); view.set(panel);
                frame.add(panel); frame.setSize(1100, 400); frame.setVisible(true);
            });
            try {
                JTable table = component(view.get(), "normalized-credits", JTable.class);
                CustomerCreditDialogAdapterTest.waitFor(() -> table.getRowCount() == 1);
                SwingUtilities.invokeAndWait(() -> table.setRowSelectionInterval(0, 0));
                SwingUtilities.invokeLater(() -> component(view.get(), "credit-metadata", JButton.class).doClick());
                var dialog = showing(); JButton save = component(dialog, "metadata-save", JButton.class);
                CustomerCreditDialogAdapterTest.waitFor(save::isEnabled);
                SwingUtilities.invokeAndWait(() -> { component(dialog, "metadata-printed", JCheckBox.class).setSelected(true); save.doClick(); });
                CustomerCreditDialogAdapterTest.waitFor(() -> !dialog.isDisplayable());
                var store = new NormalizedInvoiceStore(f.anchor);
                assertTrue(store.findCreditInvoiceMetadata(71, credit).orElseThrow().printed());
                assertFalse(store.findInvoiceMetadata(71, f.invoice).orElseThrow().printed());
            } finally { SwingUtilities.invokeAndWait(() -> { view.get().close(); owner.get().dispose(); }); }
        }
    }

    @Test void uncertainCommitDisablesSaveAndReloadWithoutReportingSuccess() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture()) {
            var adapter = new CustomerDocumentMetadataAdapter(() -> {
                Connection c = f.open();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
                    if (m.getName().equals("commit")) { c.commit(); throw new SQLException("Unknown commit outcome"); }
                    try { return m.invoke(c, a); } catch (InvocationTargetException error) { throw error.getCause(); }
                });
            }, f.gate, 71, 11, CustomerDocumentMetadataAdapter.Kind.INVOICE, f.invoice, CustomerCreditGuiFixture.CLOCK);
            AtomicInteger callbacks = new AtomicInteger(); AtomicReference<CustomerDocumentMetadataDialog> ref = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> ref.set(new CustomerDocumentMetadataDialog(null, adapter, saved -> callbacks.incrementAndGet())));
            var dialog = ref.get();
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true)); JButton save = component(dialog, "metadata-save", JButton.class);
                CustomerCreditDialogAdapterTest.waitFor(save::isEnabled);
                SwingUtilities.invokeAndWait(() -> { component(dialog, "metadata-printed", JCheckBox.class).setSelected(true); save.doClick(); });
                CustomerCreditDialogAdapterTest.waitFor(() -> adapter.isBlocked() && component(dialog, "metadata-cancel", JButton.class).isEnabled());
                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(save.isEnabled()); assertFalse(component(dialog, "metadata-reload", JButton.class).isEnabled());
                    assertTrue(dialog.isShowing()); component(dialog, "metadata-cancel", JButton.class).doClick();
                });
                assertEquals(0, callbacks.get()); assertTrue(new NormalizedInvoiceStore(f.anchor).findInvoiceMetadata(71, f.invoice).orElseThrow().printed());
            } finally { SwingUtilities.invokeAndWait(dialog::dispose); }
        }
    }

    private static void await(CountDownLatch latch) throws SQLException {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new SQLException("Test timeout"); }
        catch (InterruptedException error) { throw new SQLException(error); }
    }
    private static <T extends Component> T component(Container parent, String name, Class<T> type) {
        return CustomerCreditDialogAdapterTest.component(parent, name, type);
    }
    private static CustomerDocumentMetadataDialog showing() throws Exception {
        AtomicReference<CustomerDocumentMetadataDialog> result = new AtomicReference<>();
        CustomerCreditDialogAdapterTest.waitFor(() -> {
            for (Window window : Window.getWindows()) if (window instanceof CustomerDocumentMetadataDialog dialog && dialog.isShowing()) result.set(dialog);
            return result.get() != null;
        });
        return result.get();
    }
    private static final class LegacyGuard implements AutoCloseable {
        final java.lang.reflect.Field field; final Object before;
        LegacyGuard() throws Exception {
            field = se.swedsoft.bookkeeping.data.system.SSDB.class.getDeclaredField("iConnection"); field.setAccessible(true);
            var db = se.swedsoft.bookkeeping.data.system.SSDB.getInstance(); before = field.get(db);
            field.set(db, Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> { throw new AssertionError("Legacy JDBC " + m.getName()); }));
        }
        public void close() throws Exception { field.set(se.swedsoft.bookkeeping.data.system.SSDB.getInstance(), before); }
    }
}
