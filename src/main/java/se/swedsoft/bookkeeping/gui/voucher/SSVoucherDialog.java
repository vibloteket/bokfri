package se.swedsoft.bookkeeping.gui.voucher;


import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.SSVoucherTemplate;
import se.swedsoft.bookkeeping.data.system.SSDB;
import org.fribok.bookkeeping.service.voucher.VoucherService;

import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSDialog;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSErrorDialog;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSInformationDialog;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSQueryDialog;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSValidationDialog;
import se.swedsoft.bookkeeping.gui.voucher.dialogs.SSAddAccountDialog;
import se.swedsoft.bookkeeping.gui.voucher.dialogs.SSCopyReversedVoucherDialog;
import se.swedsoft.bookkeeping.gui.voucher.panel.SSVoucherPanel;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * User: Andreas Lago
 * Date: 2006-nov-03
 * Time: 15:48:37
 */
public class SSVoucherDialog {    private static final Logger LOG = LoggerFactory.getLogger(SSVoucherDialog.class);

    private SSVoucherDialog() {}

    private static boolean isValid(SSDialog dialog, SSVoucher voucher) {
        var validation = new VoucherService(SSDB.getInstance()).validate(voucher);
        return SSValidationDialog.showIfInvalid(dialog, "Verifikationen",
                validation.issues().stream().map(issue -> SSValidationDialog.formatIssue(issue.row(), issue.message())).toList());
    }

    private static void storeVoucherTemplate(SSMainFrame iMainFrame, SSVoucher iVoucher) {
        SSVoucherTemplate template = new SSVoucherTemplate(iVoucher);
        boolean templateExists = SSDB.getInstance().getVoucherTemplates().stream()
                .anyMatch(existing -> template.getDescription().equals(existing.getDescription()));

        if (templateExists
                && SSQueryDialog.showDialog(iMainFrame, SSBundle.getBundle(),
                "voucherframe.replacetemplate", template.getDescription())
                != JOptionPane.YES_OPTION) {
            return;
        }

        SSDB.getInstance().addVoucherTemplate(template);
    }

    /**
     *
     * @param iMainFrame
     * @param pModel
     */
    public static void newDialog(final SSMainFrame iMainFrame, final AbstractTableModel pModel) {
        final SSDialog       iDialog = new SSDialog(iMainFrame,
                SSBundle.getBundle().getString("voucherframe.new.title"));
        final SSVoucherPanel iPanel = new SSVoucherPanel(iDialog);

        // iPanel.setModel( new SSVoucherRowTableModelOld( false, false ));
        iPanel.setVoucher(new SSVoucher(), false, false);
        iPanel.setMarkRowButtonVisible(false);
        iPanel.setDeleteRowButtonVisible(true);

        iDialog.add(iPanel.getPanel(), BorderLayout.CENTER);

        iPanel.addOkAction(
                e -> {

                        SSVoucher iVoucher = iPanel.getVoucher();

                if (!isValid(iDialog, iVoucher)) {
                    return;
                }

                        if (!iPanel.getDate().isInCurrentAccountYear()) {
                            new SSErrorDialog(new JFrame(), "voucherpanel.badyear");
                            iPanel.setVoucher(iVoucher, false, false);
                            return;
                        }

                        new VoucherService(SSDB.getInstance()).create(iVoucher);

                        if (iPanel.isStoreAsTemplate()) {
                            storeVoucherTemplate(iMainFrame, iVoucher);
                        }

                        if (pModel != null) {
                            pModel.fireTableDataChanged();
                        }

                        if (iPanel.doReopen()) {
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException e1) {
                                LOG.error("Unexpected error", e1);
                            }
                            iPanel.setVoucher(new SSVoucher(), false, false);
                            return;
                        }
                        iDialog.closeDialog();

                    });

        iPanel.addCancelAction(e -> {

                iDialog.closeDialog();

            });

        iPanel.addAddAccountAction(e -> {

                SSAddAccountDialog.showDialog(iMainFrame);
                iPanel.updateAccounts();

            });

        iDialog.addWindowListener(
                new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (SSQueryDialog.showDialog(iMainFrame, SSBundle.getBundle(),
                        "voucherframe.saveonclose")
                        != JOptionPane.OK_OPTION) {
                    return;
                }
                if (!iPanel.isValid()) {
                    return;
                }
                if (!iPanel.getDate().isInCurrentAccountYear()) {
                    new SSErrorDialog(new JFrame(), "voucherpanel.badyear");
                    return;
                }
                SSVoucher iVoucher = iPanel.getVoucher();

                if (!isValid(iDialog, iVoucher)) {
                    return;
                }

                new VoucherService(SSDB.getInstance()).create(iVoucher);

                if (pModel != null) {
                    pModel.fireTableDataChanged();
                }
            }
        });
        iDialog.setSize(800, 600);
        iDialog.setLocationRelativeTo(iMainFrame);
        iDialog.showDialog();
    }

    /**
     *
     * @param iMainFrame
     * @param iVoucher
     * @param pModel
     */
    public static void editDialog(final SSMainFrame iMainFrame, SSVoucher iVoucher, final AbstractTableModel pModel) {
        final SSDialog       iDialog = new SSDialog(iMainFrame,
                SSBundle.getBundle().getString("voucherframe.edit.title"));
        final SSVoucherPanel iPanel = new SSVoucherPanel(iDialog);

        // iPanel.setModel( new SSVoucherRowTableModelOld( true, false ));
        iPanel.setMarkRowButtonVisible(true);
        iPanel.setDeleteRowButtonVisible(true);
        iPanel.setVoucher(new SSVoucher(iVoucher), true, false);

        iDialog.add(iPanel.getPanel(), BorderLayout.CENTER);

        iPanel.addOkAction(
                e -> {

                        SSVoucher iVoucher1 = iPanel.getVoucher();

                        if (!isValid(iDialog, iVoucher1)) {
                            return;
                        }
                        if (!iPanel.getDate().isInCurrentAccountYear()) {
                            new SSErrorDialog(new JFrame(), "voucherpanel.badyear");
                            iPanel.setVoucher(iVoucher1, true, true);
                            return;
                        }

                        SSDB.getInstance().updateVoucher(iVoucher1);

                        if (iPanel.isStoreAsTemplate()) {
                            storeVoucherTemplate(iMainFrame, iVoucher1);
                        }

                        if (pModel != null) {
                            pModel.fireTableDataChanged();
                        }
                        iDialog.closeDialog();

                        if (iPanel.doReopen()) {
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException e1) {
                                LOG.error("Unexpected error", e1);
                            }
                            newDialog(iMainFrame, pModel);
                        }

                    });

        iPanel.addCancelAction(e -> {

                iDialog.closeDialog();

            });

        iPanel.addAddAccountAction(e -> {

                SSAddAccountDialog.showDialog(iMainFrame);
                iPanel.updateAccounts();

            });

        iDialog.addWindowListener(
                new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!iPanel.isValid()
                        || SSQueryDialog.showDialog(iMainFrame, SSBundle.getBundle(),
                        "voucherframe.saveonclose")
                                != JOptionPane.OK_OPTION) {
                    return;
                }
                if (!iPanel.getDate().isInCurrentAccountYear()) {
                    new SSErrorDialog(new JFrame(), "voucherpanel.badyear");
                    return;
                }
                SSVoucher iVoucher = iPanel.getVoucher();

                if (!isValid(iDialog, iVoucher)) {
                    return;
                }

                SSDB.getInstance().updateVoucher(iVoucher);

            }
        });
        iDialog.setSize(800, 600);
        iDialog.setLocationRelativeTo(iMainFrame);
        iDialog.setVisible();
    }

    /**
     *
     * @param iMainFrame
     * @param iVoucher
     * @param pModel
     */
    public static void copyDialog(final SSMainFrame iMainFrame, SSVoucher iVoucher, final AbstractTableModel pModel) {
        final SSDialog       iDialog = new SSDialog(iMainFrame,
                SSBundle.getBundle().getString("voucherframe.copy.title"));
        final SSVoucherPanel iPanel = new SSVoucherPanel(iDialog);

        if (iVoucher.getCorrectedBy() != null) {
            SSInformationDialog.showDialog(iMainFrame, "voucherframe.alreadyedited",
                    iVoucher.getNumber());
            return;
        }
        Boolean iCopyReverse = SSCopyReversedVoucherDialog.showDialog(iMainFrame, iVoucher);

        if (iCopyReverse == null) {
            return;
        }

        SSVoucher iNew = new SSVoucher(iVoucher);

        iNew.doAutoIncrecement();
        if (iCopyReverse) {
            SSVoucherMath.copyRows(iVoucher, iNew, iCopyReverse);
        } else {
            iNew.setVoucherRows(new LinkedList<>());
        }

        iNew.setLocalDate(SSVoucherMath.getNextVoucherLocalDate());
        iNew.setDescription(
                String.format(
                        SSBundle.getBundle().getString("voucherframe.correctsdescription"),
                        iVoucher.getNumber(), iVoucher.getDescription()));
        iNew.setCorrects(iVoucher);

        // iPanel.setModel( new SSVoucherRowTableModelOld( false, false ));
        iPanel.setMarkRowButtonVisible(false);
        iPanel.setDeleteRowButtonVisible(true);
        iPanel.setVoucher(iNew, false, false);

        final SSVoucher iOriginal = iVoucher;

        iDialog.add(iPanel.getPanel(), BorderLayout.CENTER);

        iPanel.addOkAction(
                e -> {

                        SSVoucher iVoucher1 = iPanel.getVoucher();

                        if (!isValid(iDialog, iVoucher1)) {
                            return;
                        }
                        if (!iPanel.getDate().isInCurrentAccountYear()) {
                            new SSErrorDialog(new JFrame(), "voucherpanel.badyear");
                            iPanel.setVoucher(iVoucher1, false, false);
                            return;
                        }

                        new VoucherService(SSDB.getInstance()).create(iVoucher1);

                        if (iPanel.isStoreAsTemplate()) {
                            storeVoucherTemplate(iMainFrame, iVoucher1);
                        }

                        iOriginal.setCorrectedBy(iVoucher1);

                        SSDB.getInstance().updateVoucher(iOriginal);

                        if (pModel != null) {
                            pModel.fireTableDataChanged();
                        }
                        iDialog.closeDialog();

                        if (iPanel.doReopen()) {
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException e1) {
                                LOG.error("Unexpected error", e1);
                            }
                            newDialog(iMainFrame, pModel);
                        }

                    });

        iPanel.addCancelAction(e -> {

                iDialog.closeDialog();

            });

        iPanel.addAddAccountAction(e -> {

                SSAddAccountDialog.showDialog(iMainFrame);
                iPanel.updateAccounts();

            });

        iDialog.addWindowListener(
                new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!iPanel.isValid()
                        || SSQueryDialog.showDialog(iMainFrame, SSBundle.getBundle(),
                        "voucherframe.saveonclose")
                                != JOptionPane.OK_OPTION) {
                    return;
                }

                if (!iPanel.getDate().isInCurrentAccountYear()) {
                    new SSErrorDialog(new JFrame(), "voucherpanel.badyear");
                    return;
                }
                SSVoucher iVoucher = iPanel.getVoucher();

                if (!isValid(iDialog, iVoucher)) {
                    return;
                }

                new VoucherService(SSDB.getInstance()).create(iVoucher);

                iOriginal.setCorrectedBy(iVoucher);
                SSDB.getInstance().updateVoucher(iVoucher);

                if (pModel != null) {
                    pModel.fireTableDataChanged();
                }
            }
        });
        iDialog.setSize(800, 600);
        iDialog.setLocationRelativeTo(iMainFrame);
        iDialog.setVisible();
    }
}
