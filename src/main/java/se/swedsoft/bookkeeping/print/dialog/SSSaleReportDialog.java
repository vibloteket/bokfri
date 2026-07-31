package se.swedsoft.bookkeeping.print.dialog;


import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.SSButtonPanel;
import se.swedsoft.bookkeeping.gui.util.datechooser.SSDateChooser;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSDialog;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.time.LocalDate;

import static se.swedsoft.bookkeeping.print.report.SSSaleReportPrinter.SortingMode;


/**
 * $Id$
 *
 */
public class SSSaleReportDialog extends SSDialog {

    private JPanel iPanel;

    private SSButtonPanel iButtonPanel;

    private SSDateChooser iToDate;
    private SSDateChooser iFromDate;

    private JRadioButton iSortAscending;
    private JRadioButton iSortDescending;

    private JComboBox<SortingMode> iSort;

    /**
     *
     * @param iMainFrame
     */
    public SSSaleReportDialog(SSMainFrame iMainFrame) {
        super(iMainFrame, SSBundle.getBundle().getString("salereport.dialog.title"));

        SSNewAccountingYear iCurrentYear = SSDB.getInstance().getCurrentYear();

        if (iCurrentYear != null) {
            iFromDate.setLocalDate(iCurrentYear.getLocalFrom());
            iToDate.setLocalDate(iCurrentYear.getLocalTo());
        }

        setPanel(iPanel);

        iButtonPanel.addCancelActionListener(e -> setModalResult(JOptionPane.CANCEL_OPTION, true));
        iButtonPanel.addOkActionListener(e -> setModalResult(JOptionPane.OK_OPTION, true));

	getRootPane().setDefaultButton(iButtonPanel.getOkButton());

        iSort.setModel(new DefaultComboBoxModel<>(SortingMode.values()));
        iSort.setSelectedItem(SortingMode.Product);

        ButtonGroup iGroup = new ButtonGroup();

        iGroup.add(iSortAscending);
        iGroup.add(iSortDescending);

    }

    /**
     *
     * @return
     */
    public JPanel getPanel() {
        return iPanel;
    }

    /**
     *
     * @return
     */
    public SortingMode getSortingMode() {
        return (SortingMode) iSort.getSelectedItem();
    }

    /**
     *
     * @return
     */
    public boolean getAscending() {
        return iSortAscending.isSelected();
    }

    /**
     *
     * @return
     */
    public LocalDate getFrom() {
        return iFromDate.getLocalDate();
    }

    /**
     *
     * @return
     */
    public LocalDate getTo() {
        return iToDate.getLocalDate();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.dialog.SSSaleReportDialog");
        sb.append("{iButtonPanel=").append(iButtonPanel);
        sb.append(", iFromDate=").append(iFromDate);
        sb.append(", iPanel=").append(iPanel);
        sb.append(", iSort=").append(iSort);
        sb.append(", iSortAscending=").append(iSortAscending);
        sb.append(", iSortDescending=").append(iSortDescending);
        sb.append(", iToDate=").append(iToDate);
        sb.append('}');
        return sb.toString();
    }
}
