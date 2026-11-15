package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

@Tag("integration")
class SupplierDocumentViewGuiTest {
    @Test
    void bothJournalRoutesRefreshBothTablesAfterCommitAndRestoreSortedStableIdentity()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (var kind : SupplierJournalAdapter.Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var ignored = new SupplierJournalGuiFixture.LegacyGuard()) {
                long invoice = f.add(false, 5, "100"), credit = f.add(true, 5, "10");
                AtomicInteger opens = new AtomicInteger(), events = new AtomicInteger();
                var session =
                        new SupplierDocumentGuiSession(
                                () -> {
                                    assertFalse(SwingUtilities.isEventDispatchThread());
                                    opens.incrementAndGet();
                                    return f.open();
                                },
                                f.gate,
                                71,
                                11,
                                SupplierJournalGuiFixture.CLOCK);
                try (var ui = new View(session)) {
                    ready(ui);
                    JTable invoices =
                            component(ui.view, "normalized-supplier-invoices", JTable.class);
                    JTable credits =
                            component(ui.view, "normalized-supplier-credits", JTable.class);
                    boolean isCredit = kind == SupplierJournalAdapter.Kind.CREDIT;
                    JTable selected = isCredit ? credits : invoices;
                    long id = isCredit ? credit : invoice;
                    SwingUtilities.invokeAndWait(
                            () -> {
                                assertFalse(invoices.isCellEditable(0, 1));
                                assertFalse(credits.isCellEditable(0, 6));
                                selected.getRowSorter().toggleSortOrder(1);
                                select(selected, id);
                                invoices.getModel()
                                        .addTableModelListener(
                                                event -> {
                                                    assertTrue(
                                                            SwingUtilities.isEventDispatchThread());
                                                    if (invoices.getModel().getRowCount() > 0) {
                                                        assertEquals(
                                                                3,
                                                                invoices.getModel().getRowCount());
                                                        assertEquals(
                                                                1,
                                                                credits.getModel().getRowCount());
                                                        events.incrementAndGet();
                                                    }
                                                });
                            });
                    f.add(false, 0, "25");
                    SwingUtilities.invokeLater(
                            () ->
                                    component(
                                                    ui.view,
                                                    isCredit
                                                            ? "supplier-credit-journal"
                                                            : "supplier-invoice-journal",
                                                    JButton.class)
                                            .doClick());
                    var dialog = dialog();
                    SwingUtilities.invokeAndWait(
                            () -> component(dialog, "journal-preview", JButton.class).doClick());
                    journalReady(dialog);
                    assertEquals(0, events.get());
                    assertFalse(f.stored(isCredit, id).isEntered());
                    confirm(dialog);
                    waitFor(() -> !dialog.isDisplayable() && events.get() == 1);
                    SwingUtilities.invokeAndWait(
                            () -> {
                                assertEquals(id, selected.getValueAt(selected.getSelectedRow(), 0));
                                assertEquals(
                                        Boolean.TRUE,
                                        selected.getValueAt(selected.getSelectedRow(), 7));
                            });
                    assertEquals(4, opens.get());
                    assertEquals(1, f.voucherCount());
                }
            }
    }

    @Test
    void failedRefreshRetainsConfirmedRowsAndRequiresSuccessfulReloadBeforeJournalActions()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            f.add(false, 5, "100");
            AtomicBoolean failLoad = new AtomicBoolean();
            AtomicInteger opens = new AtomicInteger();
            var session =
                    new SupplierDocumentGuiSession(
                            () -> {
                                opens.incrementAndGet();
                                if (failLoad.get())
                                    throw new SQLException("Injected snapshot failure");
                                return f.open();
                            },
                            f.gate,
                            71,
                            11,
                            SupplierJournalGuiFixture.CLOCK);
            try (var ui = new View(session)) {
                ready(ui);
                failLoad.set(true);
                SwingUtilities.invokeAndWait(ui.view::refresh);
                waitFor(
                        () ->
                                component(ui.view, "supplier-document-status", JLabel.class)
                                        .getText()
                                        .contains("Injected snapshot failure"));
                SwingUtilities.invokeAndWait(
                        () -> {
                            assertEquals(
                                    2,
                                    component(ui.view, "normalized-supplier-invoices", JTable.class)
                                            .getRowCount());
                            assertFalse(
                                    component(ui.view, "supplier-invoice-journal", JButton.class)
                                            .isEnabled());
                            assertTrue(
                                    component(ui.view, "supplier-document-refresh", JButton.class)
                                            .isEnabled());
                        });
                failLoad.set(false);
                SwingUtilities.invokeAndWait(ui.view::refresh);
                ready(ui);
                assertEquals(3, opens.get());
            }
        }
    }

    @Test
    void replacingSelectionDiscardsLateLoadsAndClosingClearsRowsAndPreventsLegacyFallback()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            f.add(false, 5, "100");
            CountDownLatch opening = new CountDownLatch(1), release = new CountDownLatch(1);
            var old =
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
            try (var ui = new View(old)) {
                assertTrue(opening.await(10, TimeUnit.SECONDS));
                var fresh =
                        new SupplierDocumentGuiSession(
                                f::open, f.gate, 72, 12, SupplierJournalGuiFixture.CLOCK);
                SwingUtilities.invokeAndWait(
                        () -> {
                            ui.view.replaceSelection(fresh);
                            assertEquals(
                                    0,
                                    component(ui.view, "normalized-supplier-invoices", JTable.class)
                                            .getRowCount());
                            assertFalse(
                                    component(ui.view, "supplier-invoice-journal", JButton.class)
                                            .isEnabled());
                        });
                release.countDown();
                ready(ui);
                SwingUtilities.invokeAndWait(
                        () -> {
                            assertEquals(
                                    0,
                                    component(ui.view, "normalized-supplier-invoices", JTable.class)
                                            .getRowCount());
                            assertTrue(
                                    component(ui.view, "supplier-document-context", JLabel.class)
                                            .getText()
                                            .contains("GUI company 72"));
                            ui.view.refresh();
                            ui.view.close();
                            assertFalse(fresh.isActive());
                            assertTrue(
                                    SupplierDocumentMenuBinding.openIfInstalled(
                                            ui.frame, SupplierJournalAdapter.Kind.INVOICE));
                        });
                assertFalse(old.isActive());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void replacementDuringAcceptedPostingCommitsOriginalIdsWithoutRefreshingNewSelection()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(false, 5, "100");
            CountDownLatch committing = new CountDownLatch(1), release = new CountDownLatch(1);
            var old =
                    new SupplierDocumentGuiSession(
                            () -> {
                                var connection = f.open();
                                return SupplierJournalGuiFixture.wrap(
                                        connection,
                                        (method, args) -> {
                                            if (method.getName().equals("commit")) {
                                                committing.countDown();
                                                SupplierDocumentGuiSessionTest.await(release);
                                            }
                                            return SupplierJournalGuiFixture.invoke(
                                                    connection, method, args);
                                        });
                            },
                            f.gate,
                            71,
                            11,
                            SupplierJournalGuiFixture.CLOCK);
            try (var ui = new View(old)) {
                ready(ui);
                SwingUtilities.invokeLater(
                        () ->
                                component(ui.view, "supplier-invoice-journal", JButton.class)
                                        .doClick());
                var dialog = dialog();
                SwingUtilities.invokeAndWait(
                        () -> component(dialog, "journal-preview", JButton.class).doClick());
                journalReady(dialog);
                confirm(dialog);
                assertTrue(committing.await(10, TimeUnit.SECONDS));
                AtomicInteger freshOpens = new AtomicInteger();
                var fresh =
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
                            ui.view.replaceSelection(fresh);
                            assertTrue(dialog.isDisplayable());
                        });
                release.countDown();
                ready(ui);
                waitFor(() -> !dialog.isDisplayable());
                assertEquals(1, freshOpens.get());
                assertTrue(f.stored(false, id).isEntered());
                assertEquals(1, f.voucherCount());
                SwingUtilities.invokeAndWait(
                        () ->
                                assertEquals(
                                        0,
                                        component(
                                                        ui.view,
                                                        "normalized-supplier-invoices",
                                                        JTable.class)
                                                .getRowCount()));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void uncertainPostingDisablesViewAndBothMenuRoutesWithoutRefreshOrBlindRetry()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            f.add(false, 5, "100");
            AtomicInteger opens = new AtomicInteger();
            var session =
                    new SupplierDocumentGuiSession(
                            () -> {
                                opens.incrementAndGet();
                                var connection = f.open();
                                return SupplierJournalGuiFixture.wrap(
                                        connection,
                                        (method, args) -> {
                                            if (method.getName().equals("commit")) {
                                                connection.commit();
                                                throw new SQLException("Unknown durability");
                                            }
                                            return SupplierJournalGuiFixture.invoke(
                                                    connection, method, args);
                                        });
                            },
                            f.gate,
                            71,
                            11,
                            SupplierJournalGuiFixture.CLOCK);
            try (var ui = new View(session)) {
                ready(ui);
                SwingUtilities.invokeLater(
                        () ->
                                component(ui.view, "supplier-invoice-journal", JButton.class)
                                        .doClick());
                var dialog = dialog();
                SwingUtilities.invokeAndWait(
                        () -> component(dialog, "journal-preview", JButton.class).doClick());
                journalReady(dialog);
                confirm(dialog);
                waitFor(
                        () ->
                                session.isBlocked()
                                        && component(dialog, "journal-cancel", JButton.class)
                                                .isEnabled());
                SwingUtilities.invokeAndWait(
                        () -> component(dialog, "journal-cancel", JButton.class).doClick());
                waitFor(
                        () ->
                                !component(ui.view, "supplier-invoice-journal", JButton.class)
                                        .isEnabled());
                AtomicInteger legacy = new AtomicInteger();
                SwingUtilities.invokeAndWait(
                        () -> {
                            assertFalse(
                                    component(ui.view, "supplier-credit-journal", JButton.class)
                                            .isEnabled());
                            assertFalse(
                                    component(ui.view, "supplier-document-refresh", JButton.class)
                                            .isEnabled());
                            ui.view.refresh();
                            for (var kind : SupplierJournalAdapter.Kind.values())
                                SupplierDocumentMenuBinding.route(
                                        ui.frame, kind, legacy::incrementAndGet);
                        });
                assertEquals(0, legacy.get());
                assertEquals(3, opens.get());
                assertEquals(1, f.voucherCount());
            }
        }
    }

    static final class View implements AutoCloseable {
        JFrame frame;
        SupplierDocumentView view;

        View(SupplierDocumentGuiSession session) throws Exception {
            SwingUtilities.invokeAndWait(
                    () -> {
                        frame = new JFrame();
                        view = new SupplierDocumentView(frame, session);
                        frame.add(view);
                        frame.setSize(1000, 450);
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

    static void ready(View view) throws Exception {
        waitFor(() -> component(view.view, "supplier-invoice-journal", JButton.class).isEnabled());
    }

    static <T extends Component> T component(Container parent, String name, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName()) && type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T found = component(nested, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    static void select(JTable table, long id) {
        for (int row = 0; row < table.getRowCount(); row++)
            if (Long.valueOf(id).equals(table.getValueAt(row, 0))) {
                table.setRowSelectionInterval(row, row);
                return;
            }
        fail("Missing SQL identity " + id);
    }

    static SupplierJournalDialog dialog() throws Exception {
        AtomicReference<SupplierJournalDialog> selected = new AtomicReference<>();
        waitFor(
                () -> {
                    for (Window window : Window.getWindows())
                        if (window instanceof SupplierJournalDialog dialog && dialog.isShowing())
                            selected.set(dialog);
                    return selected.get() != null;
                });
        return selected.get();
    }

    static void journalReady(SupplierJournalDialog dialog) throws Exception {
        waitFor(() -> component(dialog, "journal-confirm", JCheckBox.class).isEnabled());
    }

    static void confirm(SupplierJournalDialog dialog) throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    component(dialog, "journal-confirm", JCheckBox.class).doClick();
                    component(dialog, "journal-post", JButton.class).doClick();
                });
    }

    static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        AtomicBoolean ready = new AtomicBoolean();
        do {
            SwingUtilities.invokeAndWait(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("Timed out waiting for supplier view");
    }
}
