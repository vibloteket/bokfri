package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierExchangeRateService.CurrencyValues;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierExchangeRateService.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
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
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

@Tag("integration")
class SupplierCurrencyDialogGuiTest {
    @BeforeEach
    void display() {
        assumeFalse(GraphicsEnvironment.isHeadless());
    }

    @Test
    void bothSortedViewRoutesPreviewTotalsAndResetConfirmationBeforeCommittedRefresh()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture();
                    var guard = new SupplierJournalGuiFixture.LegacyGuard()) {
                boolean credit = kind == Kind.CREDIT;
                long id = SupplierCurrencyAdapterTest.document(f, credit),
                        bank = f.add(credit, 0, "2");
                try (var s = f.anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE "
                                    + SupplierCurrencyAdapterTest.table(credit)
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
                                            ? "supplier-credit-currency"
                                            : "supplier-invoice-currency",
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
                                                                                            SupplierCurrencyDialog
                                                                                    && w
                                                                                            .isShowing())));
                    var dialog =
                            edt(
                                    () ->
                                            (SupplierCurrencyDialog)
                                                    java.util.Arrays.stream(
                                                                    java.awt.Window.getWindows())
                                                            .filter(
                                                                    w ->
                                                                            w
                                                                                            instanceof
                                                                                            SupplierCurrencyDialog
                                                                                    && w
                                                                                            .isShowing())
                                                            .findFirst()
                                                            .orElseThrow());
                    try {
                        ready(dialog);
                        var before = f.snapshot();
                        var header = SupplierDocumentMetadataAdapterTest.header(f, credit, id);
                        proposed(dialog);
                        preview(dialog);
                        assertEquals(before, f.snapshot());
                        assertEquals(0, events.get());
                        edt(
                                () -> {
                                    String summary =
                                            ((JTextArea) component(dialog, "summary")).getText();
                                    assertTrue(summary.contains("EUR"));
                                    assertTrue(summary.contains("USD"));
                                    assertTrue(summary.contains("1.25"));
                                    assertTrue(summary.contains("187.5"));
                                    assertTrue(summary.contains("300"));
                                    assertFalse(button(dialog, "save").isEnabled());
                                    button(dialog, "confirm").doClick();
                                    assertTrue(button(dialog, "save").isEnabled());
                                    field(dialog, "rate").setText("3");
                                    assertFalse(
                                            ((JCheckBox) button(dialog, "confirm")).isSelected());
                                    assertFalse(button(dialog, "save").isEnabled());
                                    return null;
                                });
                        preview(dialog);
                        edt(
                                () -> {
                                    button(dialog, "confirm").doClick();
                                    selectCode(dialog, "GBP");
                                    assertEquals("3", field(dialog, "rate").getText());
                                    assertFalse(
                                            ((JCheckBox) button(dialog, "confirm")).isSelected());
                                    assertFalse(button(dialog, "save").isEnabled());
                                    return null;
                                });
                        preview(dialog);
                        save(dialog);
                        waitFor(() -> events.get() == 1 && !edt(dialog::isDisplayable));
                        assertEquals(id, edt(() -> table.getValueAt(table.getSelectedRow(), 0)));
                        SupplierCurrencyAdapterTest.assertNarrow(
                                f,
                                credit,
                                id,
                                before,
                                header,
                                new CurrencyValues("GBP", new BigDecimal("3")));
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
    void invalidNumericDraftCannotSaveAndRejectedNegativeVatRequiresReloadWithoutWrites()
            throws Exception {
        try (var f = new SupplierJournalGuiFixture()) {
            long id = SupplierCurrencyAdapterTest.document(f, false);
            var before = f.snapshot();
            var dialog = create(f.session(), Kind.INVOICE, id, new AtomicInteger());
            try {
                ready(dialog);
                for (String invalid : List.of("NaN", "Infinity", "1,5", "abc"))
                    edt(
                            () -> {
                                field(dialog, "rate").setText(invalid);
                                button(dialog, "preview").doClick();
                                assertFalse(button(dialog, "confirm").isEnabled());
                                assertFalse(button(dialog, "save").isEnabled());
                                assertTrue(button(dialog, "preview").isEnabled());
                                return null;
                            });
                assertEquals(before, f.snapshot());
                edt(
                        () -> {
                            field(dialog, "rate").setText("-1");
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
                decimal("1.25", new BigDecimal(edt(() -> field(dialog, "rate").getText())));
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
    void historicalNullsStaySelectedAndBaseCurrencyRequiresAnExplicitRateWithoutAutomaticDefaults()
            throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                long id = SupplierCurrencyAdapterTest.document(f, kind == Kind.CREDIT);
                try (var sql = f.anchor.createStatement()) {
                    sql.executeUpdate(
                            "UPDATE "
                                    + SupplierCurrencyAdapterTest.table(kind == Kind.CREDIT)
                                    + " SET currency_code=NULL,currency_rate=NULL WHERE id="
                                    + id);
                }
                f.anchor.commit();
                var before = f.snapshot();
                AtomicInteger callbacks = new AtomicInteger();
                var session = f.session();
                var dialog = create(session, kind, id, callbacks);
                try {
                    ready(dialog);
                    assertEquals(0, edt(() -> combo(dialog).getSelectedIndex()));
                    assertFalse(edt(() -> combo(dialog).isEditable()));
                    assertEquals("", edt(() -> field(dialog, "rate").getText()));
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
                var second = create(session, kind, id, callbacks);
                try {
                    ready(second);
                    proposed(second);
                    edt(
                            () -> {
                                button(second, "reload").doClick();
                                return null;
                            });
                    ready(second);
                    assertEquals(0, edt(() -> combo(second).getSelectedIndex()));
                    assertEquals("", edt(() -> field(second, "rate").getText()));
                    proposed(second);
                    edt(
                            () -> {
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
                var third = create(session, kind, id, callbacks);
                try {
                    ready(third);
                    edt(
                            () -> {
                                selectCode(third, "SEK");
                                assertEquals("", field(third, "rate").getText());
                                field(third, "rate").setText("1");
                                return null;
                            });
                    preview(third);
                    save(third);
                    waitFor(() -> !edt(third::isDisplayable));
                    assertEquals(2, callbacks.get());
                    var header =
                            SupplierDocumentMetadataAdapterTest.header(f, kind == Kind.CREDIT, id);
                    assertEquals("SEK", header.get("CURRENCY_CODE"));
                    decimal("1", new BigDecimal(header.get("CURRENCY_RATE")));
                } finally {
                    edt(
                            () -> {
                                third.dispose();
                                return null;
                            });
                }
            }
    }

    @Test
    void staleConfirmationWritesNothingAndReloadShowsConcurrentRawAmounts() throws Exception {
        for (var kind : Kind.values())
            try (var f = new SupplierJournalGuiFixture()) {
                long id = SupplierCurrencyAdapterTest.document(f, kind == Kind.CREDIT);
                AtomicInteger callbacks = new AtomicInteger();
                var dialog = create(f.session(), kind, id, callbacks);
                try {
                    ready(dialog);
                    proposed(dialog);
                    preview(dialog);
                    try (var s = f.anchor.createStatement()) {
                        s.executeUpdate(
                                "UPDATE "
                                        + SupplierCurrencyAdapterTest.table(kind == Kind.CREDIT)
                                        + " SET currency_rate=1.3 WHERE id="
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
                    decimal("1.3", new BigDecimal(edt(() -> field(dialog, "rate").getText())));
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
            long id = SupplierCurrencyAdapterTest.document(f, false);
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
                long id = SupplierCurrencyAdapterTest.document(f, false);
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
                        decimal("2", f.stored(false, id).getCurrencyRate());
                    } else {
                        decimal("1.25", f.stored(false, id).getCurrencyRate());
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
            long id = SupplierCurrencyAdapterTest.document(f, false);
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
                decimal("2", f.stored(false, id).getCurrencyRate());
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
            long id = SupplierCurrencyAdapterTest.document(f, true);
            AtomicInteger callbacks = new AtomicInteger();
            var session = f.session();
            var dialog =
                    edt(
                            () ->
                                    new SupplierCurrencyDialog(
                                            null,
                                            session.currency(Kind.CREDIT, id),
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
                decimal("2", f.stored(true, id).getCurrencyRate());
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
                                .getString("supplier.currency.code")
                        + code;
        var box = combo(d);
        for (int i = 0; i < box.getItemCount(); i++) {
            if (label.equals(box.getItemAt(i).toString())) {
                box.setSelectedIndex(i);
                return;
            }
        }
        throw new AssertionError("Missing currency " + code);
    }

    private static void decimal(String expected, BigDecimal actual) {
        SupplierCurrencyAdapterTest.decimal(expected, actual);
    }

    private static SupplierCurrencyDialog create(
            SupplierDocumentGuiSession session, Kind kind, long id, AtomicInteger callbacks) {
        return edt(
                () ->
                        new SupplierCurrencyDialog(
                                null,
                                session.currency(kind, id),
                                saved -> callbacks.incrementAndGet()));
    }

    private static void ready(SupplierCurrencyDialog d) throws Exception {
        waitFor(() -> edt(() -> button(d, "preview").isEnabled()));
    }

    private static void proposed(SupplierCurrencyDialog d) {
        edt(
                () -> {
                    selectCode(d, "USD");
                    field(d, "rate").setText("2");
                    return null;
                });
    }

    private static void preview(SupplierCurrencyDialog d) throws Exception {
        edt(
                () -> {
                    button(d, "preview").doClick();
                    return null;
                });
        waitFor(() -> edt(() -> button(d, "confirm").isEnabled()));
    }

    private static void save(SupplierCurrencyDialog d) {
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
            if (child instanceof JComponent c
                    && ("supplier-currency-" + suffix).equals(c.getName())) return c;
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
