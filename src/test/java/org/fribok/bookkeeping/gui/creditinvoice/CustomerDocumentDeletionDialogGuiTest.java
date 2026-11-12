package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedSaleDocumentStore;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerDocumentDeletionService.Kind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@Tag("integration")
class CustomerDocumentDeletionDialogGuiTest {
    @Test void viewDeletesSortedSelectedInvoiceOnlyAfterConfirmationAndRefreshesAfterCommit() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture(); var ignored = new LegacyGuard()) {
            long id = CustomerDocumentDeletionAdapterTest.unbooked(f, 5), other = CustomerDocumentDeletionAdapterTest.unbooked(f, 0);
            long order = CustomerDocumentDeletionAdapterTest.order(f, 201, 5);
            AtomicInteger refreshes = new AtomicInteger();
            var session = new CustomerDocumentGuiSession(() -> { assertFalse(SwingUtilities.isEventDispatchThread()); return f.open(); },
                    f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            try (var ui = new View(session)) {
                JTable invoices = component(ui.view, "normalized-invoices", JTable.class);
                CustomerCreditDialogAdapterTest.waitFor(() -> invoices.getRowCount() == 3);
                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(component(ui.view, "invoice-delete", JButton.class).isEnabled());
                    invoices.getRowSorter().toggleSortOrder(1);
                    for (int i = 0; i < invoices.getRowCount(); i++) if (Long.valueOf(id).equals(invoices.getValueAt(i, 0))) invoices.setRowSelectionInterval(i, i);
                    invoices.getModel().addTableModelListener(event -> { if (invoices.getRowCount() > 0) { assertTrue(SwingUtilities.isEventDispatchThread()); refreshes.incrementAndGet(); } });
                });
                SwingUtilities.invokeLater(() -> component(ui.view, "invoice-delete", JButton.class).doClick());
                var dialog = showing(); waitReady(dialog);
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(component(dialog, "deletion-summary", JTextArea.class).getText().contains("SQL-ID " + id));
                    assertTrue(component(dialog, "deletion-summary", JTextArea.class).getText().contains("[" + order + "]"));
                    assertFalse(component(dialog, "deletion-delete", JButton.class).isEnabled());
                });
                assertEquals(0, refreshes.get()); confirm(dialog);
                CustomerCreditDialogAdapterTest.waitFor(() -> !dialog.isDisplayable() && invoices.getRowCount() == 2);
                assertEquals(1, refreshes.get());
                var store = new NormalizedInvoiceStore(f.anchor);
                assertTrue(store.findInvoice(71, id).isEmpty()); assertTrue(store.findInvoice(71, other).isPresent());
                assertTrue(store.findInvoice(71, f.invoice).orElseThrow().document().isEntered());
                assertNull(new NormalizedSaleDocumentStore(f.anchor).findOrder(71, order).orElseThrow().document().getInvoiceNr());
            }
        }
    }
    @Test void creditRouteDeletesCreditAndRefreshesOriginalInvoiceBalanceWithoutTouchingInvoice() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture(); var ignored = new LegacyGuard()) {
            var creator = f.adapter(); long id = creator.save(creator.preview(null, java.math.BigDecimal.TEN)).id();
            try (var ui = new View(new CustomerDocumentGuiSession(f::open, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK))) {
                JTable credits = component(ui.view, "normalized-credits", JTable.class), invoices = component(ui.view, "normalized-invoices", JTable.class);
                CustomerCreditDialogAdapterTest.waitFor(() -> credits.getRowCount() == 1);
                SwingUtilities.invokeAndWait(() -> credits.setRowSelectionInterval(0, 0));
                SwingUtilities.invokeLater(() -> component(ui.view, "credit-delete", JButton.class).doClick());
                var dialog = showing(); waitReady(dialog); confirm(dialog);
                CustomerCreditDialogAdapterTest.waitFor(() -> !dialog.isDisplayable() && credits.getRowCount() == 0);
                SwingUtilities.invokeAndWait(() -> assertEquals(0, new java.math.BigDecimal("100.00").compareTo((java.math.BigDecimal) invoices.getValueAt(0, 6))));
                var store = new NormalizedInvoiceStore(f.anchor);
                assertTrue(store.findCreditInvoice(71, id).isEmpty()); assertTrue(store.findInvoice(71, f.invoice).isPresent());
            }
        }
    }
    @Test void busyPreviewBlocksCloseAndStaleSourceRequiresReloadAndFreshConfirmation() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture()) {
            long id = CustomerDocumentDeletionAdapterTest.unbooked(f, 5);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger opens = new AtomicInteger(), callbacks = new AtomicInteger();
            var adapter = new CustomerDocumentDeletionAdapter(() -> {
                if (opens.incrementAndGet() == 1) { entered.countDown(); await(release); } return f.open();
            }, f.gate, 71, 11, Kind.INVOICE, id, CustomerCreditGuiFixture.CLOCK);
            var dialog = create(adapter, saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true)); assertTrue(entered.await(10, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(() -> {
                    dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                    assertTrue(dialog.isDisplayable()); assertFalse(component(dialog, "deletion-cancel", JButton.class).isEnabled());
                });
                release.countDown(); waitReady(dialog);
                var store = new NormalizedInvoiceStore(f.anchor); var metadata = store.findInvoiceMetadata(71, id).orElseThrow();
                store.updateInvoiceMetadata(71, id, metadata, new NormalizedInvoiceStore.Metadata(true, null, 1, false)); f.anchor.commit();
                confirm(dialog);
                JLabel status = component(dialog, "deletion-status", JLabel.class);
                CustomerCreditDialogAdapterTest.waitFor(() -> status.getText().contains("Stale"));
                assertEquals(0, callbacks.get());
                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(component(dialog, "deletion-delete", JButton.class).isEnabled());
                    component(dialog, "deletion-reload", JButton.class).doClick();
                });
                waitReady(dialog);
                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(component(dialog, "deletion-confirm", JCheckBox.class).isSelected());
                    assertFalse(component(dialog, "deletion-delete", JButton.class).isEnabled());
                });
                confirm(dialog); CustomerCreditDialogAdapterTest.waitFor(() -> !dialog.isDisplayable()); assertEquals(1, callbacks.get());
            } finally { release.countDown(); SwingUtilities.invokeAndWait(dialog::dispose); }
        }
    }
    @Test void viewReplacementDuringAcceptedDeletionKeepsOriginalCommitAndSuppressesStaleRefresh() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture()) {
            long id = CustomerDocumentDeletionAdapterTest.unbooked(f, 5);
            CountDownLatch committing = new CountDownLatch(1), release = new CountDownLatch(1);
            var old = new CustomerDocumentGuiSession(() -> {
                Connection c = f.open(); return wrap(c, () -> { committing.countDown(); await(release); });
            }, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            try (var ui = new View(old)) {
                JTable invoices = component(ui.view, "normalized-invoices", JTable.class);
                CustomerCreditDialogAdapterTest.waitFor(() -> invoices.getRowCount() == 2);
                SwingUtilities.invokeAndWait(() -> {
                    for (int i = 0; i < invoices.getRowCount(); i++) if (Long.valueOf(id).equals(invoices.getValueAt(i, 0))) invoices.setRowSelectionInterval(i, i);
                });
                SwingUtilities.invokeLater(() -> component(ui.view, "invoice-delete", JButton.class).doClick());
                var dialog = showing(); waitReady(dialog); confirm(dialog); assertTrue(committing.await(10, TimeUnit.SECONDS));
                AtomicInteger freshOpens = new AtomicInteger();
                var fresh = new CustomerDocumentGuiSession(() -> { freshOpens.incrementAndGet(); return f.open(); }, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
                SwingUtilities.invokeAndWait(() -> { ui.view.replaceSelection(fresh); assertTrue(dialog.isDisplayable()); });
                release.countDown(); CustomerCreditDialogAdapterTest.waitFor(() -> !dialog.isDisplayable() && invoices.getRowCount() == 1);
                assertFalse(old.isActive()); assertEquals(1, freshOpens.get()); assertTrue(fresh.isActive());
                assertTrue(new NormalizedInvoiceStore(f.anchor).findInvoice(71, id).isEmpty());
            } finally { release.countDown(); }
        }
    }
    @Test void unknownCommitDisablesFurtherDeletionAndReloadWithoutSuccessCallback() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture()) {
            long id = CustomerDocumentDeletionAdapterTest.unbooked(f, 5); AtomicInteger callbacks = new AtomicInteger();
            var adapter = new CustomerDocumentDeletionAdapter(() -> wrap(f.open(), () -> { throw new SQLException("Unknown deletion outcome"); }),
                    f.gate, 71, 11, Kind.INVOICE, id, CustomerCreditGuiFixture.CLOCK);
            var dialog = create(adapter, saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true)); waitReady(dialog); confirm(dialog);
                CustomerCreditDialogAdapterTest.waitFor(() -> adapter.isBlocked() && component(dialog, "deletion-cancel", JButton.class).isEnabled());
                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(component(dialog, "deletion-delete", JButton.class).isEnabled());
                    assertFalse(component(dialog, "deletion-reload", JButton.class).isEnabled());
                    assertTrue(dialog.isShowing()); component(dialog, "deletion-cancel", JButton.class).doClick();
                });
                assertEquals(0, callbacks.get()); assertTrue(new NormalizedInvoiceStore(f.anchor).findInvoice(71, id).isPresent());
            } finally { SwingUtilities.invokeAndWait(dialog::dispose); }
        }
    }
    private static void waitReady(CustomerDocumentDeletionDialog dialog) throws Exception {
        CustomerCreditDialogAdapterTest.waitFor(() -> component(dialog, "deletion-confirm", JCheckBox.class).isEnabled());
    }
    private static void confirm(CustomerDocumentDeletionDialog dialog) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            component(dialog, "deletion-confirm", JCheckBox.class).doClick(); component(dialog, "deletion-delete", JButton.class).doClick();
        });
    }
    private static CustomerDocumentDeletionDialog create(CustomerDocumentDeletionAdapter adapter, java.util.function.Consumer<CustomerDocumentDeletionAdapter.Saved> saved) throws Exception {
        AtomicReference<CustomerDocumentDeletionDialog> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> ref.set(new CustomerDocumentDeletionDialog(null, adapter, saved))); return ref.get();
    }
    private interface Commit { void before() throws Exception; }
    private static Connection wrap(Connection c, Commit before) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
            if (m.getName().equals("commit")) before.before();
            try { return m.invoke(c, a); } catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }
    private static void await(CountDownLatch latch) throws SQLException {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new SQLException("Test timeout"); } catch (InterruptedException error) { throw new SQLException(error); }
    }
    private static <T extends Component> T component(Container parent, String name, Class<T> type) { return CustomerCreditDialogAdapterTest.component(parent, name, type); }
    private static CustomerDocumentDeletionDialog showing() throws Exception {
        AtomicReference<CustomerDocumentDeletionDialog> ref = new AtomicReference<>();
        CustomerCreditDialogAdapterTest.waitFor(() -> {
            for (Window w : Window.getWindows()) if (w instanceof CustomerDocumentDeletionDialog d && d.isShowing()) ref.set(d);
            return ref.get() != null;
        }); return ref.get();
    }
    private static final class View implements AutoCloseable {
        JFrame frame; CustomerDocumentView view;
        View(CustomerDocumentGuiSession session) throws Exception {
            SwingUtilities.invokeAndWait(() -> { frame = new JFrame(); view = new CustomerDocumentView(frame, session); frame.add(view); frame.setSize(1100, 420); frame.setVisible(true); });
        }
        public void close() throws Exception { SwingUtilities.invokeAndWait(() -> { view.close(); frame.dispose(); }); }
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
