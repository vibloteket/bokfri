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
import java.util.List;
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
class SupplierIdentityDialogGuiTest {
    @BeforeEach
    void display() {
        assumeFalse(GraphicsEnvironment.isHeadless());
    }

    @Test
    void bothSortedViewRoutesPreviewNumberOnlyKeepStoredNameAndDatesAndRefreshOnlyAfterCommit()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var guard = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = SupplierIdentityAdapterTest.document(f, credit),
                        bank = f.add(credit, 0, "25");
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "UPDATE "
                                    + SupplierIdentityAdapterTest.table(credit)
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
                                    credit
                                            ? "supplier-credit-identity"
                                            : "supplier-invoice-identity",
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
                                                                                            SupplierIdentityDialog
                                                                                    && w
                                                                                            .isShowing())));
                    var dialog =
                            edt(
                                    () ->
                                            (SupplierIdentityDialog)
                                                    java.util.Arrays.stream(
                                                                    java.awt.Window.getWindows())
                                                            .filter(
                                                                    w ->
                                                                            w
                                                                                            instanceof
                                                                                            SupplierIdentityDialog
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
                                    assertFalse(combo(dialog).isEditable());
                                    assertTrue(
                                            combo(dialog)
                                                    .getSelectedItem()
                                                    .toString()
                                                    .contains("\"S30\""));
                                    return null;
                                });
                        proposed(dialog);
                        preview(dialog);
                        assertEquals(before, f.snapshot());
                        assertEquals(0, events.get());
                        edt(
                                () -> {
                                    String summary =
                                            ((JTextArea) component(dialog, "summary")).getText();
                                    for (String expected :
                                            List.of(
                                                    "\"S30\"",
                                                    "\"S60\"",
                                                    "2026-02-02",
                                                    "2026-03-04"))
                                        assertTrue(summary.contains(expected), summary);
                                    assertFalse(button(dialog, "save").isEnabled());
                                    button(dialog, "confirm").doClick();
                                    assertTrue(button(dialog, "save").isEnabled());
                                    selectCode(dialog, "SNET60");
                                    assertFalse(
                                            ((JCheckBox) button(dialog, "confirm")).isSelected());
                                    assertFalse(button(dialog, "save").isEnabled());
                                    return null;
                                });
                        preview(dialog);
                        save(dialog);
                        waitFor(() -> events.get() == 1 && !edt(dialog::isDisplayable));
                        assertEquals(id, edt(() -> table.getValueAt(table.getSelectedRow(), 0)));
                        SupplierIdentityAdapterTest.assertNarrow(
                                f,
                                credit,
                                id,
                                before,
                                header,
                                new org.fribok.bookkeeping.service.supplierinvoice
                                        .NormalizedSupplierIdentityService.Values("SNET60"));
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
    void historicalNullAndControlNumbersAreRetainedLiterallyAndReloadCancelDiscardDrafts()
            throws Exception {
        for (String value : java.util.Arrays.asList(null, "<html>old\nterm"))
            try (var f = new SupplierJournalGuiFixture()) {
                long id = SupplierIdentityAdapterTest.document(f, false);
                try (var sql =
                        f.anchor.prepareStatement(
                                "UPDATE supplier_invoice SET supplier_number=? WHERE id=?")) {
                    sql.setString(1, value);
                    sql.setLong(2, id);
                    sql.executeUpdate();
                }
                f.anchor.commit();
                var before = f.snapshot();
                var count = new AtomicInteger();
                var session = f.session();
                var dialog = create(session, Kind.INVOICE, id, count);
                try {
                    ready(dialog);
                    assertFalse(edt(() -> combo(dialog).isEditable()));
                    if (value == null) assertEquals(0, edt(() -> combo(dialog).getSelectedIndex()));
                    else {
                        String label = edt(() -> combo(dialog).getSelectedItem().toString());
                        assertFalse(label.startsWith("<html>"));
                        assertTrue(label.contains("\\u000a"));
                    }
                    preview(dialog);
                    save(dialog);
                    waitFor(() -> !edt(dialog::isDisplayable));
                    assertEquals(before, f.snapshot());
                    assertEquals(1, count.get());
                } finally {
                    edt(
                            () -> {
                                dialog.dispose();
                                return null;
                            });
                }
                var second = create(session, Kind.INVOICE, id, count);
                try {
                    ready(second);
                    String original = edt(() -> combo(second).getSelectedItem().toString());
                    proposed(second);
                    edt(
                            () -> {
                                button(second, "reload").doClick();
                                return null;
                            });
                    ready(second);
                    assertEquals(original, edt(() -> combo(second).getSelectedItem().toString()));
                    proposed(second);
                    edt(
                            () -> {
                                button(second, "cancel").doClick();
                                return null;
                            });
                    assertEquals(before, f.snapshot());
                    assertEquals(1, count.get());
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
    void staleSupplierCatalogueRefusesConfirmationAndReloadShowsChangedChoices() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = SupplierIdentityAdapterTest.document(f, false);
            var count = new AtomicInteger();
            var dialog = create(f.session(), Kind.INVOICE, id, count);
            try {
                ready(dialog);
                proposed(dialog);
                preview(dialog);
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate("DELETE FROM supplier WHERE legacy_id=702");
                }
                f.anchor.commit();
                var before = f.snapshot();
                save(dialog);
                waitFor(() -> edt(() -> button(dialog, "reload").isEnabled()));
                assertFalse(edt(() -> button(dialog, "preview").isEnabled()));
                assertFalse(edt(() -> button(dialog, "save").isEnabled()));
                assertEquals(before, f.snapshot());
                assertEquals(0, count.get());
                edt(
                        () -> {
                            button(dialog, "reload").doClick();
                            return null;
                        });
                ready(dialog);
                edt(
                        () -> {
                            assertTrue(
                                    combo(dialog).getSelectedItem().toString().contains("\"S30\""));
                            for (int i = 0; i < combo(dialog).getItemCount(); i++)
                                assertFalse(
                                        combo(dialog).getItemAt(i).toString().contains("\"S60\""));
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
    void clearingExistingSupplierIsRefusedWithoutWritesAndRequiresReload() throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = SupplierIdentityAdapterTest.document(f, true);
            var count = new AtomicInteger();
            var before = f.snapshot();
            var dialog = create(f.session(), Kind.CREDIT, id, count);
            try {
                ready(dialog);
                edt(
                        () -> {
                            combo(dialog).setSelectedIndex(0);
                            button(dialog, "preview").doClick();
                            return null;
                        });
                waitFor(() -> edt(() -> button(dialog, "reload").isEnabled()));
                assertFalse(edt(() -> button(dialog, "preview").isEnabled()));
                assertFalse(edt(() -> button(dialog, "save").isEnabled()));
                assertEquals(before, f.snapshot());
                assertEquals(0, count.get());
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
    void duplicateTargetNumbersAreNotOfferedAndHistoricalAmbiguousNumberRemainsSelected()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = SupplierIdentityAdapterTest.document(f, false);
            try (var sql = f.anchor.createStatement()) {
                sql.executeUpdate(
                        "INSERT INTO supplier(legacy_id,company_id,number,name) SELECT"
                            + " 705,id,'S60','Duplicate new' FROM company WHERE legacy_id=71");
                sql.executeUpdate(
                        "INSERT INTO supplier(legacy_id,company_id,number,name) SELECT"
                            + " 706,id,'S30','Duplicate old' FROM company WHERE legacy_id=71");
            }
            f.anchor.commit();
            var before = f.snapshot();
            var count = new AtomicInteger();
            var dialog = create(f.session(), Kind.INVOICE, id, count);
            try {
                ready(dialog);
                edt(
                        () -> {
                            assertTrue(
                                    combo(dialog).getSelectedItem().toString().contains("\"S30\""));
                            for (int i = 0; i < combo(dialog).getItemCount(); i++)
                                assertFalse(
                                        combo(dialog).getItemAt(i).toString().contains("\"S60\""));
                            return null;
                        });
                preview(dialog);
                save(dialog);
                waitFor(() -> !edt(dialog::isDisplayable));
                assertEquals(before, f.snapshot());
                assertEquals(1, count.get());
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
            long id = SupplierIdentityAdapterTest.document(f, false);
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
                long id = SupplierIdentityAdapterTest.document(f, false);
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
                    if (durable) {
                        assertEquals("S60", f.stored(false, id).getSupplierNr());
                    } else {
                        assertEquals("S30", f.stored(false, id).getSupplierNr());
                    }
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
            long id = SupplierIdentityAdapterTest.document(f, false);
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
                assertEquals("S60", f.stored(false, id).getSupplierNr());
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
            long id = SupplierIdentityAdapterTest.document(f, true);
            AtomicInteger callbacks = new AtomicInteger();
            var session = f.session();
            var dialog =
                    edt(
                            () ->
                                    new SupplierIdentityDialog(
                                            null,
                                            session.supplierIdentity(Kind.CREDIT, id),
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
                assertEquals("S60", f.stored(true, id).getSupplierNr());
            } finally {
                edt(
                        () -> {
                            dialog.dispose();
                            return null;
                        });
            }
        }
    }

    private static JComboBox<?> combo(java.awt.Container d) {
        return (JComboBox<?>) component(d, "code");
    }

    private static void selectCode(java.awt.Container d, String code) {
        String label =
                se.swedsoft.bookkeeping.gui.util.SSBundle.getBundle()
                                .getString("supplier.identity.code")
                        + "\""
                        + code
                        + "\"";
        var box = combo(d);
        for (int i = 0; i < box.getItemCount(); i++) {
            if (box.getItemAt(i).toString().startsWith(label)) {
                box.setSelectedIndex(i);
                return;
            }
        }
        throw new AssertionError("Missing currency " + code);
    }

    private static SupplierIdentityDialog create(
            SupplierDocumentGuiSession session, Kind kind, long id, AtomicInteger callbacks) {
        return edt(
                () ->
                        new SupplierIdentityDialog(
                                null,
                                session.supplierIdentity(kind, id),
                                saved -> callbacks.incrementAndGet()));
    }

    private static void ready(SupplierIdentityDialog d) throws Exception {
        waitFor(() -> edt(() -> button(d, "preview").isEnabled()));
    }

    private static void proposed(SupplierIdentityDialog d) {
        edt(
                () -> {
                    selectCode(d, "S60");
                    return null;
                });
    }

    private static void preview(SupplierIdentityDialog d) throws Exception {
        edt(
                () -> {
                    button(d, "preview").doClick();
                    return null;
                });
        waitFor(() -> edt(() -> button(d, "confirm").isEnabled()));
    }

    private static void save(SupplierIdentityDialog d) {
        edt(
                () -> {
                    button(d, "confirm").doClick();
                    button(d, "save").doClick();
                    return null;
                });
    }

    private static AbstractButton button(Container d, String name) {
        return (AbstractButton) component(d, name);
    }

    private static JComponent component(Container root, String suffix) {
        for (Component child : root.getComponents()) {
            if (child instanceof JComponent c
                    && ("supplier-identity-" + suffix).equals(c.getName())) return c;
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
        fail("Timed out waiting for supplier VAT/rounding");
    }
}
