package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

@Tag("integration")
class NormalizedApplicationManualVoucherGuiTest {
    @Test
    void explicitRowsPreviewAndPostPreserveContextAndClearDraftWithoutDuplicateRetry()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            var lease = edt(() -> host.workspace.selection().orElseThrow());
            var before = f.snapshot();
            assertEquals("", edt(() -> field(host.frame, "voucher-create-date").getText()));
            assertFalse(edt(() -> button(host.frame, "voucher-create-post").isEnabled()));
            host.preview();
            assertEquals(before, f.snapshot());
            assertTrue(edt(() -> status(host.frame).getText().contains("125.50")));
            click(host.frame, "voucher-create-post");
            host.posted();
            assertEquals(1, NormalizedApplicationManualVoucherTest.count(f, 11));
            assertTrue(f.snapshot().containsAll(before));
            assertEquals(before.size() + 3, f.snapshot().size());
            assertSame(lease, edt(() -> host.workspace.selection().orElseThrow()));
            assertEquals("", edt(() -> field(host.frame, "voucher-create-date").getText()));
            assertEquals("", edt(() -> field(host.frame, "voucher-create-description").getText()));
            assertFalse(edt(() -> button(host.frame, "voucher-create-post").isEnabled()));
            edt(
                    () -> {
                        button(host.frame, "voucher-create-post").doClick();
                        return null;
                    });
            assertEquals(1, NormalizedApplicationManualVoucherTest.count(f, 11));
        }
    }

    @Test
    void startingCellEditInvalidatesConfirmationBeforeEditorCommits() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            host.preview();
            var before = f.snapshot();
            edt(
                    () -> {
                        assertTrue(table(host.frame).editCellAt(0, 1));
                        assertFalse(button(host.frame, "voucher-create-post").isEnabled());
                        ((JTextField) table(host.frame).getEditorComponent()).setText("1.00");
                        table(host.frame).getCellEditor().stopCellEditing();
                        return null;
                    });
            click(host.frame, "voucher-create-preview");
            await(
                    () ->
                            button(host.frame, "voucher-create-load").isEnabled()
                                    && status(host.frame).getText().contains("balance"));
            assertFalse(edt(() -> button(host.frame, "voucher-create-post").isEnabled()));
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void changedAccountMetadataRejectsPostWithoutChangingAnyJournal() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            host.fill();
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE account SET description='Fresh cash' WHERE number=1910");
            }
            click(host.frame, "voucher-create-preview");
            await(() -> button(host.frame, "voucher-create-post").isEnabled());
            assertTrue(edt(() -> status(host.frame).getText().contains("Fresh cash")));
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE account SET description='changed again' WHERE number=3010");
            }
            var before = f.snapshot();
            click(host.frame, "voucher-create-post");
            await(
                    () ->
                            button(host.frame, "voucher-create-load").isEnabled()
                                    && status(host.frame).getText().contains("inputs changed"));
            assertEquals(before, f.snapshot());
            assertFalse(host.owner.isBlocked());
        }
    }

    @Test
    void replacementClearsDatesRowsAndTokenAndRetiresOldCommandFactory() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            host.preview();
            var old = edt(() -> host.workspace.selection().orElseThrow().vouchers());
            edt(
                    () -> {
                        combo(host.frame, "workspace-company").setSelectedIndex(2);
                        combo(host.frame, "workspace-year").setSelectedIndex(1);
                        button(host.frame, "workspace-apply").doClick();
                        return null;
                    });
            host.ready(72);
            int opens = f.opens.get();
            assertThrows(java.sql.SQLException.class, old::accounts);
            assertEquals(opens, f.opens.get());
            assertFalse(edt(() -> button(host.frame, "voucher-create-post").isEnabled()));
            assertEquals("", edt(() -> field(host.frame, "voucher-create-date").getText()));
            assertEquals(2, edt(() -> table(host.frame).getRowCount()));
            assertTrue(edt(() -> table(host.frame).getValueAt(0, 0).toString().contains("Choose")));
        }
    }

    @Test
    void pendingManualPostDisablesContextAndOtherCommandsWithoutStartingReload() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            host.preview();
            f.entered = new CountDownLatch(1);
            f.release = new CountDownLatch(1);
            try {
                click(host.frame, "voucher-create-post");
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                assertFalse(edt(() -> button(host.frame, "workspace-reload").isEnabled()));
                assertFalse(edt(() -> button(host.frame, "year-create-load").isEnabled()));
                assertFalse(edt(() -> button(host.frame, "company-create-load").isEnabled()));
                assertFalse(edt(() -> combo(host.frame, "workspace-company").isEnabled()));
                int opens = f.opens.get();
                edt(
                        () -> {
                            host.workspace.reload();
                            return null;
                        });
                assertEquals(opens, f.opens.get());
                f.release.countDown();
                host.posted();
                assertEquals(1, NormalizedApplicationManualVoucherTest.count(f, 11));
            } finally {
                f.release.countDown();
            }
        }
    }

    @Test
    void uncertainPostDisablesAllFamiliesAndCommandsForBothDurabilityOutcomes() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (String failure : new String[] {"before", "after"}) {
            try (var f = new Fixture();
                    var host = new Host(f)) {
                host.preview();
                var lease = edt(() -> host.workspace.selection().orElseThrow());
                f.failure = failure;
                click(host.frame, "voucher-create-post");
                await(
                        () ->
                                host.workspace.isBlocked()
                                        && !((JTabbedPane) find(host.frame, "workspace-documents"))
                                                .isEnabled());
                int opens = f.opens.get();
                assertThrows(java.sql.SQLException.class, () -> lease.vouchers().accounts());
                assertThrows(java.sql.SQLException.class, () -> lease.customers().documents());
                assertThrows(java.sql.SQLException.class, () -> lease.suppliers().documents());
                assertThrows(java.sql.SQLException.class, host.owner::companyCurrencies);
                assertEquals(opens, f.opens.get());
                assertFalse(edt(() -> button(host.frame, "voucher-create-post").isEnabled()));
                assertFalse(edt(() -> button(host.frame, "voucher-create-load").isEnabled()));
                assertFalse(edt(() -> button(host.frame, "year-create-load").isEnabled()));
                assertFalse(edt(() -> button(host.frame, "company-create-load").isEnabled()));
                assertEquals(
                        failure.equals("after") ? 1 : 0,
                        NormalizedApplicationManualVoucherTest.count(f, 11));
            }
        }
    }

    @Test
    void closingWhileAcceptedPostIsPendingCannotInstallOrActivateLateResult() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            host.preview();
            f.entered = new CountDownLatch(1);
            f.release = new CountDownLatch(1);
            try {
                click(host.frame, "voucher-create-post");
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                edt(
                        () -> {
                            host.workspace.close();
                            return null;
                        });
                f.release.countDown();
                // The closed panel intentionally suppresses its callback; await durable SQL off
                // EDT.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (NormalizedApplicationManualVoucherTest.count(f, 11) == 0
                        && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                assertEquals(1, NormalizedApplicationManualVoucherTest.count(f, 11));
                assertEquals(0, NormalizedApplicationManualVoucherTest.count(f, 12));
                assertTrue(edt(() -> host.workspace.selection().isEmpty()));
                assertFalse(edt(() -> button(host.frame, "voucher-create-post").isEnabled()));
            } finally {
                f.release.countDown();
            }
        }
    }

    @Test
    void malformedAndIncompleteDraftsRefusePreviewWithoutPostingOrInventingAccounts()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            host.fill();
            var before = f.snapshot();
            edt(
                    () -> {
                        field(host.frame, "voucher-create-date").setText("bad date");
                        return null;
                    });
            click(host.frame, "voucher-create-preview");
            assertFalse(edt(() -> button(host.frame, "voucher-create-post").isEnabled()));
            edt(
                    () -> {
                        field(host.frame, "voucher-create-date").setText("2026-06-15");
                        table(host.frame).setValueAt("NaN", 0, 1);
                        return null;
                    });
            click(host.frame, "voucher-create-preview");
            assertFalse(edt(() -> button(host.frame, "voucher-create-post").isEnabled()));
            assertEquals(before, f.snapshot());
            assertTrue(edt(() -> table(host.frame).editCellAt(0, 0)));
            edt(
                    () -> {
                        assertEquals(
                                5,
                                ((JComboBox<?>) table(host.frame).getEditorComponent())
                                        .getItemCount());
                        table(host.frame).getCellEditor().cancelCellEditing();
                        return null;
                    });
            click(host.frame, "voucher-create-add");
            assertEquals(3, edt(() -> table(host.frame).getRowCount()));
            edt(
                    () -> {
                        table(host.frame).setRowSelectionInterval(2, 2);
                        assertTrue(table(host.frame).editCellAt(2, 1));
                        ((JTextField) table(host.frame).getEditorComponent()).setText("12");
                        return null;
                    });
            click(host.frame, "voucher-create-remove");
            assertEquals(2, edt(() -> table(host.frame).getRowCount()));
            assertFalse(edt(() -> table(host.frame).isEditing()));
            assertEquals(before, f.snapshot());
        }
    }

    private static final class Host implements AutoCloseable {
        final NormalizedApplicationOwner owner;
        final JFrame frame;
        final NormalizedDocumentWorkspace workspace;

        Host(Fixture f) throws Exception {
            NormalizedApplicationManualVoucherTest.accounts(f);
            owner = f.owner();
            frame = edt(() -> new JFrame());
            workspace =
                    edt(
                            () -> {
                                var panel = new NormalizedDocumentWorkspace(frame, owner);
                                frame.add(panel);
                                frame.setSize(1100, 1100);
                                frame.setVisible(true);
                                return panel;
                            });
            await(
                    () ->
                            !workspace.isBusy()
                                    && combo(frame, "workspace-company").getItemCount() == 3);
            edt(
                    () -> {
                        combo(frame, "workspace-company").setSelectedIndex(1);
                        combo(frame, "workspace-year").setSelectedIndex(1);
                        button(frame, "workspace-apply").doClick();
                        return null;
                    });
            ready(71);
            edt(
                    () -> {
                        ((JTabbedPane) find(frame, "workspace-documents")).setSelectedIndex(2);
                        return null;
                    });
        }

        void ready(int company) throws Exception {
            await(
                    () ->
                            !workspace.isBusy()
                                    && workspace
                                            .selection()
                                            .map(s -> s.company().id() == company)
                                            .orElse(false)
                                    && button(frame, "document-refresh").isEnabled()
                                    && button(frame, "supplier-document-refresh").isEnabled()
                                    && ((JLabel) find(frame, "document-status"))
                                            .getText()
                                            .equals(
                                                    SSBundle.getBundle()
                                                            .getString("customer.documents.ready"))
                                    && ((JLabel) find(frame, "supplier-document-status"))
                                            .getText()
                                            .equals(
                                                    SSBundle.getBundle()
                                                            .getString(
                                                                    "supplier.documents.ready")));
        }

        void fill() throws Exception {
            click(frame, "voucher-create-load");
            await(
                    () ->
                            button(frame, "voucher-create-load").isEnabled()
                                    && status(frame)
                                            .getText()
                                            .equals(
                                                    SSBundle.getBundle()
                                                            .getString(
                                                                    "normalized.vouchercreate.dimensioninstructions")));
            edt(
                    () -> {
                        field(frame, "voucher-create-date").setText("2026-06-15");
                        field(frame, "voucher-create-description")
                                .setText("Manual <html> & literal");
                        for (int row = 0; row < 2; row++) {
                            assertTrue(table(frame).editCellAt(row, 0));
                            var choices = (JComboBox<?>) table(frame).getEditorComponent();
                            assertEquals(5, choices.getItemCount());
                            assertTrue(choices.getItemAt(3).toString().startsWith("4010 :"));
                            assertTrue(choices.getItemAt(4).toString().startsWith("6010 :"));
                            choices.setSelectedIndex(row + 1);
                            if (table(frame).isEditing())
                                table(frame).getCellEditor().stopCellEditing();
                            assertTrue(
                                    table(frame)
                                            .getValueAt(row, 0)
                                            .toString()
                                            .startsWith(row == 0 ? "1910 :" : "3010 :"));
                        }
                        table(frame).setValueAt("125.50", 0, 1);
                        table(frame).setValueAt("125.50", 1, 2);
                        return null;
                    });
        }

        void preview() throws Exception {
            fill();
            click(frame, "voucher-create-preview");
            await(() -> button(frame, "voucher-create-post").isEnabled());
        }

        void posted() throws Exception {
            await(
                    () ->
                            button(frame, "voucher-create-load").isEnabled()
                                    && status(frame).getText().startsWith("Manual voucher posted"));
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

    private static JTable table(Container root) {
        return (JTable) find(root, "voucher-create-rows");
    }

    private static JTextArea status(Container root) {
        return (JTextArea) find(root, "voucher-create-status");
    }

    private static JTextField field(Container root, String name) {
        return (JTextField) find(root, name);
    }

    private static JButton button(Container root, String name) {
        return (JButton) find(root, name);
    }

    private static JComboBox<?> combo(Container root, String name) {
        return (JComboBox<?>) find(root, name);
    }

    private static Component find(Container root, String name) {
        for (var component : root.getComponents()) {
            if (name.equals(component.getName())) return component;
            if (component instanceof Container child) {
                var found = find(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void click(Container root, String name) throws Exception {
        edt(
                () -> {
                    assertTrue(button(root, name).isEnabled(), name);
                    button(root, name).doClick();
                    return null;
                });
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            if (edt(condition)) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for manual voucher UI");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
