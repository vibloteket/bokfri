package org.fribok.bookkeeping.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.fribok.bookkeeping.gui.creditinvoice.CustomerDocumentView;
import org.fribok.bookkeeping.gui.supplierinvoice.SupplierDocumentView;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;

import javax.swing.*;

@Tag("integration")
class NormalizedApplicationOwnerGuiTest {
    @BeforeEach
    void display() {
        assumeFalse(GraphicsEnvironment.isHeadless());
    }

    @Test
    void bothRealViewsLoadSameOwnerAndReplaceCompanyYearWithoutLegacySelection() throws Exception {
        try (var f = new NormalizedApplicationOwnerTest.Fixture();
                var owner =
                        new NormalizedApplicationOwner(
                                () -> {
                                    assertFalse(SwingUtilities.isEventDispatchThread());
                                    return f.open();
                                },
                                NormalizedApplicationOwnerTest.CLOCK);
                var guard = new NormalizedApplicationOwnerTest.LegacyGuard()) {
            var before = f.snapshot();
            var selected = owner.select(71, 11);
            try (var ui = new Views(selected)) {
                waitFor(
                        () ->
                                edt(
                                        () ->
                                                table(ui.customers, "normalized-invoices")
                                                                        .getRowCount()
                                                                == 1
                                                        && table(
                                                                                ui.suppliers,
                                                                                "normalized-supplier-invoices")
                                                                        .getRowCount()
                                                                == 1));
                assertEquals(before, f.snapshot());
                var next = owner.select(72, 12);
                edt(
                        () -> {
                            ui.customers.replaceSelection(next.customers());
                            ui.suppliers.replaceSelection(next.suppliers());
                            return null;
                        });
                waitFor(
                        () ->
                                edt(
                                        () ->
                                                table(ui.customers, "normalized-invoices")
                                                                        .getRowCount()
                                                                == 0
                                                        && table(
                                                                                ui.suppliers,
                                                                                "normalized-supplier-invoices")
                                                                        .getRowCount()
                                                                == 0));
                assertFalse(selected.isActive());
                assertTrue(next.isActive());
                assertEquals(before, f.snapshot());
            }
        }
    }

    @Test
    void uncertainWriteBlocksBackendRefreshOfBothVisibleScreensWithoutOpeningNewConnections()
            throws Exception {
        try (var f = new NormalizedApplicationOwnerTest.Fixture();
                var owner =
                        new NormalizedApplicationOwner(
                                f::open, NormalizedApplicationOwnerTest.CLOCK)) {
            var selected = owner.select(71, 11);
            try (var ui = new Views(selected)) {
                waitFor(
                        () ->
                                edt(
                                        () ->
                                                table(ui.customers, "normalized-invoices")
                                                                        .getRowCount()
                                                                == 1
                                                        && table(
                                                                                ui.suppliers,
                                                                                "normalized-supplier-invoices")
                                                                        .getRowCount()
                                                                == 1));
                var adapter = selected.suppliers().metadata(Kind.INVOICE, f.supplier);
                var loaded = adapter.load();
                f.failure.set("before");
                assertThrows(
                        java.sql.SQLException.class, () -> adapter.save(loaded, "Unknown", null));
                int opens = f.opens.get();
                assertTrue(selected.isBlocked());
                edt(
                        () -> {
                            ui.customers.refresh();
                            ui.suppliers.refresh();
                            return null;
                        });
                waitFor(
                        () ->
                                edt(
                                        () ->
                                                ((AbstractButton)
                                                                find(
                                                                        ui.customers,
                                                                        "document-refresh"))
                                                        .isEnabled()));
                assertEquals(opens, f.opens.get());
                owner.close();
                assertFalse(selected.isActive());
            }
        }
    }

    static final class Views implements AutoCloseable {
        final JFrame frame;
        final CustomerDocumentView customers;
        final SupplierDocumentView suppliers;

        Views(NormalizedApplicationOwner.Selection selection) {
            frame = edt(() -> new JFrame("Shared normalized owner"));
            customers = edt(() -> new CustomerDocumentView(frame, selection.customers()));
            suppliers = edt(() -> new SupplierDocumentView(frame, selection.suppliers()));
            edt(
                    () -> {
                        var tabs = new JTabbedPane();
                        tabs.addTab("Customers", customers);
                        tabs.addTab("Suppliers", suppliers);
                        frame.add(tabs);
                        frame.pack();
                        frame.setVisible(true);
                        return null;
                    });
        }

        public void close() {
            edt(
                    () -> {
                        customers.close();
                        suppliers.close();
                        frame.dispose();
                        return null;
                    });
        }
    }

    private static JTable table(Container root, String name) {
        return (JTable) find(root, name);
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
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static void waitFor(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for shared-owner views");
    }
}
