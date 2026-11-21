package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.fribok.bookkeeping.gui.application.NormalizedDocumentWorkspace;
import org.fribok.bookkeeping.gui.application.NormalizedYearCreationPanel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

@Tag("integration")
class NormalizedApplicationYearCreationGuiTest {
    @Test
    void explicitPreviewConfirmationRefreshesYearsWithoutChangingLease() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new NormalizedApplicationYearCreationTest.Fixture();
                var host = new Host(f)) {
            host.select();
            var lease = edt(() -> host.workspace.selection().orElseThrow());
            var before = f.snapshot();
            assertFalse(edt(() -> button(host.frame, "year-create-confirm").isEnabled()));
            host.prepare();
            click(host.frame, "year-create-preview");
            await(() -> button(host.frame, "year-create-confirm").isEnabled());
            assertEquals(before, f.snapshot());
            assertTrue(edt(() -> text(host.frame).getText().contains("2027-01-01")));
            click(host.frame, "year-create-confirm");
            await(
                    () ->
                            !host.workspace.isBusy()
                                    && combo(host.frame, "workspace-year").getItemCount() == 3);
            assertSame(lease, edt(() -> host.workspace.selection().orElseThrow()));
            assertTrue(lease.isActive());
            assertFalse(edt(() -> button(host.frame, "year-create-confirm").isEnabled()));
            assertEquals(2, host.owner.companies().get(0).years().size());
        }
    }

    @Test
    void editingDatesInvalidatesPreviewAndInvalidDatesNeverEnableConfirmation() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new NormalizedApplicationYearCreationTest.Fixture();
                var host = new Host(f)) {
            host.select();
            host.prepare();
            click(host.frame, "year-create-preview");
            await(() -> button(host.frame, "year-create-confirm").isEnabled());
            edt(
                    () -> {
                        field(host.frame, "year-create-from").setText("not a date");
                        return null;
                    });
            assertFalse(edt(() -> button(host.frame, "year-create-confirm").isEnabled()));
            click(host.frame, "year-create-preview");
            assertFalse(edt(() -> button(host.frame, "year-create-confirm").isEnabled()));
            assertEquals(1, host.owner.companies().get(0).years().size());
        }
    }

    @Test
    void staleTemplateFailsWithoutWriteAndKeepsDocumentLeaseUsable() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new NormalizedApplicationYearCreationTest.Fixture();
                var host = new Host(f)) {
            host.select();
            host.prepare();
            click(host.frame, "year-create-preview");
            await(() -> button(host.frame, "year-create-confirm").isEnabled());
            try (var c = f.open()) {
                c.createStatement()
                        .executeUpdate("UPDATE account_plan_catalog SET name = 'changed'");
            }
            var before = f.snapshot();
            click(host.frame, "year-create-confirm");
            await(
                    () ->
                            button(host.frame, "year-create-load").isEnabled()
                                    && text(host.frame).getText().contains("inputs changed"));
            assertEquals(before, f.snapshot());
            assertFalse(host.owner.isBlocked());
            assertTrue(edt(() -> host.workspace.selection().orElseThrow().isActive()));
        }
    }

    @Test
    void replacedSelectionDropsPreviewAndClearsExplicitInputs() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new NormalizedApplicationYearCreationTest.Fixture();
                var host = new Host(f)) {
            host.select();
            host.prepare();
            click(host.frame, "year-create-preview");
            await(() -> button(host.frame, "year-create-confirm").isEnabled());
            edt(
                    () -> {
                        combo(host.frame, "workspace-company").setSelectedIndex(2);
                        combo(host.frame, "workspace-year").setSelectedIndex(1);
                        button(host.frame, "workspace-apply").doClick();
                        return null;
                    });
            await(
                    () ->
                            !host.workspace.isBusy()
                                    && host.workspace.selection().orElseThrow().company().id()
                                            == 72);
            assertFalse(edt(() -> button(host.frame, "year-create-confirm").isEnabled()));
            assertEquals("", edt(() -> field(host.frame, "year-create-from").getText()));
            assertEquals(0, edt(() -> combo(host.frame, "year-create-plan").getSelectedIndex()));
        }
    }

    @Test
    void uncertainYearCommitRetiresBothDocumentScreensAndYearActions() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        // Independent panel avoids background document reads consuming the injected fault.
        try (var f = new NormalizedApplicationYearCreationTest.Fixture();
                var owner = f.owner()) {
            var lease = owner.select(71, 11);
            JFrame frame =
                    edt(
                            () -> {
                                var window = new JFrame();
                                var panel =
                                        new NormalizedYearCreationPanel(
                                                owner,
                                                ignored -> fail("uncertain commit callback"));
                                window.add(panel);
                                panel.replaceSelection(lease);
                                window.pack();
                                window.setVisible(true);
                                return window;
                            });
            try {
                prepare(frame);
                click(frame, "year-create-preview");
                await(() -> button(frame, "year-create-confirm").isEnabled());
                f.failure = "after";
                click(frame, "year-create-confirm");
                await(() -> owner.isBlocked() && text(frame).getText().contains("unknown"));
                assertFalse(edt(() -> button(frame, "year-create-confirm").isEnabled()));
                assertFalse(edt(() -> button(frame, "year-create-load").isEnabled()));
                assertThrows(SQLException.class, () -> lease.suppliers().documents());
                assertThrows(SQLException.class, () -> lease.customers().documents());
            } finally {
                edt(
                        () -> {
                            frame.dispose();
                            return null;
                        });
            }
        }
    }

    @Test
    void closedPanelFencesLateDurableCreationCallback() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new NormalizedApplicationYearCreationTest.Fixture();
                var owner = f.owner()) {
            var lease = owner.select(71, 11);
            var callbacks = new java.util.concurrent.atomic.AtomicInteger();
            JFrame frame =
                    edt(
                            () -> {
                                var window = new JFrame();
                                var panel =
                                        new NormalizedYearCreationPanel(
                                                owner, ignored -> callbacks.incrementAndGet());
                                window.add(panel);
                                panel.replaceSelection(lease);
                                window.pack();
                                window.setVisible(true);
                                return window;
                            });
            try {
                prepare(frame);
                click(frame, "year-create-preview");
                await(() -> button(frame, "year-create-confirm").isEnabled());
                f.entered = new java.util.concurrent.CountDownLatch(1);
                f.release = new java.util.concurrent.CountDownLatch(1);
                click(frame, "year-create-confirm");
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                edt(
                        () -> {
                            ((NormalizedYearCreationPanel) frame.getContentPane().getComponent(0))
                                    .close();
                            return null;
                        });
                f.release.countDown();
                // Catalogue waits on the shared writer gate, proving the accepted write completed.
                assertEquals(2, owner.companies().get(0).years().size());
                await(() -> !button(frame, "year-create-confirm").isEnabled());
                assertEquals(0, callbacks.get());
            } finally {
                if (f.release != null) f.release.countDown();
                edt(
                        () -> {
                            frame.dispose();
                            return null;
                        });
            }
        }
    }

    private static final class Host implements AutoCloseable {
        final NormalizedApplicationOwner owner;
        final JFrame frame;
        final NormalizedDocumentWorkspace workspace;

        Host(NormalizedApplicationYearCreationTest.Fixture fixture) throws Exception {
            owner = fixture.owner();
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
                                    && combo(frame, "workspace-company").getItemCount() == 3);
        }

        void select() throws Exception {
            edt(
                    () -> {
                        combo(frame, "workspace-company").setSelectedIndex(1);
                        combo(frame, "workspace-year").setSelectedIndex(1);
                        button(frame, "workspace-apply").doClick();
                        return null;
                    });
            await(() -> !workspace.isBusy() && workspace.selection().isPresent());
        }

        void prepare() throws Exception {
            NormalizedApplicationYearCreationGuiTest.prepare(frame);
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

    private static void prepare(Container root) throws Exception {
        click(root, "year-create-load");
        await(
                () ->
                        combo(root, "year-create-plan").getItemCount() == 3
                                && button(root, "year-create-load").isEnabled());
        edt(
                () -> {
                    combo(root, "year-create-plan").setSelectedIndex(1);
                    field(root, "year-create-from").setText("2027-01-01");
                    field(root, "year-create-to").setText("2027-12-31");
                    return null;
                });
    }

    private static JTextArea text(Container root) {
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
        fail("Timed out waiting for year-creation UI");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
