package se.swedsoft.bookkeeping.gui.util.components;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.fribok.bookkeeping.GuiTestLookAndFeel;
import se.swedsoft.bookkeeping.gui.util.table.SSTableSearchable;
import se.swedsoft.bookkeeping.gui.util.table.model.SSTableModel;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Real mouse/keyboard regression tests. Run explicitly with -Dawt.headless=false. */
class SSTableComboBoxGuiTest {
    private final java.util.concurrent.atomic.AtomicReference<Throwable> eventFailure =
            new java.util.concurrent.atomic.AtomicReference<>();
    private Thread.UncaughtExceptionHandler previousHandler;
    private JFrame frame;
    private Robot robot;
    private SSTableComboBox<Item> combo;
    private final List<Item> items = new ArrayList<>();
    private final Item first = new Item("1910");
    private final Item second = new Item("1920");

    @BeforeEach
    void setup() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires an interactive desktop");
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (thread.getName().startsWith("AWT-EventQueue")) {
                eventFailure.compareAndSet(null, error);
            } else if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error);
            }
        });
        robot = new Robot();
        robot.setAutoDelay(60);
        items.add(first);
        items.add(second);
        items.add(new Item("1930"));
        for (int i = 0; i < 30; i++) {
            items.add(new Item("2" + (100 + i)));
        }
        onEdt(() -> {
            GuiTestLookAndFeel.configure();
            frame = new JFrame("Synthetic dropdown focus test");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            combo = new SSTableComboBox<>();
            configure(combo);
            JPanel content = new JPanel(new BorderLayout());
            content.add(combo, BorderLayout.NORTH);
            content.add(new JButton("Outside"), BorderLayout.SOUTH);
            frame.setContentPane(content);
            frame.setSize(650, 420);
            frame.setLocation(100, 100);
            frame.setVisible(true);
            frame.toFront();
            return null;
        });
        robot.waitForIdle();
    }

    private void configure(SSTableComboBox<Item> target) {
        SSTableModel<Item> model = new SSTableModel<>() {
            public Class<?> getType() { return Item.class; }
            public SSTableModel<Item> getDropdownmodel() { return this; }
        };
        model.addColumn(new se.swedsoft.bookkeeping.gui.util.table.model.SSTableColumn<Item>("Account") {
            public int getDefaultWidth() { return 300; }
            public Class<?> getColumnClass() { return String.class; }
            public Object getValue(Item item) { return item.value(); }
            public void setValue(Item item, Object value) { }
            public boolean isEditable(int row) { return false; }
        });
        model.setObjects(items);
        target.setModel(model);
        target.setSearchColumns(0);
    }

    @AfterEach
    void cleanup(org.junit.jupiter.api.TestInfo testInfo) throws Exception {
        if (frame != null) {
            if (System.getProperty("bokfri.guiTest.artifacts") != null) {
                Path dir = Path.of(System.getProperty("bokfri.guiTest.artifacts"));
                Files.createDirectories(dir);
                javax.imageio.ImageIO.write(robot.createScreenCapture(new java.awt.Rectangle(
                        java.awt.Toolkit.getDefaultToolkit().getScreenSize())), "png",
                        dir.resolve((SSPopupFocusDiagnostics.isLegacyMode() ? "legacy-" : "fixed-")
                                + testInfo.getTestMethod().orElseThrow().getName() + ".png").toFile());
            }
            onEdt(() -> {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                for (Window window : Window.getWindows()) {
                    window.dispose();
                }
                return null;
            });
        }
        if (!GraphicsEnvironment.isHeadless()) {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
            assertNull(eventFailure.get(), "Uncaught Swing event-thread failure");
        }
    }

    @Test
    void typingKeepsFocusAndFiltersWithoutFurtherClicks() throws Exception {
        click(combo.iTextField);
        key(KeyEvent.VK_1);
        await(() -> combo.iPopup.isVisible() && combo.iTextField.isFocusOwner());
        key(KeyEvent.VK_9);
        key(KeyEvent.VK_2);
        key(KeyEvent.VK_0);
        await(() -> combo.iTextField.getText().equals("1920") && combo.iTable.getRowCount() == 1
                && combo.iTextField.isFocusOwner());
        key(KeyEvent.VK_ENTER);
        await(() -> !combo.iPopup.isVisible() && combo.getValue() == second);
    }

    @Test
    void arrowsEscapeAndReopeningFromButtonWork() throws Exception {
        click(combo.iDropdownButton);
        await(() -> combo.iPopup.isVisible() && combo.iTextField.isFocusOwner());
        key(KeyEvent.VK_DOWN);
        key(KeyEvent.VK_DOWN);
        await(() -> combo.iTable.getSelectedRow() == 1 && combo.iTextField.isFocusOwner());
        key(KeyEvent.VK_UP);
        await(() -> combo.iTable.getSelectedRow() == 0);
        key(KeyEvent.VK_ENTER);
        await(() -> combo.getValue() == first && !combo.iPopup.isVisible());
        click(combo.iDropdownButton);
        key(KeyEvent.VK_ESCAPE);
        await(() -> !combo.iPopup.isVisible() && combo.iTextField.isFocusOwner());
    }

    @Test
    void popupRowsRemainClickableAndDoubleClickSelects() throws Exception {
        click(combo.iDropdownButton);
        await(() -> combo.iPopup.isVisible());
        Point row = onEdt(() -> {
            Point p = combo.iTable.getLocationOnScreen();
            p.translate(25, combo.iTable.getRowHeight() + combo.iTable.getRowHeight() / 2);
            return p;
        });
        robot.delay(600); // Separate the opening-button click from the table double-click.
        click(row);
        await(() -> combo.iTable.getSelectedRow() == 1);
        robot.delay(650); // Start a fresh, tightly timed double-click, independent of assertion latency.
        robot.setAutoDelay(20);
        for (int i = 0; i < 2; i++) {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        }
        robot.setAutoDelay(60);
        robot.waitForIdle();
        await(() -> combo.getValue() == second && !combo.iPopup.isVisible());
    }

    @Test
    void scrollingSuggestionsDoesNotStealTypingFocus() throws Exception {
        click(combo.iDropdownButton);
        await(() -> combo.iPopup.isVisible() && combo.iTextField.isFocusOwner());
        Point point = onEdt(() -> {
            Point p = combo.iTable.getLocationOnScreen();
            p.translate(30, 30);
            return p;
        });
        robot.mouseMove(point.x, point.y);
        robot.delay(200);
        robot.mouseWheel(3);
        robot.delay(500);
        // macOS runners can have natural scrolling enabled; test movement in either direction.
        if (onEdt(() -> ((javax.swing.JScrollPane) combo.iPopup.getComponent(0))
                .getViewport().getViewPosition().y == 0)) {
            robot.mouseWheel(-3);
        }
        await(() -> ((javax.swing.JScrollPane) combo.iPopup.getComponent(0))
                .getViewport().getViewPosition().y > 0 && combo.iTextField.isFocusOwner());
        key(KeyEvent.VK_1);
        await(() -> combo.iTextField.getText().equals("1") && combo.iTextField.isFocusOwner());
    }

    @Test
    @SuppressWarnings("unchecked")
    void tableCellRemainsEditingWhileSuggestionsOpen() throws Exception {
        JTable table = onEdt(() -> {
            SSTableComboBox.CellEditor<Item> editor = new SSTableComboBox.CellEditor<>();
            JTable t = new JTable(new DefaultTableModel(new Object[][] {{null}}, new Object[] {"Account"}));
            t.getColumnModel().getColumn(0).setCellEditor(editor);
            frame.getContentPane().add(t, BorderLayout.CENTER);
            frame.validate();
            t.editCellAt(0, 0);
            combo = (SSTableComboBox<Item>) t.getEditorComponent();
            configure(combo);
            return t;
        });
        click(combo.iTextField);
        key(KeyEvent.VK_1);
        await(() -> table.isEditing() && combo.iPopup.isVisible() && combo.iTextField.isFocusOwner());
        key(KeyEvent.VK_9);
        key(KeyEvent.VK_2);
        key(KeyEvent.VK_0);
        key(KeyEvent.VK_ENTER);
        await(() -> !table.isEditing() && table.getValueAt(0, 0) == second);
    }

    @Test
    void editingMenuStillSupportsAddEditAndDelete() throws Exception {
        int[] calls = new int[3];
        SSEditableTableComboBox<Item> wrapper = onEdt(() -> {
            SSEditableTableComboBox<Item> w = new SSEditableTableComboBox<>();
            configure(w.getComboBox());
            w.setSelected(first);
            w.setEditingFactory(new SSEditableTableComboBox.EditingFactory<>() {
                public Item newAction() { calls[0]++; return second; }
                public void editAction(Item item) { calls[1]++; }
                public void deleteAction(Item item) { calls[2]++; }
            });
            frame.getContentPane().remove(combo);
            frame.getContentPane().add(w, BorderLayout.NORTH);
            frame.validate();
            return w;
        });
        for (int index = 0; index < 3; index++) {
            click((Component) onEdt(() -> wrapper.getComponent(1)));
            await(() -> MenuSelectionManager.defaultManager().getSelectedPath().length > 0);
            int itemIndex = index;
            JMenuItem item = onEdt(() -> {
                JPopupMenu popup = (JPopupMenu) MenuSelectionManager.defaultManager().getSelectedPath()[0];
                return (JMenuItem) popup.getComponent(itemIndex);
            });
            click(item);
            await(() -> calls[itemIndex] == 1);
        }
        assertArrayEquals(new int[] {1, 1, 1}, calls);
    }

    @Test
    void emptyResultsEscapeAndTabDoNotTrapFocus() throws Exception {
        click(combo.iTextField);
        key(KeyEvent.VK_8);
        await(() -> combo.iPopup.isVisible() && combo.iTable.getRowCount() == 0);
        key(KeyEvent.VK_DOWN);
        key(KeyEvent.VK_ESCAPE);
        await(() -> !combo.iPopup.isVisible() && combo.iTextField.isFocusOwner());
        key(KeyEvent.VK_TAB);
        await(() -> !combo.iTextField.isFocusOwner());
    }

    @Test
    @SuppressWarnings("unchecked")
    void typingIntoSelectedAccountCellKeepsFirstCharacter() throws Exception {
        JTable table = accountTable();
        onEdt(() -> { table.setValueAt(first, 0, 0); return null; });
        clickFirstCell(table);
        await(() -> table.isFocusOwner() && !table.isEditing());
        key(KeyEvent.VK_1);
        combo = onEdt(() -> (SSTableComboBox<Item>) table.getEditorComponent());
        await(() -> combo.iTextField.isFocusOwner() && combo.iTextField.getText().equals("1")
                && combo.iTextField.getSelectionStart() == combo.iTextField.getSelectionEnd());
        key(KeyEvent.VK_9);
        key(KeyEvent.VK_2);
        key(KeyEvent.VK_0);
        key(KeyEvent.VK_ENTER);
        await(() -> !table.isEditing() && table.getValueAt(0, 0) == second);
    }

    @Test
    @SuppressWarnings("unchecked")
    void keyboardSelectedAccountCellKeepsFirstCharacter() throws Exception {
        JTable table = accountTable();
        onEdt(() -> { table.changeSelection(0, 0, false, false); table.requestFocusInWindow(); return null; });
        await(table::isFocusOwner);
        key(KeyEvent.VK_1);
        combo = onEdt(() -> (SSTableComboBox<Item>) table.getEditorComponent());
        key(KeyEvent.VK_9);
        key(KeyEvent.VK_2);
        key(KeyEvent.VK_0);
        await(() -> combo.iTextField.getText().equals("1920"));
        key(KeyEvent.VK_ENTER);
        await(() -> !table.isEditing() && table.getValueAt(0, 0) == second);
    }

    @Test
    void typingIntoSelectedDecimalCellKeepsFirstCharacter() throws Exception {
        JTable table = installTable(new se.swedsoft.bookkeeping.gui.util.table.editors.SSBigDecimalCellEditor(2));
        clickFirstCell(table);
        key(KeyEvent.VK_1);
        javax.swing.JFormattedTextField field = onEdt(() ->
                (javax.swing.JFormattedTextField) table.getEditorComponent());
        onEdt(() -> { field.requestFocusInWindow(); return null; });
        await(() -> field.isFocusOwner() && field.getSelectionStart() == field.getSelectionEnd());
        key(KeyEvent.VK_9);
        key(KeyEvent.VK_3);
        key(KeyEvent.VK_0);
        key(KeyEvent.VK_ENTER);
        await(() -> !table.isEditing());
        assertEquals(0, onEdt(() -> ((java.math.BigDecimal) table.getValueAt(0, 0))
                .compareTo(new java.math.BigDecimal("1930"))));
    }

    @Test
    void typingIntoSelectedIntegerCellReplacesOldValueWithoutLosingFirstCharacter() throws Exception {
        JTable table = installTable(new se.swedsoft.bookkeeping.gui.util.table.editors.SSIntegerCellEditor());
        onEdt(() -> { table.setValueAt(42, 0, 0); return null; });
        clickFirstCell(table);
        key(KeyEvent.VK_1);
        javax.swing.JFormattedTextField field = onEdt(() ->
                (javax.swing.JFormattedTextField) table.getEditorComponent());
        onEdt(() -> { field.requestFocusInWindow(); return null; });
        await(() -> field.isFocusOwner() && field.getSelectionStart() == field.getSelectionEnd());
        key(KeyEvent.VK_9);
        key(KeyEvent.VK_3);
        key(KeyEvent.VK_0);
        key(KeyEvent.VK_ENTER);
        await(() -> !table.isEditing());
        assertEquals(1930, onEdt(() -> table.getValueAt(0, 0)));
    }

    @Test
    void escapeDismissesSuggestionsBeforeClosingRealDialog() throws Exception {
        se.swedsoft.bookkeeping.gui.util.dialogs.SSDialog dialog = onEdt(() -> {
            var d = new se.swedsoft.bookkeeping.gui.util.dialogs.SSDialog(frame, "Real dialog dispatch", false);
            frame.getContentPane().remove(combo);
            d.add(combo);
            d.setSize(600, 250);
            d.setLocation(120, 120);
            d.setVisible(true);
            d.toFront();
            return d;
        });
        click(combo.iTextField);
        key(KeyEvent.VK_1);
        await(() -> combo.iPopup.isVisible() && combo.iTextField.isFocusOwner());
        key(KeyEvent.VK_ESCAPE);
        await(() -> dialog.isShowing() && !combo.iPopup.isVisible() && combo.iTextField.isFocusOwner());
        key(KeyEvent.VK_ESCAPE);
        await(() -> !dialog.isShowing());
    }

    private JTable accountTable() throws Exception {
        var editor = onEdt(() -> new SSTableComboBox.CellEditor<Item>(combo.getModel()));
        onEdt(() -> { editor.setSearchColumns(0); return null; });
        return installTable(editor);
    }

    private JTable installTable(javax.swing.table.TableCellEditor editor) throws Exception {
        return onEdt(() -> {
            JTable table = new JTable(new DefaultTableModel(new Object[][] {{null}}, new Object[] {"Account"}));
            table.setCellSelectionEnabled(true);
            table.setRowHeight(30);
            table.getColumnModel().getColumn(0).setCellEditor(editor);
            frame.getContentPane().remove(combo);
            frame.getContentPane().add(table, BorderLayout.CENTER);
            frame.validate();
            return table;
        });
    }

    private void clickFirstCell(JTable table) throws Exception {
        click(onEdt(() -> {
            Point p = table.getLocationOnScreen();
            java.awt.Rectangle rect = table.getCellRect(0, 0, true);
            p.translate(rect.x + rect.width / 2, rect.y + rect.height / 2);
            return p;
        }));
    }

    private void key(int code) {
        robot.keyPress(code);
        robot.keyRelease(code);
        robot.waitForIdle();
    }

    private void click(Component component) throws Exception {
        click(onEdt(() -> {
            Point p = component.getLocationOnScreen();
            p.translate(component.getWidth() / 2, component.getHeight() / 2);
            return p;
        }));
    }

    private void click(Point p) {
        robot.mouseMove(p.x, p.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        do {
            if (onEdt(condition::getAsBoolean)) {
                return;
            }
            robot.delay(80);
        } while (System.nanoTime() < deadline);
        fail(onEdt(() -> "GUI condition timed out: text=" + combo.iTextField.getText()
                + " popup=" + combo.iPopup.isVisible() + " selectedRow=" + combo.iTable.getSelectedRow()
                + " viewport=" + ((javax.swing.JScrollPane) combo.iPopup.getComponent(0)).getViewport().getViewPosition()
                + " rows=" + combo.iTable.getRowCount() + " focus="
                + KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()));
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private record Item(String value) implements SSTableSearchable {
        public String toRenderString() { return value; }
    }
}
