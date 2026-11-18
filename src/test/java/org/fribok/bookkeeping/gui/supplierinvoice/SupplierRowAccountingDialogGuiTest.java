package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.Kind;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierRowAccountingService.Values;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

@Tag("integration")
class SupplierRowAccountingDialogGuiTest {
    @BeforeEach
    void display() {
        assumeFalse(GraphicsEnvironment.isHeadless());
    }

    @Test
    void
            bothSortedViewRoutesUseExplicitScopedChoicesAndFreshConfirmationWithoutFillingRequiredDimensions()
                    throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var guard = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit), bank = f.add(credit, 0, "25");
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "UPDATE "
                                    + SupplierRowAccountingAdapterTest.table(credit)
                                    + " SET bgc_entered=true WHERE id="
                                    + bank);
                }
                f.anchor.commit();
                AtomicInteger events = new AtomicInteger();
                var session =
                        new SupplierDocumentGuiSession(
                                () -> {
                                    assertFalse(SwingUtilities.isEventDispatchThread());
                                    return f.open();
                                },
                                f.gate,
                                71,
                                11,
                                SupplierJournalGuiFixture.CLOCK);
                try (var ui = new SupplierDocumentViewGuiTest.View(session)) {
                    SupplierDocumentViewGuiTest.ready(ui);
                    var outer =
                            SupplierDocumentViewGuiTest.component(
                                    ui.view,
                                    credit
                                            ? "normalized-supplier-credits"
                                            : "normalized-supplier-invoices",
                                    JTable.class);
                    var action =
                            SupplierDocumentViewGuiTest.component(
                                    ui.view,
                                    credit
                                            ? "supplier-credit-row-accounting"
                                            : "supplier-invoice-row-accounting",
                                    javax.swing.JButton.class);
                    edt(
                            () -> {
                                assertFalse(action.isEnabled());
                                outer.getRowSorter().toggleSortOrder(1);
                                SupplierDocumentViewGuiTest.select(outer, bank);
                                assertFalse(action.isEnabled());
                                SupplierDocumentViewGuiTest.select(outer, id);
                                assertTrue(action.isEnabled());
                                assertTrue(
                                        action.getY() + action.getHeight()
                                                <= action.getParent().getHeight());
                                outer.getModel()
                                        .addTableModelListener(
                                                e -> {
                                                    assertTrue(
                                                            SwingUtilities.isEventDispatchThread());
                                                    events.incrementAndGet();
                                                });
                                return null;
                            });
                    SwingUtilities.invokeLater(action::doClick);
                    waitFor(
                            () ->
                                    edt(
                                            () ->
                                                    java.util.Arrays.stream(
                                                                    java.awt.Window.getWindows())
                                                            .anyMatch(
                                                                    w ->
                                                                            w
                                                                                            instanceof
                                                                                            SupplierRowAccountingDialog
                                                                                    && w
                                                                                            .isShowing())));
                    var dialog =
                            edt(
                                    () ->
                                            (SupplierRowAccountingDialog)
                                                    java.util.Arrays.stream(
                                                                    java.awt.Window.getWindows())
                                                            .filter(
                                                                    w ->
                                                                            w
                                                                                            instanceof
                                                                                            SupplierRowAccountingDialog
                                                                                    && w
                                                                                            .isShowing())
                                                            .findFirst()
                                                            .orElseThrow());
                    try {
                        ready(dialog);
                        var before = f.snapshot();
                        var old = SupplierRowAccountingAdapterTest.rows(f, credit, id);
                        edt(
                                () -> {
                                    var table = rows(dialog);
                                    table.getRowSorter().toggleSortOrder(0);
                                    table.getRowSorter().toggleSortOrder(0);
                                    assertEquals(7, table.getValueAt(0, 0));
                                    for (int column = 0; column < table.getColumnCount(); column++)
                                        assertEquals(
                                                column >= 4 && column <= 6,
                                                table.isCellEditable(1, column));
                                    select(table, 1, 4, 4990);
                                    assertNull(table.getValueAt(1, 5));
                                    assertNull(table.getValueAt(1, 6));
                                    select(table, 1, 5, "P1");
                                    select(table, 1, 6, "R1");
                                    return null;
                                });
                        preview(dialog);
                        assertEquals(before, f.snapshot());
                        assertEquals(0, events.get());
                        edt(
                                () -> {
                                    String summary =
                                            ((JTextArea) component(dialog, "summary")).getText();
                                    assertTrue(summary.contains("4990"));
                                    assertTrue(summary.contains("P1"));
                                    assertTrue(summary.contains("R1"));
                                    assertFalse(button(dialog, "save").isEnabled());
                                    button(dialog, "confirm").doClick();
                                    assertTrue(button(dialog, "save").isEnabled());
                                    select(rows(dialog), 1, 5, "P2");
                                    assertFalse(
                                            ((JCheckBox) button(dialog, "confirm")).isSelected());
                                    assertFalse(button(dialog, "save").isEnabled());
                                    return null;
                                });
                        preview(dialog);
                        save(dialog);
                        waitFor(() -> events.get() == 1 && !edt(dialog::isDisplayable));
                        assertEquals(id, edt(() -> outer.getValueAt(outer.getSelectedRow(), 0)));
                        SupplierRowAccountingAdapterTest.assertNarrow(
                                f,
                                credit,
                                id,
                                before,
                                old,
                                Map.of(0, new Values(0, 4990, "P2", "R1")));
                    } finally {
                        edt(
                                () -> {
                                    dialog.dispose();
                                    return null;
                                });
                    }
                }
            }
    }

    @Test
    void historicalUnknownAccountConcludedProjectAndNullUnitRemainSelectedWithoutImplicitFallback()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "UPDATE "
                                    + SupplierRowAccountingAdapterTest.table(credit)
                                    + "_row SET"
                                    + " account_number=9999,project_number='OLD',result_unit_number=NULL"
                                    + " WHERE "
                                    + SupplierRowAccountingAdapterTest.table(credit)
                                    + "_id="
                                    + id
                                    + " AND row_number=0");
                }
                f.anchor.commit();
                var before = f.snapshot();
                AtomicInteger callbacks = new AtomicInteger();
                var dialog = create(f.session(), kind, id, callbacks);
                try {
                    ready(dialog);
                    edt(
                            () -> {
                                var table = rows(dialog);
                                for (int column : List.of(4, 5, 6)) {
                                    assertTrue(table.editCellAt(0, column));
                                    var box = (JComboBox<?>) table.getEditorComponent();
                                    assertFalse(box.isEditable());
                                    if (column == 4)
                                        assertTrue(
                                                box.getSelectedItem().toString().contains("9999"));
                                    if (column == 5)
                                        assertTrue(
                                                box.getSelectedItem().toString().contains("OLD"));
                                    if (column == 6)
                                        assertEquals("NULL", box.getSelectedItem().toString());
                                    assertTrue(table.getCellEditor().stopCellEditing());
                                }
                                assertEquals(9999, table.getValueAt(0, 4));
                                assertEquals("OLD", table.getValueAt(0, 5));
                                assertNull(table.getValueAt(0, 6));
                                return null;
                            });
                    preview(dialog);
                    save(dialog);
                    waitFor(() -> !edt(dialog::isDisplayable));
                    assertEquals(1, callbacks.get());
                    assertEquals(before, f.snapshot());
                } finally {
                    edt(
                            () -> {
                                dialog.dispose();
                                return null;
                            });
                }
            }
    }

    @Test
    void missingRequiredDimensionsRefuseSaveAndReloadCancelDiscardAllocationDrafts()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, false);
            var before = f.snapshot();
            AtomicInteger callbacks = new AtomicInteger();
            var dialog = create(f.session(), Kind.INVOICE, id, callbacks);
            try {
                ready(dialog);
                edt(
                        () -> {
                            select(rows(dialog), 0, 4, 4990);
                            assertNull(rows(dialog).getValueAt(0, 5));
                            assertNull(rows(dialog).getValueAt(0, 6));
                            button(dialog, "preview").doClick();
                            return null;
                        });
                waitFor(() -> edt(() -> button(dialog, "reload").isEnabled()));
                assertFalse(edt(() -> button(dialog, "preview").isEnabled()));
                assertFalse(edt(() -> button(dialog, "save").isEnabled()));
                assertEquals(before, f.snapshot());
                edt(
                        () -> {
                            button(dialog, "reload").doClick();
                            return null;
                        });
                ready(dialog);
                assertEquals(4010, edt(() -> rows(dialog).getValueAt(0, 4)));
                edt(
                        () -> {
                            select(rows(dialog), 0, 4, 4991);
                            button(dialog, "reload").doClick();
                            return null;
                        });
                ready(dialog);
                assertEquals(4010, edt(() -> rows(dialog).getValueAt(0, 4)));
                edt(
                        () -> {
                            select(rows(dialog), 0, 5, "P1");
                            button(dialog, "cancel").doClick();
                            return null;
                        });
                assertEquals(0, callbacks.get());
                assertEquals(before, f.snapshot());
            } finally {
                edt(
                        () -> {
                            dialog.dispose();
                            return null;
                        });
            }
        }
    }

    @Test
    void staleCatalogueConfirmationFailsAndReloadRefreshesSelectableChoices() throws Exception {
        for (var kind : Kind.values())
            for (boolean project : List.of(false, true))
                try (var f = new SupplierJournalGuiFixture()) {
                    boolean credit = kind == Kind.CREDIT;
                    long id = document(f, credit);
                    AtomicInteger callbacks = new AtomicInteger();
                    var dialog = create(f.session(), kind, id, callbacks);
                    try {
                        ready(dialog);
                        proposed(dialog);
                        preview(dialog);
                        try (var sql = f.anchor.createStatement()) {
                            sql.executeUpdate(
                                    project
                                            ? "UPDATE project SET concluded=true WHERE"
                                                  + " company_id=(SELECT id FROM company WHERE"
                                                  + " legacy_id=71) AND number='P1'"
                                            : "UPDATE account SET active=false WHERE"
                                                  + " accounting_year_id=(SELECT id FROM"
                                                  + " accounting_year WHERE legacy_id=11) AND"
                                                  + " number=4990");
                        }
                        f.anchor.commit();
                        var before = f.snapshot();
                        save(dialog);
                        waitFor(() -> edt(() -> button(dialog, "reload").isEnabled()));
                        assertFalse(edt(() -> button(dialog, "preview").isEnabled()));
                        assertEquals(0, callbacks.get());
                        assertEquals(before, f.snapshot());
                        edt(
                                () -> {
                                    button(dialog, "reload").doClick();
                                    return null;
                                });
                        ready(dialog);
                        edt(
                                () -> {
                                    assertEquals(4010, rows(dialog).getValueAt(0, 4));
                                    assertTrue(rows(dialog).editCellAt(0, project ? 5 : 4));
                                    var box = (JComboBox<?>) rows(dialog).getEditorComponent();
                                    for (int i = 0; i < box.getItemCount(); i++)
                                        assertFalse(
                                                box.getItemAt(i)
                                                        .toString()
                                                        .startsWith(project ? "\"P1\" " : "4990 "));
                                    rows(dialog).getCellEditor().cancelCellEditing();
                                    return null;
                                });
                    } finally {
                        edt(
                                () -> {
                                    dialog.dispose();
                                    return null;
                                });
                    }
                }
    }

    @Test
    void selectionReplacementClosesLoadedDraftAndCannotWrite() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, false);
            var before = f.snapshot();
            AtomicInteger callbacks = new AtomicInteger();
            var dialog =
                    edt(
                            () ->
                                    new SupplierRowAccountingDialog(
                                            null,
                                            f.session().rowAccounting(Kind.INVOICE, id),
                                            saved -> callbacks.incrementAndGet()));
            try {
                ready(dialog);
                edt(
                        () -> {
                            proposed(dialog);
                            return null;
                        });
                preview(dialog);
                edt(
                        () -> {
                            dialog.invalidateSelection();
                            button(dialog, "confirm").doClick();
                            button(dialog, "save").doClick();
                            return null;
                        });
                assertFalse(edt(dialog::isDisplayable));
                assertEquals(0, callbacks.get());
                assertEquals(before, f.snapshot());
            } finally {
                edt(
                        () -> {
                            dialog.dispose();
                            return null;
                        });
            }
        }
    }

    @Test
    void unknownCommitDisablesDialogWithoutRefreshOnBothDurabilitySides() throws Exception {
        for (boolean durable : List.of(false, true))
            try (var f = new SupplierJournalGuiFixture()) {
                long id = document(f, false);
                AtomicInteger callbacks = new AtomicInteger();
                var session =
                        new SupplierDocumentGuiSession(
                                () -> {
                                    var c = f.open();
                                    return SupplierJournalGuiFixture.wrap(
                                            c,
                                            (method, args) -> {
                                                if (method.getName().equals("commit")) {
                                                    if (durable) c.commit();
                                                    throw new java.sql.SQLException(
                                                            "Unknown commit");
                                                }
                                                return SupplierJournalGuiFixture.invoke(
                                                        c, method, args);
                                            });
                                },
                                f.gate,
                                71,
                                11,
                                SupplierJournalGuiFixture.CLOCK);
                var dialog =
                        edt(
                                () ->
                                        new SupplierRowAccountingDialog(
                                                null,
                                                session.rowAccounting(Kind.INVOICE, id),
                                                saved -> callbacks.incrementAndGet()));
                try {
                    ready(dialog);
                    edt(
                            () -> {
                                proposed(dialog);
                                return null;
                            });
                    preview(dialog);
                    edt(
                            () -> {
                                button(dialog, "confirm").doClick();
                                button(dialog, "save").doClick();
                                return null;
                            });
                    waitFor(session::isBlocked);
                    waitFor(() -> edt(() -> button(dialog, "cancel").isEnabled()));
                    assertFalse(edt(() -> button(dialog, "save").isEnabled()));
                    assertFalse(edt(() -> button(dialog, "reload").isEnabled()));
                    assertFalse(edt(() -> button(dialog, "preview").isEnabled()));
                    assertEquals(0, callbacks.get());
                    assertEquals(
                            durable ? 4990 : 4010,
                            f.stored(false, id).getRows().getFirst().getAccountNr());
                } finally {
                    edt(
                            () -> {
                                dialog.dispose();
                                return null;
                            });
                }
            }
    }

    @Test
    void acceptedSaveMayFinishOriginalContextButReplacementSuppressesRefresh() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, false);
            AtomicInteger callbacks = new AtomicInteger();
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var session =
                    new SupplierDocumentGuiSession(
                            () -> {
                                var c = f.open();
                                return SupplierJournalGuiFixture.wrap(
                                        c,
                                        (method, args) -> {
                                            if (method.getName().equals("commit")) {
                                                entered.countDown();
                                                if (!release.await(
                                                        10, java.util.concurrent.TimeUnit.SECONDS))
                                                    throw new java.sql.SQLException("Timed out");
                                            }
                                            return SupplierJournalGuiFixture.invoke(
                                                    c, method, args);
                                        });
                            },
                            f.gate,
                            71,
                            11,
                            SupplierJournalGuiFixture.CLOCK);
            var dialog =
                    edt(
                            () ->
                                    new SupplierRowAccountingDialog(
                                            null,
                                            session.rowAccounting(Kind.INVOICE, id),
                                            saved -> callbacks.incrementAndGet()));
            try {
                ready(dialog);
                edt(
                        () -> {
                            proposed(dialog);
                            return null;
                        });
                preview(dialog);
                edt(
                        () -> {
                            button(dialog, "confirm").doClick();
                            button(dialog, "save").doClick();
                            return null;
                        });
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
                edt(
                        () -> {
                            dialog.invalidateSelection();
                            return null;
                        });
                release.countDown();
                waitFor(() -> !edt(dialog::isDisplayable));
                assertEquals(0, callbacks.get());
                assertFalse(session.isBlocked());
                assertEquals(4990, f.stored(false, id).getRows().getFirst().getAccountNr());
            } finally {
                release.countDown();
                edt(
                        () -> {
                            dialog.dispose();
                            return null;
                        });
            }
        }
    }

    @Test
    void callbackFailureAfterKnownCommitDoesNotRetryOrBlockSession() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = document(f, true);
            AtomicInteger callbacks = new AtomicInteger();
            var session = f.session();
            var dialog =
                    edt(
                            () ->
                                    new SupplierRowAccountingDialog(
                                            null,
                                            session.rowAccounting(Kind.CREDIT, id),
                                            saved -> {
                                                callbacks.incrementAndGet();
                                                throw new IllegalStateException("refresh failed");
                                            }));
            try {
                ready(dialog);
                edt(
                        () -> {
                            proposed(dialog);
                            return null;
                        });
                preview(dialog);
                edt(
                        () -> {
                            button(dialog, "confirm").doClick();
                            button(dialog, "save").doClick();
                            return null;
                        });
                waitFor(() -> !edt(dialog::isDisplayable));
                assertEquals(1, callbacks.get());
                assertFalse(session.isBlocked());
                assertEquals(4990, f.stored(true, id).getRows().getFirst().getAccountNr());
            } finally {
                edt(
                        () -> {
                            dialog.dispose();
                            return null;
                        });
            }
        }
    }

    private static long document(SupplierJournalGuiFixture f, boolean credit) throws Exception {
        return SupplierRowAccountingAdapterTest.document(f, credit);
    }

    private static SupplierRowAccountingDialog create(
            SupplierDocumentGuiSession session, Kind kind, long id, AtomicInteger callbacks) {
        return edt(
                () ->
                        new SupplierRowAccountingDialog(
                                null,
                                session.rowAccounting(kind, id),
                                saved -> callbacks.incrementAndGet()));
    }

    private static void proposed(SupplierRowAccountingDialog d) {
        edt(
                () -> {
                    select(rows(d), 0, 4, 4990);
                    select(rows(d), 0, 5, "P1");
                    select(rows(d), 0, 6, "R1");
                    return null;
                });
    }

    private static void select(JTable table, int row, int column, Object value) {
        assertTrue(table.editCellAt(row, column));
        var box = (JComboBox<?>) table.getEditorComponent();
        assertFalse(box.isEditable());
        String prefix =
                value == null
                        ? "NULL"
                        : value instanceof Integer ? value + " " : "\"" + value + "\" ";
        for (int i = 0; i < box.getItemCount(); i++) {
            String label = box.getItemAt(i).toString();
            if (value == null ? label.equals(prefix) : label.startsWith(prefix)) {
                box.setSelectedIndex(i);
                if (table.isEditing()) assertTrue(table.getCellEditor().stopCellEditing());
                return;
            }
        }
        throw new AssertionError("Missing allocation choice " + value);
    }

    private static void ready(SupplierRowAccountingDialog d) throws Exception {
        waitFor(() -> edt(() -> button(d, "preview").isEnabled() && rows(d).getRowCount() == 2));
    }

    private static void preview(SupplierRowAccountingDialog d) throws Exception {
        edt(
                () -> {
                    button(d, "preview").doClick();
                    return null;
                });
        waitFor(() -> edt(() -> button(d, "confirm").isEnabled()));
    }

    private static void save(SupplierRowAccountingDialog d) {
        edt(
                () -> {
                    button(d, "confirm").doClick();
                    button(d, "save").doClick();
                    return null;
                });
    }

    private static JTable rows(Container d) {
        return (JTable) component(d, "rows");
    }

    private static AbstractButton button(Container d, String name) {
        return (AbstractButton) component(d, name);
    }

    private static JComponent component(Container root, String suffix) {
        for (Component child : root.getComponents()) {
            if (child instanceof JComponent c
                    && ("supplier-allocation-" + suffix).equals(c.getName())) return c;
            if (child instanceof Container c) {
                JComponent found = component(c, suffix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T edt(Callable<T> action) {
        try {
            if (SwingUtilities.isEventDispatchThread()) return action.call();
            var task = new FutureTask<T>(action);
            SwingUtilities.invokeAndWait(task);
            return task.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void waitFor(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for supplier row accounting");
    }
}
