package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierRowAmountsService.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

@Tag("integration")
class SupplierRowAmountsDialogGuiTest {
    @BeforeEach
    void display() {
        assumeFalse(GraphicsEnvironment.isHeadless());
    }

    @Test
    void bothFamiliesUseSortedStoredKeysPreviewAndFreshConfirmationThenRefreshOnce()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var guard = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = SupplierRowAmountsAdapterTest.document(f, credit, 5);
                AtomicInteger callbacks = new AtomicInteger();
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
                var dialog =
                        edt(
                                () ->
                                        new SupplierRowAmountsDialog(
                                                null,
                                                session.rowAmounts(kind, id),
                                                saved -> {
                                                    assertTrue(
                                                            SwingUtilities.isEventDispatchThread());
                                                    callbacks.incrementAndGet();
                                                }));
                try {
                    ready(dialog);
                    var before = f.snapshot();
                    edt(
                            () -> {
                                var table = rows(dialog);
                                table.getRowSorter().toggleSortOrder(0);
                                table.getRowSorter().toggleSortOrder(0);
                                assertEquals(7, table.getValueAt(0, 0));
                                assertFalse(table.isCellEditable(0, 1));
                                table.setValueAt("3", 1, 7);
                                table.setValueAt("20", 1, 8);
                                table.setValueAt("4", 1, 9);
                                return null;
                            });
                    preview(dialog);
                    assertEquals(before, f.snapshot());
                    assertFalse(edt(() -> button(dialog, "save").isEnabled()));
                    assertTrue(
                            edt(
                                    () ->
                                            ((JTextArea) component(dialog, "summary"))
                                                    .getText()
                                                    .contains("137.50")));
                    edt(
                            () -> {
                                button(dialog, "confirm").doClick();
                                assertTrue(button(dialog, "save").isEnabled());
                                rows(dialog).setValueAt("21", 1, 8);
                                assertFalse(((JCheckBox) button(dialog, "confirm")).isSelected());
                                assertFalse(button(dialog, "save").isEnabled());
                                return null;
                            });
                    preview(dialog);
                    edt(
                            () -> {
                                button(dialog, "confirm").doClick();
                                button(dialog, "save").doClick();
                                return null;
                            });
                    waitFor(() -> callbacks.get() == 1);
                    waitFor(() -> !edt(dialog::isDisplayable));
                    var stored = f.stored(credit, id);
                    assertEquals(3, stored.getRows().getFirst().getQuantity());
                    SupplierRowAmountsAdapterTest.decimal(
                            "21", stored.getRows().getFirst().getUnitprice());
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
    void invalidDraftWritesNothingAndReloadDiscardsDraftThenCancelDoesNotSave() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = SupplierRowAmountsAdapterTest.document(f, false, 5);
            var before = f.snapshot();
            AtomicInteger callbacks = new AtomicInteger();
            var dialog =
                    edt(
                            () ->
                                    new SupplierRowAmountsDialog(
                                            null,
                                            f.session().rowAmounts(Kind.INVOICE, id),
                                            saved -> callbacks.incrementAndGet()));
            try {
                ready(dialog);
                edt(
                        () -> {
                            rows(dialog).setValueAt("1.5", 0, 7);
                            button(dialog, "preview").doClick();
                            assertFalse(button(dialog, "save").isEnabled());
                            button(dialog, "reload").doClick();
                            return null;
                        });
                ready(dialog);
                assertEquals("1", edt(() -> rows(dialog).getValueAt(0, 7)));
                assertEquals(before, f.snapshot());
                edt(
                        () -> {
                            rows(dialog).setValueAt("9", 0, 7);
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
    void staleConfirmationFailsAndRequiresReloadWithoutChangingRows() throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                long id = SupplierRowAmountsAdapterTest.document(f, kind == Kind.CREDIT, 5);
                AtomicInteger callbacks = new AtomicInteger();
                var dialog =
                        edt(
                                () ->
                                        new SupplierRowAmountsDialog(
                                                null,
                                                f.session().rowAmounts(kind, id),
                                                saved -> callbacks.incrementAndGet()));
                try {
                    ready(dialog);
                    edt(
                            () -> {
                                rows(dialog).setValueAt("3", 0, 7);
                                return null;
                            });
                    preview(dialog);
                    try (var s = f.anchor.createStatement()) {
                        s.executeUpdate(
                                "UPDATE "
                                        + SupplierRowAmountsAdapterTest.table(kind == Kind.CREDIT)
                                        + " SET reference_number='Concurrent' WHERE id="
                                        + id);
                    }
                    f.anchor.commit();
                    var before = f.snapshot();
                    edt(
                            () -> {
                                button(dialog, "confirm").doClick();
                                button(dialog, "save").doClick();
                                return null;
                            });
                    waitFor(() -> edt(() -> button(dialog, "reload").isEnabled()));
                    assertFalse(edt(() -> button(dialog, "save").isEnabled()));
                    assertFalse(edt(() -> button(dialog, "preview").isEnabled()));
                    assertEquals(0, callbacks.get());
                    assertEquals(before, f.snapshot());
                    edt(
                            () -> {
                                button(dialog, "reload").doClick();
                                return null;
                            });
                    ready(dialog);
                    assertEquals("1", edt(() -> rows(dialog).getValueAt(0, 7)));
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
            long id = SupplierRowAmountsAdapterTest.document(f, false, 5);
            var before = f.snapshot();
            AtomicInteger callbacks = new AtomicInteger();
            var dialog =
                    edt(
                            () ->
                                    new SupplierRowAmountsDialog(
                                            null,
                                            f.session().rowAmounts(Kind.INVOICE, id),
                                            saved -> callbacks.incrementAndGet()));
            try {
                ready(dialog);
                edt(
                        () -> {
                            rows(dialog).setValueAt("3", 0, 7);
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
    void callbackFailureAfterKnownCommitDoesNotRetryOrBlockSession() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = SupplierRowAmountsAdapterTest.document(f, true, 5);
            AtomicInteger callbacks = new AtomicInteger();
            var session = f.session();
            var dialog =
                    edt(
                            () ->
                                    new SupplierRowAmountsDialog(
                                            null,
                                            session.rowAmounts(Kind.CREDIT, id),
                                            saved -> {
                                                callbacks.incrementAndGet();
                                                throw new IllegalStateException("refresh failed");
                                            }));
            try {
                ready(dialog);
                edt(
                        () -> {
                            rows(dialog).setValueAt("3", 0, 7);
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
                assertEquals(3, f.stored(true, id).getRows().getFirst().getQuantity());
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
    void sortedViewRoutesBothFamiliesAndRefreshesAfterConfirmedSave() throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                boolean credit = kind == Kind.CREDIT;
                long id = SupplierRowAmountsAdapterTest.document(f, credit, 5),
                        bank = f.add(credit, 0, "25");
                try (var statement = f.anchor.createStatement()) {
                    statement.executeUpdate(
                            "UPDATE "
                                    + SupplierRowAmountsAdapterTest.table(credit)
                                    + " SET bgc_entered=true WHERE id="
                                    + bank);
                }
                f.anchor.commit();
                var session = f.session();
                AtomicInteger refresh = new AtomicInteger();
                try (var ui = new SupplierDocumentViewGuiTest.View(session)) {
                    SupplierDocumentViewGuiTest.ready(ui);
                    var table =
                            SupplierDocumentViewGuiTest.component(
                                    ui.view,
                                    credit
                                            ? "normalized-supplier-credits"
                                            : "normalized-supplier-invoices",
                                    JTable.class);
                    var action =
                            SupplierDocumentViewGuiTest.component(
                                    ui.view,
                                    credit ? "supplier-credit-amounts" : "supplier-invoice-amounts",
                                    javax.swing.JButton.class);
                    edt(
                            () -> {
                                assertFalse(action.isEnabled());
                                table.getRowSorter().toggleSortOrder(1);
                                SupplierDocumentViewGuiTest.select(table, bank);
                                assertFalse(action.isEnabled());
                                SupplierDocumentViewGuiTest.select(table, id);
                                assertTrue(action.isEnabled());
                                assertTrue(
                                        action.getY() + action.getHeight()
                                                <= action.getParent().getHeight());
                                table.getModel()
                                        .addTableModelListener(event -> refresh.incrementAndGet());
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
                                                                                            SupplierRowAmountsDialog
                                                                                    && w
                                                                                            .isShowing())));
                    var dialog =
                            edt(
                                    () ->
                                            (SupplierRowAmountsDialog)
                                                    java.util.Arrays.stream(
                                                                    java.awt.Window.getWindows())
                                                            .filter(
                                                                    w ->
                                                                            w
                                                                                            instanceof
                                                                                            SupplierRowAmountsDialog
                                                                                    && w
                                                                                            .isShowing())
                                                            .findFirst()
                                                            .orElseThrow());
                    try {
                        ready(dialog);
                        edt(
                                () -> {
                                    rows(dialog).setValueAt("3", 0, 7);
                                    return null;
                                });
                        preview(dialog);
                        edt(
                                () -> {
                                    button(dialog, "confirm").doClick();
                                    button(dialog, "save").doClick();
                                    return null;
                                });
                        waitFor(() -> refresh.get() == 1 && !edt(dialog::isDisplayable));
                        assertEquals(id, edt(() -> table.getValueAt(table.getSelectedRow(), 0)));
                        assertEquals(3, f.stored(credit, id).getRows().getFirst().getQuantity());
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
    void unknownCommitDisablesDialogWithoutRefreshOnBothDurabilitySides() throws Exception {
        for (boolean durable : List.of(false, true))
            try (var f = new SupplierJournalGuiFixture()) {
                long id = SupplierRowAmountsAdapterTest.document(f, false, 5);
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
                                        new SupplierRowAmountsDialog(
                                                null,
                                                session.rowAmounts(Kind.INVOICE, id),
                                                saved -> callbacks.incrementAndGet()));
                try {
                    ready(dialog);
                    edt(
                            () -> {
                                rows(dialog).setValueAt("3", 0, 7);
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
                            durable ? 3 : 1,
                            f.stored(false, id).getRows().getFirst().getQuantity());
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
            long id = SupplierRowAmountsAdapterTest.document(f, false, 5);
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
                                    new SupplierRowAmountsDialog(
                                            null,
                                            session.rowAmounts(Kind.INVOICE, id),
                                            saved -> callbacks.incrementAndGet()));
            try {
                ready(dialog);
                edt(
                        () -> {
                            rows(dialog).setValueAt("3", 0, 7);
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
                assertEquals(3, f.stored(false, id).getRows().getFirst().getQuantity());
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

    private static void ready(SupplierRowAmountsDialog d) throws Exception {
        waitFor(() -> edt(() -> button(d, "preview").isEnabled() && rows(d).getRowCount() == 2));
    }

    private static void preview(SupplierRowAmountsDialog d) throws Exception {
        edt(
                () -> {
                    button(d, "preview").doClick();
                    return null;
                });
        waitFor(() -> edt(() -> button(d, "confirm").isEnabled()));
    }

    private static JTable rows(Container d) {
        return (JTable) component(d, "rows");
    }

    private static AbstractButton button(Container d, String name) {
        return (AbstractButton) component(d, name);
    }

    private static JComponent component(Container root, String suffix) {
        for (Component child : root.getComponents()) {
            if (child instanceof JComponent c && ("supplier-amount-" + suffix).equals(c.getName()))
                return c;
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
        fail("Timed out waiting for amount editor");
    }
}
