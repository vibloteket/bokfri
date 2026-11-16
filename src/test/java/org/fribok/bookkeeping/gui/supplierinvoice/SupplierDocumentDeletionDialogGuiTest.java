package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.fribok.bookkeeping.gui.supplierinvoice.SupplierDocumentViewGuiTest.component;
import static org.fribok.bookkeeping.gui.supplierinvoice.SupplierDocumentViewGuiTest.select;
import static org.fribok.bookkeeping.gui.supplierinvoice.SupplierDocumentViewGuiTest.waitFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentDeletionService.Kind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

@Tag("integration")
class SupplierDocumentDeletionDialogGuiTest {
    @Test
    void bothSortedViewRoutesShowExactTargetAndStockWarningAndRefreshOnlyAfterConfirmedDeletion()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var ignored = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = SupplierDocumentDeletionAdapterTest.addGraph(f, credit, 5);
                long other = f.add(credit, 0, "25");
                try (var statement = f.anchor.createStatement()) {
                    statement.executeUpdate(
                            "UPDATE "
                                    + SupplierDocumentDeletionAdapterTest.table(credit)
                                    + " SET bgc_entered=true WHERE id="
                                    + other);
                }
                f.anchor.commit();
                var session = f.session();
                try (var ui = new SupplierDocumentViewGuiTest.View(session)) {
                    SupplierDocumentViewGuiTest.ready(ui);
                    JTable table =
                            component(
                                    ui.view,
                                    credit
                                            ? "normalized-supplier-credits"
                                            : "normalized-supplier-invoices",
                                    JTable.class);
                    JButton action =
                            component(
                                    ui.view,
                                    credit ? "supplier-credit-delete" : "supplier-invoice-delete",
                                    JButton.class);
                    AtomicInteger events = new AtomicInteger();
                    SwingUtilities.invokeAndWait(
                            () -> {
                                assertFalse(action.isEnabled());
                                if (!credit) {
                                    select(table, f.invoice);
                                    assertFalse(action.isEnabled());
                                }
                                table.getRowSorter().toggleSortOrder(1);
                                select(table, other);
                                assertFalse(action.isEnabled());
                                select(table, id);
                                assertTrue(action.isEnabled());
                                table.getModel()
                                        .addTableModelListener(
                                                event -> {
                                                    assertTrue(
                                                            SwingUtilities.isEventDispatchThread());
                                                    events.incrementAndGet();
                                                });
                            });
                    var before = f.snapshot();
                    SwingUtilities.invokeLater(action::doClick);
                    var dialog = showing();
                    ready(dialog);
                    SwingUtilities.invokeAndWait(
                            () -> {
                                String text =
                                        component(dialog, "deletion-summary", JTextArea.class)
                                                .getText();
                                assertTrue(text.contains("SQL-ID: " + id));
                                assertTrue(text.contains("100"));
                                assertTrue(text.contains("lagersaldo"));
                                assertFalse(
                                        component(dialog, "deletion-delete", JButton.class)
                                                .isEnabled());
                            });
                    assertEquals(before, f.snapshot());
                    assertEquals(0, events.get());
                    confirm(dialog);
                    waitFor(() -> !dialog.isDisplayable() && events.get() == 1);
                    SwingUtilities.invokeAndWait(() -> assertEquals(-1, table.getSelectedRow()));
                    assertFalse(SupplierDocumentDeletionAdapterTest.exists(f, credit, id));
                    assertTrue(SupplierDocumentDeletionAdapterTest.exists(f, credit, other));
                    assertTrue(SupplierDocumentDeletionAdapterTest.exists(f, false, f.invoice));
                }
            }
    }

    @Test
    void staleSourceRequiresReloadAndFreshConfirmationBeforeAnotherAttempt() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(false, 5, "100");
            var session = f.session();
            try (var ui = new SupplierDocumentViewGuiTest.View(session)) {
                SupplierDocumentViewGuiTest.ready(ui);
                SwingUtilities.invokeAndWait(
                        () ->
                                select(
                                        component(
                                                ui.view,
                                                "normalized-supplier-invoices",
                                                JTable.class),
                                        id));
                SwingUtilities.invokeLater(
                        () ->
                                component(ui.view, "supplier-invoice-delete", JButton.class)
                                        .doClick());
                var dialog = showing();
                ready(dialog);
                try {
                    try (var s = f.anchor.createStatement()) {
                        s.executeUpdate(
                                "UPDATE supplier_invoice SET reference_number='Changed' WHERE id="
                                        + id);
                    }
                    f.anchor.commit();
                    confirm(dialog);
                    waitFor(
                            () ->
                                    component(dialog, "deletion-status", JLabel.class)
                                            .getText()
                                            .contains("Stale"));
                    assertTrue(SupplierDocumentDeletionAdapterTest.exists(f, false, id));
                    SwingUtilities.invokeAndWait(
                            () -> {
                                assertFalse(
                                        component(dialog, "deletion-delete", JButton.class)
                                                .isEnabled());
                                assertFalse(
                                        component(dialog, "deletion-confirm", JCheckBox.class)
                                                .isSelected());
                                component(dialog, "deletion-reload", JButton.class).doClick();
                            });
                    ready(dialog);
                    SwingUtilities.invokeAndWait(
                            () ->
                                    assertFalse(
                                            component(dialog, "deletion-confirm", JCheckBox.class)
                                                    .isSelected()));
                    confirm(dialog);
                    waitFor(() -> !dialog.isDisplayable());
                    assertFalse(SupplierDocumentDeletionAdapterTest.exists(f, false, id));
                } finally {
                    SwingUtilities.invokeAndWait(dialog::dispose);
                }
            }
        }
    }

    @Test
    void cancellationAndSelectionInvalidationDuringPreviewNeverDeleteOrNotify() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(false, 5, "100");
            AtomicInteger callbacks = new AtomicInteger();
            var first = f.session();
            var dialog =
                    create(first.deletion(Kind.INVOICE, id), saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true));
                ready(dialog);
                SwingUtilities.invokeAndWait(
                        () -> component(dialog, "deletion-cancel", JButton.class).doClick());
                assertTrue(SupplierDocumentDeletionAdapterTest.exists(f, false, id));
                assertEquals(0, callbacks.get());
            } finally {
                SwingUtilities.invokeAndWait(dialog::dispose);
                first.close();
            }
            CountDownLatch opening = new CountDownLatch(1), release = new CountDownLatch(1);
            var pending =
                    new SupplierDocumentGuiSession(
                            () -> {
                                opening.countDown();
                                SupplierDocumentGuiSessionTest.await(release);
                                return f.open();
                            },
                            f.gate,
                            71,
                            11,
                            SupplierJournalGuiFixture.CLOCK);
            var loading =
                    create(
                            pending.deletion(Kind.INVOICE, id),
                            saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> loading.setVisible(true));
                assertTrue(opening.await(10, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(
                        () -> {
                            loading.dispatchEvent(
                                    new WindowEvent(loading, WindowEvent.WINDOW_CLOSING));
                            assertTrue(loading.isDisplayable());
                            assertFalse(
                                    component(loading, "deletion-cancel", JButton.class)
                                            .isEnabled());
                            pending.close();
                            loading.invalidateSelection();
                        });
                release.countDown();
                waitFor(() -> !loading.isDisplayable());
                assertTrue(SupplierDocumentDeletionAdapterTest.exists(f, false, id));
                assertEquals(0, callbacks.get());
            } finally {
                release.countDown();
                SwingUtilities.invokeAndWait(loading::dispose);
                pending.close();
            }
        }
    }

    @Test
    void unknownCommitDisablesDeletionAndJournalsAcrossTheViewWithoutBlindRefresh()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (boolean durable : List.of(false, true))
            try (var f = new SupplierJournalGuiFixture()) {
                long id = f.add(false, 5, "100");
                AtomicInteger opens = new AtomicInteger();
                var session =
                        new SupplierDocumentGuiSession(
                                () -> {
                                    opens.incrementAndGet();
                                    var c = f.open();
                                    return SupplierJournalGuiFixture.wrap(
                                            c,
                                            (method, args) -> {
                                                if (method.getName().equals("commit")) {
                                                    if (durable) c.commit();
                                                    throw new SQLException(
                                                            "Unknown deletion outcome");
                                                }
                                                return SupplierJournalGuiFixture.invoke(
                                                        c, method, args);
                                            });
                                },
                                f.gate,
                                71,
                                11,
                                SupplierJournalGuiFixture.CLOCK);
                try (var ui = new SupplierDocumentViewGuiTest.View(session)) {
                    SupplierDocumentViewGuiTest.ready(ui);
                    SwingUtilities.invokeAndWait(
                            () ->
                                    select(
                                            component(
                                                    ui.view,
                                                    "normalized-supplier-invoices",
                                                    JTable.class),
                                            id));
                    SwingUtilities.invokeLater(
                            () ->
                                    component(ui.view, "supplier-invoice-delete", JButton.class)
                                            .doClick());
                    var dialog = showing();
                    ready(dialog);
                    confirm(dialog);
                    waitFor(
                            () ->
                                    session.isBlocked()
                                            && component(dialog, "deletion-cancel", JButton.class)
                                                    .isEnabled());
                    SwingUtilities.invokeAndWait(
                            () -> {
                                assertFalse(
                                        component(dialog, "deletion-reload", JButton.class)
                                                .isEnabled());
                                component(dialog, "deletion-cancel", JButton.class).doClick();
                            });
                    waitFor(
                            () ->
                                    !component(ui.view, "supplier-invoice-delete", JButton.class)
                                            .isEnabled());
                    SwingUtilities.invokeAndWait(
                            () -> {
                                for (String name :
                                        List.of(
                                                "supplier-invoice-delete",
                                                "supplier-credit-delete",
                                                "supplier-invoice-journal",
                                                "supplier-credit-journal",
                                                "supplier-document-refresh"))
                                    assertFalse(
                                            component(ui.view, name, JButton.class).isEnabled());
                                ui.view.refresh();
                            });
                    assertEquals(3, opens.get());
                    assertEquals(
                            !durable, SupplierDocumentDeletionAdapterTest.exists(f, false, id));
                }
            }
    }

    @Test
    void replacementDuringAcceptedDeletionRetainsOriginalDeleteAndSuppressesNewContextRefresh()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(false, 5, "100");
            CountDownLatch committing = new CountDownLatch(1), release = new CountDownLatch(1);
            var old =
                    new SupplierDocumentGuiSession(
                            () -> {
                                var c = f.open();
                                return SupplierJournalGuiFixture.wrap(
                                        c,
                                        (method, args) -> {
                                            if (method.getName().equals("commit")) {
                                                committing.countDown();
                                                SupplierDocumentGuiSessionTest.await(release);
                                            }
                                            return SupplierJournalGuiFixture.invoke(
                                                    c, method, args);
                                        });
                            },
                            f.gate,
                            71,
                            11,
                            SupplierJournalGuiFixture.CLOCK);
            try (var ui = new SupplierDocumentViewGuiTest.View(old)) {
                SupplierDocumentViewGuiTest.ready(ui);
                SwingUtilities.invokeAndWait(
                        () ->
                                select(
                                        component(
                                                ui.view,
                                                "normalized-supplier-invoices",
                                                JTable.class),
                                        id));
                SwingUtilities.invokeLater(
                        () ->
                                component(ui.view, "supplier-invoice-delete", JButton.class)
                                        .doClick());
                var dialog = showing();
                ready(dialog);
                confirm(dialog);
                assertTrue(committing.await(10, TimeUnit.SECONDS));
                AtomicInteger freshOpens = new AtomicInteger();
                var next =
                        new SupplierDocumentGuiSession(
                                () -> {
                                    freshOpens.incrementAndGet();
                                    return f.open();
                                },
                                f.gate,
                                72,
                                12,
                                SupplierJournalGuiFixture.CLOCK);
                SwingUtilities.invokeAndWait(
                        () -> {
                            ui.view.replaceSelection(next);
                            assertTrue(dialog.isDisplayable());
                        });
                release.countDown();
                SupplierDocumentViewGuiTest.ready(ui);
                waitFor(() -> !dialog.isDisplayable());
                assertEquals(1, freshOpens.get());
                assertFalse(SupplierDocumentDeletionAdapterTest.exists(f, false, id));
                assertTrue(SupplierDocumentDeletionAdapterTest.exists(f, false, f.invoice));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void refreshCallbackFailureAfterConfirmedDeletionClosesWithoutRetrying() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(true, 5, "100");
            var session = f.session();
            AtomicInteger callbacks = new AtomicInteger();
            var dialog =
                    create(
                            session.deletion(Kind.CREDIT, id),
                            saved -> {
                                callbacks.incrementAndGet();
                                throw new IllegalStateException(
                                        "Refresh failed after confirmed deletion");
                            });
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true));
                ready(dialog);
                confirm(dialog);
                waitFor(() -> !dialog.isDisplayable());
                assertEquals(1, callbacks.get());
                assertFalse(session.isBlocked());
                assertFalse(SupplierDocumentDeletionAdapterTest.exists(f, true, id));
            } finally {
                SwingUtilities.invokeAndWait(dialog::dispose);
                session.close();
            }
        }
    }

    private static SupplierDocumentDeletionDialog create(
            SupplierDocumentDeletionAdapter adapter,
            java.util.function.Consumer<SupplierDocumentDeletionAdapter.Saved> callback)
            throws Exception {
        AtomicReference<SupplierDocumentDeletionDialog> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(
                () -> ref.set(new SupplierDocumentDeletionDialog(null, adapter, callback)));
        return ref.get();
    }

    private static SupplierDocumentDeletionDialog showing() throws Exception {
        AtomicReference<SupplierDocumentDeletionDialog> ref = new AtomicReference<>();
        waitFor(
                () -> {
                    for (Window window : Window.getWindows())
                        if (window instanceof SupplierDocumentDeletionDialog dialog
                                && dialog.isShowing()) ref.set(dialog);
                    return ref.get() != null;
                });
        return ref.get();
    }

    private static void ready(SupplierDocumentDeletionDialog dialog) throws Exception {
        waitFor(() -> component(dialog, "deletion-confirm", JCheckBox.class).isEnabled());
    }

    private static void confirm(SupplierDocumentDeletionDialog dialog) throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    component(dialog, "deletion-confirm", JCheckBox.class).doClick();
                    component(dialog, "deletion-delete", JButton.class).doClick();
                });
    }
}
