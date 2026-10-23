package se.swedsoft.bookkeeping.gui.invoice;

import org.fribok.bookkeeping.GuiTestLookAndFeel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.data.system.SSDBTestFixture;
import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.gui.invoice.dialog.SSInvoiceTypeDialog;
import se.swedsoft.bookkeeping.gui.invoice.panel.SSInvoicePanel;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.SSButtonPanel;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSDialog;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Real populated form and all four public dialog entry points; separate JVM per font/LAF case. */
@Tag("integration")
class SSInvoiceDialogGuiTest {
    private Robot robot;
    private static final AtomicReference<Throwable> eventFailure = new AtomicReference<>();
    private static Thread.UncaughtExceptionHandler previousHandler;

    @BeforeAll
    static void initialize() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a desktop");
        SSDBTestFixture.setupOnce();
        onEdt(() -> {
            String look = System.getProperty("bokfri.invoiceTest.laf");
            if (look == null) {
                GuiTestLookAndFeel.configure();
            } else {
                UIManager.setLookAndFeel(look);
            }
            float scale = Float.parseFloat(System.getProperty("bokfri.invoiceTest.fontScale", "1"));
            Map<Object, Font> fonts = new HashMap<>();
            for (Object key : new ArrayList<>(UIManager.getDefaults().keySet())) {
                Object value = UIManager.get(key);
                if (value instanceof Font font) {
                    fonts.put(key, font);
                }
            }
            fonts.forEach((key, font) -> UIManager.put(key,
                    new FontUIResource(font.deriveFont(font.getSize2D() * scale))));
            for (Window window : Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(window);
            }
            System.out.println("Invoice GUI: " + UIManager.getLookAndFeel().getClass().getName()
                    + " font scale=" + scale);
            return null;
        });
    }

    @BeforeEach
    void setup() throws Exception {
        robot = new Robot();
        robot.setAutoDelay(40);
        eventFailure.set(null);
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (thread.getName().startsWith("AWT-EventQueue")) {
                eventFailure.compareAndSet(null, error);
            } else if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error);
            }
        });
    }

    @AfterEach
    void cleanup(org.junit.jupiter.api.TestInfo info) throws Exception {
        try {
            if (robot != null) {
                JDialog visible = onEdt(SSInvoiceDialogGuiTest::visibleInvoiceDialog);
                if (visible != null) {
                    screenshot(visible, info.getTestMethod().orElseThrow().getName() + "-final");
                }
            }
            onEdt(() -> {
                for (Window window : Window.getWindows()) {
                    window.dispose();
                }
                return null;
            });
        } finally {
            if (robot != null) {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler);
                assertNull(eventFailure.get(), "Uncaught Swing exception");
            }
        }
    }

    @Test
    void populatedFormKeepsButtonsOutsideScrollableBodyAtSmallAndPreferredSizes() throws Exception {
        SSDialog dialog = onEdt(() -> {
            SSDialog d = new SSDialog((javax.swing.JFrame) null, "Invoice layout regression", false);
            SSInvoicePanel panel = new SSInvoicePanel(d);
            panel.setInvoice(sampleInvoice());
            d.add(panel.getPanel());
            SSInvoiceDialogLayout.sizeAndPosition(d, null, new Rectangle(10, 30, 1000, 740));
            d.setVisible(true);
            return d;
        });
        JPanel root = onEdt(() -> (JPanel) dialog.getContentPane().getComponent(0));
        for (Rectangle workArea : List.of(new Rectangle(10, 30, 1000, 740),
                new Rectangle(20, 40, 640, 440))) {
            onEdt(() -> {
                SSInvoiceDialogLayout.sizeAndPosition(dialog, null, workArea);
                dialog.validate();
                assertTrue(workArea.contains(dialog.getBounds()), dialog.getBounds().toString());
                assertTrue(dialog.getMinimumSize().width <= workArea.width);
                assertTrue(dialog.getMinimumSize().height <= workArea.height);
                return null;
            });
            assertAllTabsAndButtonsFit(dialog, root);
            screenshot(dialog, "form-" + workArea.width + "x" + workArea.height);
        }
        JScrollPane scroll = onEdt(() -> (JScrollPane) ((BorderLayout) root.getLayout())
                .getLayoutComponent(BorderLayout.CENTER));
        onEdt(() -> {
            assertTrue(scroll.getVerticalScrollBar().isVisible(), "Small-screen vertical fallback");
            scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
            scroll.getHorizontalScrollBar().setValue(scroll.getHorizontalScrollBar().getMaximum());
            assertTrue(scroll.getViewport().getViewPosition().y > 0);
            return null;
        });
        AtomicInteger saves = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();
        SSButtonPanel actions = onEdt(() -> actions(root));
        onEdt(() -> {
            actions.addOkActionListener(e -> saves.incrementAndGet());
            actions.addCancelActionListener(e -> cancels.incrementAndGet());
            return null;
        });
        click(actions.getOkButton());
        click(actions.getCancelButton());
        assertEquals(1, saves.get());
        assertEquals(1, cancels.get());
        onEdt(() -> {
            dialog.setSize(dialog.getMinimumSize());
            dialog.validate();
            return null;
        });
        assertAllTabsAndButtonsFit(dialog, root);
    }

    @Test
    void allFourPublicDialogPathsShowAccessibleActionsAndCancelWithoutSaving() throws Exception {
        SSMainFrame owner = onEdt(() -> {
            SSMainFrame f = SSMainFrame.getInstance();
            f.setVisible(true);
            return f;
        });
        int before = SSDB.getInstance().getInvoices().size();
        for (int mode = 0; mode < 4; mode++) {
            int path = mode;
            AtomicInteger completed = new AtomicInteger();
            SwingUtilities.invokeLater(() -> {
                try {
                    SSInvoice invoice = sampleInvoice();
                    switch (path) {
                        case 0 -> SSInvoiceDialog.newDialog(owner, null);
                        case 1 -> SSInvoiceDialog.editDialog(owner, invoice, null);
                        case 2 -> SSInvoiceDialog.copyDialog(owner, invoice, null);
                        case 3 -> SSInvoiceDialog.newDialog(owner, invoice, List.of(), null);
                        default -> throw new AssertionError(path);
                    }
                } catch (Throwable error) {
                    eventFailure.compareAndSet(null, error);
                } finally {
                    completed.incrementAndGet();
                }
            });
            if (mode == 0) {
                await(() -> visibleTypeDialog() != null);
                JButton normal = onEdt(() -> findButton(visibleTypeDialog(),
                        SSBundle.getBundle().getString("invoiceframe.type.1")));
                click(normal);
            }
            await(() -> visibleInvoiceDialog() != null);
            JDialog dialog = onEdt(SSInvoiceDialogGuiTest::visibleInvoiceDialog);
            JPanel root = onEdt(() -> (JPanel) dialog.getContentPane().getComponent(0));
            assertAllTabsAndButtonsFit(dialog, root);
            screenshot(dialog, "entry-path-" + mode);
            SSButtonPanel footer = onEdt(() -> actions(root));
            click(footer.getCancelButton());
            await(() -> completed.get() == 1);
            assertFalse(onEdt(dialog::isVisible));
            onEdt(() -> { dialog.dispose(); return null; });
        }
        assertEquals(before, SSDB.getInstance().getInvoices().size(), "Cancel must not create invoices");
    }

    @Test
    void actualEditDialogSaveWorksAfterResizingAndScrolling() throws Exception {
        SSMainFrame owner = onEdt(() -> {
            SSMainFrame f = SSMainFrame.getInstance();
            f.setVisible(true);
            return f;
        });
        SSInvoice invoice = onEdt(SSInvoiceDialogGuiTest::sampleInvoice);
        SSDB.getInstance().addInvoice(invoice);
        try {
            AtomicInteger completed = new AtomicInteger();
            SwingUtilities.invokeLater(() -> {
                try {
                    SSInvoiceDialog.editDialog(owner, invoice, null);
                } catch (Throwable error) {
                    eventFailure.compareAndSet(null, error);
                } finally {
                    completed.incrementAndGet();
                }
            });
            await(() -> visibleInvoiceDialog() != null);
            JDialog dialog = onEdt(SSInvoiceDialogGuiTest::visibleInvoiceDialog);
            JPanel root = onEdt(() -> (JPanel) dialog.getContentPane().getComponent(0));
            onEdt(() -> {
                SSInvoiceDialogLayout.sizeAndPosition(dialog, owner, new Rectangle(20, 40, 640, 440));
                dialog.validate();
                JScrollPane scroll = (JScrollPane) ((BorderLayout) root.getLayout())
                        .getLayoutComponent(BorderLayout.CENTER);
                scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                assertTrue(replaceTextField(root, invoice.getCustomerName(), "Saved layout test customer"));
                return null;
            });
            assertAllTabsAndButtonsFit(dialog, root);
            screenshot(dialog, "edit-before-save");
            click(onEdt(() -> actions(root).getOkButton()));
            await(() -> completed.get() == 1);
            SSInvoice saved = SSDB.getInstance().getInvoice(invoice).orElseThrow();
            assertEquals("Saved layout test customer", saved.getCustomerName());
            assertEquals(invoice.getRows().size(), saved.getRows().size());
            onEdt(() -> { dialog.dispose(); return null; });
        } finally {
            SSDB.getInstance().deleteInvoice(invoice);
        }
    }

    private void assertAllTabsAndButtonsFit(JDialog dialog, JPanel root) throws Exception {
        onEdt(() -> {
            SSButtonPanel footer = actions(root);
            JTabbedPane tabs = findTabs(root);
            for (int index = 0; index < tabs.getTabCount(); index++) {
                tabs.setSelectedIndex(index);
                dialog.validate();
                for (JButton button : List.of(footer.getOkButton(), footer.getCancelButton())) {
                    Rectangle rect = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(),
                            dialog.getContentPane());
                    Rectangle area = new Rectangle(dialog.getContentPane().getSize());
                    assertTrue(area.contains(rect), "Clipped action: " + rect + " in " + area);
                    assertEquals(button.getSize(), button.getVisibleRect().getSize(), "Action clipped by parent");
                }
                JScrollPane scroll = (JScrollPane) ((BorderLayout) root.getLayout())
                        .getLayoutComponent(BorderLayout.CENTER);
                Component view = scroll.getViewport().getView();
                Dimension min = view.getMinimumSize();
                assertTrue(view.getWidth() >= min.width && view.getHeight() >= min.height,
                        "Form squeezed below minimum: " + view.getSize() + " vs " + min);
            }
            return null;
        });
    }

    private static SSInvoice sampleInvoice() {
        SSInvoice invoice = new SSInvoice(SSInvoiceType.NORMAL);
        invoice.setCustomerNr("LAYOUT-TEST");
        invoice.setCustomerName("Layout Test Customer");
        invoice.setLocalDate(java.time.LocalDate.now());
        invoice.setLocalDueDate(java.time.LocalDate.now().plusDays(30));
        var row = new se.swedsoft.bookkeeping.data.base.SSSaleRow();
        row.setDescription("Layout test service");
        row.setQuantity(1);
        row.setUnitprice(new java.math.BigDecimal("100"));
        row.setAccountNr(SSDB.getInstance().getAccounts().get(0).getNumber());
        row.setTaxCode(se.swedsoft.bookkeeping.data.common.SSTaxCode.TAXRATE_1);
        invoice.setRows(new ArrayList<>(List.of(row)));
        return invoice;
    }

    private static SSButtonPanel actions(JPanel root) {
        return (SSButtonPanel) ((BorderLayout) root.getLayout()).getLayoutComponent(BorderLayout.SOUTH);
    }

    private static JTabbedPane findTabs(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JTabbedPane tabs) {
                return tabs;
            }
            if (child instanceof Container container) {
                JTabbedPane found = findTabs(container);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean replaceTextField(Container parent, String old, String replacement) {
        for (Component child : parent.getComponents()) {
            if (child instanceof javax.swing.JTextField field && old.equals(field.getText())) {
                field.setText(replacement);
                return true;
            }
            if (child instanceof Container container && replaceTextField(container, old, replacement)) {
                return true;
            }
        }
        return false;
    }

    private static JButton findButton(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (child instanceof Container container) {
                JButton found = findButton(container, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static SSInvoiceTypeDialog visibleTypeDialog() {
        for (Window window : Window.getWindows()) {
            if (window instanceof SSInvoiceTypeDialog type && type.isShowing()) {
                return type;
            }
        }
        return null;
    }

    private static JDialog visibleInvoiceDialog() {
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog dialog && dialog.isShowing()
                    && dialog.getContentPane().getComponentCount() == 1
                    && dialog.getContentPane().getComponent(0) instanceof JPanel root
                    && root.getLayout() instanceof BorderLayout
                    && ((BorderLayout) root.getLayout()).getLayoutComponent(BorderLayout.SOUTH)
                            instanceof SSButtonPanel) {
                return dialog;
            }
        }
        return null;
    }

    private void screenshot(JDialog dialog, String name) throws Exception {
        String directory = System.getProperty("bokfri.invoiceTest.artifacts");
        if (directory != null) {
            java.nio.file.Path path = java.nio.file.Path.of(directory);
            java.nio.file.Files.createDirectories(path);
            Rectangle bounds = onEdt(dialog::getBounds);
            javax.imageio.ImageIO.write(robot.createScreenCapture(bounds), "png",
                    path.resolve(name + ".png").toFile());
        }
    }

    private void click(Component component) throws Exception {
        Point p = onEdt(() -> {
            Point point = component.getLocationOnScreen();
            point.translate(component.getWidth() / 2, component.getHeight() / 2);
            return point;
        });
        robot.mouseMove(p.x, p.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        do {
            if (eventFailure.get() != null) {
                fail("Swing failure", eventFailure.get());
            }
            if (onEdt(condition::getAsBoolean)) {
                return;
            }
            robot.delay(80);
        } while (System.nanoTime() < deadline);
        fail("Invoice dialog did not reach expected state");
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
