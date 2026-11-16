package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDatesService.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.time.LocalDate;
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
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

@Tag("integration")
class SupplierDatesDialogGuiTest {
    @BeforeEach
    void display() {
        assumeFalse(GraphicsEnvironment.isHeadless());
    }

    @Test
    void bothSortedViewRoutesPreviewExplicitDatesResetConfirmationAndRefreshOnlyAfterCommit()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var guard = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = SupplierDatesAdapterTest.document(f, credit),
                        bank = f.add(credit, 0, "25");
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE "
                                    + SupplierDatesAdapterTest.table(credit)
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
                                    credit ? "supplier-credit-dates" : "supplier-invoice-dates",
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
                                        .addTableModelListener(
                                                event -> {
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
                                                                                            SupplierDatesDialog
                                                                                    && w
                                                                                            .isShowing())));
                    var dialog =
                            edt(
                                    () ->
                                            (SupplierDatesDialog)
                                                    java.util.Arrays.stream(
                                                                    java.awt.Window.getWindows())
                                                            .filter(
                                                                    w ->
                                                                            w
                                                                                            instanceof
                                                                                            SupplierDatesDialog
                                                                                    && w
                                                                                            .isShowing())
                                                            .findFirst()
                                                            .orElseThrow());
                    try {
                        ready(dialog);
                        var before = f.snapshot();
                        var header = SupplierDocumentMetadataAdapterTest.header(f, credit, id);
                        edt(
                                () -> {
                                    assertEquals("2026-03-04", field(dialog, "due").getText());
                                    field(dialog, "invoice").setText("2026-04-01");
                                    assertEquals("2026-03-04", field(dialog, "due").getText());
                                    field(dialog, "due").setText("2026-05-01");
                                    return null;
                                });
                        preview(dialog);
                        assertEquals(before, f.snapshot());
                        assertEquals(0, events.get());
                        edt(
                                () -> {
                                    String summary =
                                            ((JTextArea) component(dialog, "summary")).getText();
                                    assertTrue(summary.contains("2026-02-02"));
                                    assertTrue(summary.contains("2026-03-04"));
                                    assertTrue(summary.contains("2026-04-01"));
                                    assertTrue(summary.contains("2026-05-01"));
                                    assertFalse(button(dialog, "save").isEnabled());
                                    button(dialog, "confirm").doClick();
                                    assertTrue(button(dialog, "save").isEnabled());
                                    field(dialog, "due").setText("2026-05-02");
                                    assertFalse(
                                            ((JCheckBox) button(dialog, "confirm")).isSelected());
                                    assertFalse(button(dialog, "save").isEnabled());
                                    return null;
                                });
                        preview(dialog);
                        save(dialog);
                        waitFor(() -> events.get() == 1 && !edt(dialog::isDisplayable));
                        assertEquals(id, edt(() -> table.getValueAt(table.getSelectedRow(), 0)));
                        SupplierDatesAdapterTest.assertNarrow(
                                f,
                                credit,
                                id,
                                before,
                                header,
                                new org.fribok.bookkeeping.service.supplierinvoice
                                        .NormalizedSupplierDatesService.Values(
                                        SupplierDatesAdapterTest.NEXT, LocalDate.of(2026, 5, 2)));
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
    void invalidIsoNeverStartsSaveAndMissingRequiredDateRequiresReloadWithoutWrites()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = SupplierDatesAdapterTest.document(f, false);
            var before = f.snapshot();
            var dialog = create(f.session(), Kind.INVOICE, id, new AtomicInteger());
            try {
                ready(dialog);
                for (String invalid :
                        List.of("2026-02-30", "not a date", "2026-2-03", "2026-13-01"))
                    edt(
                            () -> {
                                field(dialog, "invoice").setText(invalid);
                                button(dialog, "preview").doClick();
                                assertFalse(button(dialog, "confirm").isEnabled());
                                assertFalse(button(dialog, "save").isEnabled());
                                assertTrue(button(dialog, "preview").isEnabled());
                                return null;
                            });
                assertEquals(before, f.snapshot());
                edt(
                        () -> {
                            field(dialog, "invoice").setText("");
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
                assertEquals("2026-02-02", edt(() -> field(dialog, "invoice").getText()));
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
    void historicalNullsCanBeConfirmedUnchangedAndReloadAndCancelDiscardDraftDates()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                long id = SupplierDatesAdapterTest.document(f, kind == Kind.CREDIT);
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE "
                                    + SupplierDatesAdapterTest.table(kind == Kind.CREDIT)
                                    + " SET invoice_date=NULL,due_date=NULL WHERE id="
                                    + id);
                }
                f.anchor.commit();
                var before = f.snapshot();
                AtomicInteger callbacks = new AtomicInteger();
                var session = f.session();
                var dialog = create(session, kind, id, callbacks);
                try {
                    ready(dialog);
                    assertEquals("", edt(() -> field(dialog, "invoice").getText()));
                    assertEquals("", edt(() -> field(dialog, "due").getText()));
                    preview(dialog);
                    assertTrue(
                            edt(
                                    () ->
                                            ((JTextArea) component(dialog, "summary"))
                                                    .getText()
                                                    .contains("NULL")));
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
                var second = create(session, kind, id, callbacks);
                try {
                    ready(second);
                    edt(
                            () -> {
                                field(second, "invoice").setText("2026-04-01");
                                field(second, "due").setText("2026-05-01");
                                button(second, "reload").doClick();
                                return null;
                            });
                    ready(second);
                    assertEquals("", edt(() -> field(second, "invoice").getText()));
                    edt(
                            () -> {
                                field(second, "due").setText("2026-05-01");
                                button(second, "cancel").doClick();
                                return null;
                            });
                    assertEquals(1, callbacks.get());
                    assertEquals(before, f.snapshot());
                } finally {
                    edt(
                            () -> {
                                second.dispose();
                                return null;
                            });
                }
            }
    }

    @Test
    void staleConfirmationWritesNothingAndReloadShowsConcurrentSource() throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                long id = SupplierDatesAdapterTest.document(f, kind == Kind.CREDIT);
                AtomicInteger callbacks = new AtomicInteger();
                var dialog = create(f.session(), kind, id, callbacks);
                try {
                    ready(dialog);
                    proposed(dialog);
                    preview(dialog);
                    try (var s = f.anchor.createStatement()) {
                        s.executeUpdate(
                                "UPDATE "
                                        + SupplierDatesAdapterTest.table(kind == Kind.CREDIT)
                                        + " SET due_date=DATE '2026-03-05' WHERE id="
                                        + id);
                    }
                    f.anchor.commit();
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
                    assertEquals("2026-03-05", edt(() -> field(dialog, "due").getText()));
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
    void selectionReplacementInvalidatesConfirmedDraftWithoutWrites() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = SupplierDatesAdapterTest.document(f, false);
            var before = f.snapshot();
            AtomicInteger callbacks = new AtomicInteger();
            var dialog = create(f.session(), Kind.INVOICE, id, callbacks);
            try {
                ready(dialog);
                proposed(dialog);
                preview(dialog);
                edt(
                        () -> {
                            button(dialog, "confirm").doClick();
                            dialog.invalidateSelection();
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
    void unknownCommitDisablesAllDialogWritesWithoutRefreshOnBothDurabilitySides()
            throws Exception {
        for (boolean durable : List.of(false, true))
            try (var f = new SupplierJournalGuiFixture()) {
                long id = SupplierDatesAdapterTest.document(f, false);
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
                                                            "Unknown date commit");
                                                }
                                                return SupplierJournalGuiFixture.invoke(
                                                        c, method, args);
                                            });
                                },
                                f.gate,
                                71,
                                11,
                                SupplierJournalGuiFixture.CLOCK);
                var dialog = create(session, Kind.INVOICE, id, callbacks);
                try {
                    ready(dialog);
                    proposed(dialog);
                    preview(dialog);
                    save(dialog);
                    waitFor(session::isBlocked);
                    waitFor(() -> edt(() -> button(dialog, "cancel").isEnabled()));
                    assertFalse(edt(() -> button(dialog, "save").isEnabled()));
                    assertFalse(edt(() -> button(dialog, "reload").isEnabled()));
                    assertFalse(edt(() -> button(dialog, "preview").isEnabled()));
                    assertEquals(0, callbacks.get());
                    assertEquals(
                            durable
                                    ? SupplierDatesAdapterTest.NEXT
                                    : SupplierDatesAdapterTest.ORIGINAL,
                            f.stored(false, id).getLocalDate());
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
    void acceptedOriginalContextSaveMayFinishButReplacementSuppressesCallback() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = SupplierDatesAdapterTest.document(f, false);
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
            var dialog = create(session, Kind.INVOICE, id, callbacks);
            try {
                ready(dialog);
                proposed(dialog);
                preview(dialog);
                save(dialog);
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
                assertEquals(SupplierDatesAdapterTest.NEXT, f.stored(false, id).getLocalDate());
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
    void callbackFailureAfterKnownCommitDoesNotRetryOrBlock() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = SupplierDatesAdapterTest.document(f, true);
            AtomicInteger callbacks = new AtomicInteger();
            var session = f.session();
            var dialog =
                    edt(
                            () ->
                                    new SupplierDatesDialog(
                                            null,
                                            session.dates(Kind.CREDIT, id),
                                            saved -> {
                                                callbacks.incrementAndGet();
                                                throw new IllegalStateException("Refresh failed");
                                            }));
            try {
                ready(dialog);
                proposed(dialog);
                preview(dialog);
                save(dialog);
                waitFor(() -> !edt(dialog::isDisplayable));
                assertEquals(1, callbacks.get());
                assertFalse(session.isBlocked());
                assertEquals(SupplierDatesAdapterTest.NEXT, f.stored(true, id).getLocalDate());
            } finally {
                edt(
                        () -> {
                            dialog.dispose();
                            return null;
                        });
            }
        }
    }

    private static SupplierDatesDialog create(
            SupplierDocumentGuiSession session, Kind kind, long id, AtomicInteger callbacks) {
        return edt(
                () ->
                        new SupplierDatesDialog(
                                null,
                                session.dates(kind, id),
                                saved -> callbacks.incrementAndGet()));
    }

    private static void ready(SupplierDatesDialog d) throws Exception {
        waitFor(() -> edt(() -> button(d, "preview").isEnabled()));
    }

    private static void proposed(SupplierDatesDialog d) {
        edt(
                () -> {
                    field(d, "invoice").setText("2026-04-01");
                    field(d, "due").setText("2026-05-01");
                    return null;
                });
    }

    private static void preview(SupplierDatesDialog d) throws Exception {
        edt(
                () -> {
                    button(d, "preview").doClick();
                    return null;
                });
        waitFor(() -> edt(() -> button(d, "confirm").isEnabled()));
    }

    private static void save(SupplierDatesDialog d) {
        edt(
                () -> {
                    button(d, "confirm").doClick();
                    button(d, "save").doClick();
                    return null;
                });
    }

    private static JTextField field(Container d, String name) {
        return (JTextField) component(d, name);
    }

    private static AbstractButton button(Container d, String name) {
        return (AbstractButton) component(d, name);
    }

    private static JComponent component(Container root, String suffix) {
        for (Component child : root.getComponents()) {
            if (child instanceof JComponent c && ("supplier-dates-" + suffix).equals(c.getName()))
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
        fail("Timed out waiting for supplier dates");
    }
}
