package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.fribok.bookkeeping.application.NormalizedApplicationFinancialReportsTest.Observed;
import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.fribok.bookkeeping.gui.application.NormalizedDocumentWorkspace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.*;
import java.math.BigDecimal;
import java.util.concurrent.*;

import javax.swing.*;

@Tag("integration")
class NormalizedApplicationFinancialReportsGuiTest {
    static void prepared(Fixture f) throws Exception {
        try (var owner = f.owner()) {
            NormalizedApplicationFinancialReportsTest.postedAndReversed(f, owner);
        }
    }

    @Test
    void explicitTrialAndLedgerShowOpeningTurnoverAndReversalWithoutChangingSqlOrContext()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture()) {
            prepared(f);
            try (var owner = f.owner();
                    var host = new Host(owner)) {
                var before = f.snapshot();
                var lease = edt(() -> host.workspace.selection().orElseThrow());
                assertEquals(0, edt(() -> kind(host).getSelectedIndex()));
                assertEquals("", edt(() -> field(host, "from").getText()));
                assertEquals("", edt(() -> field(host, "to").getText()));
                assertFalse(edt(() -> button(host, "generate").isEnabled()));
                host.trial();
                assertEquals(2, edt(() -> table(host).getRowCount()));
                edt(
                        () -> {
                            assertEquals(1910, table(host).getValueAt(0, 0));
                            NormalizedApplicationFinancialReportsTest.money(
                                    "15", (BigDecimal) table(host).getValueAt(0, 2));
                            NormalizedApplicationFinancialReportsTest.money(
                                    "100.25", (BigDecimal) table(host).getValueAt(0, 3));
                            NormalizedApplicationFinancialReportsTest.money(
                                    "100.25", (BigDecimal) table(host).getValueAt(0, 4));
                            NormalizedApplicationFinancialReportsTest.money(
                                    "15", (BigDecimal) table(host).getValueAt(0, 5));
                            assertFalse(table(host).isCellEditable(0, 0));
                            assertFalse(area(host, "header").isEditable());
                            return null;
                        });
                host.ledger();
                assertEquals(2, edt(() -> table(host).getRowCount()));
                assertEquals(2, edt(() -> table(host).getValueAt(0, 0)));
                assertEquals(3, edt(() -> table(host).getValueAt(1, 0)));
                edt(
                        () -> {
                            NormalizedApplicationFinancialReportsTest.money(
                                    "115.25", (BigDecimal) table(host).getValueAt(0, 5));
                            NormalizedApplicationFinancialReportsTest.money(
                                    "15", (BigDecimal) table(host).getValueAt(1, 5));
                            assertTrue(
                                    area(host, "header")
                                            .getText()
                                            .contains("2026-06-01 - 2026-06-30"));
                            assertTrue(area(host, "header").getText().contains("CURRENT"));
                            return null;
                        });
                assertEquals(before, f.snapshot());
                assertSame(lease, edt(() -> host.workspace.selection().orElseThrow()));
            }
        }
    }

    @Test
    void changingDatesKindOrAccountClearsPriorResultsAndMalformedDateNeverOpensSql()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture()) {
            prepared(f);
            try (var observed = new Observed(f);
                    var host = new Host(observed.owner)) {
                host.trial();
                edt(
                        () -> {
                            field(host, "from").setText("bad-date");
                            return null;
                        });
                assertEquals(0, edt(() -> table(host).getRowCount()));
                assertEquals("", edt(() -> area(host, "header").getText()));
                int opens = observed.opens.get();
                click(host, "generate");
                host.failed();
                assertEquals(opens, observed.opens.get());
                host.trial();
                edt(
                        () -> {
                            kind(host).setSelectedIndex(2);
                            return null;
                        });
                assertEquals(0, edt(() -> table(host).getRowCount()));
                assertFalse(edt(() -> button(host, "generate").isEnabled()));
                host.ledger();
                edt(
                        () -> {
                            account(host).setSelectedIndex(0);
                            return null;
                        });
                assertEquals(0, edt(() -> table(host).getRowCount()));
                assertEquals("", edt(() -> area(host, "header").getText()));
            }
        }
    }

    @Test
    void oneInactiveAccountStillRequiresExplicitSelectionAndDatesWithNoDefaults() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE account SET active=FALSE WHERE accounting_year_id=(SELECT"
                                    + " id FROM accounting_year WHERE legacy_id=11)");
            }
            try (var host = new Host(owner)) {
                edt(
                        () -> {
                            kind(host).setSelectedIndex(2);
                            return null;
                        });
                click(host, "load");
                host.loaded();
                assertEquals(2, edt(() -> account(host).getItemCount()));
                assertEquals(0, edt(() -> account(host).getSelectedIndex()));
                assertEquals("", edt(() -> field(host, "from").getText()));
                assertFalse(edt(() -> button(host, "generate").isEnabled()));
                host.dates();
                assertFalse(edt(() -> button(host, "generate").isEnabled()));
                host.pickAccount();
                click(host, "generate");
                host.generated();
                assertEquals(0, edt(() -> table(host).getRowCount()));
                assertTrue(edt(() -> area(host, "header").getText().contains("1910")));
            }
        }
    }

    @Test
    void companyReplacementClearsChoicesAndShowsOnlyNewCompanyYearSnapshots() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture()) {
            prepared(f);
            try (var owner = f.owner();
                    var host = new Host(owner)) {
                host.ledger();
                var old = edt(() -> host.workspace.selection().orElseThrow().reports());
                host.apply(2);
                assertEquals(0, edt(() -> kind(host).getSelectedIndex()));
                assertEquals(1, edt(() -> account(host).getItemCount()));
                assertEquals("", edt(() -> field(host, "from").getText()));
                assertEquals(0, edt(() -> table(host).getRowCount()));
                assertThrows(java.sql.SQLException.class, old::accounts);
                host.ledger();
                assertEquals(0, edt(() -> table(host).getRowCount()));
                assertTrue(edt(() -> area(host, "header").getText().contains("72 / 12")));
                edt(
                        () -> {
                            for (int i = 0; i < account(host).getItemCount(); i++)
                                assertFalse(
                                        account(host).getItemAt(i).toString().startsWith("3010 :"));
                            return null;
                        });
            }
        }
    }

    @Test
    void readFailureClearsOldResultsAndCanRecoverWithoutUncertainty() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture()) {
            prepared(f);
            try (var observed = new Observed(f);
                    var host = new Host(observed.owner)) {
                host.trial();
                observed.failure = "query";
                click(host, "generate");
                host.failed();
                assertEquals(0, edt(() -> table(host).getRowCount()));
                assertEquals("", edt(() -> area(host, "header").getText()));
                assertFalse(observed.owner.isBlocked());
                observed.failure = "";
                click(host, "generate");
                host.generated();
                assertEquals(2, edt(() -> table(host).getRowCount()));
            }
        }
    }

    @Test
    void pendingReportInterlocksHostAndAllVoucherCommandsWithoutPollingSql() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture()) {
            prepared(f);
            try (var observed = new Observed(f);
                    var host = new Host(observed.owner)) {
                host.trialInputs();
                observed.entered = new CountDownLatch(1);
                observed.release = new CountDownLatch(1);
                try {
                    click(host, "generate");
                    assertTrue(observed.entered.await(5, TimeUnit.SECONDS));
                    int opens = observed.opens.get();
                    edt(
                            () -> {
                                assertFalse(
                                        ((JButton) find(host.frame, "workspace-reload"))
                                                .isEnabled());
                                assertFalse(
                                        ((JComboBox<?>) find(host.frame, "workspace-company"))
                                                .isEnabled());
                                assertFalse(
                                        ((JButton) find(host.frame, "voucher-create-load"))
                                                .isEnabled());
                                assertFalse(
                                        ((JButton) find(host.frame, "voucher-register-refresh"))
                                                .isEnabled());
                                assertFalse(
                                        ((JTextField) find(host.frame, "voucher-reversal-source"))
                                                .isEnabled());
                                host.workspace.reload();
                                return null;
                            });
                    Thread.sleep(150);
                    assertEquals(opens, observed.opens.get());
                } finally {
                    observed.release.countDown();
                }
                host.generated();
                assertTrue(
                        edt(
                                () ->
                                        ((JButton) find(host.frame, "voucher-register-refresh"))
                                                .isEnabled()));
            }
        }
    }

    @Test
    void closureClearsAndFencesPendingReadWithoutDisposingCallerWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture()) {
            prepared(f);
            try (var observed = new Observed(f);
                    var host = new Host(observed.owner)) {
                host.trialInputs();
                observed.entered = new CountDownLatch(1);
                observed.release = new CountDownLatch(1);
                int closed = observed.closes.get();
                try {
                    click(host, "generate");
                    assertTrue(observed.entered.await(5, TimeUnit.SECONDS));
                    edt(
                            () -> {
                                host.workspace.close();
                                assertTrue(host.frame.isDisplayable());
                                return null;
                            });
                } finally {
                    observed.release.countDown();
                }
                await(() -> observed.closes.get() > closed);
                assertEquals(0, edt(() -> table(host).getRowCount()));
                assertEquals("", edt(() -> area(host, "header").getText()));
                assertEquals(text("closed"), edt(() -> area(host, "status").getText()));
                assertFalse(edt(() -> button(host, "generate").isEnabled()));
            }
        }
    }

    @Test
    void uncertainCommitElsewhereRetiresAndClearsPreviouslyGeneratedReports() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture()) {
            prepared(f);
            try (var owner = f.owner();
                    var host = new Host(owner)) {
                host.trial();
                var lease = edt(() -> host.workspace.selection().orElseThrow());
                var token =
                        lease.vouchers()
                                .preview(
                                        NormalizedApplicationFinancialReportsTest.draft(
                                                java.time.LocalDate.of(2026, 6, 17), "25"));
                f.failure = "after";
                assertThrows(java.sql.SQLException.class, () -> lease.vouchers().post(token));
                await(() -> area(host, "status").getText().equals(text("blocked")));
                assertEquals(0, edt(() -> table(host).getRowCount()));
                assertEquals("", edt(() -> area(host, "header").getText()));
                assertFalse(edt(() -> button(host, "generate").isEnabled()));
                int opens = f.opens.get();
                assertThrows(java.sql.SQLException.class, lease.reports()::accounts);
                assertEquals(opens, f.opens.get());
            }
        }
    }

    private static final class Host implements AutoCloseable {
        final JFrame frame;
        final NormalizedDocumentWorkspace workspace;

        Host(NormalizedApplicationOwner owner) throws Exception {
            frame = edt(JFrame::new);
            workspace =
                    edt(
                            () -> {
                                var p = new NormalizedDocumentWorkspace(frame, owner);
                                frame.add(p);
                                frame.setSize(1300, 1100);
                                frame.setVisible(true);
                                return p;
                            });
            await(
                    () ->
                            !workspace.isBusy()
                                    && ((JComboBox<?>) find(frame, "workspace-company"))
                                                    .getItemCount()
                                            == 3);
            apply(1);
            edt(
                    () -> {
                        ((JTabbedPane) find(frame, "workspace-documents")).setSelectedIndex(5);
                        return null;
                    });
        }

        void apply(int index) throws Exception {
            edt(
                    () -> {
                        ((JComboBox<?>) find(frame, "workspace-company")).setSelectedIndex(index);
                        ((JComboBox<?>) find(frame, "workspace-year")).setSelectedIndex(1);
                        ((JButton) find(frame, "workspace-apply")).doClick();
                        return null;
                    });
            int company = index == 1 ? 71 : 72;
            await(
                    () ->
                            !workspace.isBusy()
                                    && workspace
                                            .selection()
                                            .map(s -> s.company().id() == company)
                                            .orElse(false)
                                    && kind(this).isEnabled()
                                    && ((JButton) find(frame, "document-refresh")).isEnabled()
                                    && ((JButton) find(frame, "supplier-document-refresh"))
                                            .isEnabled());
        }

        void dates() throws Exception {
            edt(
                    () -> {
                        field(this, "from").setText("2026-06-01");
                        field(this, "to").setText("2026-06-30");
                        return null;
                    });
        }

        void trialInputs() throws Exception {
            edt(
                    () -> {
                        kind(this).setSelectedIndex(1);
                        return null;
                    });
            dates();
        }

        void trial() throws Exception {
            trialInputs();
            click(this, "generate");
            generated();
        }

        void ledger() throws Exception {
            edt(
                    () -> {
                        kind(this).setSelectedIndex(2);
                        return null;
                    });
            dates();
            click(this, "load");
            loaded();
            assertEquals(0, edt(() -> account(this).getSelectedIndex()));
            pickAccount();
            click(this, "generate");
            generated();
        }

        void pickAccount() throws Exception {
            edt(
                    () -> {
                        int chosen = -1;
                        for (int i = 1; i < account(this).getItemCount(); i++)
                            if (account(this).getItemAt(i).toString().startsWith("1910 :"))
                                chosen = i;
                        assertTrue(chosen > 0);
                        account(this).setSelectedIndex(chosen);
                        return null;
                    });
        }

        void loaded() throws Exception {
            await(
                    () ->
                            button(this, "load").isEnabled()
                                    && area(this, "status").getText().startsWith(text("loaded")));
        }

        void generated() throws Exception {
            await(
                    () ->
                            kind(this).isEnabled()
                                    && area(this, "status")
                                            .getText()
                                            .startsWith(text("generated")));
        }

        void failed() throws Exception {
            await(
                    () ->
                            kind(this).isEnabled()
                                    && area(this, "status").getText().startsWith(text("failed")));
        }

        public void close() throws Exception {
            edt(
                    () -> {
                        workspace.close();
                        frame.dispose();
                        return null;
                    });
        }
    }

    private static String text(String key) {
        return SSBundle.getBundle().getString("normalized.financialreports." + key);
    }

    private static JComboBox<?> kind(Host h) {
        return (JComboBox<?>) find(h.frame, "financial-report-kind");
    }

    private static JComboBox<?> account(Host h) {
        return (JComboBox<?>) find(h.frame, "financial-report-account");
    }

    private static JTextField field(Host h, String key) {
        return (JTextField) find(h.frame, "financial-report-" + key);
    }

    private static JButton button(Host h, String key) {
        return (JButton) find(h.frame, "financial-report-" + key);
    }

    private static JTextArea area(Host h, String key) {
        return (JTextArea) find(h.frame, "financial-report-" + key);
    }

    private static JTable table(Host h) {
        return (JTable) find(h.frame, "financial-report-rows");
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

    private static void click(Host h, String key) throws Exception {
        edt(
                () -> {
                    assertTrue(button(h, key).isEnabled(), key);
                    button(h, key).doClick();
                    return null;
                });
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < end) {
            if (edt(condition)) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for financial reports");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
