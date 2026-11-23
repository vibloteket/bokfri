package org.fribok.bookkeeping.application;

import static org.fribok.bookkeeping.application.NormalizedApplicationOpeningBalancesTest.accounts;
import static org.fribok.bookkeeping.application.NormalizedApplicationOpeningBalancesTest.count;
import static org.fribok.bookkeeping.application.NormalizedApplicationOpeningBalancesTest.draft;
import static org.fribok.bookkeeping.application.NormalizedApplicationOpeningBalancesTest.failingOwner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.fribok.bookkeeping.gui.application.NormalizedDocumentWorkspace;
import org.fribok.bookkeeping.gui.application.NormalizedOpeningBalancesPanel;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Real display tests; deliberately never enables normalized application startup. */
@Tag("integration")
class NormalizedApplicationOpeningBalancesGuiTest {
    @Test
    void explicitChoicesBalancedPreviewSeparateConfirmAndReopenTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner();
                var guard = new NormalizedApplicationOwnerTest.LegacyGuard();
                var h = new Host(owner.select(71, 11))) {
            accounts(f);
            var before = f.snapshot();
            assertEquals(0, edt(() -> h.account().getSelectedIndex()));
            assertFalse(edt(() -> h.button("confirm").isEnabled()));
            h.load();
            assertEquals(3, edt(() -> h.account().getItemCount()));
            assertEquals(0, edt(() -> h.account().getSelectedIndex()));
            h.draft();
            assertEquals(2, edt(() -> h.rows().getRowCount()));
            assertFalse(edt(() -> h.button("confirm").isEnabled()));
            h.preview();
            assertEquals(before, f.snapshot());
            assertTrue(edt(() -> h.area("header").getText()).contains("123.45"));
            assertTrue(edt(() -> h.area("header").getText()).contains("BAS95"));
            h.click("confirm");
            h.waitStatus("created");
            assertEquals(2, count(f, 11));
            assertEquals(0, edt(() -> h.rows().getRowCount()));
            assertFalse(edt(() -> h.button("confirm").isEnabled()));
            h.click("load");
            h.waitStatus("failed");
            assertEquals(2, count(f, 11));
            try (var reopened = f.owner()) {
                assertEquals(
                        0,
                        new java.math.BigDecimal("123.45")
                                .compareTo(
                                        reopened.select(71, 11)
                                                .reports()
                                                .accountLedger(
                                                        1910,
                                                        java.time.LocalDate.of(2026, 1, 1),
                                                        java.time.LocalDate.of(2026, 12, 31))
                                                .report()
                                                .opening()));
            }
        }
    }

    @Test
    void editsRemoveAndContextReplacementInvalidateConfirmationTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner();
                var h = new Host(owner.select(71, 11))) {
            accounts(f);
            h.load();
            h.draft();
            h.preview();
            edt(
                    () -> {
                        h.amount().setText("1");
                        return null;
                    });
            assertFalse(edt(() -> h.button("confirm").isEnabled()));
            assertEquals("", edt(() -> h.area("header").getText()));
            h.preview();
            edt(
                    () -> {
                        h.rows().setRowSelectionInterval(0, 0);
                        return null;
                    });
            h.click("remove");
            assertFalse(edt(() -> h.button("confirm").isEnabled()));
            assertFalse(edt(() -> h.button("preview").isEnabled()));
            var other = owner.select(72, 12);
            edt(
                    () -> {
                        h.panel.replaceSelection(other);
                        return null;
                    });
            assertEquals(0, edt(() -> h.rows().getRowCount()));
            assertEquals(1, edt(() -> h.account().getItemCount()));
            assertEquals("", edt(() -> h.amount().getText()));
            assertEquals(0, count(f, 11));
        }
    }

    @Test
    void invalidDecimalsDuplicatesZeroAndImbalanceNeverWriteTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner();
                var h = new Host(owner.select(71, 11))) {
            accounts(f);
            h.load();
            var before = f.snapshot();
            for (String value : new String[] {"1e2", "1,00", "0", "0.001", "garbage"}) {
                h.enter(1910, value);
                h.click("add");
                assertEquals(0, edt(() -> h.rows().getRowCount()));
            }
            h.enter(1910, "1.00");
            h.click("add");
            h.enter(1910, "-1.00");
            h.click("add");
            assertEquals(1, edt(() -> h.rows().getRowCount()));
            h.enter(2010, "-2.00");
            h.click("add");
            h.click("preview");
            h.waitStatus("failed");
            assertFalse(edt(() -> h.button("confirm").isEnabled()));
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void stalePreviewAndLateInsertRollbackRequireFreshPreviewTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner();
                var h = new Host(owner.select(71, 11))) {
            accounts(f);
            h.load();
            h.draft();
            h.preview();
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE account SET description='Changed' WHERE number=1910");
            }
            h.click("confirm");
            h.waitStatus("failed");
            assertEquals(0, count(f, 11));
            assertFalse(edt(() -> h.button("confirm").isEnabled()));
            h.preview();
            h.click("confirm");
            h.waitStatus("created");
            assertEquals(2, count(f, 11));
        }
        try (var f = new Fixture();
                var owner = failingOwner(f, new java.util.concurrent.atomic.AtomicBoolean(true));
                var h = new Host(owner.select(71, 11))) {
            accounts(f);
            h.load();
            h.draft();
            h.preview();
            var before = f.snapshot();
            h.click("confirm");
            h.waitStatus("failed");
            assertEquals(before, f.snapshot());
            assertFalse(owner.isBlocked());
            assertFalse(edt(() -> h.button("confirm").isEnabled()));
        }
    }

    @Test
    void uncertainCommitBlocksFurtherPanelCommandsTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner();
                var h = new Host(owner.select(71, 11))) {
            accounts(f);
            h.load();
            h.draft();
            h.preview();
            f.failure = "after";
            h.click("confirm");
            h.waitStatus("blocked");
            assertTrue(owner.isBlocked());
            assertEquals(2, count(f, 11));
            assertFalse(edt(() -> h.button("load").isEnabled()));
            assertFalse(edt(() -> h.button("confirm").isEnabled()));
            assertEquals("", edt(() -> h.area("header").getText()));
        }
    }

    @Test
    void closingClearsDraftWithoutClosingLeaseAndOffEdtConstructionRefusedTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        assertThrows(IllegalStateException.class, NormalizedOpeningBalancesPanel::new);
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            var lease = owner.select(71, 11);
            try (var h = new Host(lease)) {
                h.load();
                h.draft();
                h.preview();
                edt(
                        () -> {
                            h.panel.close();
                            return null;
                        });
                assertEquals(0, edt(() -> h.rows().getRowCount()));
                assertEquals("", edt(() -> h.area("header").getText()));
                assertFalse(edt(() -> h.button("confirm").isEnabled()));
                assertTrue(lease.isActive());
                assertEquals(2, lease.openingBalances().accounts().size());
            }
        }
    }

    @Test
    void workspaceInstallsSeventhPanelAndRetiresOnOwnerUncertaintyTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            accounts(f);
            JFrame frame = edt(JFrame::new);
            var workspace =
                    edt(
                            () -> {
                                var w = new NormalizedDocumentWorkspace(frame, owner);
                                frame.add(w);
                                frame.setSize(1300, 1100);
                                frame.setVisible(true);
                                return w;
                            });
            try {
                await(
                        () ->
                                !workspace.isBusy()
                                        && ((JComboBox<?>) find(frame, "workspace-company"))
                                                        .getItemCount()
                                                == 3);
                edt(
                        () -> {
                            ((JComboBox<?>) find(frame, "workspace-company")).setSelectedIndex(1);
                            ((JComboBox<?>) find(frame, "workspace-year")).setSelectedIndex(1);
                            ((JButton) find(frame, "workspace-apply")).doClick();
                            return null;
                        });
                await(
                        () ->
                                workspace.selection().isPresent()
                                        && find(frame, "opening-load") != null
                                        && ((JButton) find(frame, "opening-load")).isEnabled());
                assertEquals(
                        7,
                        edt(
                                () ->
                                        ((javax.swing.JTabbedPane)
                                                        find(frame, "workspace-documents"))
                                                .getTabCount()));
                var lease = edt(() -> workspace.selection().orElseThrow());
                var token = lease.openingBalances().preview(draft());
                f.failure = "after";
                assertThrows(SQLException.class, () -> lease.openingBalances().create(token));
                await(
                        () ->
                                ((JTextArea) find(frame, "opening-status"))
                                        .getText()
                                        .equals(text("closed")));
                assertFalse(edt(() -> ((JButton) find(frame, "opening-load")).isEnabled()));
            } finally {
                edt(
                        () -> {
                            workspace.close();
                            frame.dispose();
                            return null;
                        });
            }
        }
    }

    @Test
    void backgroundReadsRollbackAndLateLoadCannotRepopulateReplacedContextTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var observed = new NormalizedApplicationFinancialReportsTest.Observed(f);
                var h = new Host(observed.owner.select(71, 11))) {
            accounts(f);
            observed.reset();
            h.load();
            h.draft();
            h.preview();
            assertEquals(0, observed.commits.get());
            // Each read rolls back schema validation and its own read transaction.
            assertEquals(4, observed.rollbacks.get());
            assertEquals(2, observed.closes.get());
            observed.entered = new java.util.concurrent.CountDownLatch(1);
            observed.release = new java.util.concurrent.CountDownLatch(1);
            int closed = observed.closes.get();
            try {
                h.click("load");
                assertTrue(observed.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(edt(() -> h.button("confirm").isEnabled()));
                assertFalse(edt(() -> h.button("add").isEnabled()));
                edt(
                        () -> {
                            h.panel.replaceSelection(null);
                            return null;
                        });
            } finally {
                observed.release.countDown();
            }
            await(() -> observed.closes.get() > closed);
            edt(() -> null);
            assertEquals(0, edt(() -> h.rows().getRowCount()));
            assertEquals(1, edt(() -> h.account().getItemCount()));
            assertEquals("", edt(() -> h.area("header").getText()));
            assertFalse(edt(() -> h.button("load").isEnabled()));
        }
    }

    @Test
    void closeFencesLatePreviewCallbackTest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var observed = new NormalizedApplicationFinancialReportsTest.Observed(f);
                var h = new Host(observed.owner.select(71, 11))) {
            accounts(f);
            h.load();
            h.draft();
            observed.entered = new java.util.concurrent.CountDownLatch(1);
            observed.release = new java.util.concurrent.CountDownLatch(1);
            int closed = observed.closes.get();
            try {
                h.click("preview");
                assertTrue(observed.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                edt(
                        () -> {
                            h.panel.close();
                            return null;
                        });
            } finally {
                observed.release.countDown();
            }
            await(() -> observed.closes.get() > closed);
            edt(() -> null);
            assertEquals("", edt(() -> h.area("header").getText()));
            assertFalse(edt(() -> h.button("confirm").isEnabled()));
            assertEquals(text("closed"), edt(() -> h.area("status").getText()));
            assertEquals(0, count(f, 11));
        }
    }

    private static final class Host implements AutoCloseable {
        final JFrame frame;
        final NormalizedOpeningBalancesPanel panel;

        Host(NormalizedApplicationOwner.Selection selection) throws Exception {
            frame = edt(JFrame::new);
            panel =
                    edt(
                            () -> {
                                var p = new NormalizedOpeningBalancesPanel();
                                p.replaceSelection(selection);
                                frame.add(p);
                                frame.setSize(1300, 850);
                                frame.setVisible(true);
                                return p;
                            });
        }

        JComboBox<?> account() {
            return (JComboBox<?>) find(frame, "opening-account");
        }

        JTextField amount() {
            return (JTextField) find(frame, "opening-amount");
        }

        JButton button(String k) {
            return (JButton) find(frame, "opening-" + k);
        }

        JTextArea area(String k) {
            return (JTextArea) find(frame, "opening-" + k);
        }

        JTable rows() {
            return (JTable) find(frame, "opening-rows");
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

        void load() throws Exception {
            click("load");
            waitStatus("loaded");
        }

        void enter(int account, String value) throws Exception {
            edt(
                    () -> {
                        int found = -1;
                        for (int i = 1; i < account().getItemCount(); i++)
                            if (account().getItemAt(i).toString().startsWith(account + " :"))
                                found = i;
                        assertTrue(found > 0);
                        account().setSelectedIndex(found);
                        amount().setText(value);
                        return null;
                    });
        }

        void draft() throws Exception {
            enter(1910, "123.45");
            click("add");
            enter(2010, "-123.45");
            click("add");
        }

        void preview() throws Exception {
            click("preview");
            waitStatus("previewready");
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
                var result = find(child, name);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static String text(String k) {
        return SSBundle.getBundle().getString("normalized.openingbalances." + k);
    }

    private static <T> T edt(Callable<T> call) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        var task = new FutureTask<T>(call);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < end) {
            if (edt(condition)) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for opening-balance UI");
    }
}
