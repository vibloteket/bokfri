package org.fribok.bookkeeping.gui.creditinvoice;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.creditinvoice.SSCreditInvoiceDialog;
import se.swedsoft.bookkeeping.gui.creditinvoice.panel.NormalizedCreditRequestPanel;
import javax.swing.JDialog;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@Tag("integration")
class CustomerCreditRequestDialogGuiTest {
    @Test void actualDialogBlocksCloseDuringRequestAndRefreshesOnceAfterConfirmedCommit() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Desktop display required");
        try (var f = new CustomerCreditGuiFixture(); var ignored = new LegacyGuard()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger opens = new AtomicInteger(), notified = new AtomicInteger(), refreshes = new AtomicInteger();
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            var adapter = new CustomerCreditDialogAdapter(() -> {
                assertFalse(SwingUtilities.isEventDispatchThread());
                if (opens.incrementAndGet() == 1) {
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new SQLException("Test wait timed out"); }
                    catch (InterruptedException interrupted) { throw new SQLException(interrupted); }
                }
                return f.open();
            }, f.gate, 71, 11, f.invoice, CustomerCreditGuiFixture.CLOCK);
            AbstractTableModel table = new AbstractTableModel() {
                public int getRowCount() { return 0; }
                public int getColumnCount() { return 0; }
                public Object getValueAt(int row, int column) { return null; }
            };
            table.addTableModelListener(event -> { assertTrue(SwingUtilities.isEventDispatchThread()); refreshes.incrementAndGet(); });
            AtomicReference<JDialog> ref = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> ref.set(SSCreditInvoiceDialog.normalizedDialog(null, adapter, result -> {
                try { assertTrue(SwingUtilities.isEventDispatchThread()); assertEquals(1, f.count()); table.fireTableDataChanged(); }
                catch (Throwable failure) { callbackFailure.set(failure); }
                notified.incrementAndGet();
            })));
            JDialog dialog = ref.get();
            try {
                SwingUtilities.invokeLater(() -> dialog.setVisible(true));
                CustomerCreditDialogAdapterTest.waitFor(dialog::isShowing);
                JButton preview = CustomerCreditDialogAdapterTest.component(dialog, "credit-preview", JButton.class);
                JButton create = CustomerCreditDialogAdapterTest.component(dialog, "credit-create", JButton.class);
                SwingUtilities.invokeAndWait(preview::doClick);
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(() -> {
                    dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                    dialog.getRootPane().getActionMap().get("credit-cancel").actionPerformed(null);
                    assertTrue(dialog.isShowing()); assertFalse(create.isEnabled());
                });
                release.countDown();
                CustomerCreditDialogAdapterTest.waitFor(create::isEnabled);
                SwingUtilities.invokeAndWait(create::doClick);
                CustomerCreditDialogAdapterTest.waitFor(() -> !dialog.isDisplayable());
                assertNull(callbackFailure.get()); assertEquals(1, notified.get()); assertEquals(1, refreshes.get());
                assertEquals(1, f.count()); assertEquals(2, opens.get());
            } finally {
                release.countDown(); SwingUtilities.invokeAndWait(dialog::dispose);
            }
        }
    }

    private static final class LegacyGuard implements AutoCloseable {
        private final Map<Field, Object> saved = new HashMap<>();
        LegacyGuard() throws Exception {
            for (String name : List.of("iConnection", "iCurrentCompany", "iCurrentYear", "iVouchers")) {
                Field f = SSDB.class.getDeclaredField(name); f.setAccessible(true);
                saved.put(f, f.get(SSDB.getInstance())); f.set(SSDB.getInstance(), null);
            }
            Field f = SSDB.class.getDeclaredField("iConnection"); f.setAccessible(true);
            f.set(SSDB.getInstance(), Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        throw new AssertionError("Unexpected legacy JDBC: " + method.getName());
                    }));
        }
        public void close() throws Exception {
            for (var entry : saved.entrySet()) entry.getKey().set(SSDB.getInstance(), entry.getValue());
        }
    }
}
