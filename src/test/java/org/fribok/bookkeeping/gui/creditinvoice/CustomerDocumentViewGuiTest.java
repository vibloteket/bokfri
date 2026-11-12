package org.fribok.bookkeeping.gui.creditinvoice;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@Tag("integration")
class CustomerDocumentViewGuiTest {
    @Test void realCreditCommitRefreshesBothReadOnlyTablesOffEdtAndPreservesStableSelection() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Desktop display required");
        try (var f = new CustomerCreditGuiFixture(); var ignored = new LegacyJdbcGuard()) {
            AtomicInteger opens = new AtomicInteger();
            var session = new CustomerDocumentGuiSession(() -> {
                assertFalse(SwingUtilities.isEventDispatchThread()); opens.incrementAndGet(); return f.open();
            }, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            try (var ui = new View(session)) {
                JTable invoices = component(ui.view, "normalized-invoices", JTable.class);
                JTable credits = component(ui.view, "normalized-credits", JTable.class);
                CustomerCreditDialogAdapterTest.waitFor(() -> invoices.getRowCount() == 1);
                AtomicInteger tableEvents = new AtomicInteger();
                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(invoices.isCellEditable(0, 1));
                    assertEquals(f.invoice, invoices.getValueAt(0, 0));
                    invoices.getRowSorter().toggleSortOrder(1); invoices.setRowSelectionInterval(0, 0);
                    invoices.getModel().addTableModelListener(event -> {
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        // Both backing models must already represent the same committed snapshot.
                        assertEquals(invoices.getRowCount() == 0 ? 0 : 1, credits.getRowCount()); tableEvents.incrementAndGet();
                    });
                });
                // Add an unbooked invoice that will sort before the selected source on refresh.
                var store = new org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore(f.anchor);
                var added = store.findInvoice(71, f.invoice).orElseThrow().document();
                added.setNumber(0); added.setEntered(false); added.setVoucher(null); store.addInvoice(71, added);
                f.anchor.commit();
                SwingUtilities.invokeLater(() -> component(ui.view, "document-credit", JButton.class).doClick());
                JDialog selector = dialog("customer-credit-source");
                JComboBox<?> sources = component(selector, "credit-source", JComboBox.class);
                CustomerCreditDialogAdapterTest.waitFor(() -> sources.getItemCount() == 1);
                SwingUtilities.invokeAndWait(() -> sources.setSelectedIndex(0));
                SwingUtilities.invokeLater(() -> component(selector, "credit-source-choose", JButton.class).doClick());
                JDialog request = dialog("normalized-credit-request");
                JButton preview = component(request, "credit-preview", JButton.class);
                JButton create = component(request, "credit-create", JButton.class);
                SwingUtilities.invokeAndWait(preview::doClick);
                CustomerCreditDialogAdapterTest.waitFor(create::isEnabled);
                SwingUtilities.invokeAndWait(create::doClick);
                CustomerCreditDialogAdapterTest.waitFor(() -> tableEvents.get() == 1);
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals(2, invoices.getRowCount()); assertEquals(1, invoices.getSelectedRow());
                    assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) invoices.getValueAt(invoices.getSelectedRow(), 6)));
                    assertEquals(f.invoice, invoices.getValueAt(invoices.getSelectedRow(), 0));
                    assertEquals(1, credits.getValueAt(0, 6)); assertFalse(credits.isCellEditable(0, 1));
                });
                assertEquals(1, f.count()); assertEquals(5, opens.get());
            }
        }
    }

    @Test void failedRefreshRetainsLastSnapshotAndForeignCommitDoesNotOpenConnection() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Desktop display required");
        try (var f = new CustomerCreditGuiFixture()) {
            AtomicBoolean fail = new AtomicBoolean(); AtomicInteger opens = new AtomicInteger();
            var session = new CustomerDocumentGuiSession(() -> {
                opens.incrementAndGet(); if (fail.get()) throw new SQLException("Injected load failure"); return f.open();
            }, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            try (var ui = new View(session)) {
                JTable table = component(ui.view, "normalized-invoices", JTable.class);
                CustomerCreditDialogAdapterTest.waitFor(() -> table.getRowCount() == 1);
                SwingUtilities.invokeAndWait(() -> {
                    ui.view.creditSaved(new CustomerCreditMenuBinding.Saved(72, 11, f.invoice, null));
                    ui.view.creditSaved(new CustomerCreditMenuBinding.Saved(71, 12, f.invoice, null));
                });
                assertEquals(1, opens.get()); fail.set(true);
                SwingUtilities.invokeAndWait(ui.view::refresh);
                JLabel status = component(ui.view, "document-status", JLabel.class);
                CustomerCreditDialogAdapterTest.waitFor(() -> status.getText().contains("Injected load failure"));
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals(1, table.getRowCount()); assertEquals(f.invoice, table.getValueAt(0, 0));
                    assertTrue(component(ui.view, "document-refresh", JButton.class).isEnabled());
                });
                fail.set(false); SwingUtilities.invokeAndWait(ui.view::refresh);
                CustomerCreditDialogAdapterTest.waitFor(() -> component(ui.view, "document-refresh", JButton.class).isEnabled());
                assertEquals(3, opens.get());
            }
        }
    }

    @Test void replacingAndClosingSelectionDiscardLateLoadsAndClearRowsImmediately() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Desktop display required");
        try (var f = new CustomerCreditGuiFixture()) {
            var writer = new org.fribok.bookkeeping.dataformat.NormalizedAccountingWriter(CustomerCreditGuiFixture.CLOCK);
            var company = new se.swedsoft.bookkeeping.data.SSNewCompany(); company.setId(72); company.setName("Other company");
            writer.addCompany(f.anchor, company);
            var year = new se.swedsoft.bookkeeping.data.SSNewAccountingYear(); year.setId(12);
            year.setLocalFrom(java.time.LocalDate.of(2027, 1, 1)); year.setLocalTo(java.time.LocalDate.of(2027, 12, 31));
            year.setAccountPlan(new se.swedsoft.bookkeeping.data.SSAccountPlan());
            writer.addAccountingYear(f.anchor, 72, year); f.anchor.commit();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger opens = new AtomicInteger();
            var old = new CustomerDocumentGuiSession(() -> {
                opens.incrementAndGet(); entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new SQLException("Timeout"); }
                catch (InterruptedException interrupted) { throw new SQLException(interrupted); }
                return f.open();
            }, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            try (var ui = new View(old)) {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var fresh = new CustomerDocumentGuiSession(f::open, f.gate, 72, 12, CustomerCreditGuiFixture.CLOCK);
                JTable table = component(ui.view, "normalized-invoices", JTable.class);
                SwingUtilities.invokeAndWait(() -> {
                    ui.view.replaceSelection(fresh); assertEquals(0, table.getRowCount());
                });
                release.countDown();
                CustomerCreditDialogAdapterTest.waitFor(() -> component(ui.view, "document-refresh", JButton.class).isEnabled());
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals(0, table.getRowCount());
                    assertTrue(component(ui.view, "document-context", JLabel.class).getText().contains("Other company"));
                });
                assertFalse(old.isActive()); assertEquals(1, opens.get());
                SwingUtilities.invokeAndWait(() -> {
                    ui.view.refresh(); ui.view.close(); assertEquals(0, table.getRowCount());
                    assertTrue(CustomerCreditMenuBinding.openIfInstalled(ui.frame));
                    assertThrows(IllegalStateException.class, () -> ui.view.replaceSelection(fresh));
                });
                assertFalse(fresh.isActive());
            } finally { release.countDown(); }
        }
    }

    private static final class View implements AutoCloseable {
        JFrame frame; CustomerDocumentView view;
        View(CustomerDocumentGuiSession session) throws Exception {
            SwingUtilities.invokeAndWait(() -> {
                frame = new JFrame(); view = new CustomerDocumentView(frame, session);
                frame.add(view); frame.setSize(850, 350); frame.setVisible(true);
            });
        }
        public void close() throws Exception { SwingUtilities.invokeAndWait(() -> { view.close(); frame.dispose(); }); }
    }
    private static <T extends Component> T component(Container container, String name, Class<T> type) {
        return CustomerCreditDialogAdapterTest.component(container, name, type);
    }
    private static JDialog dialog(String name) throws Exception {
        AtomicReference<JDialog> found = new AtomicReference<>();
        CustomerCreditDialogAdapterTest.waitFor(() -> {
            for (Window window : Window.getWindows())
                if (window instanceof JDialog dialog && name.equals(dialog.getName()) && dialog.isShowing()) found.set(dialog);
            return found.get() != null;
        });
        return found.get();
    }
    private static final class LegacyJdbcGuard implements AutoCloseable {
        private final java.lang.reflect.Field field;
        private final Object previous;
        LegacyJdbcGuard() throws Exception {
            field = se.swedsoft.bookkeeping.data.system.SSDB.class.getDeclaredField("iConnection"); field.setAccessible(true);
            var db = se.swedsoft.bookkeeping.data.system.SSDB.getInstance(); previous = field.get(db);
            field.set(db, java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),
                    new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                        throw new AssertionError("Unexpected legacy JDBC: " + method.getName());
                    }));
        }
        public void close() throws Exception { field.set(se.swedsoft.bookkeeping.data.system.SSDB.getInstance(), previous); }
    }
}
