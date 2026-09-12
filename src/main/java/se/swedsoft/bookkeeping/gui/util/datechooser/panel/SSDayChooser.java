package se.swedsoft.bookkeeping.gui.util.datechooser.panel;


import se.swedsoft.bookkeeping.gui.util.SSUiMetrics;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.DateFormatSymbols;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;


/**
 * Day picker panel that displays a 6x7 grid of day buttons for a given month.
 *
 * <p>Internally uses {@link LocalDate} for all date arithmetic.
 */
public class SSDayChooser implements ActionListener {

    private static Color WEEK_COLOR = new Color(100, 100, 100);

    private static Color BACKGROUND_COLOR = new Color(210, 228, 238);

    private static Color sundayForeground = new Color(164, 0, 0);
    private static Color weekdayForeground = new Color(0, 90, 164);

    private static Color SELECTED_COLOR = new Color(160, 160, 160); // new Color(119, 137, 162);

    // The main panel
    private JPanel iPanel;
    // The panel that contains the day buttons
    private JPanel iDayPanel;

    private JPanel iDayNamePanel;

    private JPanel iWeekNamePanel;

    // The buttons for the days
    private List<DayButton> iDayButtons;
    // The label for the day names
    private List<JLabel> iDayNames;
    // The labels for the weeks
    private List<JLabel> iWeekNames;

    // The selected date (stored as LocalDate internally)
    private LocalDate iLocalDate;
    // the change listeners
    private List<ActionListener> iChangeListeners;

    /**
     *
     */
    public SSDayChooser() {
        iChangeListeners = new LinkedList<>();

        iPanel.setBackground(BACKGROUND_COLOR);

        iDayPanel.setLayout(new GridLayout(6, 7));
        iDayPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        iDayNamePanel.setLayout(new GridLayout(1, 7));
        iDayNamePanel.setOpaque(false);
        iDayNamePanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        iWeekNamePanel.setLayout(new GridLayout(6, 1));
        iWeekNamePanel.setOpaque(false);
        iWeekNamePanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        // Initializate all the day buttons
        iDayButtons = new LinkedList<>();
        for (int i = 0; i < 42; i++) {
            DayButton iButton = new DayButton();

            iButton.setText(Integer.toString(i + 1));
            iButton.addActionListener(this);
            iButton.setContentAreaFilled(true);

            iDayButtons.add(iButton);
            iDayPanel.add(iButton);
        }

        // Initialize the day names
        iDayNames = new LinkedList<>();
        for (int i = 0; i < 7; i++) {
            JLabel iLabel = new JLabel();

            iLabel.setOpaque(false);
            iLabel.setHorizontalAlignment(SwingConstants.CENTER);
            iLabel.setVerticalAlignment(SwingConstants.CENTER);

            iDayNames.add(iLabel);
            iDayNamePanel.add(iLabel);
        }

        // Initialize the week names
        iWeekNames = new LinkedList<>();
        for (int i = 0; i < 6; i++) {
            JLabel iLabel = new JLabel();

            iLabel.setOpaque(false);
            iLabel.setHorizontalAlignment(SwingConstants.CENTER);
            iLabel.setVerticalAlignment(SwingConstants.CENTER);
            iLabel.setForeground(WEEK_COLOR);

            iWeekNames.add(iLabel);
            iWeekNamePanel.add(iLabel);
        }

        setLocalDate(LocalDate.now());
    }

    /**
     * @return the selected date
     */
    public LocalDate getLocalDate() {
        return iLocalDate;
    }

    /**
     * Set the selected date.
     *
     * @param date the date
     */
    public void setLocalDate(LocalDate date) {
        this.iLocalDate = date;

        updateDayColumns();
        updateDays();
        updateWeeks();
    }

    /**
     * Returns the panel for the date chooser
     *
     * @return the panel
     */
    public JPanel getPanel() {
        return iPanel;
    }

    /**
     * Updates the day-of-week column headers using locale-aware names.
     */
    private void updateDayColumns() {
        WeekFields weekFields = WeekFields.of(Locale.getDefault());
        DayOfWeek firstDayOfWeek = weekFields.getFirstDayOfWeek();

        String[] dayNames = new DateFormatSymbols().getShortWeekdays();

        DayOfWeek current = firstDayOfWeek;
        for (JLabel iLabel : this.iDayNames) {
            // Map DayOfWeek to DateFormatSymbols weekday index (SUNDAY=1..SATURDAY=7)
            int calendarDay = current.getValue() % 7 + 1; // ISO Mon=1..Sun=7 -> Sun=1..Sat=7
            iLabel.setText(dayNames[calendarDay]);

            if (current == DayOfWeek.SUNDAY) {
                iLabel.setForeground(sundayForeground);
            } else {
                iLabel.setForeground(weekdayForeground);
            }

            current = current.plus(1);
        }
    }

    /**
     * Updates the week number labels.
     */
    private void updateWeeks() {
        WeekFields weekFields = WeekFields.of(Locale.getDefault());
        LocalDate firstOfMonth = iLocalDate.withDayOfMonth(1);

        for (JLabel iLabel : iWeekNames) {
            int weekNumber = firstOfMonth.get(weekFields.weekOfWeekBasedYear());

            if (weekNumber < 10) {
                iLabel.setText("0" + weekNumber);
            } else {
                iLabel.setText(Integer.toString(weekNumber));
            }

            firstOfMonth = firstOfMonth.plusWeeks(1);
        }
    }

    /**
     * Updates the day buttons for the current month.
     */
    private void updateDays() {
        WeekFields weekFields = WeekFields.of(Locale.getDefault());
        DayOfWeek firstDayOfWeek = weekFields.getFirstDayOfWeek();

        LocalDate firstOfMonth = iLocalDate.withDayOfMonth(1);
        int lengthOfMonth = iLocalDate.lengthOfMonth();

        // Calculate offset: how many cells before the first day of the month
        int dayOfWeekValue = firstOfMonth.getDayOfWeek().getValue(); // Mon=1..Sun=7
        int firstDowValue = firstDayOfWeek.getValue();
        int iStart = (dayOfWeekValue - firstDowValue + 7) % 7;
        int iStop = iStart + lengthOfMonth;

        java.time.format.DateTimeFormatter longFormat =
                java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG);

        int iIndex = 0;
        LocalDate currentDate = firstOfMonth;

        for (DayButton iButton : iDayButtons) {
            // Only show the button if the day is in the current month
            if (iIndex >= iStart && iIndex < iStop) {
                iButton.setVisible(true);
                iButton.setText(Integer.toString(currentDate.getDayOfMonth()));
                iButton.setToolTipText(currentDate.format(longFormat));
                iButton.setLocalDate(currentDate);

                if (currentDate.equals(iLocalDate)) {
                    iButton.setBackground(SELECTED_COLOR);
                } else {
                    iButton.setBackground(new JButton().getBackground());
                }

                currentDate = currentDate.plusDays(1);
            } else {
                iButton.setVisible(false);
                iButton.setLocalDate(null);
            }

            iIndex++;
        }
    }

    /**
     * Invoked when an action occurs.
     */
    public void actionPerformed(ActionEvent e) {
        if (e.getSource() instanceof DayButton) {
            DayButton iButton = (DayButton) e.getSource();

            iLocalDate = iButton.getLocalDate();

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
        ActionEvent iEvent = new ActionEvent(this, 0, "day");

        for (ActionListener iActionListener : iChangeListeners) {
            iActionListener.actionPerformed(iEvent);
        }
    }

    /**
     * Used to clean up references making sure the garbage collector
     * is able to clean up the object.
     */
    public void dispose() {

        iPanel.removeAll();
        iPanel = null;

        iDayPanel.removeAll();
        iDayPanel = null;

        iDayNamePanel.removeAll();
        iDayNamePanel = null;

        iWeekNamePanel.removeAll();
        iWeekNamePanel = null;
        for (DayButton iDayButton:iDayButtons) {
            ActionListener[] iActionListeners = iDayButton.getActionListeners();

            for (ActionListener iActionListener : iActionListeners) {
                iDayButton.removeActionListener(iActionListener);
            }
        }

        iDayButtons.removeAll(iDayButtons);
        iDayButtons = null;
        iDayNames.removeAll(iDayNames);
        iDayNames = null;
        iWeekNames.removeAll(iWeekNames);
        iWeekNames = null;

        iLocalDate = null;

        iChangeListeners.removeAll(iChangeListeners);
        iChangeListeners = null;
    }

    /**
     * The button to use for the day selecting
     */
    private class DayButton extends JButton {

        // The date to select if the user presses this button
        private LocalDate iDate;

        /**
         * Creates a button with no set text or icon.
         */
        public DayButton() {
            int height = SSUiMetrics.controlHeight(this, 21);
            Dimension size = new Dimension(Math.max(27, height), height);
            setMinimumSize(size);
            setMaximumSize(size);
            setPreferredSize(size);

            setMargin(new Insets(0, 0, 0, 0));

            setFocusPainted(false);
            setOpaque(false);
        }

        /**
         * @return the date
         */
        public LocalDate getLocalDate() {
            return iDate;
        }

        /**
         * @param date the date
         */
        public void setLocalDate(LocalDate date) {
            this.iDate = date;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();

            sb.append(
                    "se.swedsoft.bookkeeping.gui.util.datechooser.panel.SSDayChooser.DayButton");
            sb.append("{iDate=").append(iDate);
            sb.append('}');
            return sb.toString();
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.gui.util.datechooser.panel.SSDayChooser");
        sb.append("{iChangeListeners=").append(iChangeListeners);
        sb.append(", iLocalDate=").append(iLocalDate);
        sb.append(", iDayButtons=").append(iDayButtons);
        sb.append(", iDayNamePanel=").append(iDayNamePanel);
        sb.append(", iDayNames=").append(iDayNames);
        sb.append(", iDayPanel=").append(iDayPanel);
        sb.append(", iPanel=").append(iPanel);
        sb.append(", iWeekNamePanel=").append(iWeekNamePanel);
        sb.append(", iWeekNames=").append(iWeekNames);
        sb.append('}');
        return sb.toString();
    }
}
