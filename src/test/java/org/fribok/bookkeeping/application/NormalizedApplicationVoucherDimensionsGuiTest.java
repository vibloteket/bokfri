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
class NormalizedApplicationVoucherDimensionsGuiTest {
    @Test
    void explicitDimensionsPreviewAndPostWithoutChangingContextOrCreatingCatalogueData()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            var lease = edt(() -> host.workspace.selection().orElseThrow());
            var before = f.snapshot();
            host.preview();
            assertEquals(before, f.snapshot());
            assertTrue(edt(() -> status(host.frame).getText().contains("Local project")));
            assertTrue(edt(() -> status(host.frame).getText().contains("Local unit")));
            assertFalse(edt(() -> status(host.frame).getText().contains("Foreign project")));
            click(host.frame, "voucher-create-post");
            host.posted();
            NormalizedApplicationVoucherDimensionsTest.checkStored(f, 1);
            assertTrue(f.snapshot().containsAll(before));
            assertEquals(before.size() + 3, f.snapshot().size());
            assertSame(lease, edt(() -> host.workspace.selection().orElseThrow()));
            assertTrue(
                    edt(
                            () ->
                                    table(host.frame)
                                            .getValueAt(0, 3)
                                            .toString()
                                            .equals(text("nodimension"))));
            assertTrue(
                    edt(
                            () ->
                                    table(host.frame)
                                            .getValueAt(0, 4)
                                            .toString()
                                            .equals(text("nodimension"))));
            assertFalse(edt(() -> button(host.frame, "voucher-create-post").isEnabled()));
        }
    }

    @Test
    void requiredDimensionsAreNeverDefaultedEvenWithOneUnitAndOptionalAbsenceIsPreserved()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            host.fill(false);
            var before = f.snapshot();
            click(host.frame, "voucher-create-preview");
            await(
                    () ->
                            button(host.frame, "voucher-create-load").isEnabled()
                                    && status(host.frame).getText().contains("PROJECT_REQUIRED"));
            assertEquals(before, f.snapshot());
            assertFalse(edt(() -> button(host.frame, "voucher-create-post").isEnabled()));
            host.pick(0, 3, "[P-common]");
            host.pick(1, 4, "[U-common]");
            click(host.frame, "voucher-create-preview");
            await(() -> button(host.frame, "voucher-create-post").isEnabled());
            click(host.frame, "voucher-create-post");
            host.posted();
            try (var c = f.open();
                    var r =
                            c.createStatement()
                                    .executeQuery(
                                            "SELECT project_number,result_unit_number FROM"
                                                    + " voucher_row ORDER BY row_number")) {
                assertTrue(r.next());
                assertEquals("P-common", r.getString(1));
                assertNull(r.getString(2));
                assertTrue(r.next());
                assertNull(r.getString(1));
                assertEquals("U-common", r.getString(2));
            }
        }
    }

    @Test
    void dateChangeInvalidatesPreviewAndClosedOrUnknownClosureProjectsCannotBePosted()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            host.fill(false);
            host.pick(0, 3, "[P-future]");
            host.pick(1, 4, "[U-common]");
            edt(
                    () -> {
                        field(host.frame, "voucher-create-date").setText("2026-06-30");
                        return null;
                    });
            click(host.frame, "voucher-create-preview");
            await(() -> button(host.frame, "voucher-create-post").isEnabled());
            var before = f.snapshot();
            edt(
                    () -> {
                        field(host.frame, "voucher-create-date").setText("2026-07-01");
                        return null;
                    });
            assertFalse(edt(() -> button(host.frame, "voucher-create-post").isEnabled()));
            click(host.frame, "voucher-create-preview");
            await(
                    () ->
                            button(host.frame, "voucher-create-load").isEnabled()
                                    && status(host.frame).getText().contains("closed"));
            host.pick(0, 3, "[P-unknown]");
            click(host.frame, "voucher-create-preview");
            await(
                    () ->
                            button(host.frame, "voucher-create-load").isEnabled()
                                    && status(host.frame).getText().contains("unknown closure"));
            assertEquals(before, f.snapshot());
            assertFalse(host.owner.isBlocked());
        }
    }

    @Test
    void staleDimensionMetadataRefusesPostingThenFreshPreviewShowsCurrentLabels() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            host.preview();
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate(
                                "UPDATE project SET name='Updated local' WHERE number='P-common'"
                                        + " AND company_id=(SELECT id FROM company WHERE"
                                        + " legacy_id=71)");
            }
            var before = f.snapshot();
            click(host.frame, "voucher-create-post");
            await(
                    () ->
                            button(host.frame, "voucher-create-load").isEnabled()
                                    && status(host.frame).getText().contains("inputs changed"));
            assertEquals(before, f.snapshot());
            click(host.frame, "voucher-create-preview");
            await(() -> button(host.frame, "voucher-create-post").isEnabled());
            assertTrue(edt(() -> status(host.frame).getText().contains("Updated local")));
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void companyReplacementClearsDimensionsAndLoadsOnlyNewCompanyLabelsForSameKeys()
            throws Exception {
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
            assertThrows(java.sql.SQLException.class, old::catalogue);
            assertEquals(opens, f.opens.get());
            assertTrue(
                    edt(
                            () ->
                                    table(host.frame)
                                            .getValueAt(0, 3)
                                            .toString()
                                            .equals(text("nodimension"))));
            assertFalse(edt(() -> button(host.frame, "voucher-create-post").isEnabled()));
            host.load();
            edt(
                    () -> {
                        assertTrue(table(host.frame).editCellAt(0, 3));
                        var choices = (JComboBox<?>) table(host.frame).getEditorComponent();
                        assertEquals(3, choices.getItemCount());
                        assertTrue(choices.getItemAt(1).toString().contains("Foreign project"));
                        table(host.frame).getCellEditor().cancelCellEditing();
                        return null;
                    });
        }
    }

    @Test
    void dimensionedPendingPostInterlocksHostAndOtherCommandsUntilDurableResult() throws Exception {
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
                assertFalse(edt(() -> table(host.frame).isCellEditable(0, 3)));
                int opens = f.opens.get();
                edt(
                        () -> {
                            host.workspace.reload();
                            return null;
                        });
                assertEquals(opens, f.opens.get());
                f.release.countDown();
                host.posted();
                NormalizedApplicationVoucherDimensionsTest.checkStored(f, 1);
            } finally {
                f.release.countDown();
            }
        }
    }

    @Test
    void uncertainDimensionedPostBlocksCatalogueAndBothDocumentFamiliesOnEitherDurabilitySide()
            throws Exception {
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
                assertThrows(java.sql.SQLException.class, () -> lease.vouchers().catalogue());
                assertThrows(java.sql.SQLException.class, () -> lease.customers().documents());
                assertThrows(java.sql.SQLException.class, () -> lease.suppliers().documents());
                assertThrows(java.sql.SQLException.class, host.owner::companyCurrencies);
                assertEquals(opens, f.opens.get());
                assertFalse(edt(() -> button(host.frame, "voucher-create-post").isEnabled()));
                assertFalse(edt(() -> table(host.frame).isEnabled()));
                assertEquals(
                        failure.equals("after") ? 1 : 0,
                        NormalizedApplicationManualVoucherTest.count(f, 11));
            }
        }
    }

    @Test
    void enteringDimensionEditorInvalidatesTokenAndClosingCannotActivateAcceptedPost()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            host.preview();
            edt(
                    () -> {
                        assertTrue(table(host.frame).editCellAt(0, 4));
                        assertFalse(button(host.frame, "voucher-create-post").isEnabled());
                        table(host.frame).getCellEditor().cancelCellEditing();
                        return null;
                    });
            click(host.frame, "voucher-create-preview");
            await(() -> button(host.frame, "voucher-create-post").isEnabled());
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
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (NormalizedApplicationManualVoucherTest.count(f, 11) == 0
                        && System.nanoTime() < deadline) Thread.sleep(20);
                NormalizedApplicationVoucherDimensionsTest.checkStored(f, 1);
                assertTrue(edt(() -> host.workspace.selection().isEmpty()));
                assertFalse(edt(() -> button(host.frame, "voucher-create-post").isEnabled()));
            } finally {
                f.release.countDown();
            }
        }
    }

    private static final class Host implements AutoCloseable {
        final NormalizedApplicationOwner owner;
        final JFrame frame;
        final NormalizedDocumentWorkspace workspace;

        Host(Fixture f) throws Exception {
            NormalizedApplicationVoucherDimensionsTest.dimensions(f);
            owner = f.owner();
            frame = edt(() -> new JFrame());
            workspace =
                    edt(
                            () -> {
                                var panel = new NormalizedDocumentWorkspace(frame, owner);
                                frame.add(panel);
                                frame.setSize(1200, 1100);
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

        void load() throws Exception {
            click(frame, "voucher-create-load");
            await(
                    () ->
                            button(frame, "voucher-create-load").isEnabled()
                                    && status(frame)
                                            .getText()
                                            .equals(text("dimensioninstructions")));
        }

        void pick(int row, int column, String prefix) throws Exception {
            edt(
                    () -> {
                        assertTrue(table(frame).editCellAt(row, column));
                        var choices = (JComboBox<?>) table(frame).getEditorComponent();
                        int index = -1;
                        for (int i = 0; i < choices.getItemCount(); i++)
                            if (choices.getItemAt(i).toString().startsWith(prefix)) {
                                index = i;
                                break;
                            }
                        assertTrue(index >= 0, "Missing explicit choice " + prefix);
                        choices.setSelectedIndex(index);
                        if (table(frame).isEditing())
                            table(frame).getCellEditor().stopCellEditing();
                        assertTrue(
                                table(frame).getValueAt(row, column).toString().startsWith(prefix));
                        return null;
                    });
        }

        void fill(boolean includeDimensions) throws Exception {
            load();
            edt(
                    () -> {
                        field(frame, "voucher-create-date").setText("2026-06-15");
                        field(frame, "voucher-create-description")
                                .setText("Explicit dimensions <html>");
                        for (int row = 0; row < 2; row++) {
                            assertEquals(
                                    text("nodimension"),
                                    table(frame).getValueAt(row, 3).toString());
                            assertEquals(
                                    text("nodimension"),
                                    table(frame).getValueAt(row, 4).toString());
                        }
                        table(frame).setValueAt("125.50", 0, 1);
                        table(frame).setValueAt("125.50", 1, 2);
                        return null;
                    });
            pick(0, 0, "4010 :");
            pick(1, 0, "6010 :");
            if (includeDimensions)
                for (int row = 0; row < 2; row++) {
                    pick(row, 3, "[P-common]");
                    pick(row, 4, "[U-common]");
                }
        }

        void preview() throws Exception {
            fill(true);
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

    private static String text(String key) {
        return SSBundle.getBundle().getString("normalized.vouchercreate." + key);
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
        fail("Timed out waiting for voucher dimensions");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
