package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.Kind;
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
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

@Tag("integration")
class SupplierRowDescriptionDialogGuiTest {
    @BeforeEach
    void display() {
        assumeFalse(GraphicsEnvironment.isHeadless());
    }

    @Test
    void bothSortedViewRoutesEditOnlyDescriptionAndRequireFreshConfirmationBeforeRefresh()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var guard = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit), bank = f.add(credit, 0, "25");
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "UPDATE "
                                    + SupplierRowDescriptionAdapterTest.table(credit)
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
                                            ? "supplier-credit-row-descriptions"
                                            : "supplier-invoice-row-descriptions",
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
                                                                                            SupplierRowDescriptionDialog
                                                                                    && w
                                                                                            .isShowing())));
                    var dialog =
                            edt(
                                    () ->
                                            (SupplierRowDescriptionDialog)
                                                    java.util.Arrays.stream(
                                                                    java.awt.Window.getWindows())
                                                            .filter(
                                                                    w ->
                                                                            w
                                                                                            instanceof
                                                                                            SupplierRowDescriptionDialog
                                                                                    && w
                                                                                            .isShowing())
                                                            .findFirst()
                                                            .orElseThrow());
                    try {
                        ready(dialog);
                        var before = f.snapshot();
                        var old = SupplierRowDescriptionAdapterTest.rows(f, credit, id);
                        edt(
                                () -> {
                                    var table = rows(dialog);
                                    table.getRowSorter().toggleSortOrder(0);
                                    table.getRowSorter().toggleSortOrder(0);
                                    assertEquals(7, table.getValueAt(0, 0));
                                    for (int column = 0; column < table.getColumnCount(); column++)
                                        assertEquals(column == 2, table.isCellEditable(1, column));
                                    table.setValueAt("  Edited Ö  ", 1, 2);
                                    return null;
                                });
                        preview(dialog);
                        assertEquals(before, f.snapshot());
                        assertEquals(0, events.get());
                        edt(
                                () -> {
                                    assertTrue(
                                            ((JTextArea) component(dialog, "summary"))
                                                    .getText()
                                                    .contains("Edited Ö"));
                                    assertFalse(button(dialog, "save").isEnabled());
                                    button(dialog, "confirm").doClick();
                                    assertTrue(button(dialog, "save").isEnabled());
                                    rows(dialog).setValueAt("Newer", 1, 2);
                                    assertFalse(
                                            ((JCheckBox) button(dialog, "confirm")).isSelected());
                                    assertFalse(button(dialog, "save").isEnabled());
                                    return null;
                                });
                        preview(dialog);
                        save(dialog);
                        waitFor(() -> events.get() == 1 && !edt(dialog::isDisplayable));
                        assertEquals(id, edt(() -> outer.getValueAt(outer.getSelectedRow(), 0)));
                        SupplierRowDescriptionAdapterTest.assertNarrow(
                                f, credit, id, before, old, Map.of(0, "Newer"));
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
    void untouchedSwingEditorsReturnRawHistoricalControlsAndNullAndRenderingDoesNotInterpretHtml()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                String raw = "<html>historical\ntext\t</html>";
                SupplierRowDescriptionAdapterTest.setRaw(f, credit, id, raw);
                var before = f.snapshot();
                AtomicInteger callbacks = new AtomicInteger();
                var dialog = create(f.session(), kind, id, callbacks);
                try {
                    ready(dialog);
                    edt(
                            () -> {
                                var table = rows(dialog);
                                var rendered =
                                        (JLabel)
                                                table.prepareRenderer(
                                                        table.getCellRenderer(0, 2), 0, 2);
                                assertTrue(rendered.getText().startsWith("\""));
                                assertTrue(rendered.getText().contains("\\u000a"));
                                assertNull(rendered.getClientProperty("html"));
                                assertTrue(table.editCellAt(0, 2));
                                assertTrue(table.getEditorComponent() instanceof JTextField);
                                assertTrue(table.getCellEditor().stopCellEditing());
                                assertEquals(raw, table.getValueAt(0, 2));
                                assertNull(table.getValueAt(1, 2));
                                assertTrue(table.editCellAt(1, 2));
                                assertEquals(
                                        "", ((JTextField) table.getEditorComponent()).getText());
                                assertTrue(table.getCellEditor().stopCellEditing());
                                assertNull(table.getValueAt(1, 2));
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
    void invalidDescriptionRequiresReloadAndReloadOrCancelDiscardsUncommittedCellEditorText()
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
                            rows(dialog).setValueAt(" \t", 0, 2);
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
                assertEquals("Original", edt(() -> rows(dialog).getValueAt(0, 2)));
                edt(
                        () -> {
                            assertTrue(rows(dialog).editCellAt(0, 2));
                            ((JTextField) rows(dialog).getEditorComponent())
                                    .setText("Unsaved editor");
                            button(dialog, "reload").doClick();
                            return null;
                        });
                ready(dialog);
                assertEquals("Original", edt(() -> rows(dialog).getValueAt(0, 2)));
                edt(
                        () -> {
                            rows(dialog).setValueAt("Unsaved draft", 0, 2);
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
    void staleConfirmationRefusesWithoutWritesAndReloadRestoresFreshRawSource() throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                boolean credit = kind == Kind.CREDIT;
                long id = document(f, credit);
                AtomicInteger callbacks = new AtomicInteger();
                var dialog = create(f.session(), kind, id, callbacks);
                try {
                    ready(dialog);
                    edt(
                            () -> {
                                rows(dialog).setValueAt("Edited", 0, 2);
                                return null;
                            });
                    preview(dialog);
                    SupplierRowDescriptionAdapterTest.setRaw(f, credit, id, "Concurrent");
                    var before = f.snapshot();
                    save(dialog);
                    waitFor(() -> edt(() -> button(dialog, "reload").isEnabled()));
                    assertFalse(edt(() -> button(dialog, "preview").isEnabled()));
                    assertFalse(edt(() -> button(dialog, "save").isEnabled()));
                    assertEquals(0, callbacks.get());
                    assertEquals(before, f.snapshot());
                    edt(
                            () -> {
                                button(dialog, "reload").doClick();
                                return null;
                            });
                    ready(dialog);
                    assertEquals("Concurrent", edt(() -> rows(dialog).getValueAt(0, 2)));
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
                                    new SupplierRowDescriptionDialog(
                                            null,
                                            f.session().rowDescriptions(Kind.INVOICE, id),
                                            saved -> callbacks.incrementAndGet()));
            try {
                ready(dialog);
                edt(
                        () -> {
                            rows(dialog).setValueAt("Edited", 0, 2);
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
                                        new SupplierRowDescriptionDialog(
                                                null,
                                                session.rowDescriptions(Kind.INVOICE, id),
                                                saved -> callbacks.incrementAndGet()));
                try {
                    ready(dialog);
                    edt(
                            () -> {
                                rows(dialog).setValueAt("Edited", 0, 2);
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
                            durable ? "Edited" : "Original",
                            f.stored(false, id).getRows().getFirst().getDescription());
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
                                    new SupplierRowDescriptionDialog(
                                            null,
                                            session.rowDescriptions(Kind.INVOICE, id),
                                            saved -> callbacks.incrementAndGet()));
            try {
                ready(dialog);
                edt(
                        () -> {
                            rows(dialog).setValueAt("Edited", 0, 2);
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
                assertEquals("Edited", f.stored(false, id).getRows().getFirst().getDescription());
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
                                    new SupplierRowDescriptionDialog(
                                            null,
                                            session.rowDescriptions(Kind.CREDIT, id),
                                            saved -> {
                                                callbacks.incrementAndGet();
                                                throw new IllegalStateException("refresh failed");
                                            }));
            try {
                ready(dialog);
                edt(
                        () -> {
                            rows(dialog).setValueAt("Edited", 0, 2);
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
                assertEquals("Edited", f.stored(true, id).getRows().getFirst().getDescription());
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
        long id = SupplierRowDescriptionAdapterTest.document(f, credit);
        SupplierRowDescriptionAdapterTest.setRaw(f, credit, id, "Original");
        return id;
    }

    private static SupplierRowDescriptionDialog create(
            SupplierDocumentGuiSession session, Kind kind, long id, AtomicInteger callbacks) {
        return edt(
                () ->
                        new SupplierRowDescriptionDialog(
                                null,
                                session.rowDescriptions(kind, id),
                                saved -> callbacks.incrementAndGet()));
    }

    private static void ready(SupplierRowDescriptionDialog d) throws Exception {
        waitFor(() -> edt(() -> button(d, "preview").isEnabled() && rows(d).getRowCount() == 2));
    }

    private static void preview(SupplierRowDescriptionDialog d) throws Exception {
        edt(
                () -> {
                    button(d, "preview").doClick();
                    return null;
                });
        waitFor(() -> edt(() -> button(d, "confirm").isEnabled()));
    }

    private static void save(SupplierRowDescriptionDialog d) {
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
                    && ("supplier-description-" + suffix).equals(c.getName())) return c;
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
        fail("Timed out waiting for supplier row descriptions");
    }
}
