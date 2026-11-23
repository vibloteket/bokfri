package org.fribok.bookkeeping.application;

import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.FROM;
import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.Observed;
import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.TO;
import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.accounts;
import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.draft;
import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.money;
import static org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.postedAndReversed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.fribok.bookkeeping.gui.application.NormalizedFinancialReportsPanel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Real-display contracts for the two additional read-only report choices. */
@Tag("integration")
class NormalizedApplicationBalanceIncomeGuiTest {
    @Test
    void balanceUsesExplicitToDateAndExposesAllTotalsAndUnadjustedDifferenceTest()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            postedAndReversed(f, owner);
            try (var h = new Host(owner.select(71, 11));
                    var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
                var before = f.snapshot();
                assertEquals(0, edt(() -> h.kind().getSelectedIndex()));
                h.inputs(3, "unused-not-a-date", "2026-06-15");
                assertFalse(edt(() -> h.field("from").isEnabled()));
                assertFalse(edt(() -> h.button("load").isEnabled()));
                assertFalse(edt(() -> h.account().isEnabled()));
                h.generate();
                assertEquals(1, edt(() -> h.table().getRowCount()));
                assertEquals(3, edt(() -> h.table().getColumnCount()));
                money("115.25", edt(() -> (java.math.BigDecimal) h.table().getValueAt(0, 2)));
                String header = edt(() -> h.area("header").getText());
                for (String key :
                        new String[] {
                            "balancescope", "assets", "liabilities", "currentresult", "difference"
                        }) assertTrue(header.contains(text(key)));
                assertTrue(header.contains("105.25"));
                assertTrue(header.contains("10"));
                assertEquals(before, f.snapshot());
                assertFalse(edt(() -> h.table().isCellEditable(0, 2)));
            }
        }
    }

    @Test
    void incomeExcludesOpeningAndPriorEntriesShowsExplicitResultAndZeroRowsTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            postedAndReversed(f, owner);
            try (var h = new Host(owner.select(71, 11))) {
                h.inputs(4, "2026-06-15", "2026-06-15");
                assertTrue(edt(() -> h.field("from").isEnabled()));
                h.generate();
                assertEquals(1, edt(() -> h.table().getRowCount()));
                money("100.25", edt(() -> (java.math.BigDecimal) h.table().getValueAt(0, 2)));
                assertTrue(edt(() -> h.area("header").getText()).contains(text("incomescope")));
                h.inputs(4, "2026-06-01", "2026-06-30");
                h.generate();
                assertEquals(0, edt(() -> h.table().getRowCount()));
                assertTrue(edt(() -> h.area("header").getText()).contains(text("result") + " 0"));
            }
        }
    }

    @Test
    void inputOrKindChangesClearTablesAndRegenerateOnlyOnExplicitClickTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            try (var h = new Host(owner.select(71, 11))) {
                h.inputs(3, "", "2026-06-30");
                h.generate();
                int opens = f.opens.get();
                edt(
                        () -> {
                            h.field("to").setText("2026-06-15");
                            return null;
                        });
                assertEquals(0, edt(() -> h.table().getRowCount()));
                assertEquals("", edt(() -> h.area("header").getText()));
                assertEquals(opens, f.opens.get());
                h.generate();
                edt(
                        () -> {
                            h.kind().setSelectedIndex(4);
                            return null;
                        });
                assertEquals(0, edt(() -> h.table().getRowCount()));
                assertFalse(edt(() -> h.button("generate").isEnabled()));
                assertTrue(edt(() -> h.field("from").isEnabled()));
            }
        }
    }

    @Test
    void malformedDatesAvoidSqlAndOutOfYearPeriodsFailWithoutStaleResultsTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            try (var h = new Host(owner.select(71, 11))) {
                h.inputs(3, "", "2026-06-30");
                h.generate();
                h.inputs(3, "", "invalid");
                int opens = f.opens.get();
                h.click("generate");
                h.waitStatus("failed");
                assertEquals(opens, f.opens.get());
                assertEquals(0, edt(() -> h.table().getRowCount()));
                h.inputs(3, "", "2027-01-01");
                h.click("generate");
                h.waitStatus("failed");
                assertEquals("", edt(() -> h.area("header").getText()));
                h.inputs(4, "2026-06-30", "2026-06-01");
                h.click("generate");
                h.waitStatus("failed");
                assertEquals(0, edt(() -> h.table().getRowCount()));
            }
        }
    }

    @Test
    void replacingCompanyClearsDatesKindAndResultsWithoutAutomaticReadTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            try (var h = new Host(owner.select(71, 11))) {
                h.inputs(3, "", "2026-06-30");
                h.generate();
                var other = owner.select(72, 12);
                int opens = f.opens.get();
                edt(
                        () -> {
                            h.panel.replaceSelection(other);
                            return null;
                        });
                assertEquals(opens, f.opens.get());
                assertEquals(0, edt(() -> h.kind().getSelectedIndex()));
                assertEquals("", edt(() -> h.field("to").getText()));
                assertEquals(0, edt(() -> h.table().getRowCount()));
                h.inputs(4, "2026-06-01", "2026-06-30");
                h.generate();
                assertEquals(0, edt(() -> h.table().getRowCount()));
            }
        }
    }

    @Test
    void bothPendingReportKindsAreBackgroundOnlyAndFenceClosedCallbacksTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (int kind : new int[] {3, 4})
            try (var f = new Fixture();
                    var observed = new Observed(f);
                    var h = new Host(observed.owner.select(71, 11))) {
                accounts(f);
                h.inputs(kind, "2026-06-01", "2026-06-30");
                observed.entered = new CountDownLatch(1);
                observed.release = new CountDownLatch(1);
                int closes = observed.closes.get();
                try {
                    h.click("generate");
                    assertTrue(observed.entered.await(5, TimeUnit.SECONDS));
                    assertFalse(edt(() -> h.kind().isEnabled()));
                    assertFalse(edt(() -> h.button("generate").isEnabled()));
                    edt(
                            () -> {
                                h.panel.close();
                                return null;
                            });
                } finally {
                    observed.release.countDown();
                }
                await(() -> observed.closes.get() > closes);
                edt(() -> null);
                assertEquals(0, edt(() -> h.table().getRowCount()));
                assertEquals("", edt(() -> h.area("header").getText()));
                assertEquals(text("closed"), edt(() -> h.area("status").getText()));
                assertEquals(0, observed.commits.get());
            }
    }

    @Test
    void backgroundQueryFailureClearsResultsAndAllowsExplicitRetryTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var observed = new Observed(f);
                var h = new Host(observed.owner.select(71, 11))) {
            accounts(f);
            h.inputs(3, "", "2026-06-30");
            h.generate();
            observed.failure = "query";
            h.click("generate");
            h.waitStatus("failed");
            assertEquals("", edt(() -> h.area("header").getText()));
            assertEquals(0, edt(() -> h.table().getRowCount()));
            assertFalse(observed.owner.isBlocked());
            observed.failure = "";
            h.generate();
            assertEquals(1, edt(() -> h.table().getRowCount()));
        }
    }

    @Test
    void commitUncertaintyElsewherePreventsFurtherReportReadsTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var lease = owner.select(71, 11);
            try (var h = new Host(lease)) {
                h.inputs(4, "2026-06-01", "2026-06-30");
                h.generate();
                var token = lease.vouchers().preview(draft(FROM, "1"));
                f.failure = "after";
                assertThrows(java.sql.SQLException.class, () -> lease.vouchers().post(token));
                edt(
                        () -> {
                            h.panel.setHostAvailable(false);
                            return null;
                        });
                int opens = f.opens.get();
                assertFalse(edt(() -> h.button("generate").isEnabled()));
                assertThrows(
                        java.sql.SQLException.class,
                        () -> lease.reports().incomeStatement(FROM, TO));
                assertEquals(opens, f.opens.get());
            }
        }
    }

    private static final class Host implements AutoCloseable {
        final JFrame frame;
        final NormalizedFinancialReportsPanel panel;

        Host(NormalizedApplicationOwner.Selection lease) throws Exception {
            frame = edt(JFrame::new);
            panel =
                    edt(
                            () -> {
                                var p = new NormalizedFinancialReportsPanel();
                                p.replaceSelection(lease);
                                frame.add(p);
                                frame.setSize(1400, 1000);
                                frame.setVisible(true);
                                return p;
                            });
        }

        JComboBox<?> kind() {
            return (JComboBox<?>) find(frame, "financial-report-kind");
        }

        JComboBox<?> account() {
            return (JComboBox<?>) find(frame, "financial-report-account");
        }

        JTextField field(String k) {
            return (JTextField) find(frame, "financial-report-" + k);
        }

        JButton button(String k) {
            return (JButton) find(frame, "financial-report-" + k);
        }

        JTextArea area(String k) {
            return (JTextArea) find(frame, "financial-report-" + k);
        }

        JTable table() {
            return (JTable) find(frame, "financial-report-rows");
        }

        void inputs(int kind, String from, String to) throws Exception {
            edt(
                    () -> {
                        kind().setSelectedIndex(kind);
                        field("from").setText(from);
                        field("to").setText(to);
                        return null;
                    });
        }

        void click(String k) throws Exception {
            edt(
                    () -> {
                        assertTrue(button(k).isEnabled(), k);
                        button(k).doClick();
                        return null;
                    });
        }

        void waitStatus(String k) throws Exception {
            await(() -> !panel.isBusy() && area("status").getText().startsWith(text(k)));
        }

        void generate() throws Exception {
            click("generate");
            waitStatus("generated");
        }

        public void close() throws Exception {
            edt(
                    () -> {
                        panel.close();
                        frame.dispose();
                        return null;
                    });
        }
    }

    private static Component find(Container root, String name) {
        for (var c : root.getComponents()) {
            if (name.equals(c.getName())) return c;
            if (c instanceof Container child) {
                var found = find(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("normalized.financialreports." + key);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        var task = new FutureTask<T>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < end) {
            if (edt(condition)) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for balance/income UI");
    }
}
