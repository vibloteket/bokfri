package org.fribok.bookkeeping.gui.creditinvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.service.invoice.NormalizedCustomerFinancialEditService.Kind;

@Tag("integration")
class CustomerFinancialEditDialogGuiTest {
    @Test
    void bothViewRoutesUseStableIdentityPreviewAndConfirmationThenRefreshOnceWithoutLegacyJdbcTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (var kind : Kind.values())
            try (var f = new CustomerCreditGuiFixture();
                    var ignored = new LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = CustomerFinancialEditAdapterTest.target(f, kind);
                if (!credit) CustomerDocumentDeletionAdapterTest.unbooked(f, 0);
                AtomicInteger refreshes = new AtomicInteger();
                var session =
                        new CustomerDocumentGuiSession(
                                () -> {
                                    assertFalse(SwingUtilities.isEventDispatchThread());
                                    return f.open();
                                },
                                f.gate,
                                71,
                                11,
                                CustomerCreditGuiFixture.CLOCK);
                try (var ui = new View(session)) {
                    JTable invoices = component(ui.view, "normalized-invoices", JTable.class),
                            table =
                                    component(
                                            ui.view,
                                            credit ? "normalized-credits" : "normalized-invoices",
                                            JTable.class);
                    CustomerCreditDialogAdapterTest.waitFor(
                            () -> table.getRowCount() == (credit ? 1 : 3));
                    SwingUtilities.invokeAndWait(
                            () -> {
                                for (int i = 0; i < invoices.getRowCount(); i++)
                                    if (Long.valueOf(f.invoice).equals(invoices.getValueAt(i, 0)))
                                        invoices.setRowSelectionInterval(i, i);
                                assertFalse(
                                        component(ui.view, "invoice-edit", JButton.class)
                                                .isEnabled());
                                table.getRowSorter().toggleSortOrder(1);
                                for (int i = 0; i < table.getRowCount(); i++)
                                    if (Long.valueOf(id).equals(table.getValueAt(i, 0)))
                                        table.setRowSelectionInterval(i, i);
                                table.getModel()
                                        .addTableModelListener(
                                                event -> {
                                                    if (table.getRowCount() > 0) {
                                                        assertTrue(
                                                                SwingUtilities
                                                                        .isEventDispatchThread());
                                                        refreshes.incrementAndGet();
                                                    }
                                                });
                            });
                    SwingUtilities.invokeLater(
                            () ->
                                    component(
                                                    ui.view,
                                                    credit ? "credit-edit" : "invoice-edit",
                                                    JButton.class)
                                            .doClick());
                    var dialog = showing();
                    ready(dialog);
                    try {
                        SwingUtilities.invokeAndWait(
                                () -> {
                                    assertTrue(dialog.getTitle().contains("SQL-ID " + id));
                                    JTable rows =
                                            component(dialog, "financial-lines", JTable.class);
                                    assertFalse(rows.isCellEditable(0, 0));
                                    assertFalse(rows.isCellEditable(0, 2));
                                    assertTrue(rows.isCellEditable(0, 3));
                                    rows.setValueAt(new BigDecimal("2"), 0, 3);
                                    rows.setValueAt(new BigDecimal("12.5"), 0, 4);
                                    component(dialog, "financial-date", JTextField.class)
                                            .setText("2026-11-12");
                                    assertFalse(
                                            component(dialog, "financial-save", JButton.class)
                                                    .isEnabled());
                                    component(dialog, "financial-preview", JButton.class).doClick();
                                });
                        previewed(dialog);
                        assertEquals(0, refreshes.get());
                        SwingUtilities.invokeAndWait(
                                () ->
                                        assertFalse(
                                                component(dialog, "financial-save", JButton.class)
                                                        .isEnabled()));
                        confirm(dialog);
                        CustomerCreditDialogAdapterTest.waitFor(
                                () ->
                                        !dialog.isDisplayable()
                                                && total(table, id).compareTo(new BigDecimal("25"))
                                                        == 0);
                        assertEquals(1, refreshes.get());
                        assertEquals(0, CustomerJournalAdapterTest.voucherCount(f));
                        assertTrue(
                                new NormalizedInvoiceStore(f.anchor)
                                        .findInvoice(71, f.invoice)
                                        .orElseThrow()
                                        .document()
                                        .isEntered());
                    } finally {
                        SwingUtilities.invokeAndWait(dialog::dispose);
                    }
                }
            }
    }

    @Test
    void liveCellValidationChangedInputsAndStaleSourcesRequireFreshPreviewOrReloadTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture()) {
            long id = CustomerFinancialEditAdapterTest.target(f, Kind.INVOICE);
            AtomicInteger opens = new AtomicInteger(), callbacks = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var session =
                    new CustomerDocumentGuiSession(
                            () -> {
                                if (opens.incrementAndGet() == 2) {
                                    entered.countDown();
                                    await(release);
                                }
                                return f.open();
                            },
                            f.gate,
                            71,
                            11,
                            CustomerCreditGuiFixture.CLOCK);
            var dialog =
                    create(
                            session.financialEdit(Kind.INVOICE, id),
                            saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true));
                ready(dialog);
                JTable rows = component(dialog, "financial-lines", JTable.class);
                SwingUtilities.invokeAndWait(
                        () -> {
                            assertTrue(rows.editCellAt(0, 3));
                            ((JTextField) rows.getEditorComponent()).setText("bad");
                            component(dialog, "financial-preview", JButton.class).doClick();
                            assertTrue(rows.isEditing());
                            assertFalse(
                                    component(dialog, "financial-save", JButton.class).isEnabled());
                            ((JTextField) rows.getEditorComponent()).setText("2.5");
                            component(dialog, "financial-preview", JButton.class).doClick();
                        });
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertEquals(2, opens.get());
                SwingUtilities.invokeAndWait(
                        () -> {
                            dialog.dispatchEvent(
                                    new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                            assertTrue(dialog.isDisplayable());
                            assertFalse(
                                    component(dialog, "financial-cancel", JButton.class)
                                            .isEnabled());
                            component(dialog, "financial-date", JTextField.class)
                                    .setText("2026-11-13");
                        });
                release.countDown();
                CustomerCreditDialogAdapterTest.waitFor(
                        () -> component(dialog, "financial-preview", JButton.class).isEnabled());
                SwingUtilities.invokeAndWait(
                        () -> {
                            assertFalse(
                                    component(dialog, "financial-confirm", JCheckBox.class)
                                            .isEnabled());
                            component(dialog, "financial-preview", JButton.class).doClick();
                        });
                previewed(dialog);
                SwingUtilities.invokeAndWait(
                        () -> {
                            component(dialog, "financial-confirm", JCheckBox.class).doClick();
                            assertTrue(rows.editCellAt(0, 4));
                            assertFalse(
                                    component(dialog, "financial-save", JButton.class).isEnabled());
                            assertFalse(
                                    component(dialog, "financial-confirm", JCheckBox.class)
                                            .isSelected());
                            ((JTextField) rows.getEditorComponent()).setText("20");
                            component(dialog, "financial-preview", JButton.class).doClick();
                        });
                previewed(dialog);
                var store = new NormalizedInvoiceStore(f.anchor);
                var old = store.findInvoiceMetadata(71, id).orElseThrow();
                store.updateInvoiceMetadata(
                        71, id, old, new NormalizedInvoiceStore.Metadata(true, null, 2, false));
                f.anchor.commit();
                confirm(dialog);
                CustomerCreditDialogAdapterTest.waitFor(
                        () ->
                                component(dialog, "financial-status", JTextArea.class)
                                        .getText()
                                        .contains("stale"));
                assertEquals(0, callbacks.get());
                SwingUtilities.invokeAndWait(
                        () -> component(dialog, "financial-reload", JButton.class).doClick());
                ready(dialog);
                SwingUtilities.invokeAndWait(
                        () -> {
                            assertEquals(
                                    0,
                                    BigDecimal.ONE.compareTo((BigDecimal) rows.getValueAt(0, 3)));
                            rows.setValueAt(new BigDecimal("2.5"), 0, 3);
                            rows.setValueAt(new BigDecimal("20"), 0, 4);
                            component(dialog, "financial-preview", JButton.class).doClick();
                        });
                previewed(dialog);
                confirm(dialog);
                CustomerCreditDialogAdapterTest.waitFor(() -> !dialog.isDisplayable());
                assertEquals(1, callbacks.get());
                assertTrue(store.findInvoiceMetadata(71, id).orElseThrow().printed());
            } finally {
                release.countDown();
                SwingUtilities.invokeAndWait(dialog::dispose);
                session.close();
            }
        }
    }

    @Test
    void replacementDuringAcceptedSaveRetainsOriginalCommitWithoutStaleRefreshTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture()) {
            long id = CustomerFinancialEditAdapterTest.target(f, Kind.INVOICE);
            CountDownLatch committing = new CountDownLatch(1), release = new CountDownLatch(1);
            var old =
                    new CustomerDocumentGuiSession(
                            () ->
                                    wrap(
                                            f.open(),
                                            c -> {
                                                committing.countDown();
                                                await(release);
                                            }),
                            f.gate,
                            71,
                            11,
                            CustomerCreditGuiFixture.CLOCK);
            try (var ui = new View(old)) {
                JTable table = component(ui.view, "normalized-invoices", JTable.class);
                CustomerCreditDialogAdapterTest.waitFor(() -> table.getRowCount() == 2);
                SwingUtilities.invokeAndWait(
                        () -> {
                            for (int i = 0; i < table.getRowCount(); i++)
                                if (Long.valueOf(id).equals(table.getValueAt(i, 0)))
                                    table.setRowSelectionInterval(i, i);
                        });
                SwingUtilities.invokeLater(
                        () -> component(ui.view, "invoice-edit", JButton.class).doClick());
                var dialog = showing();
                ready(dialog);
                SwingUtilities.invokeAndWait(
                        () -> {
                            component(dialog, "financial-lines", JTable.class)
                                    .setValueAt(new BigDecimal("25"), 0, 4);
                            component(dialog, "financial-preview", JButton.class).doClick();
                        });
                previewed(dialog);
                confirm(dialog);
                assertTrue(committing.await(10, TimeUnit.SECONDS));
                AtomicInteger freshOpens = new AtomicInteger();
                var fresh =
                        new CustomerDocumentGuiSession(
                                () -> {
                                    freshOpens.incrementAndGet();
                                    return f.open();
                                },
                                f.gate,
                                71,
                                11,
                                CustomerCreditGuiFixture.CLOCK);
                SwingUtilities.invokeAndWait(
                        () -> {
                            ui.view.replaceSelection(fresh);
                            assertTrue(dialog.isDisplayable());
                        });
                release.countDown();
                CustomerCreditDialogAdapterTest.waitFor(
                        () ->
                                !dialog.isDisplayable()
                                        && total(table, id).compareTo(new BigDecimal("25")) == 0);
                assertFalse(old.isActive());
                assertEquals(1, freshOpens.get());
                assertEquals(0, CustomerJournalAdapterTest.voucherCount(f));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void durableUnknownCommitDisablesEditorPreviewReloadAndSaveWithoutSuccessCallbackTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture()) {
            long id = CustomerFinancialEditAdapterTest.target(f, Kind.INVOICE);
            AtomicInteger callbacks = new AtomicInteger();
            var session =
                    new CustomerDocumentGuiSession(
                            () ->
                                    wrap(
                                            f.open(),
                                            c -> {
                                                c.commit();
                                                throw new SQLException("Unknown financial outcome");
                                            }),
                            f.gate,
                            71,
                            11,
                            CustomerCreditGuiFixture.CLOCK);
            var adapter = session.financialEdit(Kind.INVOICE, id);
            var dialog = create(adapter, saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true));
                ready(dialog);
                SwingUtilities.invokeAndWait(
                        () -> {
                            component(dialog, "financial-lines", JTable.class)
                                    .setValueAt(new BigDecimal("25"), 0, 4);
                            component(dialog, "financial-preview", JButton.class).doClick();
                        });
                previewed(dialog);
                confirm(dialog);
                CustomerCreditDialogAdapterTest.waitFor(
                        () ->
                                adapter.isBlocked()
                                        && component(dialog, "financial-cancel", JButton.class)
                                                .isEnabled());
                SwingUtilities.invokeAndWait(
                        () -> {
                            for (String name :
                                    new String[] {
                                        "financial-preview", "financial-save", "financial-reload"
                                    })
                                assertFalse(component(dialog, name, JButton.class).isEnabled());
                            assertFalse(
                                    component(dialog, "financial-date", JTextField.class)
                                            .isEnabled());
                            assertFalse(
                                    component(dialog, "financial-lines", JTable.class).isEnabled());
                            component(dialog, "financial-cancel", JButton.class).doClick();
                        });
                assertEquals(0, callbacks.get());
                assertEquals(
                        0,
                        new BigDecimal("25")
                                .compareTo(
                                        new NormalizedInvoiceStore(f.anchor)
                                                .findInvoice(71, id)
                                                .orElseThrow()
                                                .document()
                                                .getRows()
                                                .getFirst()
                                                .getUnitprice()));
            } finally {
                SwingUtilities.invokeAndWait(dialog::dispose);
                session.close();
            }
        }
    }

    private static BigDecimal total(JTable table, long id) {
        for (int i = 0; i < table.getRowCount(); i++)
            if (Long.valueOf(id).equals(table.getValueAt(i, 0)))
                return (BigDecimal) table.getValueAt(i, 5);
        return new BigDecimal("-999999");
    }

    private static void ready(CustomerFinancialEditDialog dialog) throws Exception {
        CustomerCreditDialogAdapterTest.waitFor(
                () -> component(dialog, "financial-preview", JButton.class).isEnabled());
    }

    private static void previewed(CustomerFinancialEditDialog dialog) throws Exception {
        CustomerCreditDialogAdapterTest.waitFor(
                () -> component(dialog, "financial-confirm", JCheckBox.class).isEnabled());
    }

    private static void confirm(CustomerFinancialEditDialog dialog) throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    component(dialog, "financial-confirm", JCheckBox.class).doClick();
                    component(dialog, "financial-save", JButton.class).doClick();
                });
    }

    private static CustomerFinancialEditDialog create(
            CustomerFinancialEditAdapter adapter,
            java.util.function.Consumer<CustomerFinancialEditAdapter.Saved> saved)
            throws Exception {
        AtomicReference<CustomerFinancialEditDialog> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(
                () -> ref.set(new CustomerFinancialEditDialog(null, adapter, saved)));
        return ref.get();
    }

    private static CustomerFinancialEditDialog showing() throws Exception {
        AtomicReference<CustomerFinancialEditDialog> ref = new AtomicReference<>();
        CustomerCreditDialogAdapterTest.waitFor(
                () -> {
                    for (Window w : Window.getWindows())
                        if (w instanceof CustomerFinancialEditDialog d && d.isShowing()) ref.set(d);
                    return ref.get() != null;
                });
        return ref.get();
    }

    private interface Commit {
        void before(Connection c) throws Exception;
    }

    private static Connection wrap(Connection c, Commit before) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (p, m, a) -> {
                            if (m.getName().equals("commit")) before.before(c);
                            try {
                                return m.invoke(c, a);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
    }

    private static void await(CountDownLatch latch) throws SQLException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new SQLException("Test timeout");
        } catch (InterruptedException e) {
            throw new SQLException(e);
        }
    }

    private static <T extends Component> T component(Container c, String name, Class<T> type) {
        return CustomerCreditDialogAdapterTest.component(c, name, type);
    }

    private static final class View implements AutoCloseable {
        JFrame frame;
        CustomerDocumentView view;

        View(CustomerDocumentGuiSession s) throws Exception {
            SwingUtilities.invokeAndWait(
                    () -> {
                        frame = new JFrame();
                        view = new CustomerDocumentView(frame, s);
                        frame.add(view);
                        frame.setSize(1150, 550);
                        frame.setVisible(true);
                    });
        }

        public void close() throws Exception {
            SwingUtilities.invokeAndWait(
                    () -> {
                        view.close();
                        frame.dispose();
                    });
        }
    }

    private static final class LegacyGuard implements AutoCloseable {
        final java.lang.reflect.Field field;
        final Object old;

        LegacyGuard() throws Exception {
            field = se.swedsoft.bookkeeping.data.system.SSDB.class.getDeclaredField("iConnection");
            field.setAccessible(true);
            var db = se.swedsoft.bookkeeping.data.system.SSDB.getInstance();
            old = field.get(db);
            field.set(
                    db,
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (p, m, a) -> {
                                throw new AssertionError("Legacy JDBC " + m.getName());
                            }));
        }

        public void close() throws Exception {
            field.set(se.swedsoft.bookkeeping.data.system.SSDB.getInstance(), old);
        }
    }
}
