package se.swedsoft.bookkeeping.print.dialog;


import se.swedsoft.bookkeeping.calc.math.SSAccountMath;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.SSButtonPanel;
import se.swedsoft.bookkeeping.gui.util.SSSelectionListener;
import se.swedsoft.bookkeeping.gui.util.components.SSTableComboBox;
import se.swedsoft.bookkeeping.gui.util.datechooser.SSDateChooser;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSDialog;
import se.swedsoft.bookkeeping.gui.util.model.SSAccountTableModel;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * $Id$
 *
 */
public class SSVATReportDialog extends SSDialog {    private static final Logger LOG = LoggerFactory.getLogger(SSVATReportDialog.class);


    private JPanel iPanel;

    private SSButtonPanel iButtonPanel;

    private SSDateChooser iTo;

    private SSDateChooser iFrom;

    private SSTableComboBox<SSAccount> iAccountA;
    private SSTableComboBox<SSAccount> iAccountR1;
    private SSTableComboBox<SSAccount> iAccountR2;

    private JLabel startVoucherLabel;
    private JTextField txtStartVoucher;
    private JTextField txtAccountR2;
    private JTextField txtAccountR1;
    private JTextField txtAccountA;

    /**
     *
     * @param iMainFrame
     */
    public SSVATReportDialog(SSMainFrame iMainFrame) {
        super(iMainFrame, SSBundle.getBundle().getString("vatreport2007.dialog.title"));

        setPanel(iPanel);

        setStartVoucher(1);

        iAccountA.setModel(SSAccountTableModel.getDropDownModel());
        iAccountA.setSearchColumns(0);
        iAccountA.setAllowCustomValues(false);

        iAccountR1.setModel(SSAccountTableModel.getDropDownModel());
        iAccountR1.setSearchColumns(0);
        iAccountR1.setAllowCustomValues(false);

        iAccountR2.setModel(SSAccountTableModel.getDropDownModel());
        iAccountR2.setSearchColumns(0);
        iAccountR2.setAllowCustomValues(false);

        iAccountR1.addSelectionListener(new SSSelectionListener<>() {
            public void selected(SSAccount selected) {
                txtAccountR1.setText(selected == null ? "" : selected.getDescription());
            }
        });
        iAccountR2.addSelectionListener(new SSSelectionListener<>() {
            public void selected(SSAccount selected) {
                txtAccountR2.setText(selected == null ? "" : selected.getDescription());
            }
        });
        iAccountA.addSelectionListener(new SSSelectionListener<>() {
            public void selected(SSAccount selected) {
                txtAccountA.setText(selected == null ? "" : selected.getDescription());
            }
        });

        iAccountR1.setSelected(getSettlementAccount(1650, "R1"), true);
        iAccountR2.setSelected(getSettlementAccount(2650, "R2"), true);
        iAccountA.setSelected(getSettlementAccount(3740, "A"), true);

        iButtonPanel.addCancelActionListener(e -> setModalResult(JOptionPane.CANCEL_OPTION, true));
        iButtonPanel.addOkActionListener(e -> setModalResult(JOptionPane.OK_OPTION, true));

	getRootPane().setDefaultButton(iButtonPanel.getOkButton());

        SSNewCompany iCurrentCompany = SSDB.getInstance().getCurrentCompany();

        LocalDate now = LocalDate.now();
        int vatMonths = (iCurrentCompany.getVatPeriod() != null
                && iCurrentCompany.getVatPeriod() != 0)
                ? iCurrentCompany.getVatPeriod()
                : 1;
        LocalDate fromDate = now.minusMonths(vatMonths)
                .with(TemporalAdjusters.firstDayOfMonth());
        iFrom.setLocalDate(fromDate);

        LocalDate toDate = now.minusMonths(1)
                .with(TemporalAdjusters.lastDayOfMonth());
        iTo.setLocalDate(toDate);

    }

    private SSAccount getSettlementAccount(int accountNumber, String vatCode) {
        SSAccount standardAccount = SSDB.getInstance().getCurrentYear()
                .getAccountPlan().getAccount(accountNumber);
        if (standardAccount != null) {
            return standardAccount;
        }
        return SSAccountMath.getAccountWithVATCode(SSDB.getInstance().getAccounts(), vatCode,
                null).orElse(null);
    }

    /**
     *
     * @return
     */
    public LocalDate getTo() {
        return iTo.getLocalDate();
    }

    /**
     *
     * @param to
     */
    public void setTo(LocalDate to) {
        iTo.setLocalDate(to);
    }

    /**
     *
     * @return
     */
    public LocalDate getFrom() {
        return iFrom.getLocalDate();
    }

    /**
     *
     * @param from
     */
    public void setFrom(LocalDate from) {
        iFrom.setLocalDate(from);
    }

    /**
     *
     * @return
     */
    public int getStartVoucher() {
	int startVoucher = 1;
        try {
            startVoucher = Integer.parseInt(txtStartVoucher.getText());
        } catch (NumberFormatException e) {
	    LOG.error("Kunde inte hantera som siffra: " + txtStartVoucher.getText());
        }
        return startVoucher;
    }

    /**
     *
     * @param from
     */
    public void setStartVoucher(final int startVoucher) {
        txtStartVoucher.setText(Integer.toString(startVoucher));
    }

    /**
     *
     * @return
     */
    public SSAccount getAccountR1() {
        return iAccountR1.getSelected();
    }

    /**
     *
     * @return
     */
    public SSAccount getAccountR2() {
        return iAccountR2.getSelected();
    }

    /**
     *
     * @return
     */
    public SSAccount getAccountA() {
        return iAccountA.getSelected();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.dialog.SSVATReportDialog");
        sb.append("{iAccountA=").append(iAccountA);
        sb.append(", iAccountR1=").append(iAccountR1);
        sb.append(", iAccountR2=").append(iAccountR2);
        sb.append(", iButtonPanel=").append(iButtonPanel);
        sb.append(", iFrom=").append(iFrom);
        sb.append(", iPanel=").append(iPanel);
        sb.append(", iTo=").append(iTo);
        sb.append(", txtStartVoucher=").append(txtStartVoucher);
        sb.append(", txtAccountA=").append(txtAccountA);
        sb.append(", txtAccountR1=").append(txtAccountR1);
        sb.append(", txtAccountR2=").append(txtAccountR2);
        sb.append('}');
        return sb.toString();
    }
}
