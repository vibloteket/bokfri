package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore.Metadata;
import org.fribok.bookkeeping.gui.application.NormalizedDocumentWorkspace;
import org.fribok.bookkeeping.gui.creditinvoice.CustomerCreditMenuBinding;
import org.fribok.bookkeeping.gui.creditinvoice.CustomerDocumentMetadataAdapter;
import org.fribok.bookkeeping.gui.supplierinvoice.SupplierDocumentMenuBinding;
import org.fribok.bookkeeping.gui.supplierinvoice.SupplierJournalAdapter;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import javax.swing.*;

@Tag("integration")
class NormalizedApplicationWorkspaceGuiTest {
    @BeforeEach
    void display() {
        assumeFalse(GraphicsEnvironment.isHeadless());
    }

    @Test
    void explicitChoicesLoadBothViewsWithoutPersistingOrGuessingSelectionAndRenderNamesLiterally()
            throws Exception {
        try (var f = new NormalizedApplicationOwnerTest.Fixture();
                var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate("UPDATE company SET name='<html>Company' WHERE legacy_id=71");
            }
            f.anchor.commit();
            var before = f.snapshot();
            try (var ui = new Host(f)) {
                ready(ui);
                edt(
                        () -> {
                            assertTrue(ui.workspace.selection().isEmpty());
                            assertFalse(button(ui, "apply").isEnabled());
                            assertFalse(combo(ui, "company").isEditable());
                            assertFalse(combo(ui, "year").isEditable());
                            assertEquals(0, combo(ui, "company").getSelectedIndex());
                            select(combo(ui, "company"), 71);
                            assertEquals(0, combo(ui, "year").getSelectedIndex());
                            assertFalse(button(ui, "apply").isEnabled());
                            return null;
                        });
                apply(ui, 71, 11);
                loaded(ui, 71, 1);
                assertEquals(before, f.snapshot());
                edt(
                        () -> {
                            assertTrue(
                                    combo(ui, "company")
                                            .getSelectedItem()
                                            .toString()
                                            .startsWith("71 : \"<html>"));
                            assertEquals(
                                    6,
                                    ((JTabbedPane) find(ui.workspace, "workspace-documents"))
                                            .getTabCount());
                            assertNotNull(find(ui.workspace, "workspace-manual-voucher"));
                            assertNotNull(find(ui.workspace, "workspace-voucher-register"));
                            assertNotNull(find(ui.workspace, "workspace-voucher-reversal"));
                            assertNotNull(find(ui.workspace, "workspace-financial-reports"));
                            return null;
                        });
            }
        }
    }

    @Test
    void switchingCompanyReplacesBothViewsAndRetiresOldLeasesWithoutGlobalSelectionWrites()
            throws Exception {
        try (var f = new NormalizedApplicationOwnerTest.Fixture();
                var ui = new Host(f)) {
            ready(ui);
            apply(ui, 71, 11);
            loaded(ui, 71, 1);
            var old = edt(() -> ui.workspace.selection().orElseThrow());
            var adapter = old.suppliers().metadata(Kind.INVOICE, f.supplier);
            var source = adapter.load();
            var before = f.snapshot();
            apply(ui, 72, 12);
            loaded(ui, 72, 0);
            assertFalse(old.isActive());
            int opens = f.opens.get();
            assertThrows(SQLException.class, () -> adapter.save(source, "Wrong context", null));
            assertEquals(opens, f.opens.get());
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void invalidConcurrentYearChoiceRetainsOldViewsAndReloadClearsDraftsNotCurrentContext()
            throws Exception {
        try (var f = new NormalizedApplicationOwnerTest.Fixture();
                var ui = new Host(f)) {
            ready(ui);
            apply(ui, 71, 11);
            loaded(ui, 71, 1);
            var old = edt(() -> ui.workspace.selection().orElseThrow());
            edt(
                    () -> {
                        select(combo(ui, "company"), 72);
                        select(combo(ui, "year"), 12);
                        return null;
                    });
            try (var s = f.anchor.createStatement()) {
                s.executeUpdate("UPDATE accounting_year SET legacy_id=13 WHERE legacy_id=12");
            }
            f.anchor.commit();
            var before = f.snapshot();
            edt(
                    () -> {
                        button(ui, "apply").doClick();
                        return null;
                    });
            ready(ui);
            assertSame(old, edt(() -> ui.workspace.selection().orElseThrow()));
            assertTrue(old.isActive());
            assertEquals(1, edt(() -> table(ui, "normalized-invoices").getRowCount()));
            assertEquals(1, edt(() -> table(ui, "normalized-supplier-invoices").getRowCount()));
            edt(
                    () -> {
                        button(ui, "reload").doClick();
                        return null;
                    });
            ready(ui);
            edt(
                    () -> {
                        assertEquals(0, combo(ui, "company").getSelectedIndex());
                        assertFalse(button(ui, "apply").isEnabled());
                        return null;
                    });
            assertSame(old, edt(() -> ui.workspace.selection().orElseThrow()));
            assertEquals(before, f.snapshot());
        }
    }

    @Test
    void unknownCommitFromEitherFamilyPoisonsBothViewsMenusAndSelectionWithoutNewConnections()
            throws Exception {
        for (boolean supplier : List.of(false, true))
            for (boolean durable : List.of(false, true))
                try (var f = new NormalizedApplicationOwnerTest.Fixture();
                        var ui = new Host(f)) {
                    ready(ui);
                    apply(ui, 71, 11);
                    loaded(ui, 71, 1);
                    var lease = edt(() -> ui.workspace.selection().orElseThrow());
                    var supplierAdapter = lease.suppliers().metadata(Kind.INVOICE, f.supplier);
                    var supplierSource = supplierAdapter.load();
                    var customerAdapter =
                            lease.customers()
                                    .metadata(
                                            CustomerDocumentMetadataAdapter.Kind.INVOICE,
                                            f.customer);
                    var customerSource = customerAdapter.load();
                    f.failure.set(durable ? "after" : "before");
                    if (supplier)
                        assertThrows(
                                SQLException.class,
                                () -> supplierAdapter.save(supplierSource, "Unknown", null));
                    else
                        assertThrows(
                                SQLException.class,
                                () ->
                                        customerAdapter.save(
                                                customerSource,
                                                new Metadata(true, "unknown", 2, true)));
                    int opens = f.opens.get();
                    waitFor(
                            () ->
                                    edt(
                                            () ->
                                                    ui.workspace.isBlocked()
                                                            && !button(ui, "reload").isEnabled()));
                    edt(
                            () -> {
                                assertFalse(button(ui, "apply").isEnabled());
                                assertFalse(combo(ui, "company").isEnabled());
                                assertFalse(combo(ui, "year").isEnabled());
                                assertFalse(
                                        ((JTabbedPane) find(ui.workspace, "workspace-documents"))
                                                .isEnabled());
                                assertTrue(CustomerCreditMenuBinding.openIfInstalled(ui.frame));
                                assertTrue(
                                        SupplierDocumentMenuBinding.openIfInstalled(
                                                ui.frame, SupplierJournalAdapter.Kind.INVOICE));
                                ui.workspace.reload();
                                return null;
                            });
                    assertFalse(lease.isActive());
                    assertEquals(opens, f.opens.get());
                    if (supplier)
                        assertEquals(
                                durable ? "Unknown" : "Stored supplier",
                                f.value(
                                        "SELECT supplier_name FROM supplier_invoice WHERE id="
                                                + f.supplier));
                    else
                        assertEquals(
                                durable ? "unknown" : null,
                                f.value(
                                        "SELECT ocr_number FROM customer_invoice WHERE id="
                                                + f.customer));
                }
    }

    @Test
    void closeRetainsConsumedMenuMarkersIsolatesUnboundOwnerAndDoesNotDisposeFrameOrDatabase()
            throws Exception {
        try (var f = new NormalizedApplicationOwnerTest.Fixture();
                var ui = new Host(f)) {
            ready(ui);
            apply(ui, 71, 11);
            loaded(ui, 71, 1);
            int opens = f.opens.get();
            edt(
                    () -> {
                        var other = new JFrame("Unbound");
                        try {
                            assertFalse(CustomerCreditMenuBinding.openIfInstalled(other));
                            assertFalse(
                                    SupplierDocumentMenuBinding.openIfInstalled(
                                            other, SupplierJournalAdapter.Kind.INVOICE));
                            ui.workspace.close();
                            assertTrue(ui.frame.isDisplayable());
                            assertTrue(ui.workspace.selection().isEmpty());
                            assertTrue(CustomerCreditMenuBinding.openIfInstalled(ui.frame));
                            assertTrue(
                                    SupplierDocumentMenuBinding.openIfInstalled(
                                            ui.frame, SupplierJournalAdapter.Kind.INVOICE));
                        } finally {
                            other.dispose();
                        }
                        return null;
                    });
            assertEquals(opens, f.opens.get());
            assertEquals("2", f.value("SELECT COUNT(*) FROM company"));
        }
    }

    @Test
    void disposingWindowDuringInitialLoadCannotInstallLateContextOrLeakConnections()
            throws Exception {
        try (var f = new NormalizedApplicationOwnerTest.Fixture()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var application =
                    new NormalizedApplicationOwner(
                            () -> {
                                entered.countDown();
                                try {
                                    if (!release.await(5, TimeUnit.SECONDS))
                                        throw new SQLException("Timed out");
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new SQLException(e);
                                }
                                return f.open();
                            },
                            NormalizedApplicationOwnerTest.CLOCK);
            try (var ui = new Host(application)) {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                edt(
                        () -> {
                            ui.frame.dispose();
                            return null;
                        });
                waitFor(() -> edt(() -> !button(ui, "reload").isEnabled()));
                release.countDown();
                waitFor(() -> f.opens.get() > 0 && f.opens.get() == f.closes.get());
                assertTrue(edt(() -> ui.workspace.selection().isEmpty()));
                assertEquals("2", f.value("SELECT COUNT(*) FROM company"));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void acceptedOldWriteFinishesBeforeReplacementWithoutRetargetingToNewCompany()
            throws Exception {
        try (var f = new NormalizedApplicationOwnerTest.Fixture();
                var ui = new Host(f)) {
            ready(ui);
            apply(ui, 71, 11);
            loaded(ui, 71, 1);
            var old = edt(() -> ui.workspace.selection().orElseThrow());
            var adapter = old.suppliers().metadata(Kind.INVOICE, f.supplier);
            var source = adapter.load();
            f.entered = new CountDownLatch(1);
            f.release = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var save = executor.submit(() -> adapter.save(source, "Accepted original", null));
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                apply(ui, 72, 12);
                assertSame(old, edt(() -> ui.workspace.selection().orElseThrow()));
                assertFalse(edt(() -> button(ui, "apply").isEnabled()));
                f.release.countDown();
                assertEquals(71, save.get(5, TimeUnit.SECONDS).companyId());
                loaded(ui, 72, 0);
                assertFalse(old.isActive());
                assertEquals(
                        "Accepted original",
                        f.value(
                                "SELECT supplier_name FROM supplier_invoice WHERE id="
                                        + f.supplier));
            } finally {
                f.release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void legacyFormatIsRefusedWithoutInitializationOrAutomaticSelection() throws Exception {
        try (var f = new NormalizedApplicationOwnerTest.Fixture()) {
            DataFormatManager.recordVersion(f.anchor, 2);
            f.anchor.commit();
            var before = f.snapshot();
            try (var ui = new Host(f)) {
                ready(ui);
                assertTrue(edt(() -> ui.workspace.selection().isEmpty()));
                assertFalse(edt(() -> button(ui, "apply").isEnabled()));
                assertEquals(before, f.snapshot());
            }
        }
    }

    @Test
    void pendingSelectionFailureCannotOverwriteTheStrongerUnknownCommitWarning() throws Exception {
        try (var f = new NormalizedApplicationOwnerTest.Fixture();
                var ui = new Host(f)) {
            ready(ui);
            apply(ui, 71, 11);
            loaded(ui, 71, 1);
            var old = edt(() -> ui.workspace.selection().orElseThrow());
            var adapter = old.suppliers().metadata(Kind.INVOICE, f.supplier);
            var source = adapter.load();
            f.entered = new CountDownLatch(1);
            f.release = new CountDownLatch(1);
            f.failure.set("before");
            var executor = Executors.newSingleThreadExecutor();
            try {
                var save = executor.submit(() -> adapter.save(source, "Unknown", null));
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                apply(ui, 72, 12);
                f.release.countDown();
                assertInstanceOf(
                        SQLException.class,
                        assertThrows(ExecutionException.class, () -> save.get(5, TimeUnit.SECONDS))
                                .getCause());
                String warning =
                        se.swedsoft.bookkeeping.gui.util.SSBundle.getBundle()
                                .getString("normalized.workspace.uncertain");
                waitFor(
                        () ->
                                edt(
                                        () ->
                                                warning.equals(
                                                                ((JTextArea)
                                                                                find(
                                                                                        ui.workspace,
                                                                                        "workspace-status"))
                                                                        .getText())
                                                        && !button(ui, "reload").isEnabled()
                                                        && !ui.workspace.isBusy()));
                assertSame(old, edt(() -> ui.workspace.selection().orElseThrow()));
                assertFalse(old.isActive());
                assertEquals(
                        "Stored supplier",
                        f.value(
                                "SELECT supplier_name FROM supplier_invoice WHERE id="
                                        + f.supplier));
            } finally {
                f.release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void emptyCompanyIsNotReadyUntilBothBackgroundDocumentReadsFinish() throws Exception {
        try (var f = new NormalizedApplicationOwnerTest.Fixture()) {
            var armed = new java.util.concurrent.atomic.AtomicBoolean();
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var application =
                    new NormalizedApplicationOwner(
                            () -> {
                                assertFalse(SwingUtilities.isEventDispatchThread());
                                // First connection validates selection; the next is a background
                                // document load.
                                if (armed.get() && reads.incrementAndGet() == 2) {
                                    entered.countDown();
                                    try {
                                        if (!release.await(10, TimeUnit.SECONDS))
                                            throw new SQLException("Timed out");
                                    } catch (InterruptedException error) {
                                        Thread.currentThread().interrupt();
                                        throw new SQLException(error);
                                    }
                                }
                                return f.open();
                            },
                            NormalizedApplicationOwnerTest.CLOCK);
            try (var ui = new Host(application)) {
                ready(ui);
                apply(ui, 71, 11);
                loaded(ui, 71, 1);
                armed.set(true);
                apply(ui, 72, 12);
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertFalse(edt(() -> isLoaded(ui, 72, 0)));
                release.countDown();
                loaded(ui, 72, 0);
                assertEquals(3, reads.get());
                assertEquals(f.opens.get(), f.closes.get());
            } finally {
                release.countDown();
            }
        }
    }

    static final class Host implements AutoCloseable {
        final JFrame frame;
        final NormalizedDocumentWorkspace workspace;

        Host(NormalizedApplicationOwnerTest.Fixture f) {
            this(
                    new NormalizedApplicationOwner(
                            () -> {
                                assertFalse(SwingUtilities.isEventDispatchThread());
                                return f.open();
                            },
                            NormalizedApplicationOwnerTest.CLOCK));
        }

        Host(NormalizedApplicationOwner application) {
            frame = edt(() -> new JFrame("Explicit normalized workspace"));
            workspace = edt(() -> new NormalizedDocumentWorkspace(frame, application));
            edt(
                    () -> {
                        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                        frame.add(workspace);
                        frame.setSize(1450, 900);
                        frame.setVisible(true);
                        return null;
                    });
        }

        public void close() {
            edt(
                    () -> {
                        workspace.close();
                        frame.dispose();
                        return null;
                    });
        }
    }

    private static void ready(Host ui) throws Exception {
        waitFor(() -> edt(() -> button(ui, "reload").isEnabled()));
    }

    private static void apply(Host ui, int company, int year) {
        edt(
                () -> {
                    select(combo(ui, "company"), company);
                    select(combo(ui, "year"), year);
                    assertTrue(button(ui, "apply").isEnabled());
                    button(ui, "apply").doClick();
                    return null;
                });
    }

    private static void loaded(Host ui, int company, int rows) throws Exception {
        waitFor(() -> edt(() -> isLoaded(ui, company, rows)));
    }

    // Replacing a context clears both tables before its background document reads finish. Empty
    // tables and an enabled workspace Reload therefore do not prove that either view is ready.
    private static boolean isLoaded(Host ui, int company, int rows) {
        return ui.workspace.selection().map(s -> s.company().id() == company).orElse(false)
                && table(ui, "normalized-invoices") != null
                && table(ui, "normalized-supplier-invoices") != null
                && table(ui, "normalized-invoices").getRowCount() == rows
                && table(ui, "normalized-supplier-invoices").getRowCount() == rows
                && button(ui, "reload").isEnabled()
                && ((AbstractButton) find(ui.workspace, "document-refresh")).isEnabled()
                && ((AbstractButton) find(ui.workspace, "supplier-document-refresh")).isEnabled()
                && se.swedsoft.bookkeeping.gui.util.SSBundle.getBundle()
                        .getString("customer.documents.ready")
                        .equals(((JLabel) find(ui.workspace, "document-status")).getText())
                && se.swedsoft.bookkeeping.gui.util.SSBundle.getBundle()
                        .getString("supplier.documents.ready")
                        .equals(
                                ((JLabel) find(ui.workspace, "supplier-document-status"))
                                        .getText());
    }

    private static void select(JComboBox<?> box, int id) {
        for (int i = 0; i < box.getItemCount(); i++) {
            if (box.getItemAt(i).toString().startsWith(id + " : ")) {
                box.setSelectedIndex(i);
                return;
            }
        }
        throw new AssertionError("Missing choice " + id);
    }

    private static JComboBox<?> combo(Host ui, String suffix) {
        return (JComboBox<?>) find(ui.workspace, "workspace-" + suffix);
    }

    private static AbstractButton button(Host ui, String suffix) {
        return (AbstractButton) find(ui.workspace, "workspace-" + suffix);
    }

    private static JTable table(Host ui, String name) {
        return (JTable) find(ui.workspace, name);
    }

    private static JComponent find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JComponent c && name.equals(c.getName())) return c;
            if (child instanceof Container c) {
                var found = find(c, name);
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
        fail("Timed out waiting for normalized workspace");
    }
}
