package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.fribok.bookkeeping.dataformat.NormalizedSupplierInvoiceStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

@Tag("integration")
class SupplierJournalDialogGuiTest {
    @Test
    void invalidAndChangedDatesNeverPostOldPreviewAndStaleSourceRequiresFreshConfirmation()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(false, 5, "100");
            AtomicInteger opens = new AtomicInteger(), callbacks = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var session =
                    new SupplierDocumentGuiSession(
                            () -> {
                                assertFalse(SwingUtilities.isEventDispatchThread());
                                if (opens.incrementAndGet() == 1) {
                                    entered.countDown();
                                    await(release);
                                }
                                return f.open();
                            },
                            f.gate,
                            71,
                            11,
                            SupplierJournalGuiFixture.CLOCK);
            var dialog =
                    create(
                            session.journal(SupplierJournalAdapter.Kind.INVOICE),
                            saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true));
                JButton preview = component(dialog, "journal-preview", JButton.class);
                JTextField from = component(dialog, "journal-from", JTextField.class),
                        to = component(dialog, "journal-to", JTextField.class);
                SwingUtilities.invokeAndWait(
                        () -> {
                            from.setText("bad");
                            preview.doClick();
                            assertFalse(
                                    component(dialog, "journal-post", JButton.class).isEnabled());
                        });
                assertEquals(0, opens.get());
                SwingUtilities.invokeAndWait(
                        () -> {
                            from.setText(SupplierJournalAdapterTest.FROM.toString());
                            preview.doClick();
                        });
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(
                        () -> {
                            dialog.dispatchEvent(
                                    new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                            assertTrue(dialog.isDisplayable());
                            assertFalse(
                                    component(dialog, "journal-cancel", JButton.class).isEnabled());
                            to.setText("2026-11-30");
                        });
                release.countDown();
                waitFor(preview::isEnabled);
                SwingUtilities.invokeAndWait(
                        () -> {
                            assertFalse(
                                    component(dialog, "journal-confirm", JCheckBox.class)
                                            .isEnabled());
                            assertEquals(
                                    0,
                                    component(dialog, "journal-documents", JTable.class)
                                            .getRowCount());
                            preview.doClick();
                        });
                ready(dialog);
                SwingUtilities.invokeAndWait(
                        () -> {
                            component(dialog, "journal-confirm", JCheckBox.class).doClick();
                            to.setText("2026-11-29");
                            assertFalse(
                                    component(dialog, "journal-post", JButton.class).isEnabled());
                            assertFalse(
                                    component(dialog, "journal-confirm", JCheckBox.class)
                                            .isSelected());
                            preview.doClick();
                        });
                ready(dialog);
                var store = new NormalizedSupplierInvoiceStore(f.anchor);
                var invoice = store.findSupplierInvoice(71, id).orElseThrow().document();
                invoice.getRows().getFirst().setUnitprice(new java.math.BigDecimal("101"));
                store.updateSupplierInvoice(71, id, invoice);
                f.anchor.commit();
                confirm(dialog);
                JTextArea status = component(dialog, "journal-status", JTextArea.class);
                waitFor(() -> status.getText().contains("stale"));
                assertEquals(0, callbacks.get());
                SwingUtilities.invokeAndWait(
                        () -> {
                            assertFalse(
                                    component(dialog, "journal-post", JButton.class).isEnabled());
                            preview.doClick();
                        });
                ready(dialog);
                SwingUtilities.invokeAndWait(
                        () ->
                                assertFalse(
                                        component(dialog, "journal-confirm", JCheckBox.class)
                                                .isSelected()));
                confirm(dialog);
                waitFor(() -> !dialog.isDisplayable());
                assertEquals(1, callbacks.get());
                assertEquals(1, f.voucherCount());
            } finally {
                release.countDown();
                SwingUtilities.invokeAndWait(dialog::dispose);
                session.close();
            }
        }
    }

    @Test
    void uncertainDurableCommitDisablesPreviewAndPostingWithoutSuccessNotification()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(false, 5, "100");
            AtomicInteger callbacks = new AtomicInteger();
            var session =
                    new SupplierDocumentGuiSession(
                            () ->
                                    wrap(
                                            f.open(),
                                            c -> {
                                                c.commit();
                                                throw new SQLException("Unknown journal outcome");
                                            }),
                            f.gate,
                            71,
                            11,
                            SupplierJournalGuiFixture.CLOCK);
            var adapter = session.journal(SupplierJournalAdapter.Kind.INVOICE);
            var dialog = create(adapter, saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true));
                SwingUtilities.invokeAndWait(
                        () -> component(dialog, "journal-preview", JButton.class).doClick());
                ready(dialog);
                confirm(dialog);
                waitFor(
                        () ->
                                adapter.isBlocked()
                                        && component(dialog, "journal-cancel", JButton.class)
                                                .isEnabled());
                SwingUtilities.invokeAndWait(
                        () -> {
                            assertFalse(
                                    component(dialog, "journal-preview", JButton.class)
                                            .isEnabled());
                            assertFalse(
                                    component(dialog, "journal-post", JButton.class).isEnabled());
                            assertFalse(
                                    component(dialog, "journal-from", JTextField.class)
                                            .isEnabled());
                            assertTrue(dialog.isShowing());
                            component(dialog, "journal-cancel", JButton.class).doClick();
                        });
                assertEquals(0, callbacks.get());
                assertEquals(1, f.voucherCount());
                assertTrue(
                        new NormalizedSupplierInvoiceStore(f.anchor)
                                .findSupplierInvoice(71, id)
                                .orElseThrow()
                                .document()
                                .isEntered());
            } finally {
                SwingUtilities.invokeAndWait(dialog::dispose);
                session.close();
            }
        }
    }

    @Test
    void bothExplicitSupplierRoutesShowImmutableIdentityRowsAndNotifyOnlyAfterConfirmedCommit()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (var kind : SupplierJournalAdapter.Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var ignored = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == SupplierJournalAdapter.Kind.CREDIT;
                long id = f.add(credit, 5, credit ? "10" : "100");
                AtomicInteger callbacks = new AtomicInteger(), opens = new AtomicInteger();
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
                var adapter = session.journal(kind);
                var dialog =
                        create(
                                adapter,
                                saved -> {
                                    assertTrue(SwingUtilities.isEventDispatchThread());
                                    assertEquals(kind, saved.kind());
                                    assertEquals(71, saved.companyId());
                                    assertEquals(11, saved.yearId());
                                    assertEquals(java.util.List.of(id), saved.documentIds());
                                    callbacks.incrementAndGet();
                                });
                try {
                    assertEquals(0, opens.get());
                    SwingUtilities.invokeLater(() -> dialog.setVisible(true));
                    SwingUtilities.invokeAndWait(
                            () -> {
                                assertEquals(
                                        SupplierJournalGuiFixture.FROM.toString(),
                                        component(dialog, "journal-from", JTextField.class)
                                                .getText());
                                assertEquals(
                                        SupplierJournalGuiFixture.TO.toString(),
                                        component(dialog, "journal-to", JTextField.class)
                                                .getText());
                                assertEquals("supplier-journal", dialog.getName());
                                assertFalse(
                                        component(dialog, "journal-post", JButton.class)
                                                .isEnabled());
                                component(dialog, "journal-preview", JButton.class).doClick();
                            });
                    ready(dialog);
                    SwingUtilities.invokeAndWait(
                            () -> {
                                JTable
                                        documents =
                                                component(
                                                        dialog, "journal-documents", JTable.class),
                                        rows = component(dialog, "journal-rows", JTable.class);
                                assertEquals(1, documents.getRowCount());
                                assertEquals(id, documents.getValueAt(0, 0));
                                assertEquals("Leverantör", documents.getColumnName(3));
                                assertFalse(documents.isCellEditable(0, 1));
                                assertFalse(rows.isCellEditable(0, 0));
                                assertTrue(rows.getRowCount() >= 2);
                                assertEquals(String.class, rows.getColumnClass(1));
                                assertTrue(
                                        component(dialog, "journal-status", JTextArea.class)
                                                .getText()
                                                .contains("verifikat 1"));
                                assertFalse(
                                        component(dialog, "journal-post", JButton.class)
                                                .isEnabled());
                            });
                    assertEquals(0, callbacks.get());
                    assertFalse(f.stored(credit, id).isEntered());
                    confirm(dialog);
                    waitFor(() -> !dialog.isDisplayable());
                    assertEquals(1, callbacks.get());
                    assertEquals(2, opens.get());
                    assertTrue(f.stored(credit, id).isEntered());
                    assertEquals(1, f.voucherCount());
                    // The other journal family is untouched; no view or legacy menu/cache was
                    // involved.
                } finally {
                    SwingUtilities.invokeAndWait(dialog::dispose);
                    session.close();
                }
            }
    }

    @Test
    void cancelledPreviewAndInvalidatedPendingLoadNeverPostOrNotify() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(false, 5, "100");
            AtomicInteger callbacks = new AtomicInteger();
            var session = f.session();
            var dialog =
                    create(
                            session.journal(SupplierJournalAdapter.Kind.INVOICE),
                            saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true));
                SwingUtilities.invokeAndWait(
                        () -> component(dialog, "journal-preview", JButton.class).doClick());
                ready(dialog);
                SwingUtilities.invokeAndWait(
                        () -> component(dialog, "journal-cancel", JButton.class).doClick());
                assertFalse(dialog.isDisplayable());
                assertFalse(f.stored(false, id).isEntered());
                assertEquals(0, callbacks.get());
                assertEquals(0, f.voucherCount());
            } finally {
                SwingUtilities.invokeAndWait(dialog::dispose);
                session.close();
            }
            CountDownLatch opening = new CountDownLatch(1), release = new CountDownLatch(1);
            var pending =
                    new SupplierDocumentGuiSession(
                            () -> {
                                opening.countDown();
                                await(release);
                                return f.open();
                            },
                            f.gate,
                            71,
                            11,
                            SupplierJournalGuiFixture.CLOCK);
            var loading =
                    create(
                            pending.journal(SupplierJournalAdapter.Kind.INVOICE),
                            saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> loading.setVisible(true));
                SwingUtilities.invokeAndWait(
                        () -> component(loading, "journal-preview", JButton.class).doClick());
                assertTrue(opening.await(10, TimeUnit.SECONDS));
                pending.close();
                SwingUtilities.invokeAndWait(loading::invalidateSelection);
                release.countDown();
                waitFor(() -> !loading.isDisplayable());
                assertEquals(0, callbacks.get());
                assertEquals(0, f.voucherCount());
            } finally {
                release.countDown();
                SwingUtilities.invokeAndWait(loading::dispose);
                pending.close();
            }
        }
    }

    @Test
    void closedSelectionDuringAcceptedPostingRetainsOriginalCommitAndSuppressesStaleCallback()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            long id = f.add(false, 5, "100");
            CountDownLatch committing = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            var session =
                    new SupplierDocumentGuiSession(
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
                            SupplierJournalGuiFixture.CLOCK);
            var dialog =
                    create(
                            session.journal(SupplierJournalAdapter.Kind.INVOICE),
                            saved -> callbacks.incrementAndGet());
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true));
                SwingUtilities.invokeAndWait(
                        () -> component(dialog, "journal-preview", JButton.class).doClick());
                ready(dialog);
                confirm(dialog);
                assertTrue(committing.await(10, TimeUnit.SECONDS));
                session.close();
                SwingUtilities.invokeAndWait(
                        () -> {
                            dialog.invalidateSelection();
                            assertTrue(dialog.isDisplayable());
                        });
                release.countDown();
                waitFor(() -> !dialog.isDisplayable());
                assertEquals(0, callbacks.get());
                assertTrue(f.stored(false, id).isEntered());
                assertEquals(1, f.voucherCount());
            } finally {
                release.countDown();
                SwingUtilities.invokeAndWait(dialog::dispose);
                session.close();
            }
        }
    }

    @Test
    void refreshCallbackFailureAfterCommitNeverOffersAnotherPost() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            f.add(true, 5, "10");
            var session = f.session();
            AtomicInteger callbacks = new AtomicInteger();
            var dialog =
                    create(
                            session.journal(SupplierJournalAdapter.Kind.CREDIT),
                            saved -> {
                                callbacks.incrementAndGet();
                                throw new IllegalStateException(
                                        "Refresh failed after confirmed commit");
                            });
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true));
                SwingUtilities.invokeAndWait(
                        () -> component(dialog, "journal-preview", JButton.class).doClick());
                ready(dialog);
                confirm(dialog);
                waitFor(() -> !dialog.isDisplayable());
                assertEquals(1, callbacks.get());
                assertEquals(1, f.voucherCount());
            } finally {
                SwingUtilities.invokeAndWait(dialog::dispose);
                session.close();
            }
        }
    }

    private static SupplierJournalDialog create(
            SupplierJournalAdapter adapter,
            java.util.function.Consumer<SupplierJournalAdapter.Saved> saved)
            throws Exception {
        AtomicReference<SupplierJournalDialog> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(
                () ->
                        ref.set(
                                new SupplierJournalDialog(
                                        null,
                                        adapter,
                                        SupplierJournalGuiFixture.FROM,
                                        SupplierJournalGuiFixture.TO,
                                        saved)));
        return ref.get();
    }

    private static void ready(SupplierJournalDialog dialog) throws Exception {
        waitFor(() -> component(dialog, "journal-confirm", JCheckBox.class).isEnabled());
    }

    private static void confirm(SupplierJournalDialog dialog) throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    component(dialog, "journal-confirm", JCheckBox.class).doClick();
                    component(dialog, "journal-post", JButton.class).doClick();
                });
    }

    private interface Commit {
        void before(Connection connection) throws Exception;
    }

    private static Connection wrap(Connection connection, Commit before) {
        return SupplierJournalGuiFixture.wrap(
                connection,
                (method, args) -> {
                    if (method.getName().equals("commit")) before.before(connection);
                    return SupplierJournalGuiFixture.invoke(connection, method, args);
                });
    }

    private static void await(CountDownLatch latch) throws SQLException {
        SupplierDocumentGuiSessionTest.await(latch);
    }

    private static <T extends Component> T component(Container parent, String name, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName()) && type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T found = component(nested, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        AtomicBoolean ready = new AtomicBoolean();
        do {
            SwingUtilities.invokeAndWait(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("Timed out waiting for GUI condition");
    }
}
