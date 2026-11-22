package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.fribok.bookkeeping.application.NormalizedApplicationVoucherRegisterTest.Observed;
import org.fribok.bookkeeping.application.NormalizedApplicationYearCreationTest.Fixture;
import org.fribok.bookkeeping.gui.application.NormalizedDocumentWorkspace;
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
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

@Tag("integration")
class NormalizedApplicationVoucherRegisterGuiTest {
    @Test
    void explicitRefreshSelectionAndOpenShowRawReadOnlyRowsWithoutChangingContextOrSql()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture()) {
            long id = NormalizedApplicationVoucherRegisterTest.historical(f);
            try (var observed = new Observed(f);
                    var host = new Host(observed.owner)) {
                var lease = edt(() -> host.workspace.selection().orElseThrow());
                var before = f.snapshot();
                assertEquals(0, edt(() -> list(host).getRowCount()));
                assertFalse(edt(() -> button(host, "open").isEnabled()));
                host.refresh();
                assertEquals(1, edt(() -> list(host).getRowCount()));
                assertEquals(-1, edt(() -> list(host).getSelectedRow()));
                assertEquals(0, edt(() -> rows(host).getRowCount()));
                host.open(0);
                assertEquals(2, edt(() -> rows(host).getRowCount()));
                edt(
                        () -> {
                            assertFalse(list(host).isCellEditable(0, 0));
                            for (int column = 0; column < rows(host).getColumnCount(); column++)
                                assertFalse(rows(host).isCellEditable(0, column));
                            assertFalse(area(host, "header").isEditable());
                            assertTrue(
                                    area(host, "header")
                                            .getText()
                                            .contains("SQL identity : " + id));
                            assertTrue(
                                    area(host, "header")
                                            .getText()
                                            .contains("\"<html>literal\\nheader\""));
                            assertEquals("null", rows(host).getValueAt(0, 1));
                            assertEquals("\"gone\"", rows(host).getValueAt(0, 6));
                            assertEquals(false, rows(host).getValueAt(0, 8));
                            assertEquals("\"Local project\"", rows(host).getValueAt(1, 7));
                            assertEquals("\"Local unit\"", rows(host).getValueAt(1, 10));
                            assertEquals(true, rows(host).getValueAt(1, 14));
                            assertEquals(true, rows(host).getValueAt(1, 15));
                            assertTrue(area(host, "header").getText().contains("CURRENT"));
                            return null;
                        });
                assertSame(lease, edt(() -> host.workspace.selection().orElseThrow()));
                assertEquals(before, f.snapshot());
                assertEquals(0, observed.commits.get());
            }
        }
    }

    @Test
    void pagesClearSelectionAndDetailAndExplicitFirstRefreshReturnsToNewest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            for (int i = 1; i <= 101; i++)
                NormalizedApplicationVoucherRegisterTest.insert(f, 11, i, "row");
            try (var host = new Host(owner)) {
                host.refresh();
                assertEquals(100, edt(() -> list(host).getRowCount()));
                assertEquals(101, edt(() -> list(host).getValueAt(0, 0)));
                host.open(0);
                click(host, "next");
                host.loaded();
                assertEquals(1, edt(() -> list(host).getRowCount()));
                assertEquals(1, edt(() -> list(host).getValueAt(0, 0)));
                assertEquals(-1, edt(() -> list(host).getSelectedRow()));
                assertEquals("", edt(() -> area(host, "header").getText()));
                assertFalse(edt(() -> button(host, "next").isEnabled()));
                assertFalse(edt(() -> button(host, "open").isEnabled()));
                host.refresh();
                assertEquals(101, edt(() -> list(host).getValueAt(0, 0)));
            }
        }
    }

    @Test
    void replacementClearsOldRowsAndSameNumberCannotRedirectTheNewCompany() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            NormalizedApplicationVoucherRegisterTest.insert(f, 11, 1, "local");
            NormalizedApplicationVoucherRegisterTest.insert(f, 12, 1, "foreign");
            try (var host = new Host(owner)) {
                host.refresh();
                host.open(0);
                var old = edt(() -> host.workspace.selection().orElseThrow().voucherRegister());
                host.apply(2);
                assertEquals(0, edt(() -> list(host).getRowCount()));
                assertEquals("", edt(() -> area(host, "header").getText()));
                assertThrows(java.sql.SQLException.class, () -> old.page(null));
                host.refresh();
                host.open(0);
                assertTrue(edt(() -> area(host, "header").getText().contains("\"foreign\"")));
                assertFalse(edt(() -> area(host, "header").getText().contains("\"local\"")));
            }
        }
    }

    @Test
    void deletedVoucherAndReadFailureCannotLeaveOldDetailAndRefreshRecovers() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var observed = new Observed(f)) {
            long id = NormalizedApplicationVoucherRegisterTest.insert(f, 11, 1, "local");
            try (var host = new Host(observed.owner)) {
                host.refresh();
                host.open(0);
                try (var c = f.open()) {
                    c.createStatement().executeUpdate("DELETE FROM voucher WHERE id=" + id);
                }
                click(host, "open");
                host.failed();
                assertEquals("", edt(() -> area(host, "header").getText()));
                assertEquals(0, edt(() -> rows(host).getRowCount()));
                observed.failure = "query";
                click(host, "refresh");
                host.failed();
                assertEquals(0, edt(() -> list(host).getRowCount()));
                assertFalse(observed.owner.isBlocked());
                observed.failure = "";
                host.refresh();
                assertEquals(0, edt(() -> list(host).getRowCount()));
                assertTrue(edt(() -> area(host, "status").getText().startsWith(text("empty"))));
            }
        }
    }

    @Test
    void rereadingShowsCurrentLabelsWithoutRewritingHistoricAmountsOrStaleDetails()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            NormalizedApplicationVoucherRegisterTest.historical(f);
            try (var host = new Host(owner)) {
                host.refresh();
                host.open(0);
                try (var c = f.open()) {
                    c.createStatement()
                            .executeUpdate(
                                    "UPDATE project SET name='Current changed' WHERE"
                                        + " number='P-common' AND company_id=(SELECT id FROM"
                                        + " company WHERE legacy_id=71)");
                }
                var before = f.snapshot();
                click(host, "open");
                host.opened();
                assertEquals("\"Current changed\"", edt(() -> rows(host).getValueAt(1, 7)));
                assertEquals(before, f.snapshot());
                edt(
                        () -> {
                            list(host).clearSelection();
                            return null;
                        });
                assertEquals("", edt(() -> area(host, "header").getText()));
                assertEquals(0, edt(() -> rows(host).getRowCount()));
            }
        }
    }

    @Test
    void pendingReadDisablesHostAndOtherCommandsUntilReadCompletesWithoutPollingSql()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var observed = new Observed(f);
                var host = new Host(observed.owner)) {
            var lease = edt(() -> host.workspace.selection().orElseThrow());
            observed.entered = new CountDownLatch(1);
            observed.release = new CountDownLatch(1);
            try {
                click(host, "refresh");
                assertTrue(observed.entered.await(5, TimeUnit.SECONDS));
                int opens = observed.opens.get();
                edt(
                        () -> {
                            assertFalse(
                                    ((JButton) find(host.frame, "workspace-reload")).isEnabled());
                            assertFalse(
                                    ((JComboBox<?>) find(host.frame, "workspace-company"))
                                            .isEnabled());
                            assertFalse(
                                    ((JButton) find(host.frame, "voucher-create-load"))
                                            .isEnabled());
                            assertFalse(button(host, "refresh").isEnabled());
                            host.workspace.reload();
                            return null;
                        });
                Thread.sleep(150);
                assertEquals(opens, observed.opens.get());
            } finally {
                observed.release.countDown();
            }
            host.loaded();
            assertSame(lease, edt(() -> host.workspace.selection().orElseThrow()));
            assertTrue(edt(() -> ((JButton) find(host.frame, "voucher-create-load")).isEnabled()));
        }
    }

    @Test
    void closureClearsDisplayAndFencesPendingReadCompletionWithoutDisposingCallerFrame()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var observed = new Observed(f);
                var host = new Host(observed.owner)) {
            NormalizedApplicationVoucherRegisterTest.insert(f, 11, 1, "late");
            observed.entered = new CountDownLatch(1);
            observed.release = new CountDownLatch(1);
            try {
                click(host, "refresh");
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
            await(() -> observed.closes.get() >= 1 && !button(host, "refresh").isEnabled());
            Thread.sleep(150);
            assertEquals(0, edt(() -> list(host).getRowCount()));
            assertEquals("", edt(() -> area(host, "header").getText()));
            assertEquals(text("closed"), edt(() -> area(host, "status").getText()));
        }
    }

    @Test
    void uncertaintyRetiresRegisterClearsPreviouslyReadDataAndRejectsFurtherReads()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var owner = f.owner()) {
            NormalizedApplicationManualVoucherTest.accounts(f);
            NormalizedApplicationVoucherRegisterTest.insert(f, 11, 1, "old");
            try (var host = new Host(owner)) {
                host.refresh();
                host.open(0);
                var lease = edt(() -> host.workspace.selection().orElseThrow());
                var preview =
                        lease.vouchers().preview(NormalizedApplicationManualVoucherTest.draft());
                f.failure = "after";
                assertThrows(java.sql.SQLException.class, () -> lease.vouchers().post(preview));
                await(() -> area(host, "status").getText().equals(text("blocked")));
                assertEquals(0, edt(() -> list(host).getRowCount()));
                assertEquals("", edt(() -> area(host, "header").getText()));
                assertFalse(edt(() -> button(host, "refresh").isEnabled()));
                int opens = f.opens.get();
                assertThrows(java.sql.SQLException.class, () -> lease.voucherRegister().page(null));
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
                                var panel = new NormalizedDocumentWorkspace(frame, owner);
                                frame.add(panel);
                                frame.setSize(1300, 1100);
                                frame.setVisible(true);
                                return panel;
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
                        ((JTabbedPane) find(frame, "workspace-documents")).setSelectedIndex(3);
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
                                    && button(this, "refresh").isEnabled()
                                    && ((JButton) find(frame, "document-refresh")).isEnabled()
                                    && ((JButton) find(frame, "supplier-document-refresh"))
                                            .isEnabled());
        }

        void refresh() throws Exception {
            click(this, "refresh");
            loaded();
        }

        void loaded() throws Exception {
            await(
                    () ->
                            button(this, "refresh").isEnabled()
                                    && !area(this, "status").getText().equals(text("working")));
        }

        void open(int row) throws Exception {
            edt(
                    () -> {
                        list(this).setRowSelectionInterval(row, row);
                        return null;
                    });
            click(this, "open");
            opened();
        }

        void opened() throws Exception {
            await(
                    () ->
                            button(this, "refresh").isEnabled()
                                    && area(this, "status").getText().startsWith(text("opened")));
        }

        void failed() throws Exception {
            await(
                    () ->
                            button(this, "refresh").isEnabled()
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
        return SSBundle.getBundle().getString("normalized.voucherregister." + key);
    }

    private static JTable list(Host host) {
        return (JTable) find(host.frame, "voucher-register-list");
    }

    private static JTable rows(Host host) {
        return (JTable) find(host.frame, "voucher-register-rows");
    }

    private static JTextArea area(Host host, String name) {
        return (JTextArea) find(host.frame, "voucher-register-" + name);
    }

    private static JButton button(Host host, String name) {
        return (JButton) find(host.frame, "voucher-register-" + name);
    }

    private static void click(Host host, String name) throws Exception {
        edt(
                () -> {
                    assertTrue(button(host, name).isEnabled(), name);
                    button(host, name).doClick();
                    return null;
                });
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

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            if (edt(condition)) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for voucher register");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
