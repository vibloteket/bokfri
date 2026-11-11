package se.swedsoft.bookkeeping.gui.creditinvoice;


import org.fribok.bookkeeping.gui.creditinvoice.CustomerCreditDialogAdapter;
import se.swedsoft.bookkeeping.gui.creditinvoice.panel.NormalizedCreditRequestPanel;
import se.swedsoft.bookkeeping.calc.math.SSOrderMath;
import se.swedsoft.bookkeeping.calc.math.SSTenderMath;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.gui.creditinvoice.dialog.SSSelectInvoiceDialog;
import se.swedsoft.bookkeeping.gui.creditinvoice.panel.SSCreditInvoicePanel;
import se.swedsoft.bookkeeping.gui.invoice.SSInvoiceFrame;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSDialog;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSErrorDialog;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSQueryDialog;
import se.swedsoft.bookkeeping.util.SSDateUtil;

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
 * Date: 2006-okt-10
 * Time: 13:22:12
 */
public class SSCreditInvoiceDialog {

    private static ResourceBundle bundle = SSBundle.getBundle();

    private SSCreditInvoiceDialog() {}

    /**
     * Builds (but does not show) the normalized request dialog. The composition root supplies
     * dedicated sessions, selection, a shared transaction gate and committed refresh callback.
     * Does not change the format-3 startup guard or the legacy dialog path.
     */
    public static javax.swing.JDialog normalizedDialog(javax.swing.JFrame owner,
            CustomerCreditDialogAdapter adapter,
            java.util.function.Consumer<CustomerCreditDialogAdapter.Created> saved) {
        javax.swing.JDialog dialog = new javax.swing.JDialog(
                owner, bundle.getString("creditinvoice.normalized.title"), true);
        var panel = new NormalizedCreditRequestPanel(
                adapter, saved, dialog::dispose);
        dialog.add(panel, BorderLayout.CENTER);
        dialog.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { if (!panel.isBusy()) dialog.dispose(); }
        });
        dialog.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "credit-cancel");
        dialog.getRootPane().getActionMap().put("credit-cancel", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!panel.isBusy()) dialog.dispose();
            }
        });
        dialog.pack(); dialog.setLocationRelativeTo(owner);
        return dialog;
    }

    /**
     *
     * @param iMainFrame
     * @param iInvoice
     * @param pModel
     */
    public static void editDialog(final SSMainFrame iMainFrame, SSCreditInvoice iInvoice, final AbstractTableModel pModel) {
        final SSDialog             iDialog = new SSDialog(iMainFrame,
                bundle.getString("creditinvoiceframe.edit.title"));
        final SSCreditInvoicePanel iPanel = new SSCreditInvoicePanel(iDialog);

        iPanel.setCreditInvoice(new SSCreditInvoice(iInvoice));
        iPanel.setSavecustomerandproductsSelected(false);

        iDialog.add(iPanel.getPanel(), BorderLayout.CENTER);

        final ActionListener iSaveAction = e -> {

                if (!iPanel.isValid()) {
                    return;
                }
                SSCreditInvoice iInvoice1 = iPanel.getCreditInvoice();

                SSDB.getInstance().updateCreditInvoice(iInvoice1);

                if (iPanel.doSaveCustomerAndProducts()) {
                    SSOrderMath.addCustomerAndProducts(iInvoice1);
                }

                if (pModel != null) {
                    pModel.fireTableDataChanged();
                }
                if (SSInvoiceFrame.getInstance() != null) {
                    SSInvoiceFrame.getInstance().updateFrame();
                }
                iPanel.dispose();
                iDialog.closeDialog();

            };

        iPanel.addOkAction(iSaveAction);

        iPanel.addCancelAction(e -> {

                iPanel.dispose();
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
                        "creditinvoiceframe.saveonclose")
                        != JOptionPane.OK_OPTION) {
                    return;
                }

                iSaveAction.actionPerformed(null);
            }
        });

        iDialog.setSize(800, 600);
        iDialog.setLocationRelativeTo(iMainFrame);
        iDialog.setVisible();
    }

    /**
     *
     * @param iMainFrame
     * @param pModel
     */
    public static void newDialog(final SSMainFrame iMainFrame, final AbstractTableModel pModel) {
        // Get the sales to credit

        SSInvoice iCrediting = SSSelectInvoiceDialog.showDialog(iMainFrame);

        if (iCrediting == null) {
            new SSErrorDialog(iMainFrame, "creditinvoiceframe.creditinvoicenoinvoice");
            return;
        } else if (iCrediting.getNumber() == -1) {
            return;
        }

        newDialog(iMainFrame, iCrediting, pModel);
    }

    /**
     *
     * @param iMainFrame
     * @param iCrediting
     * @param pModel
     */
    public static void newDialog(final SSMainFrame iMainFrame, SSInvoice iCrediting, final AbstractTableModel pModel) {
        final SSDialog             iDialog = new SSDialog(iMainFrame,
                bundle.getString("creditinvoiceframe.new.title"));
        final SSCreditInvoicePanel iPanel = new SSCreditInvoicePanel(iDialog);
        SSCreditInvoice iCreditInvoice = new SSCreditInvoice(iCrediting);

        iCreditInvoice.setNumber(null);

        iPanel.setCreditInvoice(iCreditInvoice);

        iDialog.add(iPanel.getPanel(), BorderLayout.CENTER);

        final ActionListener iSaveAction = e -> {

                if (!iPanel.isValid()) {
                    return;
                }
                SSCreditInvoice iInvoice = iPanel.getCreditInvoice();

                SSDB.getInstance().addCreditInvoice(iInvoice);

                if (iPanel.doSaveCustomerAndProducts()) {
                    SSTenderMath.addCustomerAndProducts(iInvoice);
                }

                if (pModel != null) {
                    pModel.fireTableDataChanged();
                }
                if (SSInvoiceFrame.getInstance() != null) {
                    SSInvoiceFrame.getInstance().updateFrame();
                }
                iPanel.dispose();
                iDialog.closeDialog();

            };

        iPanel.addOkAction(iSaveAction);

        iPanel.addCancelAction(e -> {

                iPanel.dispose();
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
                        "creditinvoiceframe.saveonclose")
                        != JOptionPane.OK_OPTION) {
                    return;
                }

                iSaveAction.actionPerformed(null);
            }
        });

        iDialog.setSize(800, 600);
        iDialog.setLocationRelativeTo(iMainFrame);
        iDialog.setVisible();
    }

    /**
     *
     * @param iMainFrame
     * @param iCreditInvoice
     * @param pModel
     */
    public static void copyDialog(final SSMainFrame iMainFrame, SSCreditInvoice iCreditInvoice, final AbstractTableModel pModel) {
        final SSDialog             iDialog = new SSDialog(iMainFrame,
                bundle.getString("creditinvoiceframe.copy.title"));
        final SSCreditInvoicePanel iPanel = new SSCreditInvoicePanel(iDialog);

        SSCreditInvoice iNew = new SSCreditInvoice(iCreditInvoice);

        iNew.setLocalDate(SSDateUtil.today());
        iNew.setNumber(null);
        iNew.setEntered(false);
        iNew.setPrinted(false);
        if (iCreditInvoice.getCrediting() == null) {
            new SSErrorDialog(iMainFrame, "creditinvoiceframe.creditinvoicenoinvoice");
            return;
        }
        iPanel.setCreditInvoice(iNew);

        iDialog.add(iPanel.getPanel(), BorderLayout.CENTER);

        final ActionListener iSaveAction = e -> {

                if (!iPanel.isValid()) {
                    return;
                }
                SSCreditInvoice iInvoice = iPanel.getCreditInvoice();

                SSDB.getInstance().addCreditInvoice(iInvoice);

                if (iPanel.doSaveCustomerAndProducts()) {
                    SSTenderMath.addCustomerAndProducts(iInvoice);
                }

                if (pModel != null) {
                    pModel.fireTableDataChanged();
                }
                if (SSInvoiceFrame.getInstance() != null) {
                    SSInvoiceFrame.getInstance().updateFrame();
                }
                iPanel.dispose();
                iDialog.closeDialog();

            };

        iPanel.addOkAction(iSaveAction);

        iPanel.addCancelAction(e -> {

                iPanel.dispose();
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
                        "creditinvoiceframe.saveonclose")
                        != JOptionPane.OK_OPTION) {
                    return;
                }

                iSaveAction.actionPerformed(null);
            }
        });

        iDialog.setSize(800, 600);
        iDialog.setLocationRelativeTo(iMainFrame);
        iDialog.setVisible();
    }
}
