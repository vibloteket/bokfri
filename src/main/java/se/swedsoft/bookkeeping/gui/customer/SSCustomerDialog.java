package se.swedsoft.bookkeeping.gui.customer;


import org.fribok.bookkeeping.service.customer.CustomerValidationException;
import org.fribok.bookkeeping.service.customer.CustomerService;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.gui.customer.panel.SSCustomerPanel;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSDialog;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSQueryDialog;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSValidationDialog;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ResourceBundle;


/**
 * User: Andreas Lago
 * Date: 2006-sep-05
 * Time: 13:42:44
 */
public class SSCustomerDialog {

    private static ResourceBundle bundle = SSBundle.getBundle();

    private static Dimension iDialogSize = new Dimension(640, 480);

    private SSCustomerDialog() {}

    /**
     *
     * @param iMainFrame
     * @param pModel
     */
    public static void newDialog(final SSMainFrame iMainFrame, final AbstractTableModel pModel) {
        final SSDialog        iDialog = new SSDialog(iMainFrame,
                bundle.getString("customerframe.new.title"));
        final SSCustomerPanel iPanel = new SSCustomerPanel(iDialog, false);

        iPanel.setCustomer(new SSCustomer());

        iDialog.add(iPanel.getPanel(), BorderLayout.CENTER);

        final ActionListener iSaveAction = e -> {

                if (!iPanel.isValid()) {
                    return;
                }
                SSCustomer iCustomer = iPanel.getCustomer();

                try {
                    new CustomerService(SSDB.getInstance()).create(iCustomer);
                } catch (CustomerValidationException exception) {
                    SSValidationDialog.showIfInvalid(iDialog, "Kunden",
                            exception.getResult().issues().stream()
                                    .map(issue -> issue.message()).toList());
                    return;
                }

                if (pModel != null) {
                    pModel.fireTableDataChanged();
                }

                iDialog.closeDialog();

            };

        iPanel.addOkAction(iSaveAction);

        iPanel.addCancelAction(e -> iDialog.closeDialog());
        iDialog.addWindowListener(
                new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!iPanel.isValid()) {
                    return;
                }

                if (SSQueryDialog.showDialog(iMainFrame, SSBundle.getBundle(),
                        "customerframe.saveonclose")
                        != JOptionPane.OK_OPTION) {
                    return;
                }

                iSaveAction.actionPerformed(null);
            }
        });
        iDialog.setSize(iDialogSize);
        iDialog.setLocationRelativeTo(iMainFrame);
        iDialog.setVisible();
    }

    /**
     *
     * @param iMainFrame
     * @param iCustomer
     * @param pModel
     */
    public static void editDialog(final SSMainFrame iMainFrame, SSCustomer iCustomer, final AbstractTableModel pModel) {
        final SSDialog        iDialog = new SSDialog(iMainFrame,
                bundle.getString("customerframe.edit.title"));
        final SSCustomerPanel iPanel = new SSCustomerPanel(iDialog, true);

        iPanel.setCustomer(iCustomer);
        // iPanel.setEditPanel(true);
        iDialog.add(iPanel.getPanel(), BorderLayout.CENTER);

        final ActionListener iSaveAction = e -> {

                if (!iPanel.isValid()) {
                    return;
                }
                SSCustomer iCustomer1 = iPanel.getCustomer();

                SSDB.getInstance().updateCustomer(iCustomer1);

                if (pModel != null) {
                    pModel.fireTableDataChanged();
                }
                iDialog.closeDialog();

            };

        iPanel.addOkAction(iSaveAction);

        iPanel.addCancelAction(e -> {

                iDialog.closeDialog();

            });
        iDialog.addWindowListener(
                new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!iPanel.isValid()) {
                    return;
                }

                if (SSQueryDialog.showDialog(iMainFrame, SSBundle.getBundle(),
                        "customerframe.saveonclose")
                        != JOptionPane.OK_OPTION) {
                    return;
                }

                iSaveAction.actionPerformed(null);
            }
        });
        iDialog.setSize(iDialogSize);
        iDialog.setLocationRelativeTo(iMainFrame);
        iDialog.setVisible();
    }

    /**
     *
     * @param iMainFrame
     * @param iCustomer
     * @param pModel
     */
    public static void copyDialog(final SSMainFrame iMainFrame, SSCustomer iCustomer, final AbstractTableModel pModel) {
        final SSDialog        iDialog = new SSDialog(iMainFrame,
                bundle.getString("customerframe.copy.title"));
        final SSCustomerPanel iPanel = new SSCustomerPanel(iDialog, false);

        SSCustomer iNew = new SSCustomer(iCustomer);

        iPanel.setCustomer(iNew);

        iDialog.add(iPanel.getPanel(), BorderLayout.CENTER);

        final ActionListener iSaveAction = e -> {

                if (!iPanel.isValid()) {
                    return;
                }
                SSCustomer iCustomer1 = iPanel.getCustomer();

                try {
                    new CustomerService(SSDB.getInstance()).create(iCustomer1);
                } catch (CustomerValidationException exception) {
                    SSValidationDialog.showIfInvalid(iDialog, "Kunden",
                            exception.getResult().issues().stream()
                                    .map(issue -> issue.message()).toList());
                    return;
                }

                if (pModel != null) {
                    pModel.fireTableDataChanged();
                }

                iDialog.closeDialog();

            };

        iPanel.addOkAction(iSaveAction);

        iPanel.addCancelAction(e -> iDialog.closeDialog());
        iDialog.addWindowListener(
                new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!iPanel.isValid()) {
                    return;
                }

                if (SSQueryDialog.showDialog(iMainFrame, SSBundle.getBundle(),
                        "customerframe.saveonclose")
                        != JOptionPane.OK_OPTION) {
                    return;
                }

                iSaveAction.actionPerformed(null);
            }
        });
        iDialog.setSize(iDialogSize);
        iDialog.setLocationRelativeTo(iMainFrame);
        iDialog.setVisible();
    }
}
