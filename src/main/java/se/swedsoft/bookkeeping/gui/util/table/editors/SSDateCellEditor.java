package se.swedsoft.bookkeeping.gui.util.table.editors;


import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.SSUiMetrics;
import se.swedsoft.bookkeeping.gui.util.components.SSButton;
import se.swedsoft.bookkeeping.gui.util.datechooser.SSDateChooser;
import se.swedsoft.bookkeeping.util.SSDateUtil;

import javax.swing.*;
import javax.swing.table.TableCellEditor;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.EventObject;


/**
 * Table cell editor for {@link LocalDate} values.
 */
public class SSDateCellEditor extends AbstractCellEditor implements TableCellEditor {

    private JPanel iPanel;

    private JTextField iTextField;

    private JButton iButton;

    private SSDateChooser iDateChooser;

    private LocalDate iDate;

    private final DateTimeFormatter iFormatter;

    /**
     * Default constructor.
     */
    public SSDateCellEditor() {
        iFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);

        iDateChooser = new SSDateChooser();
        iDateChooser.addChangeListener(e -> setLocalDate(iDateChooser.getLocalDate()));

        iTextField = new JTextField();
        iTextField.setHorizontalAlignment(JTextField.TRAILING);
        iTextField.addActionListener(e -> updateDateFromTextField());
        iTextField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                updateDateFromTextField();
            }
        });

        iButton = new SSButton("ICON_CALENDAR16");
        iButton.setToolTipText(SSBundle.getBundle().getString("date.tooltip"));
        Dimension buttonSize = SSUiMetrics.squareControlSize(iButton, 20);
        iButton.setPreferredSize(buttonSize);
        iButton.setMaximumSize(buttonSize);
        iButton.setMinimumSize(buttonSize);

        iButton.addActionListener(e -> {

                iDateChooser.setLocalDate(iDate);
                iDateChooser.show(iButton, 0, iButton.getHeight());

            });

        iPanel = new JPanel();
        iPanel.setLayout(new BorderLayout());
        iPanel.add(iTextField, BorderLayout.CENTER);
        iPanel.add(iButton, BorderLayout.EAST);
    }

    /**
     * Returns the value contained in the editor.
     *
     * @return the value contained in the editor
     */
    public Object getCellEditorValue() {
        updateDateFromTextField();
        return iDate;
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
        if (value instanceof LocalDate) {
            setLocalDate((LocalDate) value);
        } else {
            setLocalDate(SSDateUtil.today());
        }

        return iPanel;
    }

    @Override
    public boolean isCellEditable(EventObject e) {
        if (e instanceof MouseEvent) {
            MouseEvent iMouseEvent = (MouseEvent) e;

            return iMouseEvent.getClickCount() >= 2;
        }
        return super.isCellEditable(e);
    }

    /**
     * @return the selected date
     */
    public LocalDate getLocalDate() {
        return iDate;
    }

    /**
     * @param iDate the selected date
     */
    public void setLocalDate(LocalDate iDate) {
        this.iDate = iDate;

        iTextField.setText(iDate == null ? "" : iDate.format(iFormatter));
        iDateChooser.setLocalDate(iDate);
    }

    private void updateDateFromTextField() {
        String iText = iTextField.getText();

        if (iText == null || iText.trim().isEmpty()) {
            iDate = null;
            return;
        }

        try {
            iDate = LocalDate.parse(iText.trim(), iFormatter);
        } catch (DateTimeParseException e) {
            try {
                iDate = LocalDate.parse(iText.trim());
            } catch (DateTimeParseException ignored) {
                Toolkit.getDefaultToolkit().beep();
                iTextField.setText(iDate == null ? "" : iDate.format(iFormatter));
            }
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.gui.util.table.editors.SSDateCellEditor");
        sb.append("{iButton=").append(iButton);
        sb.append(", iDate=").append(iDate);
        sb.append(", iDateChooser=").append(iDateChooser);
        sb.append(", iPanel=").append(iPanel);
        sb.append(", iTextField=").append(iTextField);
        sb.append('}');
        return sb.toString();
    }
}
