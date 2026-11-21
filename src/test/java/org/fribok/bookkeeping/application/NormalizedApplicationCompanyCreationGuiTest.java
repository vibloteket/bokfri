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
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

@Tag("integration")
class NormalizedApplicationCompanyCreationGuiTest {
    @Test
    void emptyCompanyThenFirstYearThenExplicitApplyIsACompleteGuardedWorkflow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture(false)) {
            NormalizedApplicationCompanyCreationTest.currency(f);
            try (var c = f.open()) {
                c.createStatement().executeUpdate("DELETE FROM company");
            }
            try (var host = new Host(f)) {
                assertEquals(1, edt(() -> combo(host.frame, "workspace-company").getItemCount()));
                assertEquals(
                        0,
                        edt(() -> combo(host.frame, "company-create-period").getSelectedIndex()));
                assertEquals(
                        0,
                        edt(() -> combo(host.frame, "company-create-rounding").getSelectedIndex()));
                assertFalse(edt(() -> button(host.frame, "company-create-preview").isEnabled()));
                var before = f.snapshot();
                host.preview("First company");
                assertEquals(before, f.snapshot());
                click(host.frame, "company-create-confirm");
                await(
                        () ->
                                !host.workspace.isBusy()
                                        && combo(host.frame, "workspace-company").getItemCount()
                                                == 2
                                        && button(host.frame, "year-create-load").isEnabled());
                assertTrue(edt(() -> host.workspace.selection().isEmpty()));
                assertEquals(1, edt(() -> combo(host.frame, "workspace-year").getItemCount()));
                click(host.frame, "year-create-load");
                await(
                        () ->
                                combo(host.frame, "year-create-plan").getItemCount() == 3
                                        && button(host.frame, "year-create-load").isEnabled());
                edt(
                        () -> {
                            combo(host.frame, "year-create-plan").setSelectedIndex(1);
                            field(host.frame, "year-create-from").setText("2027-01-01");
                            field(host.frame, "year-create-to").setText("2027-12-31");
                            return null;
                        });
                click(host.frame, "year-create-preview");
                await(() -> button(host.frame, "year-create-confirm").isEnabled());
                click(host.frame, "year-create-confirm");
                await(
                        () ->
                                !host.workspace.isBusy()
                                        && combo(host.frame, "workspace-year").getItemCount() == 2);
                assertTrue(edt(() -> host.workspace.selection().isEmpty()));
                host.apply();
                assertEquals(0, edt(() -> host.workspace.selection().orElseThrow().company().id()));
                assertEquals(0, edt(() -> host.workspace.selection().orElseThrow().year().id()));
            }
        }
    }

    @Test
    void creatingCompanyPreservesActiveDocumentLeaseAndViews() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture()) {
            NormalizedApplicationCompanyCreationTest.currency(f);
            try (var host = new Host(f)) {
                host.selectExisting();
                var lease = edt(() -> host.workspace.selection().orElseThrow());
                var table = edt(() -> find(host.frame, "normalized-invoices"));
                assertNotNull(table);
                host.preview("New company");
                click(host.frame, "company-create-confirm");
                await(
                        () ->
                                !host.workspace.isBusy()
                                        && combo(host.frame, "workspace-company").getItemCount()
                                                == 4
                                        && button(host.frame, "year-create-load").isEnabled());
                assertSame(lease, edt(() -> host.workspace.selection().orElseThrow()));
                assertTrue(lease.isActive());
                assertSame(table, edt(() -> find(host.frame, "normalized-invoices")));
                assertEquals(1, edt(() -> combo(host.frame, "workspace-year").getItemCount()));
                assertFalse(edt(() -> button(host.frame, "company-create-confirm").isEnabled()));
            }
        }
    }

    @Test
    void editsInvalidateConfirmationAndDuplicateNamesCannotBeConfirmed() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture()) {
            NormalizedApplicationCompanyCreationTest.currency(f);
            try (var host = new Host(f)) {
                host.preview("New company");
                edt(
                        () -> {
                            combo(host.frame, "company-create-period").setSelectedIndex(1);
                            return null;
                        });
                assertFalse(edt(() -> button(host.frame, "company-create-confirm").isEnabled()));
                edt(
                        () -> {
                            field(host.frame, "company-create-name").setText("Company 0");
                            return null;
                        });
                var before = f.snapshot();
                click(host.frame, "company-create-preview");
                await(
                        () ->
                                button(host.frame, "company-create-load").isEnabled()
                                        && status(host.frame).getText().contains("already exists"));
                assertFalse(edt(() -> button(host.frame, "company-create-confirm").isEnabled()));
                assertEquals(before, f.snapshot());
            }
        }
    }

    @Test
    void staleCurrencyRefusesConfirmationWithoutMutatingAnyCompany() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture()) {
            NormalizedApplicationCompanyCreationTest.currency(f);
            try (var host = new Host(f)) {
                host.preview("New company");
                try (var c = f.open()) {
                    c.createStatement().executeUpdate("UPDATE currency SET description='changed'");
                }
                var before = f.snapshot();
                click(host.frame, "company-create-confirm");
                await(
                        () ->
                                button(host.frame, "company-create-load").isEnabled()
                                        && status(host.frame).getText().contains("inputs changed"));
                assertEquals(before, f.snapshot());
                assertFalse(host.owner.isBlocked());
            }
        }
    }

    @Test
    void pendingCompanyWriteDisablesContextReloadAndYearCommands() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture()) {
            NormalizedApplicationCompanyCreationTest.currency(f);
            try (var host = new Host(f)) {
                host.selectExisting();
                host.preview("New company");
                f.entered = new CountDownLatch(1);
                f.release = new CountDownLatch(1);
                try {
                    click(host.frame, "company-create-confirm");
                    assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                    assertFalse(edt(() -> button(host.frame, "workspace-reload").isEnabled()));
                    assertFalse(edt(() -> combo(host.frame, "workspace-company").isEnabled()));
                    assertFalse(edt(() -> button(host.frame, "year-create-load").isEnabled()));
                    int opens = f.opens.get();
                    edt(
                            () -> {
                                host.workspace.reload();
                                return null;
                            });
                    assertEquals(opens, f.opens.get());
                    f.release.countDown();
                    await(
                            () ->
                                    !host.workspace.isBusy()
                                            && combo(host.frame, "workspace-company").getItemCount()
                                                    == 4);
                } finally {
                    f.release.countDown();
                }
            }
        }
    }

    @Test
    void uncertainCompanyCommitRetiresBothDocumentScreensAndAllCommandInputs() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        for (String failure : new String[] {"before", "after"}) {
            try (var f = new Fixture()) {
                NormalizedApplicationCompanyCreationTest.currency(f);
                try (var host = new Host(f)) {
                    host.selectExisting();
                    var lease = edt(() -> host.workspace.selection().orElseThrow());
                    host.preview("New company");
                    f.failure = failure;
                    click(host.frame, "company-create-confirm");
                    await(
                            () ->
                                    host.workspace.isBlocked()
                                            && !((JTabbedPane)
                                                            find(host.frame, "workspace-documents"))
                                                    .isEnabled());
                    int opens = f.opens.get();
                    assertThrows(java.sql.SQLException.class, () -> lease.customers().documents());
                    assertThrows(java.sql.SQLException.class, () -> lease.suppliers().documents());
                    assertThrows(java.sql.SQLException.class, host.owner::companyCurrencies);
                    assertEquals(opens, f.opens.get());
                    assertFalse(edt(() -> button(host.frame, "company-create-load").isEnabled()));
                    assertFalse(edt(() -> button(host.frame, "year-create-load").isEnabled()));
                    assertFalse(edt(() -> combo(host.frame, "workspace-company").isEnabled()));
                }
            }
        }
    }

    @Test
    void closedHostCannotSelectOrInstallAnAcceptedCompanyWrite() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture()) {
            NormalizedApplicationCompanyCreationTest.currency(f);
            try (var host = new Host(f)) {
                host.preview("New company");
                f.entered = new CountDownLatch(1);
                f.release = new CountDownLatch(1);
                try {
                    click(host.frame, "company-create-confirm");
                    assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                    edt(
                            () -> {
                                host.workspace.close();
                                return null;
                            });
                    f.release.countDown();
                    try (var fresh = f.owner()) {
                        assertEquals(3, fresh.companies().size());
                    }
                    assertTrue(edt(() -> host.workspace.selection().isEmpty()));
                    assertFalse(
                            edt(() -> button(host.frame, "company-create-confirm").isEnabled()));
                } finally {
                    f.release.countDown();
                }
            }
        }
    }

    @Test
    void missingCurrencyCatalogueShowsExplicitRefusalWithoutSeedingDefaults() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new Fixture();
                var host = new Host(f)) {
            var before = f.snapshot();
            click(host.frame, "company-create-load");
            await(
                    () ->
                            button(host.frame, "company-create-load").isEnabled()
                                    && status(host.frame)
                                            .getText()
                                            .contains("No stored currencies"));
            assertEquals(1, edt(() -> combo(host.frame, "company-create-currency").getItemCount()));
            assertFalse(edt(() -> button(host.frame, "company-create-preview").isEnabled()));
            assertEquals(before, f.snapshot());
        }
    }

    private static final class Host implements AutoCloseable {
        final NormalizedApplicationOwner owner;
        final JFrame frame;
        final NormalizedDocumentWorkspace workspace;

        Host(Fixture f) throws Exception {
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
            await(() -> !workspace.isBusy());
        }

        void selectExisting() throws Exception {
            edt(
                    () -> {
                        combo(frame, "workspace-company").setSelectedIndex(1);
                        return null;
                    });
            apply();
        }

        void apply() throws Exception {
            edt(
                    () -> {
                        combo(frame, "workspace-year").setSelectedIndex(1);
                        button(frame, "workspace-apply").doClick();
                        return null;
                    });
            await(
                    () ->
                            !workspace.isBusy()
                                    && workspace.selection().isPresent()
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

        void preview(String value) throws Exception {
            click(frame, "company-create-load");
            await(
                    () ->
                            combo(frame, "company-create-currency").getItemCount() == 2
                                    && button(frame, "company-create-load").isEnabled());
            edt(
                    () -> {
                        field(frame, "company-create-name").setText(value);
                        field(frame, "company-create-corporate").setText("123");
                        combo(frame, "company-create-currency").setSelectedIndex(1);
                        combo(frame, "company-create-period").setSelectedIndex(2);
                        combo(frame, "company-create-rounding").setSelectedIndex(1);
                        return null;
                    });
            click(frame, "company-create-preview");
            await(() -> button(frame, "company-create-confirm").isEnabled());
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
        return (JTextArea) find(root, "company-create-status");
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
        fail("Timed out waiting for company creation UI");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
