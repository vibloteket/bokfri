package se.swedsoft.bookkeeping.gui.util.datechooser.panel;


import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.text.DateFormatSymbols;
import java.time.LocalDate;
import java.util.LinkedList;
import java.util.List;


/**
 * Month picker panel using a {@link JComboBox} with {@link JSpinner}.
 *
 * <p>Internally uses {@link LocalDate} for all date arithmetic.
 */
public class SSMonthChooser implements ItemListener {

    private JPanel iPanel;
    // The combobox
    private JComboBox<String> iComboBox;
    // the spinner
    private JSpinner iSpinner;

    // The selected date (stored as LocalDate internally)
    private LocalDate iLocalDate;
    // the change listeners
    private List<ActionListener> iChangeListeners;

    /**
     *
     */
    public SSMonthChooser() {
        iChangeListeners = new LinkedList<>();

        iComboBox = new JComboBox<>();
        iComboBox.addItemListener(this);
        iComboBox.setBorder(BorderFactory.createEmptyBorder());
        iComboBox.setLightWeightPopupEnabled(true);

        // iSpinner.setBorder(new EmptyBorder(0, 0, 0, 0));

        iSpinner.setEditor(iComboBox);
        iSpinner.setModel(new MonthSpinnerModel());

        updateMonthNames();

        setLocalDate(LocalDate.now());
    }

    /**
     *
     * @return the panel
     */
    public JPanel getPanel() {
        return iPanel;
    }

    /**
     * @return the selected date
     */
    public LocalDate getLocalDate() {
        return iLocalDate;
    }

    /**
     * Set the selected date, updating the combo box to show the correct month.
     *
     * @param date the date
     */
    public void setLocalDate(LocalDate date) {
        this.iLocalDate = date;

        ComboBoxModel<String> iComboBoxModel = iComboBox.getModel();

        // LocalDate months are 1-based; combo box is 0-based
        int iIndex = date.getMonthValue() - 1;

        iComboBoxModel.setSelectedItem(iComboBoxModel.getElementAt(iIndex));
    }

    /**
     *
     * @param e the event
     */
    public void itemStateChanged(ItemEvent e) {

        int iMonth = iComboBox.getSelectedIndex();

        if (iLocalDate != null && iMonth >= 0) {
            // Clamp the day to the new month's max day
            int currentDay = iLocalDate.getDayOfMonth();
            // withMonth is 1-based
            LocalDate newDate = iLocalDate.withMonth(iMonth + 1);
            int maxDay = newDate.lengthOfMonth();
            if (currentDay > maxDay) {
                newDate = newDate.withDayOfMonth(maxDay);
            } else {
                newDate = newDate.withDayOfMonth(currentDay);
            }

            iLocalDate = newDate;

            notifyChangeListeners();
        }
    }

    /**
     * Invoked when the date changes
     *
     * @param iActionListener the listener
     */
    public void addChangeListener(ActionListener iActionListener) {
        iChangeListeners.add(iActionListener);
    }

    /**
     *
     */
    private void notifyChangeListeners() {
        ActionEvent iEvent = new ActionEvent(this, 0, "month");

        for (ActionListener iActionListener : iChangeListeners) {
            iActionListener.actionPerformed(iEvent);
        }
    }

    /**
     * Initializes the locale specific month names.
     */
    private void updateMonthNames() {
        String[] iMonths = new DateFormatSymbols().getMonths();

        iComboBox.removeAllItems();
        for (int i = 0; i < 12; i++) {
            iComboBox.addItem(iMonths[i]);
        }
    }

    /**
     * Used to clean up references making sure the garbage collector
     * is able to clean up the object.
     */
    public void dispose() {

        iPanel.removeAll();
        iPanel = null;

        ItemListener[] iItemListeners = iComboBox.getItemListeners();

        for (ItemListener iItemListener : iItemListeners) {
            iComboBox.removeItemListener(iItemListener);
        }
        iComboBox.removeAllItems();
        iComboBox = null;
        iSpinner.removeAll();
        iSpinner = null;
        iLocalDate = null;

        iChangeListeners.removeAll(iChangeListeners);
        iChangeListeners = null;
    }

    /**
     * The model to use for the spinner
     */
    private class MonthSpinnerModel extends AbstractSpinnerModel {

        @Override
        public Object getValue() {
            return iComboBox.getSelectedItem();

        }

        @Override
        public void setValue(Object value) {
            iComboBox.setSelectedItem(value);
        }

        @Override
        public Object getNextValue() {
            int iIndex = iComboBox.getSelectedIndex();

            if (iIndex < 11) {
                return iComboBox.getItemAt(iIndex + 1);
            } else {
                return iComboBox.getItemAt(0);
            }
        }

        @Override
        public Object getPreviousValue() {
            int iIndex = iComboBox.getSelectedIndex();

            if (iIndex > 0) {
                return iComboBox.getItemAt(iIndex - 1);
            } else {
                return iComboBox.getItemAt(11);
            }

        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.gui.util.datechooser.panel.SSMonthChooser");
        sb.append("{iChangeListeners=").append(iChangeListeners);
        sb.append(", iComboBox=").append(iComboBox);
        sb.append(", iLocalDate=").append(iLocalDate);
        sb.append(", iPanel=").append(iPanel);
        sb.append(", iSpinner=").append(iSpinner);
        sb.append('}');
        return sb.toString();
    }
}
