package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.fribok.bookkeeping.gui.supplierinvoice.SupplierDocumentViewGuiTest.component;
import static org.fribok.bookkeeping.gui.supplierinvoice.SupplierDocumentViewGuiTest.select;
import static org.fribok.bookkeeping.gui.supplierinvoice.SupplierDocumentViewGuiTest.waitFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.Kind;
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
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

@Tag("integration")
class SupplierDocumentMetadataDialogGuiTest {
    @Test
    void bothSortedViewRoutesEditOnlyRawMetadataAndRefreshAfterConfirmedSaveWithoutLegacyJdbc()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var ignored = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = SupplierDocumentDeletionAdapterTest.addGraph(f, credit, 5),
                        bank = f.add(credit, 0, "25");
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE "
                                    + SupplierDocumentMetadataAdapterTest.table(credit)
                                    + " SET bgc_entered=true WHERE id="
                                    + bank);
                }
                f.anchor.commit();
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
                                    credit
                                            ? "supplier-credit-metadata"
                                            : "supplier-invoice-metadata",
                                    JButton.class);
                    SwingUtilities.invokeAndWait(
                            () -> {
                                assertFalse(action.isEnabled());
                                if (!credit) {
                                    select(table, f.invoice);
                                    assertFalse(action.isEnabled());
                                }
                                table.getRowSorter().toggleSortOrder(1);
                                select(table, bank);
                                assertFalse(action.isEnabled());
                                select(table, id);
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
                            });
                    var before = f.snapshot();
                    var header = SupplierDocumentMetadataAdapterTest.header(f, credit, id);
                    SwingUtilities.invokeLater(action::doClick);
                    var dialog = showing();
                    ready(dialog);
                    SwingUtilities.invokeAndWait(
                            () -> {
                                assertTrue(
                                        component(dialog, "supplier-metadata-target", JLabel.class)
                                                .getText()
                                                .contains("SQL-ID " + id));
                                component(dialog, "supplier-metadata-name", JTextField.class)
                                        .setText("  Updated Ö  ");
                                assertTrue(
                                        component(
                                                        dialog,
                                                        "supplier-metadata-reference-null",
                                                        JCheckBox.class)
                                                .isSelected());
                                component(
                                                dialog,
                                                "supplier-metadata-reference-null",
                                                JCheckBox.class)
                                        .doClick();
                                component(dialog, "supplier-metadata-reference", JTextField.class)
                                        .setText(" Exact 42 ");
                            });
                    assertEquals(before, f.snapshot());
                    assertEquals(0, events.get());
                    save(dialog);
                    waitFor(() -> !dialog.isDisplayable() && events.get() == 1);
                    SwingUtilities.invokeAndWait(
                            () -> {
                                assertEquals(id, table.getValueAt(table.getSelectedRow(), 0));
                                assertEquals(
                                        "  Updated Ö  ",
                                        table.getValueAt(table.getSelectedRow(), 3));
                            });
                    SupplierDocumentMetadataAdapterTest.assertNarrow(
                            f, credit, id, before, header, "  Updated Ö  ", " Exact 42 ");
                    assertEquals(4, opens.get());
                }
            }
    }

    @Test
    void untouchedHistoricalControlsStayRawAndExplicitNullBlankAndReloadDiscardRemainDistinct()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(false, 5, "100");
            SupplierDocumentMetadataAdapterTest.setRaw(f, false, id, null, " old\nref\t ");
            var session = f.session();
            AtomicInteger callbacks = new AtomicInteger();
            var before = f.snapshot();
            var first =
                    create(
                            session.metadata(Kind.INVOICE, id),
                            saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> first.setVisible(true));
                ready(first);
                SwingUtilities.invokeAndWait(
                        () -> {
                            assertTrue(
                                    component(first, "supplier-metadata-name-null", JCheckBox.class)
                                            .isSelected());
                            assertFalse(
                                    component(first, "supplier-metadata-name", JTextField.class)
                                            .isEnabled());
                        });
                save(first);
                waitFor(() -> !first.isDisplayable());
                assertEquals(before, f.snapshot());
            } finally {
                SwingUtilities.invokeAndWait(first::dispose);
            }
            var second =
                    create(
                            session.metadata(Kind.INVOICE, id),
                            saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> second.setVisible(true));
                ready(second);
                SwingUtilities.invokeAndWait(
                        () -> {
                            component(second, "supplier-metadata-name-null", JCheckBox.class)
                                    .doClick();
                            component(second, "supplier-metadata-name", JTextField.class)
                                    .setText("Draft discard");
                            component(second, "supplier-metadata-reference", JTextField.class)
                                    .setText("Draft ref");
                            component(second, "supplier-metadata-reload", JButton.class).doClick();
                        });
                ready(second);
                SwingUtilities.invokeAndWait(
                        () -> {
                            assertTrue(
                                    component(
                                                    second,
                                                    "supplier-metadata-name-null",
                                                    JCheckBox.class)
                                            .isSelected());
                            assertEquals(
                                    "",
                                    component(second, "supplier-metadata-name", JTextField.class)
                                            .getText());
                            component(second, "supplier-metadata-reference-null", JCheckBox.class)
                                    .doClick();
                        });
                save(second);
                waitFor(() -> !second.isDisplayable());
                assertNull(
                        SupplierDocumentMetadataAdapterTest.header(f, false, id)
                                .get("REFERENCE_NUMBER"));
            } finally {
                SwingUtilities.invokeAndWait(second::dispose);
            }
            var third =
                    create(
                            session.metadata(Kind.INVOICE, id),
                            saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> third.setVisible(true));
                ready(third);
                SwingUtilities.invokeAndWait(
                        () ->
                                component(
                                                third,
                                                "supplier-metadata-reference-null",
                                                JCheckBox.class)
                                        .doClick());
                save(third);
                waitFor(() -> !third.isDisplayable());
                assertEquals(
                        "",
                        SupplierDocumentMetadataAdapterTest.header(f, false, id)
                                .get("REFERENCE_NUMBER"));
                assertEquals(3, callbacks.get());
            } finally {
                SwingUtilities.invokeAndWait(third::dispose);
                session.close();
            }
        }
    }

    @Test
    void staleFinancialSourceRequiresReloadBeforeSaveAndDoesNotOverwriteExternalChanges()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(false, 5, "100");
            var session = f.session();
            AtomicInteger callbacks = new AtomicInteger();
            var dialog =
                    create(
                            session.metadata(Kind.INVOICE, id),
                            saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true));
                ready(dialog);
                SwingUtilities.invokeAndWait(
                        () ->
                                component(dialog, "supplier-metadata-name", JTextField.class)
                                        .setText("Discard stale edit"));
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE supplier_invoice_row SET description='External financial"
                                + " snapshot edit' WHERE supplier_invoice_id="
                                    + id);
                }
                f.anchor.commit();
                var before = f.snapshot();
                var header = SupplierDocumentMetadataAdapterTest.header(f, false, id);
                save(dialog);
                waitFor(
                        () ->
                                component(dialog, "supplier-metadata-status", JLabel.class)
                                        .getText()
                                        .contains("Stale"));
                assertEquals(before, f.snapshot());
                assertEquals(0, callbacks.get());
                SwingUtilities.invokeAndWait(
                        () -> {
                            assertFalse(
                                    component(dialog, "supplier-metadata-save", JButton.class)
                                            .isEnabled());
                            component(dialog, "supplier-metadata-reload", JButton.class).doClick();
                        });
                ready(dialog);
                SwingUtilities.invokeAndWait(
                        () -> {
                            assertEquals(
                                    "Supplier 5",
                                    component(dialog, "supplier-metadata-name", JTextField.class)
                                            .getText());
                            component(dialog, "supplier-metadata-name", JTextField.class)
                                    .setText("Confirmed");
                        });
                save(dialog);
                waitFor(() -> !dialog.isDisplayable());
                assertEquals(1, callbacks.get());
                SupplierDocumentMetadataAdapterTest.assertNarrow(
                        f, false, id, before, header, "Confirmed", null);
            } finally {
                SwingUtilities.invokeAndWait(dialog::dispose);
                session.close();
            }
        }
    }

    @Test
    void unknownCommitDisablesAllSupplierViewActionsWithoutBlindRefreshOrRetry() throws Exception {
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
                                                            "Unknown metadata outcome");
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
                                    component(ui.view, "supplier-invoice-metadata", JButton.class)
                                            .doClick());
                    var dialog = showing();
                    ready(dialog);
                    SwingUtilities.invokeAndWait(
                            () ->
                                    component(dialog, "supplier-metadata-name", JTextField.class)
                                            .setText("Changed"));
                    save(dialog);
                    waitFor(
                            () ->
                                    session.isBlocked()
                                            && component(
                                                            dialog,
                                                            "supplier-metadata-cancel",
                                                            JButton.class)
                                                    .isEnabled());
                    SwingUtilities.invokeAndWait(
                            () -> {
                                assertFalse(
                                        component(dialog, "supplier-metadata-save", JButton.class)
                                                .isEnabled());
                                assertFalse(
                                        component(dialog, "supplier-metadata-reload", JButton.class)
                                                .isEnabled());
                                component(dialog, "supplier-metadata-cancel", JButton.class)
                                        .doClick();
                            });
                    waitFor(
                            () ->
                                    !component(ui.view, "supplier-invoice-metadata", JButton.class)
                                            .isEnabled());
                    SwingUtilities.invokeAndWait(
                            () -> {
                                for (String button :
                                        List.of(
                                                "supplier-invoice-metadata",
                                                "supplier-credit-metadata",
                                                "supplier-invoice-delete",
                                                "supplier-credit-delete",
                                                "supplier-invoice-journal",
                                                "supplier-credit-journal",
                                                "supplier-document-refresh"))
                                    assertFalse(
                                            component(ui.view, button, JButton.class).isEnabled());
                                ui.view.refresh();
                            });
                    assertEquals(3, opens.get());
                    assertEquals(
                            durable ? "Changed" : "Supplier 5",
                            SupplierDocumentMetadataAdapterTest.header(f, false, id)
                                    .get("SUPPLIER_NAME"));
                }
            }
    }

    @Test
    void invalidatedPendingLoadsAndAcceptedOldContextSaveNeverRefreshReplacementContext()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(false, 5, "100");
            CountDownLatch opening = new CountDownLatch(1), releaseLoad = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            var pending =
                    new SupplierDocumentGuiSession(
                            () -> {
                                opening.countDown();
                                SupplierDocumentGuiSessionTest.await(releaseLoad);
                                return f.open();
                            },
                            f.gate,
                            71,
                            11,
                            SupplierJournalGuiFixture.CLOCK);
            var loading =
                    create(
                            pending.metadata(Kind.INVOICE, id),
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
                                    component(loading, "supplier-metadata-cancel", JButton.class)
                                            .isEnabled());
                            pending.close();
                            loading.invalidateSelection();
                        });
                releaseLoad.countDown();
                waitFor(() -> !loading.isDisplayable());
                assertEquals(0, callbacks.get());
            } finally {
                releaseLoad.countDown();
                SwingUtilities.invokeAndWait(loading::dispose);
                pending.close();
            }
            CountDownLatch committing = new CountDownLatch(1), releaseSave = new CountDownLatch(1);
            var old =
                    new SupplierDocumentGuiSession(
                            () -> {
                                var c = f.open();
                                return SupplierJournalGuiFixture.wrap(
                                        c,
                                        (method, args) -> {
                                            if (method.getName().equals("commit")) {
                                                committing.countDown();
                                                SupplierDocumentGuiSessionTest.await(releaseSave);
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
                                component(ui.view, "supplier-invoice-metadata", JButton.class)
                                        .doClick());
                var dialog = showing();
                ready(dialog);
                SwingUtilities.invokeAndWait(
                        () ->
                                component(dialog, "supplier-metadata-name", JTextField.class)
                                        .setText("Accepted original"));
                save(dialog);
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
                releaseSave.countDown();
                SupplierDocumentViewGuiTest.ready(ui);
                waitFor(() -> !dialog.isDisplayable());
                assertEquals(1, freshOpens.get());
                assertEquals(
                        "Accepted original",
                        SupplierDocumentMetadataAdapterTest.header(f, false, id)
                                .get("SUPPLIER_NAME"));
            } finally {
                releaseSave.countDown();
            }
        }
    }

    @Test
    void cancellationAndPostCommitCallbackFailureNeverCauseAnUnrequestedSecondSave()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(true, 5, "100");
            var session = f.session();
            AtomicInteger callbacks = new AtomicInteger();
            var cancel =
                    create(session.metadata(Kind.CREDIT, id), saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> cancel.setVisible(true));
                ready(cancel);
                SwingUtilities.invokeAndWait(
                        () -> {
                            component(cancel, "supplier-metadata-name", JTextField.class)
                                    .setText("Discarded");
                            component(cancel, "supplier-metadata-cancel", JButton.class).doClick();
                        });
                assertEquals(
                        "Supplier 5",
                        SupplierDocumentMetadataAdapterTest.header(f, true, id)
                                .get("SUPPLIER_NAME"));
                assertEquals(0, callbacks.get());
            } finally {
                SwingUtilities.invokeAndWait(cancel::dispose);
            }
            var dialog =
                    create(
                            session.metadata(Kind.CREDIT, id),
                            saved -> {
                                callbacks.incrementAndGet();
                                throw new IllegalStateException(
                                        "Confirmed metadata refresh failed");
                            });
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true));
                ready(dialog);
                SwingUtilities.invokeAndWait(
                        () ->
                                component(dialog, "supplier-metadata-name", JTextField.class)
                                        .setText("Confirmed"));
                save(dialog);
                waitFor(() -> !dialog.isDisplayable());
                assertEquals(1, callbacks.get());
                assertEquals(
                        "Confirmed",
                        SupplierDocumentMetadataAdapterTest.header(f, true, id)
                                .get("SUPPLIER_NAME"));
                assertFalse(session.isBlocked());
            } finally {
                SwingUtilities.invokeAndWait(dialog::dispose);
                session.close();
            }
        }
    }

    private static SupplierDocumentMetadataDialog create(
            SupplierDocumentMetadataAdapter adapter,
            java.util.function.Consumer<SupplierDocumentMetadataAdapter.Saved> callback)
            throws Exception {
        AtomicReference<SupplierDocumentMetadataDialog> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(
                () -> ref.set(new SupplierDocumentMetadataDialog(null, adapter, callback)));
        return ref.get();
    }

    private static SupplierDocumentMetadataDialog showing() throws Exception {
        AtomicReference<SupplierDocumentMetadataDialog> ref = new AtomicReference<>();
        waitFor(
                () -> {
                    for (Window window : Window.getWindows())
                        if (window instanceof SupplierDocumentMetadataDialog dialog
                                && dialog.isShowing()) ref.set(dialog);
                    return ref.get() != null;
                });
        return ref.get();
    }

    private static void ready(SupplierDocumentMetadataDialog dialog) throws Exception {
        waitFor(() -> component(dialog, "supplier-metadata-save", JButton.class).isEnabled());
    }

    private static void save(SupplierDocumentMetadataDialog dialog) throws Exception {
        SwingUtilities.invokeAndWait(
                () -> component(dialog, "supplier-metadata-save", JButton.class).doClick());
    }
}
