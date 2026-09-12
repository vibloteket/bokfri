package se.swedsoft.bookkeeping.gui.util.datechooser;


import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.SSUiMetrics;
import se.swedsoft.bookkeeping.gui.util.components.SSButton;
import se.swedsoft.bookkeeping.gui.util.datechooser.panel.SSCalendar;
import se.swedsoft.bookkeeping.util.SSDateUtil;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.*;
import java.time.LocalDate;
import java.util.LinkedList;
import java.util.List;


/**
 * Date chooser widget combining a {@link JSpinner} (for keyboard editing)
 * with a popup {@link SSCalendar} panel.
 *
 * <p>Internally the Swing spinner model still uses legacy date objects because
 * {@link SpinnerDateModel} requires them.  The application-facing API uses
 * {@link LocalDate}.
 */
public class SSDateChooser extends JPanel implements ActionListener, ChangeListener {

    private static final int SPINNER_FIELD_MONTH = 2;

    private static final int SPINNER_FIELD_DAY_OF_MONTH = 5;

    private JSpinner iSpinner;

    private JSpinner.DateEditor iEditor;

    private JButton iCalendarButton;

    private JDialog iPopup;

    // the change listeners
    private List<ActionListener> iChangeListeners;

    private SSCalendar iCalendar;

    private SpinnerDateModel iModel;

    // The date format string
    private String iDateFormatString;
    // The calendar field (used by SpinnerDateModel)
    private int iCalendarField;

    protected boolean isDateSelected;

    private JPanel iPanel;

    /**
     * Creates a new {@code JPanel} with a double buffer
     * and a flow layout.
     */
    public SSDateChooser() {
        iChangeListeners = new LinkedList<>();

        iDateFormatString = "yyyy-MM-dd";
        iCalendarField = SPINNER_FIELD_DAY_OF_MONTH;

        iModel = new SpinnerDateModel() {
            @Override
            public void setCalendarField(int calendarField) {
                // Always use the prefered calendar field
                super.setCalendarField(iCalendarField);
            }
        };
        iModel.setCalendarField(SPINNER_FIELD_MONTH);
        iModel.addChangeListener(this);

        iSpinner = new JSpinner();
        iSpinner.setModel(iModel);
        int spinnerHeight = SSUiMetrics.controlHeight(iSpinner, 20);
        iSpinner.setPreferredSize(new Dimension(-1, spinnerHeight));
        iSpinner.setMaximumSize(new Dimension(-1, spinnerHeight));
        iSpinner.setMinimumSize(new Dimension(-1, spinnerHeight));

        iEditor = new JSpinner.DateEditor(iSpinner, iDateFormatString);
        iSpinner.setEditor(iEditor);

        iCalendar = new SSCalendar();
        iCalendar.addChangeListener(this);

        iPanel = iCalendar.getPanel();
        iPanel.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(102, 101, 84), 1),
                        BorderFactory.createLineBorder(new Color(247, 236, 249), 1)));

        iCalendarButton = new SSButton("ICON_CALENDAR16");
        iCalendarButton.setToolTipText(SSBundle.getBundle().getString("date.tooltip"));
        Dimension buttonSize = SSUiMetrics.squareControlSize(iCalendarButton, 20);
        iCalendarButton.setPreferredSize(buttonSize);
        iCalendarButton.setMaximumSize(buttonSize);
        iCalendarButton.setMinimumSize(buttonSize);

        iCalendarButton.addActionListener(e -> {

                if (iPopup == null) {
                    createPopup(SSDateChooser.this);
                }

                int x = iCalendarButton.getWidth() - iPopup.getPreferredSize().width;
                int y = iCalendarButton.getY() + iCalendarButton.getHeight();

                isDateSelected = false;

                iCalendar.setLocalDate(getLocalDate());

                // iPopup
                show(iCalendarButton, x, y);

            });

        setLayout(new BorderLayout());
        add(iSpinner, BorderLayout.CENTER);
        add(iCalendarButton, BorderLayout.EAST);
        setLocalDate(SSDateUtil.today());
    }

    /**
     *
     * @param invoker the invoker component
     * @param x x offset
     * @param y y offset
     */
    public void show(Component invoker, int x, int y) {
        if (iPopup == null) {
            createPopup(invoker);
        }

        if (invoker != null) {
            Point invokerOrigin = invoker.getLocationOnScreen();

            iPopup.setLocation(invokerOrigin.x + x, invokerOrigin.y + y);
        } else {
            iPopup.setLocation(x, y);
        }
        // iPopup.set  getWindow
        iPopup.setVisible(true);
        iPopup.requestFocusInWindow();

    }

    /**
     * @param invoker the invoker component
     */
    private void createPopup(Component invoker) {
        JDialog iDialog = getDialog(invoker);

        if (iDialog != null) {
            iPopup = new JDialog(iDialog);
        } else {
            iPopup = new JDialog();
        }

        iPopup.setUndecorated(true);
        iPopup.getRootPane().setWindowDecorationStyle(JRootPane.NONE);
        iPopup.setAlwaysOnTop(true);
        iPopup.addWindowListener(new WindowAdapter() {
            @Override
            public void windowDeactivated(WindowEvent e) {
                iPopup.setVisible(false);
            }
        });
        iPopup.add(iPanel);
        iPopup.pack();
    }

    /**
     * Get the parent dialog for this component, or null
     *
     * @param c the component
     * @return the owning dialog
     */
    private JDialog getDialog(Component c) {
        Component w = c;

        while (w != null) {

            if (w instanceof JDialog) {
                return (JDialog) w;
            }

            w = w.getParent();
        }
        return null;
    }

    /**
     * @return the selected date as a {@link LocalDate}
     */
    public LocalDate getLocalDate() {
        return SSDateUtil.toLocalDate(iModel.getDate());
    }

    /**
     * Set the selected date using a {@link LocalDate}.
     * If the date is null, the current date is selected.
     *
     * @param date the date
     */
    public void setLocalDate(LocalDate date) {
        if (date != null) {
            iModel.setValue(SSDateUtil.toDate(date));
        } else {
            iModel.setValue(SSDateUtil.toDate(SSDateUtil.today()));
        }
    }

    /**
     * Get the date format string to use for the editor
     *
     * @return the dateformat string
     */
    public String getDateFormatString() {
        return iDateFormatString;
    }

    /**
     * Set the date format string to use for the editor
     *
     * @param iDateFormatString the date format string
     */
    public void setDateFormatString(String iDateFormatString) {
        this.iDateFormatString = iDateFormatString;

        iEditor.getFormat().applyPattern(iDateFormatString);
        iEditor.getTextField().setValue(iModel.getDate());

        invalidate();
    }

    /**
     * Set the spinner field the updown shall edit.
     *
     * @param iCalendarField the field identifier expected by {@link SpinnerDateModel}
     */
    public void setCalendarField(int iCalendarField) {
        this.iCalendarField = iCalendarField;

    }

    /**
     * Get the spinner field the updown are editing.
     *
     * @return the field identifier expected by {@link SpinnerDateModel}
     */
    public int getCalendarField() {
        return iCalendarField;
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
        ActionEvent iEvent = new ActionEvent(this, 0, "date");

        for (ActionListener iActionListener : iChangeListeners) {
            iActionListener.actionPerformed(iEvent);
        }

    }

    /**
     * Invoked when an action occurs.
     */
    public void actionPerformed(ActionEvent e) {
        iModel.setValue(SSDateUtil.toDate(iCalendar.getLocalDate()));

        if (e.getActionCommand().equals("day")) {
            isDateSelected = true;

            iPopup.setVisible(false);
        }
    }

    public JComponent getEditor() {
        return iSpinner.getEditor();
    }

    /**
     * Checks whether the currently selected date falls within the current accounting year.
     *
     * @return true if the selected date is within the current accounting year
     */
    public boolean isInCurrentAccountYear() {
        LocalDate accountYearTo = SSDB.getInstance().getCurrentYear().getLocalTo();
        LocalDate accountYearFrom = SSDB.getInstance().getCurrentYear().getLocalFrom();

        // Add end-of-day tolerance (the original code added 23:59 to the 'to' date)
        LocalDate iCurrent = getLocalDate();

        return !(iCurrent.isBefore(accountYearFrom) || iCurrent.isAfter(accountYearTo));
    }

    /**
     * Invoked when the target of the listener has changed its state.
     *
     * @param e a ChangeEvent object
     */
    public void stateChanged(ChangeEvent e) {
        notifyChangeListeners();
    }

    /**
     *
     * @param enabled whether the component is enabled
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);

        iCalendarButton.setEnabled(enabled);
        iSpinner.setEnabled(enabled);
        iEditor.setEnabled(enabled);

    }

    /**
     * Used to clean up references making sure the garbage collector
     * is able to clean up the object.
     */
    public void dispose() {
        iSpinner.removeAll();
        iSpinner = null;
        iEditor.removeAll();
        iEditor = null;

        ActionListener[] iActionListeners = iCalendarButton.getActionListeners();

        for (ActionListener iActionListener : iActionListeners) {
            iCalendarButton.removeActionListener(iActionListener);
        }
        iCalendarButton.removeAll();
        iCalendarButton = null;

        if (iPopup != null) {
            WindowListener[] iWindowListeners = iPopup.getWindowListeners();

            for (WindowListener iWindowListener : iWindowListeners) {
                iPopup.removeWindowListener(iWindowListener);
            }

            iPopup.removeAll();
            iPopup.getContentPane().removeAll();
            iPopup.dispose();
        }
        iChangeListeners.removeAll(iChangeListeners);
        iChangeListeners = null;
        iCalendar.dispose();
        iCalendar = null;
        ChangeListener[] iChangeListeners = iModel.getChangeListeners();

        for (ChangeListener iChangeListener : iChangeListeners) {
            iModel.removeChangeListener(iChangeListener);
        }
        iModel = null;
        iDateFormatString = null;
        iPanel.removeAll();
        iPanel = null;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.gui.util.datechooser.SSDateChooser");
        sb.append("{iCalendar=").append(iCalendar);
        sb.append(", iCalendarButton=").append(iCalendarButton);
        sb.append(", iCalendarField=").append(iCalendarField);
        sb.append(", iChangeListeners=").append(iChangeListeners);
        sb.append(", iDateFormatString='").append(iDateFormatString).append('\'');
        sb.append(", iEditor=").append(iEditor);
        sb.append(", iModel=").append(iModel);
        sb.append(", iPanel=").append(iPanel);
        sb.append(", iPopup=").append(iPopup);
        sb.append(", isDateSelected=").append(isDateSelected);
        sb.append(", iSpinner=").append(iSpinner);
        sb.append('}');
        return sb.toString();
    }
}
