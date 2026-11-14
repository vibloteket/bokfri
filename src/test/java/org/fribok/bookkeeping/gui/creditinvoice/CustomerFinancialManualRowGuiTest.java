package org.fribok.bookkeeping.gui.creditinvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.fribok.bookkeeping.service.invoice.NormalizedCustomerFinancialEditService.Kind;

@Tag("integration")
class CustomerFinancialManualRowGuiTest {
    @Test
    void bothViewRoutesInsertSortedDraftOnlyAfterFreshKeyPreviewAndConfirmationTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (var kind : Kind.values())
            try (var f = new CustomerCreditGuiFixture();
                    var ignored = new LegacyGuard()) {
                long id = CustomerFinancialManualRowTest.target(f, kind);
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
                AtomicReference<JFrame> owner = new AtomicReference<>();
                AtomicReference<CustomerDocumentView> ref = new AtomicReference<>();
                AtomicInteger refreshes = new AtomicInteger();
                try {
                    edt(
                            () -> {
                                var frame = new JFrame();
                                owner.set(frame);
                                var view = new CustomerDocumentView(frame, session);
                                ref.set(view);
                                frame.add(view);
                                frame.setSize(1150, 550);
                                frame.setVisible(true);
                            });
                    var view = ref.get();
                    var docs =
                            component(
                                    view,
                                    kind == Kind.INVOICE
                                            ? "normalized-invoices"
                                            : "normalized-credits",
                                    JTable.class);
                    waitFor(() -> docs.getRowCount() == (kind == Kind.INVOICE ? 2 : 1));
                    edt(
                            () -> {
                                select(docs, (int) id);
                                docs.getModel()
                                        .addTableModelListener(
                                                event -> {
                                                    if (docs.getRowCount() > 0) {
                                                        assertTrue(
                                                                SwingUtilities
                                                                        .isEventDispatchThread());
                                                        refreshes.incrementAndGet();
                                                    }
                                                });
                            });
                    SwingUtilities.invokeLater(
                            () ->
                                    button(
                                                    view,
                                                    kind == Kind.INVOICE
                                                            ? "invoice-edit"
                                                            : "credit-edit")
                                            .doClick());
                    var d = showing();
                    ready(d);
                    try {
                        var rows = component(d, "financial-lines", JTable.class);
                        edt(() -> button(d, "financial-preview").doClick());
                        previewed(d);
                        edt(
                                () -> {
                                    check(d).doClick();
                                    rows.getRowSorter().toggleSortOrder(0);
                                    rows.getRowSorter().toggleSortOrder(0);
                                    button(d, "financial-add-row").doClick();
                                    assertFalse(check(d).isSelected());
                                    assertFalse(button(d, "financial-save").isEnabled());
                                    assertEquals(2, rows.getRowCount());
                                    assertEquals(20, rows.getValueAt(rows.getSelectedRow(), 0));
                                    button(d, "financial-remove-row").doClick();
                                    assertEquals(1, rows.getRowCount());
                                    button(d, "financial-add-row").doClick();
                                    assertEquals(21, rows.getValueAt(rows.getSelectedRow(), 0));
                                });
                        assertEquals(
                                1,
                                CustomerFinancialManualRowTest.editor(f, kind, id)
                                        .load()
                                        .data()
                                        .lines()
                                        .size());
                        edt(() -> button(d, "financial-reload").doClick());
                        ready(d);
                        edt(
                                () -> {
                                    assertEquals(1, rows.getRowCount());
                                    button(d, "financial-add-row").doClick();
                                    fill(rows);
                                    button(d, "financial-preview").doClick();
                                });
                        previewed(d);
                        edt(
                                () -> {
                                    assertTrue(status(d).getText().contains("[20]"));
                                    assertTrue(status(d).getText().contains("60"));
                                    assertFalse(button(d, "financial-save").isEnabled());
                                    check(d).doClick();
                                    rows.setValueAt("Manual changed", rows.getSelectedRow(), 1);
                                    assertFalse(check(d).isSelected());
                                    assertFalse(button(d, "financial-save").isEnabled());
                                    button(d, "financial-preview").doClick();
                                });
                        previewed(d);
                        edt(
                                () -> {
                                    check(d).doClick();
                                    button(d, "financial-save").doClick();
                                });
                        waitFor(() -> !d.isDisplayable() && refreshes.get() == 1);
                        var saved =
                                CustomerFinancialManualRowTest.editor(f, kind, id).load().data();
                        assertEquals(
                                List.of(19, 20),
                                saved.lines().stream().map(l -> l.rowNumber()).toList());
                        assertEquals("P-Å", saved.lines().getFirst().project());
                        assertEquals("Manual changed", saved.lines().getLast().description());
                        assertEquals("P-B", saved.lines().getLast().project());
                        assertEquals(
                                777,
                                CustomerFinancialAccountingTest.document(f, kind, id)
                                        .getStoredVoucher()
                                        .getNumber());
                    } finally {
                        edt(d::dispose);
                    }
                } finally {
                    edt(
                            () -> {
                                if (ref.get() != null) ref.get().close();
                                if (owner.get() != null) owner.get().dispose();
                            });
                    session.close();
                }
            }
    }

    @Test
    void invalidNewRowAndStaleSourceDoNotInsertTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new CustomerCreditGuiFixture();
                var ignored = new LegacyGuard()) {
            long id = CustomerFinancialManualRowTest.target(f, Kind.INVOICE);
            var editor = CustomerFinancialManualRowTest.editor(f, Kind.INVOICE, id);
            AtomicInteger callbacks = new AtomicInteger();
            var d = create(editor, saved -> callbacks.incrementAndGet());
            ready(d);
            try {
                edt(
                        () -> {
                            button(d, "financial-add-row").doClick();
                            button(d, "financial-preview").doClick();
                        });
                waitFor(() -> button(d, "financial-preview").isEnabled() && !check(d).isEnabled());
                edt(
                        () -> {
                            fill(component(d, "financial-lines", JTable.class));
                            button(d, "financial-preview").doClick();
                        });
                previewed(d);
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "UPDATE customer_invoice_row SET description='external edit' WHERE"
                                    + " invoice_id="
                                    + id);
                }
                f.anchor.commit();
                edt(
                        () -> {
                            check(d).doClick();
                            button(d, "financial-save").doClick();
                        });
                waitFor(() -> status(d).getText().contains("stale"));
                edt(
                        () -> {
                            assertFalse(check(d).isEnabled());
                            button(d, "financial-reload").doClick();
                        });
                ready(d);
                edt(
                        () ->
                                assertEquals(
                                        1,
                                        component(d, "financial-lines", JTable.class)
                                                .getRowCount()));
                assertEquals(0, callbacks.get());
                assertEquals(1, editor.load().data().lines().size());
            } finally {
                edt(d::dispose);
            }
        }
    }

    @Test
    void unknownInsertCommitDisablesAddAndBlindRetryRegardlessOfDurabilityTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (boolean durable : List.of(false, true))
            try (var f = new CustomerCreditGuiFixture();
                    var ignored = new LegacyGuard()) {
                long id = CustomerFinancialManualRowTest.target(f, Kind.INVOICE);
                var editor =
                        new CustomerFinancialEditAdapter(
                                () -> {
                                    var c = f.open();
                                    return (Connection)
                                            Proxy.newProxyInstance(
                                                    Connection.class.getClassLoader(),
                                                    new Class<?>[] {Connection.class},
                                                    (p, m, a) -> {
                                                        if (m.getName().equals("commit")) {
                                                            if (durable) c.commit();
                                                            throw new SQLException(
                                                                    "Unknown manual insertion");
                                                        }
                                                        try {
                                                            return m.invoke(c, a);
                                                        } catch (InvocationTargetException e) {
                                                            throw e.getCause();
                                                        }
                                                    });
                                },
                                f.gate,
                                71,
                                11,
                                Kind.INVOICE,
                                id,
                                CustomerCreditGuiFixture.CLOCK);
                AtomicInteger callbacks = new AtomicInteger();
                var d = create(editor, saved -> callbacks.incrementAndGet());
                ready(d);
                try {
                    edt(
                            () -> {
                                button(d, "financial-add-row").doClick();
                                fill(component(d, "financial-lines", JTable.class));
                                button(d, "financial-preview").doClick();
                            });
                    previewed(d);
                    edt(
                            () -> {
                                check(d).doClick();
                                button(d, "financial-save").doClick();
                            });
                    waitFor(() -> editor.isBlocked() && button(d, "financial-cancel").isEnabled());
                    edt(
                            () -> {
                                for (String action :
                                        List.of(
                                                "financial-add-row",
                                                "financial-remove-row",
                                                "financial-reload",
                                                "financial-save"))
                                    assertFalse(button(d, action).isEnabled());
                                assertFalse(
                                        component(d, "financial-lines", JTable.class).isEnabled());
                            });
                    assertEquals(0, callbacks.get());
                    assertEquals(
                            durable ? 2 : 1,
                            CustomerFinancialManualRowTest.editor(f, Kind.INVOICE, id)
                                    .load()
                                    .data()
                                    .lines()
                                    .size());
                } finally {
                    edt(d::dispose);
                }
            }
    }

    private static void fill(JTable rows) {
        int row = rows.getSelectedRow();
        rows.setValueAt("Manual Å", row, 1);
        rows.setValueAt(3022, row, 2);
        rows.setValueAt(new java.math.BigDecimal("2"), row, 3);
        rows.setValueAt(java.math.BigDecimal.TEN, row, 4);
        rows.setValueAt("P-B", row, 7);
        rows.setValueAt("U-1", row, 8);
    }

    private static void select(JTable rows, int key) {
        for (int row = 0; row < rows.getRowCount(); row++)
            if (rows.getValueAt(row, 0) instanceof Number n && n.longValue() == key) {
                rows.setRowSelectionInterval(row, row);
                return;
            }
        throw new AssertionError("Missing key " + key);
    }

    private static CustomerFinancialEditDialog create(
            CustomerFinancialEditAdapter editor,
            java.util.function.Consumer<CustomerFinancialEditAdapter.Saved> saved)
            throws Exception {
        AtomicReference<CustomerFinancialEditDialog> ref = new AtomicReference<>();
        edt(() -> ref.set(new CustomerFinancialEditDialog(null, editor, saved)));
        return ref.get();
    }

    private static JButton button(Container p, String name) {
        return component(p, name, JButton.class);
    }

    private static JCheckBox check(CustomerFinancialEditDialog d) {
        return component(d, "financial-confirm", JCheckBox.class);
    }

    private static JTextArea status(CustomerFinancialEditDialog d) {
        return component(d, "financial-status", JTextArea.class);
    }

    private static void ready(CustomerFinancialEditDialog d) throws Exception {
        waitFor(() -> button(d, "financial-preview").isEnabled());
    }

    private static void previewed(CustomerFinancialEditDialog d) throws Exception {
        waitFor(() -> check(d).isEnabled());
    }

    private static void edt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        CustomerCreditDialogAdapterTest.waitFor(condition);
    }

    private static CustomerFinancialEditDialog showing() throws Exception {
        AtomicReference<CustomerFinancialEditDialog> ref = new AtomicReference<>();
        waitFor(
                () -> {
                    for (Window w : Window.getWindows())
                        if (w instanceof CustomerFinancialEditDialog d && d.isShowing()) ref.set(d);
                    return ref.get() != null;
                });
        return ref.get();
    }

    private static <T extends Component> T component(Container p, String name, Class<T> type) {
        for (Component c : p.getComponents()) {
            if (name.equals(c.getName())) return type.cast(c);
            if (c instanceof Container nested) {
                T r = component(nested, name, type);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static final class LegacyGuard implements AutoCloseable {
        final Field field;
        final Object db, old;

        LegacyGuard() throws Exception {
            field = se.swedsoft.bookkeeping.data.system.SSDB.class.getDeclaredField("iConnection");
            field.setAccessible(true);
            db = se.swedsoft.bookkeeping.data.system.SSDB.getInstance();
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
            field.set(db, old);
        }
    }
}
