package org.fribok.bookkeeping.gui.creditinvoice;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@Tag("integration")
class CustomerCreditMenuBindingGuiTest {
    @Test void selectionAndMenuCreateUsingStableIdentityAndQualifiedRefresh() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Desktop display required");
        try (var f = new CustomerCreditGuiFixture(); var ignored = new LegacyJdbcGuard()) {
            AtomicInteger opens = new AtomicInteger();
            var session = new CustomerDocumentGuiSession(() -> {
                assertFalse(SwingUtilities.isEventDispatchThread()); opens.incrementAndGet(); return f.open();
            }, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            AtomicReference<JFrame> owner = new AtomicReference<>();
            AtomicReference<CustomerCreditMenuBinding.Saved> saved = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame(); frame.setSize(300, 200); frame.setVisible(true); owner.set(frame);
                CustomerCreditMenuBinding.install(frame, session, saved::set);
            });
            try {
                SwingUtilities.invokeLater(() -> assertTrue(CustomerCreditMenuBinding.openIfInstalled(owner.get())));
                JDialog selector = dialog("customer-credit-source");
                JButton choose = CustomerCreditDialogAdapterTest.component(selector, "credit-source-choose", JButton.class);
                JComboBox<?> sources = CustomerCreditDialogAdapterTest.component(selector, "credit-source", JComboBox.class);
                CustomerCreditDialogAdapterTest.waitFor(() -> sources.getItemCount() == 1);
                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(choose.isEnabled());
                    var source = (CustomerDocumentGuiSession.Source) sources.getItemAt(0);
                    assertEquals(f.invoice, source.id()); sources.setSelectedIndex(0);
                });
                SwingUtilities.invokeLater(choose::doClick);
                JDialog request = dialog("normalized-credit-request");
                JButton preview = CustomerCreditDialogAdapterTest.component(request, "credit-preview", JButton.class);
                JButton create = CustomerCreditDialogAdapterTest.component(request, "credit-create", JButton.class);
                SwingUtilities.invokeAndWait(preview::doClick);
                CustomerCreditDialogAdapterTest.waitFor(create::isEnabled);
                SwingUtilities.invokeAndWait(create::doClick);
                CustomerCreditDialogAdapterTest.waitFor(() -> saved.get() != null);
                assertEquals(71, saved.get().companyId()); assertEquals(11, saved.get().yearId());
                assertEquals(f.invoice, saved.get().invoiceId()); assertEquals(1, f.count()); assertEquals(3, opens.get());
            } finally { SwingUtilities.invokeAndWait(owner.get()::dispose); }
            CustomerCreditDialogAdapterTest.waitFor(() -> !session.isActive());
        }
    }

    @Test void replacingSelectionClosesOldDialogAndNeverFallsBackToLegacy() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Desktop display required");
        try (var f = new CustomerCreditGuiFixture()) {
            var first = new CustomerDocumentGuiSession(f::open, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            var second = new CustomerDocumentGuiSession(f::open, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            AtomicReference<JFrame> owner = new AtomicReference<>();
            AtomicReference<JButton> button = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame(); JButton action = new JButton(); frame.add(action);
                CustomerCreditMenuBinding.bindMenuButton(frame, action);
                frame.setSize(300, 200); frame.setVisible(true); owner.set(frame); button.set(action);
                assertFalse(CustomerCreditMenuBinding.openIfInstalled(frame));
                CustomerCreditMenuBinding.install(frame, first, result -> fail("Stale refresh"));
                action.setEnabled(false);
            });
            try {
                CustomerCreditDialogAdapterTest.waitFor(button.get()::isEnabled);
                SwingUtilities.invokeLater(() -> CustomerCreditMenuBinding.openIfInstalled(owner.get()));
                JDialog selector = dialog("customer-credit-source");
                SwingUtilities.invokeAndWait(() -> CustomerCreditMenuBinding.install(owner.get(), second, result -> {}));
                CustomerCreditDialogAdapterTest.waitFor(() -> !selector.isDisplayable());
                assertFalse(first.isActive()); assertTrue(second.isActive());
                SwingUtilities.invokeAndWait(() -> {
                    owner.get().dispose();
                    assertTrue(CustomerCreditMenuBinding.openIfInstalled(owner.get()));
                });
                CustomerCreditDialogAdapterTest.waitFor(() -> !second.isActive());
                assertEquals(0, f.count());
            } finally { SwingUtilities.invokeAndWait(owner.get()::dispose); }
        }
    }

    @Test void lateSourceLoadCannotReopenReplacedSelection() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Desktop display required");
        try (var f = new CustomerCreditGuiFixture()) {
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var first = new CustomerDocumentGuiSession(() -> {
                assertFalse(SwingUtilities.isEventDispatchThread()); entered.countDown();
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new java.sql.SQLException("Timed out waiting for selection change");
                } catch (InterruptedException interrupted) { throw new java.sql.SQLException(interrupted); }
                return f.open();
            }, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            var second = new CustomerDocumentGuiSession(f::open, f.gate, 71, 11, CustomerCreditGuiFixture.CLOCK);
            AtomicReference<JFrame> owner = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame(); frame.setSize(300, 200); frame.setVisible(true); owner.set(frame);
                CustomerCreditMenuBinding.install(frame, first, result -> fail("Stale refresh"));
            });
            try {
                SwingUtilities.invokeLater(() -> CustomerCreditMenuBinding.openIfInstalled(owner.get()));
                JDialog selector = dialog("customer-credit-source");
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(() -> CustomerCreditMenuBinding.install(owner.get(), second, result -> {}));
                CustomerCreditDialogAdapterTest.waitFor(() -> !selector.isDisplayable());
                release.countDown();
                // The gate is released only after the superseded loader has closed its connection.
                second.sources();
                SwingUtilities.invokeAndWait(() -> assertFalse(selector.isDisplayable()));
                assertFalse(first.isActive()); assertTrue(second.isActive()); assertEquals(0, f.count());
            } finally { release.countDown(); SwingUtilities.invokeAndWait(owner.get()::dispose); }
        }
    }

    private static final class LegacyJdbcGuard implements AutoCloseable {
        private final java.lang.reflect.Field field;
        private final Object previous;
        LegacyJdbcGuard() throws Exception {
            field = se.swedsoft.bookkeeping.data.system.SSDB.class.getDeclaredField("iConnection");
            field.setAccessible(true);
            var db = se.swedsoft.bookkeeping.data.system.SSDB.getInstance();
            previous = field.get(db);
            field.set(db, java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),
                    new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                        throw new AssertionError("Unexpected legacy JDBC: " + method.getName());
                    }));
        }
        public void close() throws Exception {
            field.set(se.swedsoft.bookkeeping.data.system.SSDB.getInstance(), previous);
        }
    }

    private static JDialog dialog(String name) throws Exception {
        AtomicReference<JDialog> found = new AtomicReference<>();
        CustomerCreditDialogAdapterTest.waitFor(() -> {
            for (Window window : Window.getWindows())
                if (window instanceof JDialog dialog && name.equals(dialog.getName()) && dialog.isShowing()) found.set(dialog);
            return found.get() != null;
        });
        return found.get();
    }
}
