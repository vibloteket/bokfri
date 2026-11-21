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
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

@Tag("integration")
class NormalizedApplicationFirstYearGuiTest {
    @Test
    void yearlessCompanyCanCreateThenExplicitlySelectItsFirstYearWithoutDefaults()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture(false);
                var host = new Host(f)) {
            var before = f.snapshot();
            assertFalse(edt(() -> button(host.frame, "year-create-load").isEnabled()));
            host.company(1);
            assertTrue(edt(() -> host.workspace.selection().isEmpty()));
            assertFalse(edt(() -> button(host.frame, "workspace-apply").isEnabled()));
            assertEquals(1, edt(() -> combo(host.frame, "workspace-year").getItemCount()));
            assertEquals("", edt(() -> field(host.frame, "year-create-from").getText()));
            assertEquals(0, edt(() -> combo(host.frame, "year-create-plan").getSelectedIndex()));
            host.preview();
            assertEquals(before, f.snapshot());
            assertTrue(edt(() -> status(host.frame).getText().contains("71 : Company 0")));
            click(host.frame, "year-create-confirm");
            host.refreshed();
            assertTrue(edt(() -> host.workspace.selection().isEmpty()));
            assertEquals(0, edt(() -> combo(host.frame, "workspace-year").getSelectedIndex()));
            assertEquals(
                    0,
                    edt(
                            () ->
                                    ((JTabbedPane) find(host.frame, "workspace-documents"))
                                            .getTabCount()));
            assertFalse(edt(() -> button(host.frame, "year-create-load").isEnabled()));
            assertFalse(edt(() -> button(host.frame, "year-create-confirm").isEnabled()));
            host.applyYear();
            assertEquals(0, edt(() -> host.workspace.selection().orElseThrow().year().id()));
            assertEquals(71, edt(() -> host.workspace.selection().orElseThrow().company().id()));
            assertTrue(edt(() -> button(host.frame, "year-create-load").isEnabled()));
        }
    }

    @Test
    void firstYearTargetDoesNotReplaceAnExistingDocumentLeaseOrViews() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f, true)) {
            host.company(1);
            host.applyYear();
            var lease = edt(() -> host.workspace.selection().orElseThrow());
            var customerTable = edt(() -> find(host.frame, "normalized-invoices"));
            assertNotNull(customerTable);
            host.company(3);
            host.preview();
            assertTrue(edt(() -> status(host.frame).getText().contains("73 : Yearless company")));
            click(host.frame, "year-create-confirm");
            host.refreshed();
            assertSame(lease, edt(() -> host.workspace.selection().orElseThrow()));
            assertTrue(lease.isActive());
            assertSame(customerTable, edt(() -> find(host.frame, "normalized-invoices")));
            assertEquals(71, lease.company().id());
            assertEquals(1, host.owner.companies().get(2).years().size());
            assertFalse(edt(() -> button(host.frame, "year-create-load").isEnabled()));
        }
    }

    @Test
    void changingCompanyDropsPreviewAndRequiresFreshExplicitInputs() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture(false);
                var host = new Host(f)) {
            host.company(1);
            host.preview();
            host.company(2);
            assertFalse(edt(() -> button(host.frame, "year-create-confirm").isEnabled()));
            assertEquals("", edt(() -> field(host.frame, "year-create-from").getText()));
            assertEquals(0, edt(() -> combo(host.frame, "year-create-plan").getSelectedIndex()));
            assertTrue(edt(() -> status(host.frame).getText().contains("72 : Company 1")));
            host.preview();
            click(host.frame, "year-create-confirm");
            host.refreshed();
            assertTrue(host.owner.companies().get(0).years().isEmpty());
            assertEquals(1, host.owner.companies().get(1).years().size());
            assertTrue(edt(() -> host.workspace.selection().isEmpty()));
        }
    }

    @Test
    void concurrentFirstYearRejectsStaleConfirmationAndReloadDoesNotActivateIt() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture(false);
                var host = new Host(f)) {
            host.company(1);
            host.preview();
            try (var external = f.owner()) {
                external.createYear(
                        external.previewFirstYear(
                                71,
                                LocalDate.of(2027, 1, 1),
                                LocalDate.of(2027, 12, 31),
                                f.planId));
            }
            var before = f.snapshot();
            click(host.frame, "year-create-confirm");
            await(
                    () ->
                            button(host.frame, "year-create-load").isEnabled()
                                    && status(host.frame).getText().contains("inputs changed"));
            assertEquals(before, f.snapshot());
            assertFalse(host.owner.isBlocked());
            click(host.frame, "workspace-reload");
            await(() -> !host.workspace.isBusy());
            host.company(1);
            assertEquals(2, edt(() -> combo(host.frame, "workspace-year").getItemCount()));
            assertTrue(edt(() -> host.workspace.selection().isEmpty()));
            assertFalse(edt(() -> button(host.frame, "year-create-load").isEnabled()));
        }
    }

    @Test
    void uncertainFirstYearCommitDisablesHostAndBothDocumentFamilies() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (String failure : new String[] {"before", "after"}) {
            try (var f = new Fixture();
                    var host = new Host(f, true)) {
                host.company(1);
                host.applyYear();
                var lease = edt(() -> host.workspace.selection().orElseThrow());
                host.company(3);
                host.preview();
                f.failure = failure;
                click(host.frame, "year-create-confirm");
                await(
                        () ->
                                host.workspace.isBlocked()
                                        && !button(host.frame, "workspace-reload").isEnabled()
                                        && !button(host.frame, "year-create-confirm").isEnabled()
                                        && !((JTabbedPane) find(host.frame, "workspace-documents"))
                                                .isEnabled());
                int opens = f.opens.get();
                assertThrows(SQLException.class, () -> lease.customers().documents());
                assertThrows(SQLException.class, () -> lease.suppliers().documents());
                assertThrows(SQLException.class, () -> host.owner.firstYearAccountPlans(73));
                assertEquals(opens, f.opens.get());
                assertFalse(edt(() -> combo(host.frame, "workspace-company").isEnabled()));
                assertFalse(edt(() -> button(host.frame, "year-create-load").isEnabled()));
                assertFalse(
                        edt(
                                () ->
                                        ((JTabbedPane) find(host.frame, "workspace-documents"))
                                                .isEnabled()));
            }
        }
    }

    @Test
    void pendingFirstYearDisablesContextReloadAndDraftReplacementUntilRefreshed() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture(false);
                var host = new Host(f)) {
            host.company(1);
            host.preview();
            f.entered = new CountDownLatch(1);
            f.release = new CountDownLatch(1);
            try {
                click(host.frame, "year-create-confirm");
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                assertFalse(edt(() -> button(host.frame, "workspace-reload").isEnabled()));
                assertFalse(edt(() -> combo(host.frame, "workspace-company").isEnabled()));
                assertFalse(edt(() -> combo(host.frame, "workspace-year").isEnabled()));
                assertFalse(edt(() -> button(host.frame, "workspace-apply").isEnabled()));
                int opens = f.opens.get();
                edt(
                        () -> {
                            host.workspace.reload();
                            return null;
                        });
                assertEquals(
                        opens, f.opens.get(), "Pending writes cannot start a stale host reload");
                f.release.countDown();
                host.refreshed();
                assertTrue(edt(() -> combo(host.frame, "workspace-company").isEnabled()));
                assertTrue(edt(() -> host.workspace.selection().isEmpty()));
                assertEquals(1, host.owner.companies().get(0).years().size());
            } finally {
                f.release.countDown();
            }
        }
    }

    @Test
    void closedWorkspaceCannotInstallOrActivateAnAcceptedFirstYear() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture(false);
                var host = new Host(f)) {
            host.company(1);
            host.preview();
            f.entered = new CountDownLatch(1);
            f.release = new CountDownLatch(1);
            try {
                click(host.frame, "year-create-confirm");
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                edt(
                        () -> {
                            host.workspace.close();
                            return null;
                        });
                f.release.countDown();
                try (var fresh = f.owner()) {
                    assertEquals(1, fresh.companies().get(0).years().size());
                }
                assertTrue(edt(() -> host.workspace.selection().isEmpty()));
                assertFalse(edt(() -> button(host.frame, "year-create-confirm").isEnabled()));
                assertEquals(
                        0,
                        edt(
                                () ->
                                        ((JTabbedPane) find(host.frame, "workspace-documents"))
                                                .getTabCount()));
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
            this(f, false);
        }

        Host(Fixture f, boolean addCompany) throws Exception {
            if (addCompany) NormalizedApplicationFirstYearTest.addYearlessCompany(f);
            owner = f.owner();
            frame = edt(() -> new JFrame());
            workspace =
                    edt(
                            () -> {
                                var panel = new NormalizedDocumentWorkspace(frame, owner);
                                frame.add(panel);
                                frame.setSize(1100, 900);
                                frame.setVisible(true);
                                return panel;
                            });
            await(
                    () ->
                            !workspace.isBusy()
                                    && combo(frame, "workspace-company").getItemCount()
                                            == (addCompany ? 4 : 3));
        }

        void company(int index) throws Exception {
            edt(
                    () -> {
                        combo(frame, "workspace-company").setSelectedIndex(index);
                        return null;
                    });
        }

        void applyYear() throws Exception {
            edt(
                    () -> {
                        combo(frame, "workspace-year").setSelectedIndex(1);
                        assertTrue(button(frame, "workspace-apply").isEnabled());
                        button(frame, "workspace-apply").doClick();
                        return null;
                    });
            await(
                    () ->
                            !workspace.isBusy()
                                    && workspace.selection().isPresent()
                                    && button(frame, "document-refresh").isEnabled()
                                    && button(frame, "supplier-document-refresh").isEnabled()
                                    && ((javax.swing.JLabel) find(frame, "document-status"))
                                            .getText()
                                            .equals(
                                                    SSBundle.getBundle()
                                                            .getString("customer.documents.ready"))
                                    && ((javax.swing.JLabel)
                                                    find(frame, "supplier-document-status"))
                                            .getText()
                                            .equals(
                                                    SSBundle.getBundle()
                                                            .getString(
                                                                    "supplier.documents.ready")));
        }

        void preview() throws Exception {
            click(frame, "year-create-load");
            await(
                    () ->
                            combo(frame, "year-create-plan").getItemCount() == 3
                                    && button(frame, "year-create-load").isEnabled());
            edt(
                    () -> {
                        combo(frame, "year-create-plan").setSelectedIndex(1);
                        field(frame, "year-create-from").setText("2027-01-01");
                        field(frame, "year-create-to").setText("2027-12-31");
                        return null;
                    });
            click(frame, "year-create-preview");
            await(() -> button(frame, "year-create-confirm").isEnabled());
        }

        void refreshed() throws Exception {
            await(
                    () ->
                            !workspace.isBusy()
                                    && combo(frame, "workspace-year").getItemCount() == 2
                                    && button(frame, "workspace-reload").isEnabled());
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

    private static JTextArea status(Container root) {
        return (JTextArea) find(root, "year-create-status");
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
        fail("Timed out waiting for first-year UI");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
