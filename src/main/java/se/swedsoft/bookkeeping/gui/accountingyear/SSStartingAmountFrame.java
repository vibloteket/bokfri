/*
 * 2005-2010
 * $Id$
 */
package se.swedsoft.bookkeeping.gui.accountingyear;


import se.swedsoft.bookkeeping.calc.math.SSAccountMath;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.gui.accountingyear.panel.SSStartingAmountPanel;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.components.SSButton;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSErrorDialog;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSProgressDialog;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSQueryDialog;
import se.swedsoft.bookkeeping.gui.util.frame.SSDefaultTableFrame;
import org.fribok.bookkeeping.service.openingbalance.OpeningBalancePlan;
import org.fribok.bookkeeping.service.openingbalance.OpeningBalanceService;
import se.swedsoft.bookkeeping.print.report.SSStartingAmountPrinter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.math.BigDecimal;
import java.util.Map;


/**
 * Date: 2006-feb-15
 * Time: 11:17:40
 */
public class SSStartingAmountFrame extends SSDefaultTableFrame {

    private static SSStartingAmountFrame cInstance;

    /**
     *
     * @param pMainFrame
     * @param pWidth
     * @param pHeight
     */
    public static void showFrame(SSMainFrame pMainFrame, int pWidth, int pHeight) {
        if (cInstance == null || cInstance.isClosed()) {
            cInstance = new SSStartingAmountFrame(pMainFrame, pWidth, pHeight);
        }
        cInstance.setVisible(true);
        cInstance.deIconize();
    }

    /**
     *
     * @return The SSNewAccountingYearFrame
     */
    public static SSStartingAmountFrame getInstance() {
        return cInstance;
    }

    private SSStartingAmountPanel iStartingAmountPanel;

    private SSNewAccountingYear      iAccountingYear;

    /**
     * Constructor
     * @param pMainFrame
     * @param width
     * @param height
     */
    private SSStartingAmountFrame(SSMainFrame pMainFrame, int width, int height) {
        super(pMainFrame, SSBundle.getBundle().getString("startingammountframe.title"),
                width, height);

        iAccountingYear = SSDB.getInstance().getCurrentYear();

        iStartingAmountPanel.setInBalance(iAccountingYear.getInBalance(),
                SSAccountMath.getBalanceAccounts(iAccountingYear));
        addCloseListener(
                e -> {

                        if (cInstance != null) {
                            SSQueryDialog iDialog = new SSQueryDialog(getMainFrame(),
                                    SSBundle.getBundle(), "startingammountpanel.saveonclose");
                            int iResponce = iDialog.getResponce();

                            if (iResponce != JOptionPane.YES_OPTION) {
                                return;
                            }
                            if (!saveOpeningBalance()) {
                                return;
                            }
                        }

                    });
    }

    /**
     * This method should return a toolbar if the sub-class wants one.
     * Otherwise, it may return null.
     *
     * @return A JToolBar or null.
     */
    @Override
    public JToolBar getToolBar() {
        JToolBar toolBar = new JToolBar();

        // Save
        // ***************************
        SSButton iButton = new SSButton("ICON_SAVEITEM", "startingammountframe.savebutton",
                e -> {

                        if (!saveOpeningBalance()) {
                            return;
                        }
                        cInstance = null;
                        setVisible(false);

                    });

        toolBar.add(iButton);

        // Cancel
        // ***************************
        iButton = new SSButton("ICON_CANCELITEM", "startingammountframe.cancelbutton",
                e -> {

                        cInstance = null;
                        setVisible(false);

                    });
        toolBar.add(iButton);
        toolBar.addSeparator();

        /*
         // Import
         // ***************************
         iButton = new SSButton("ICON_IMPORT", "startingammountframe.importbutton", false, e -> {



             });
         toolBar.add(iButton);

         // Export
         // ***************************
         iButton = new SSButton("ICON_EXPORT", "startingammountframe.exportbutton", false, e -> {



             });
         toolBar.add(iButton);
         toolBar.addSeparator();
         */

        // Import from balance budget
        // ***************************
        iButton = new SSButton("ICON_REDO", "startingammountframe.importbalancebutton",
                e -> importFromLastYearBalanceReport());
        toolBar.add(iButton);
        toolBar.addSeparator();

        // Print
        // ***************************
        iButton = new SSButton("ICON_PRINT", "startingammountframe.printbutton",
                e -> printStartingAmmounts());
        toolBar.add(iButton);

        return toolBar;
    }

    /**
     * This method should return the main content for the frame.
     * Such as an object table.
     *
     * @return The main content for this frame.
     */
    @Override
    public JComponent getMainContent() {
        iStartingAmountPanel = new SSStartingAmountPanel();
        JPanel iPanel = new JPanel();

        iPanel.setLayout(new BorderLayout());
        iPanel.add(iStartingAmountPanel.getPanel(), BorderLayout.CENTER);
        iPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        return iPanel;
    }

    /**
     * This method should return the status bar content, if any.
     *
     * @return The content for the status bar or null if none is wanted.
     */
    @Override
    public JComponent getStatusBar() {
        return null;
    }

    /**
     * Indicates whether this frame is a company data related frame.
     *
     * @return A boolean value.
     */
    @Override
    public boolean isCompanyFrame() {
        return true;
    }

    /**
     * Indicates whether this frame is a year data related frame.
     *
     * @return A boolean value.
     */
    @Override
    public boolean isYearDataFrame() {
        return true;
    }

    private boolean saveOpeningBalance() {
        Map<Integer, BigDecimal> values = new java.util.LinkedHashMap<>();
        iStartingAmountPanel.getInBalance().forEach((account, amount) ->
                values.put(account.getNumber(), amount));
        try {
            new OpeningBalanceService(SSDB.getInstance()).replace(iAccountingYear, values);
            return true;
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(getMainFrame(), exception.getMessage(),
                    "Ingående balans", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /**
     *
     */
    private void printStartingAmmounts() {
        SSProgressDialog.runProgress(getMainFrame(),
                () -> {

                        SSNewAccountingYear iAccountingYear = SSDB.getInstance().getCurrentYear();

                        SSStartingAmountPrinter iPrinter = new SSStartingAmountPrinter(
                                iStartingAmountPanel.getInBalance(), iAccountingYear.getLocalFrom(),
                                iAccountingYear.getLocalTo());

                        iPrinter.preview(getMainFrame());

                    });
    }

    /**
     *
     */
    private void importFromLastYearBalanceReport() {
        SSNewAccountingYear iPreviousYear = SSDB.getInstance().getPreviousYear().orElse(null);

        // If nothing selected, return
        if (iPreviousYear == null) {
            new SSErrorDialog(getMainFrame(), "startingammountpanel.nopreviousyear");
            return;
        }
        SSQueryDialog iDialog = new SSQueryDialog(getMainFrame(), SSBundle.getBundle(),
                "startingammountpanel.importbalance");
        int iResponce = iDialog.getResponce();

        if (iResponce != JOptionPane.YES_OPTION) {
            return;
        }
        OpeningBalancePlan plan = new OpeningBalanceService(SSDB.getInstance())
                .carryForward(iPreviousYear, iAccountingYear, false);
        Map<SSAccount, BigDecimal> inBalance = new java.util.LinkedHashMap<>();
        for (var entry : plan.balances()) {
            inBalance.put(iAccountingYear.getAccountPlan().getAccount(entry.account()),
                    entry.amount());
        }
        if (plan.adjustment() != null) {
            int response = JOptionPane.showConfirmDialog(getMainFrame(),
                    "Avrundning till två decimaler kräver att konto "
                            + plan.adjustment().account() + " justeras med "
                            + plan.adjustment().amount().toPlainString()
                            + " kr för att ingående balans ska balansera. Fortsätt?",
                    "Överför ingående balans", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (response != JOptionPane.OK_OPTION) {
                return;
            }
        }
        iStartingAmountPanel.setInBalance(inBalance);
    }

    public void actionPerformed(ActionEvent e) {
        iStartingAmountPanel = null;
        iAccountingYear = null;
        cInstance = null;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.gui.accountingyear.SSStartingAmountFrame");
        sb.append("{iAccountingYear=").append(iAccountingYear);
        sb.append(", iStartingAmountPanel=").append(iStartingAmountPanel);
        sb.append('}');
        return sb.toString();
    }
}
