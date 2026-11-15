package org.fribok.bookkeeping.gui.supplierinvoice;

import static org.fribok.bookkeeping.gui.supplierinvoice.SupplierDocumentViewGuiTest.component;
import static org.fribok.bookkeeping.gui.supplierinvoice.SupplierDocumentViewGuiTest.waitFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.gui.suppliercreditinvoice.SSSupplierCreditInvoiceFrame;
import se.swedsoft.bookkeeping.gui.supplierinvoice.SSSupplierInvoiceFrame;
import se.swedsoft.bookkeeping.gui.util.SSBundle;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

@Tag("integration")
class SupplierDocumentMenuBindingGuiTest {
    @Test
    void realMainMenuRoutesBothSupplierFamiliesBeforeLegacyFramesAndOverridesLegacyYearEnablement()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture();
                var ignored = new SupplierJournalGuiFixture.LegacyGuard()) {
            AtomicReference<SSMainFrame> owner = new AtomicReference<>();
            AtomicReference<SupplierDocumentView> view = new AtomicReference<>();
            AtomicInteger opens = new AtomicInteger();
            var session =
                    new SupplierDocumentGuiSession(
                            () -> {
                                assertFalse(SwingUtilities.isEventDispatchThread());
                                opens.incrementAndGet();
                                return f.open();
                            },
                            f.gate,
                            71,
                            11,
                            SupplierJournalGuiFixture.CLOCK);
            var legacyInvoices = SSSupplierInvoiceFrame.getInstance();
            var legacyCredits = SSSupplierCreditInvoiceFrame.getInstance();
            try {
                SwingUtilities.invokeAndWait(
                        () -> {
                            try {
                                var constructor = SSMainFrame.class.getDeclaredConstructor();
                                constructor.setAccessible(true);
                                owner.set(constructor.newInstance());
                            } catch (ReflectiveOperationException failure) {
                                throw new IllegalStateException(failure);
                            }
                            var frame = owner.get();
                            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                            frame.getContentPane().remove(frame.getDesktopPane());
                            view.set(new SupplierDocumentView(frame, session));
                            frame.add(view.get(), BorderLayout.CENTER);
                            frame.setVisible(true);
                        });
                waitFor(
                        () ->
                                component(view.get(), "supplier-invoice-journal", JButton.class)
                                        .isEnabled());
                JMenuItem invoices =
                        find(owner.get().getJMenuBar(), "salemenu.supplierinvoice.title");
                JMenuItem credits =
                        find(owner.get().getJMenuBar(), "salemenu.suppliercreditinvoice.title");
                waitFor(() -> invoices.isEnabled() && credits.isEnabled());
                SwingUtilities.invokeAndWait(
                        () -> {
                            invoices.setEnabled(false);
                            credits.setEnabled(false);
                        });
                waitFor(() -> invoices.isEnabled() && credits.isEnabled());
                SwingUtilities.invokeAndWait(
                        () -> {
                            credits.doClick();
                            assertEquals(
                                    1,
                                    component(
                                                    view.get(),
                                                    "supplier-document-tabs",
                                                    JTabbedPane.class)
                                            .getSelectedIndex());
                            invoices.doClick();
                            assertEquals(
                                    0,
                                    component(
                                                    view.get(),
                                                    "supplier-document-tabs",
                                                    JTabbedPane.class)
                                            .getSelectedIndex());
                            assertSame(legacyInvoices, SSSupplierInvoiceFrame.getInstance());
                            assertSame(legacyCredits, SSSupplierCreditInvoiceFrame.getInstance());
                            view.get().close();
                            assertFalse(invoices.isEnabled());
                            assertFalse(credits.isEnabled());
                            // Forced delivery after close must remain consumed rather than falling
                            // back to a legacy frame.
                            for (var listener : invoices.getActionListeners())
                                listener.actionPerformed(
                                        new java.awt.event.ActionEvent(invoices, 0, "test"));
                            assertSame(legacyInvoices, SSSupplierInvoiceFrame.getInstance());
                        });
                assertEquals(1, opens.get());
            } finally {
                SwingUtilities.invokeAndWait(
                        () -> {
                            if (view.get() != null) view.get().close();
                            if (owner.get() != null) owner.get().dispose();
                        });
            }
        }
    }

    @Test
    void unboundRoutingPreservesLegacyAndReplacementAndOwnerCloseNeverFallBack() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            AtomicReference<JFrame> owner = new AtomicReference<>();
            AtomicInteger legacy = new AtomicInteger(),
                    invalidated = new AtomicInteger(),
                    shown = new AtomicInteger();
            var first = f.session();
            var next = f.session();
            SwingUtilities.invokeAndWait(
                    () -> {
                        owner.set(new JFrame());
                        owner.get().setSize(400, 250);
                        owner.get().setVisible(true);
                        SupplierDocumentMenuBinding.route(
                                owner.get(),
                                SupplierJournalAdapter.Kind.INVOICE,
                                legacy::incrementAndGet);
                        SupplierDocumentMenuBinding.install(
                                owner.get(),
                                first,
                                kind -> shown.incrementAndGet(),
                                invalidated::incrementAndGet);
                        SupplierDocumentMenuBinding.route(
                                owner.get(),
                                SupplierJournalAdapter.Kind.INVOICE,
                                legacy::incrementAndGet);
                        SupplierDocumentMenuBinding.install(
                                owner.get(),
                                next,
                                kind -> shown.incrementAndGet(),
                                invalidated::incrementAndGet);
                        assertFalse(first.isActive());
                        assertEquals(1, invalidated.get());
                        SupplierDocumentMenuBinding.route(
                                owner.get(),
                                SupplierJournalAdapter.Kind.CREDIT,
                                legacy::incrementAndGet);
                        SupplierDocumentMenuBinding.closeIfInstalled(owner.get(), next);
                        SupplierDocumentMenuBinding.route(
                                owner.get(),
                                SupplierJournalAdapter.Kind.CREDIT,
                                legacy::incrementAndGet);
                    });
            try {
                assertEquals(1, legacy.get());
                assertEquals(2, shown.get());
                assertEquals(2, invalidated.get());
                assertFalse(next.isActive());
                var last = f.session();
                SwingUtilities.invokeAndWait(
                        () -> {
                            SupplierDocumentMenuBinding.install(
                                    owner.get(),
                                    last,
                                    kind -> shown.incrementAndGet(),
                                    invalidated::incrementAndGet);
                            owner.get().dispose();
                        });
                waitFor(() -> !last.isActive());
                assertEquals(3, invalidated.get());
            } finally {
                SwingUtilities.invokeAndWait(() -> owner.get().dispose());
            }
        }
    }

    @Test
    void buttonListenersFollowDisplayLifecycleAndDoNotOverrideUnboundLegacyControls()
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        try (var f = new SupplierJournalGuiFixture()) {
            AtomicReference<JFrame> owner = new AtomicReference<>();
            AtomicReference<JButton> button = new AtomicReference<>();
            AtomicInteger rootBefore = new AtomicInteger(), enabledBefore = new AtomicInteger();
            var session = f.session();
            SwingUtilities.invokeAndWait(
                    () -> {
                        var frame = new JFrame();
                        owner.set(frame);
                        var panel = new JPanel();
                        frame.add(panel);
                        var action = new JButton("supplier");
                        button.set(action);
                        action.setEnabled(false);
                        panel.add(action);
                        frame.setSize(400, 250);
                        frame.setVisible(true);
                        rootBefore.set(frame.getRootPane().getPropertyChangeListeners().length);
                        enabledBefore.set(action.getPropertyChangeListeners().length);
                        SupplierDocumentMenuBinding.bindMenuButton(frame, action);
                        SupplierDocumentMenuBinding.bindMenuButton(frame, action);
                        assertFalse(action.isEnabled());
                        assertEquals(
                                rootBefore.get() + 1,
                                frame.getRootPane().getPropertyChangeListeners().length);
                        SupplierDocumentMenuBinding.install(frame, session, kind -> {}, () -> {});
                        assertTrue(action.isEnabled());
                        action.setEnabled(false);
                    });
            try {
                waitFor(() -> button.get().isEnabled());
                SwingUtilities.invokeAndWait(
                        () -> {
                            owner.get().getContentPane().removeAll();
                            owner.get().validate();
                            assertEquals(
                                    rootBefore.get(),
                                    owner.get().getRootPane().getPropertyChangeListeners().length);
                            assertEquals(
                                    enabledBefore.get(),
                                    button.get().getPropertyChangeListeners().length);
                            SupplierDocumentMenuBinding.closeIfInstalled(owner.get(), session);
                        });
            } finally {
                SwingUtilities.invokeAndWait(() -> owner.get().dispose());
            }
        }
    }

    private static JMenuItem find(JMenuBar bar, String key) {
        String text = SSBundle.getBundle().getString(key);
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenuItem item = find(bar.getMenu(i), text);
            if (item != null) return item;
        }
        throw new AssertionError("Missing real menu " + key);
    }

    private static JMenuItem find(JMenu menu, String text) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            var item = menu.getItem(i);
            if (item == null) continue;
            if (text.equals(item.getText())) return item;
            if (item instanceof JMenu nested) {
                var found = find(nested, text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
