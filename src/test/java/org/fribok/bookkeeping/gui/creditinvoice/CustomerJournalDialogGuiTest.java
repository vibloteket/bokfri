package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
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
class CustomerJournalDialogGuiTest {
    @Test void invoiceAndCreditViewRoutesPreviewImmutableRowsAndRefreshOnlyAfterExplicitPostingWithoutLegacyJdbc() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (var kind : CustomerJournalAdapter.Kind.values()) try (var f = new CustomerCreditGuiFixture(); var ignored = new LegacyGuard()) {
            boolean credit = kind == CustomerJournalAdapter.Kind.CREDIT;
            long id;
            if (credit) { var creator = f.adapter(); id = creator.save(creator.preview(null, java.math.BigDecimal.TEN)).id(); }
            else id = CustomerDocumentDeletionAdapterTest.unbooked(f, 5);
            AtomicInteger refreshes = new AtomicInteger();
            var session = new CustomerDocumentGuiSession(() -> { assertFalse(SwingUtilities.isEventDispatchThread()); return f.open(); },
                    f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            try (var ui = new View(session)) {
                JTable table = component(ui.view, credit ? "normalized-credits" : "normalized-invoices", JTable.class);
                CustomerCreditDialogAdapterTest.waitFor(() -> table.getRowCount() == (credit ? 1 : 2));
                SwingUtilities.invokeAndWait(() -> table.getModel().addTableModelListener(event -> {
                    if (table.getRowCount() > 0) { assertTrue(SwingUtilities.isEventDispatchThread()); refreshes.incrementAndGet(); }
                }));
                SwingUtilities.invokeLater(() -> component(ui.view, credit ? "credit-journal" : "invoice-journal", JButton.class).doClick());
                var dialog = showing();
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        assertEquals(CustomerJournalAdapterTest.FROM.toString(), component(dialog, "journal-from", JTextField.class).getText());
                        assertEquals(CustomerJournalAdapterTest.TO.toString(), component(dialog, "journal-to", JTextField.class).getText());
                        assertFalse(component(dialog, "journal-post", JButton.class).isEnabled());
                        assertEquals(0, component(dialog, "journal-documents", JTable.class).getRowCount());
                        component(dialog, "journal-preview", JButton.class).doClick();
                    });
                    ready(dialog);
                    SwingUtilities.invokeAndWait(() -> {
                        JTable documents = component(dialog, "journal-documents", JTable.class), rows = component(dialog, "journal-rows", JTable.class);
                        assertEquals(1, documents.getRowCount()); assertEquals(id, documents.getValueAt(0, 0));
                        assertFalse(documents.isCellEditable(0, 1)); assertFalse(rows.isCellEditable(0, 0)); assertTrue(rows.getRowCount() >= 2);
                        assertTrue(component(dialog, "journal-status", JTextArea.class).getText().contains("verifikat 1"));
                        assertFalse(component(dialog, "journal-post", JButton.class).isEnabled());
                    });
                    assertEquals(0, refreshes.get()); confirm(dialog);
                    CustomerCreditDialogAdapterTest.waitFor(() -> !dialog.isDisplayable() && entered(table, id));
                    assertEquals(1, refreshes.get()); assertEquals(1, CustomerJournalAdapterTest.voucherCount(f));
                    var store = new NormalizedInvoiceStore(f.anchor);
                    assertTrue(store.findInvoice(71, f.invoice).orElseThrow().document().isEntered());
                    if (credit) assertTrue(store.findCreditInvoice(71, id).orElseThrow().document().isEntered());
                    else assertTrue(store.findInvoice(71, id).orElseThrow().document().isEntered());
                } finally { SwingUtilities.invokeAndWait(dialog::dispose); }
            }
        }
    }
    @Test void invalidAndChangedDatesNeverPostOldPreviewAndStaleSourceRequiresFreshConfirmation() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture()) {
            long id = CustomerDocumentDeletionAdapterTest.unbooked(f, 5); AtomicInteger opens = new AtomicInteger(), callbacks = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var session = new CustomerDocumentGuiSession(() -> {
                assertFalse(SwingUtilities.isEventDispatchThread());
                if (opens.incrementAndGet() == 1) { entered.countDown(); await(release); } return f.open();
            }, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            var dialog = create(session.journal(CustomerJournalAdapter.Kind.INVOICE), saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true));
                JButton preview = component(dialog, "journal-preview", JButton.class); JTextField from = component(dialog, "journal-from", JTextField.class), to = component(dialog, "journal-to", JTextField.class);
                SwingUtilities.invokeAndWait(() -> { from.setText("bad"); preview.doClick(); assertFalse(component(dialog, "journal-post", JButton.class).isEnabled()); });
                assertEquals(0, opens.get());
                SwingUtilities.invokeAndWait(() -> { from.setText(CustomerJournalAdapterTest.FROM.toString()); preview.doClick(); });
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(() -> {
                    dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING)); assertTrue(dialog.isDisplayable());
                    assertFalse(component(dialog, "journal-cancel", JButton.class).isEnabled()); to.setText("2026-11-30");
                });
                release.countDown(); CustomerCreditDialogAdapterTest.waitFor(preview::isEnabled);
                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(component(dialog, "journal-confirm", JCheckBox.class).isEnabled());
                    assertEquals(0, component(dialog, "journal-documents", JTable.class).getRowCount()); preview.doClick();
                });
                ready(dialog);
                SwingUtilities.invokeAndWait(() -> {
                    component(dialog, "journal-confirm", JCheckBox.class).doClick(); to.setText("2026-11-29");
                    assertFalse(component(dialog, "journal-post", JButton.class).isEnabled()); assertFalse(component(dialog, "journal-confirm", JCheckBox.class).isSelected()); preview.doClick();
                }); ready(dialog);
                var store = new NormalizedInvoiceStore(f.anchor); var invoice = store.findInvoice(71, id).orElseThrow().document();
                invoice.getRows().getFirst().setUnitprice(new java.math.BigDecimal("101")); store.updateInvoice(71, id, invoice); f.anchor.commit();
                confirm(dialog); JTextArea status = component(dialog, "journal-status", JTextArea.class);
                CustomerCreditDialogAdapterTest.waitFor(() -> status.getText().contains("stale")); assertEquals(0, callbacks.get());
                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(component(dialog, "journal-post", JButton.class).isEnabled()); preview.doClick();
                }); ready(dialog);
                SwingUtilities.invokeAndWait(() -> assertFalse(component(dialog, "journal-confirm", JCheckBox.class).isSelected()));
                confirm(dialog); CustomerCreditDialogAdapterTest.waitFor(() -> !dialog.isDisplayable()); assertEquals(1, callbacks.get());
                assertEquals(1, CustomerJournalAdapterTest.voucherCount(f));
            } finally { release.countDown(); SwingUtilities.invokeAndWait(dialog::dispose); session.close(); }
        }
    }
    @Test void selectionReplacementDuringAcceptedPostingSuppressesStaleRefreshButKeepsOriginalCommit() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture()) {
            long id = CustomerDocumentDeletionAdapterTest.unbooked(f, 5); CountDownLatch committing = new CountDownLatch(1), release = new CountDownLatch(1);
            var old = new CustomerDocumentGuiSession(() -> wrap(f.open(), c -> { committing.countDown(); await(release); }), f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            try (var ui = new View(old)) {
                JTable table = component(ui.view, "normalized-invoices", JTable.class);
                CustomerCreditDialogAdapterTest.waitFor(() -> table.getRowCount() == 2);
                SwingUtilities.invokeLater(() -> component(ui.view, "invoice-journal", JButton.class).doClick());
                var dialog = showing(); SwingUtilities.invokeAndWait(() -> component(dialog, "journal-preview", JButton.class).doClick()); ready(dialog); confirm(dialog);
                assertTrue(committing.await(10, TimeUnit.SECONDS)); AtomicInteger freshOpens = new AtomicInteger();
                var fresh = new CustomerDocumentGuiSession(() -> { freshOpens.incrementAndGet(); return f.open(); }, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
                SwingUtilities.invokeAndWait(() -> { ui.view.replaceSelection(fresh); assertTrue(dialog.isDisplayable()); });
                release.countDown(); CustomerCreditDialogAdapterTest.waitFor(() -> !dialog.isDisplayable() && entered(table, id));
                assertFalse(old.isActive()); assertEquals(1, freshOpens.get()); assertEquals(1, CustomerJournalAdapterTest.voucherCount(f));
            } finally { release.countDown(); }
        }
    }
    @Test void uncertainDurableCommitDisablesPreviewAndPostingWithoutSuccessNotification() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture()) {
            long id = CustomerDocumentDeletionAdapterTest.unbooked(f, 5); AtomicInteger callbacks = new AtomicInteger();
            var session = new CustomerDocumentGuiSession(() -> wrap(f.open(), c -> { c.commit(); throw new SQLException("Unknown journal outcome"); }),
                    f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            var adapter = session.journal(CustomerJournalAdapter.Kind.INVOICE); var dialog = create(adapter, saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true)); SwingUtilities.invokeAndWait(() -> component(dialog, "journal-preview", JButton.class).doClick()); ready(dialog); confirm(dialog);
                CustomerCreditDialogAdapterTest.waitFor(() -> adapter.isBlocked() && component(dialog, "journal-cancel", JButton.class).isEnabled());
                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(component(dialog, "journal-preview", JButton.class).isEnabled()); assertFalse(component(dialog, "journal-post", JButton.class).isEnabled());
                    assertFalse(component(dialog, "journal-from", JTextField.class).isEnabled()); assertTrue(dialog.isShowing());
                    component(dialog, "journal-cancel", JButton.class).doClick();
                }); assertEquals(0, callbacks.get()); assertEquals(1, CustomerJournalAdapterTest.voucherCount(f));
                assertTrue(new NormalizedInvoiceStore(f.anchor).findInvoice(71, id).orElseThrow().document().isEntered());
            } finally { SwingUtilities.invokeAndWait(dialog::dispose); session.close(); }
        }
    }
    private static boolean entered(JTable table, long id) {
        for (int i = 0; i < table.getRowCount(); i++) if (Long.valueOf(id).equals(table.getValueAt(i, 0))) return Boolean.TRUE.equals(table.getValueAt(i, 7)); return false;
    }
    private static CustomerJournalDialog create(CustomerJournalAdapter adapter, java.util.function.Consumer<CustomerJournalAdapter.Saved> saved) throws Exception {
        AtomicReference<CustomerJournalDialog> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> ref.set(new CustomerJournalDialog(null, adapter, CustomerJournalAdapterTest.FROM, CustomerJournalAdapterTest.TO, saved))); return ref.get();
    }
    private static void ready(CustomerJournalDialog dialog) throws Exception { CustomerCreditDialogAdapterTest.waitFor(() -> component(dialog, "journal-confirm", JCheckBox.class).isEnabled()); }
    private static void confirm(CustomerJournalDialog dialog) throws Exception {
        SwingUtilities.invokeAndWait(() -> { component(dialog, "journal-confirm", JCheckBox.class).doClick(); component(dialog, "journal-post", JButton.class).doClick(); });
    }
    private static CustomerJournalDialog showing() throws Exception {
        AtomicReference<CustomerJournalDialog> ref = new AtomicReference<>();
        CustomerCreditDialogAdapterTest.waitFor(() -> { for (Window window : Window.getWindows()) if (window instanceof CustomerJournalDialog dialog && dialog.isShowing()) ref.set(dialog); return ref.get() != null; }); return ref.get();
    }
    private interface Commit { void before(Connection connection) throws Exception; }
    private static Connection wrap(Connection c, Commit before) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p,m,a) -> {
            if (m.getName().equals("commit")) before.before(c);
            try { return m.invoke(c, a); } catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }
    private static void await(CountDownLatch latch) throws SQLException {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new SQLException("Test timeout"); } catch (InterruptedException error) { throw new SQLException(error); }
    }
    private static <T extends Component> T component(Container parent, String name, Class<T> type) { return CustomerCreditDialogAdapterTest.component(parent, name, type); }
    private static final class View implements AutoCloseable {
        JFrame frame; CustomerDocumentView view;
        View(CustomerDocumentGuiSession session) throws Exception { SwingUtilities.invokeAndWait(() -> { frame = new JFrame(); view = new CustomerDocumentView(frame, session); frame.add(view); frame.setSize(1100, 500); frame.setVisible(true); }); }
        public void close() throws Exception { SwingUtilities.invokeAndWait(() -> { view.close(); frame.dispose(); }); }
    }
    private static final class LegacyGuard implements AutoCloseable {
        final java.lang.reflect.Field field; final Object before;
        LegacyGuard() throws Exception {
            field = se.swedsoft.bookkeeping.data.system.SSDB.class.getDeclaredField("iConnection"); field.setAccessible(true); var db = se.swedsoft.bookkeeping.data.system.SSDB.getInstance(); before = field.get(db);
            field.set(db, Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p,m,a) -> { throw new AssertionError("Legacy JDBC " + m.getName()); }));
        }
        public void close() throws Exception { field.set(se.swedsoft.bookkeeping.data.system.SSDB.getInstance(), before); }
    }
}
