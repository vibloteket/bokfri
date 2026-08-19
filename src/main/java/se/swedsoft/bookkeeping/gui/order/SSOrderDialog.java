package se.swedsoft.bookkeeping.gui.order;


import se.swedsoft.bookkeeping.calc.math.SSOrderMath;
import se.swedsoft.bookkeeping.data.*;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.gui.invoice.SSInvoiceDialog;
import se.swedsoft.bookkeeping.gui.invoice.SSInvoiceFrame;
import se.swedsoft.bookkeeping.gui.order.panel.SSOrderPanel;
import se.swedsoft.bookkeeping.gui.order.util.SSOrderTableModel;
import se.swedsoft.bookkeeping.gui.periodicinvoice.SSPeriodicInvoiceDialog;
import se.swedsoft.bookkeeping.gui.periodicinvoice.SSPeriodicInvoiceFrame;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSDialog;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSErrorDialog;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSInformationDialog;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSQueryDialog;
import se.swedsoft.bookkeeping.util.SSDateUtil;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.ResourceBundle;


/**
 * User: Andreas Lago
 * Date: 2006-aug-03
 * Time: 17:16:18
 */
public class SSOrderDialog {

    private static ResourceBundle bundle = SSBundle.getBundle();

    private SSOrderDialog() {}

    /**
     *
     * @param iMainFrame
     * @param pModel
     */
    public static void newDialog(final SSMainFrame iMainFrame, final SSOrderTableModel pModel) {
        final SSDialog      iDialog = new SSDialog(iMainFrame,
                bundle.getString("orderframe.new.title"));
        final SSOrderPanel  iPanel = new SSOrderPanel(iDialog);

        iPanel.setOrder(new SSOrder());

        iDialog.add(iPanel.getPanel(), BorderLayout.CENTER);

        final ActionListener iSaveAction = e -> {

                if (!iPanel.isValid()) {
                    return;
                }
                SSOrder iOrder = iPanel.getOrder();

                SSDB.getInstance().addOrder(iOrder);

                if (iPanel.doSaveCustomerAndProducts()) {
                    SSOrderMath.addCustomerAndProducts(iOrder);
                }
                if (pModel != null) {
                    pModel.fireTableDataChanged();
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
                        "orderframe.saveonclose")
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
     * @param iTender
     * @param iModel
     */
    public static void newDialog(final SSMainFrame iMainFrame, final SSTender iTender, final AbstractTableModel iModel) {
        final SSDialog      iDialog = new SSDialog(iMainFrame,
                bundle.getString("orderframe.new.title"));
        final SSOrderPanel  iPanel = new SSOrderPanel(iDialog);
        SSOrder iOrder = new SSOrder(iTender);

        iOrder.setPrinted(false);
        iPanel.setOrder(iOrder);

        iDialog.add(iPanel.getPanel(), BorderLayout.CENTER);

        final ActionListener iSaveAction = e -> {

                if (!iPanel.isValid()) {
                    return;
                }
                SSOrder iOrder1 = iPanel.getOrder();

                SSDB.getInstance().addOrder(iOrder1);

                iTender.setOrder(iOrder1);
                SSDB.getInstance().updateTender(iTender);

                if (iPanel.doSaveCustomerAndProducts()) {
                    SSOrderMath.addCustomerAndProducts(iOrder1);
                }

                if (iModel != null) {
                    iModel.fireTableDataChanged();
                }
                // iModel.setObjects(SSDB.getInstance().getOrders());
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
                        "orderframe.saveonclose")
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
     * @param iOrder
     * @param pModel
     */
    public static void editDialog(final SSMainFrame iMainFrame, SSOrder iOrder, final SSOrderTableModel pModel) {
        final SSDialog     iDialog = new SSDialog(iMainFrame,
                bundle.getString("orderframe.edit.title"));
        final SSOrderPanel iPanel = new SSOrderPanel(iDialog);

        iPanel.setOrder(new SSOrder(iOrder));
        iPanel.setSavecustomerandproductsSelected(false);

        iDialog.add(iPanel.getPanel(), BorderLayout.CENTER);

        final ActionListener iSaveAction = e -> {

                if (!iPanel.isValid()) {
                    return;
                }
                SSOrder iOrder1 = iPanel.getOrder();

                SSDB.getInstance().updateOrder(iOrder1);

                if (iPanel.doSaveCustomerAndProducts()) {
                    SSOrderMath.addCustomerAndProducts(iOrder1);
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
                        "orderframe.saveonclose")
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
     * @param iOrder
     * @param pModel
     */
    public static void copyDialog(final SSMainFrame iMainFrame, SSOrder iOrder, final SSOrderTableModel pModel) {
        final SSDialog     iDialog = new SSDialog(iMainFrame,
                bundle.getString("orderframe.copy.title"));
        final SSOrderPanel iPanel = new SSOrderPanel(iDialog);
        SSOrder iNew = new SSOrder(iOrder);

        iNew.setNumber(null);
        iNew.setLocalDate(SSDateUtil.today());
        iNew.setInvoice(null);
        iNew.setPurchaseOrder(null);
        iNew.setPrinted(false);

        iPanel.setOrder(iNew);

        iDialog.add(iPanel.getPanel(), BorderLayout.CENTER);

        final ActionListener iSaveAction = e -> {

                if (!iPanel.isValid()) {
                    return;
                }
                SSOrder iOrder1 = iPanel.getOrder();

                iOrder1.doAutoIncrecement();

                SSDB.getInstance().addOrder(iOrder1);

                if (iPanel.doSaveCustomerAndProducts()) {
                    SSOrderMath.addCustomerAndProducts(iOrder1);
                }

                if (pModel != null) {
                    pModel.fireTableDataChanged();
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
                        "orderframe.saveonclose")
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
     * @param iOrders
     * @param iModel
     */
    public static void invoiceDialog(final SSMainFrame iMainFrame, List<SSOrder> iOrders, final SSOrderTableModel iModel) {
        // Get the supplier from the first selected purchase order
        SSCustomer iCustomer = iOrders.get(0).getCustomer();

        // We have selected more then one purchase order, do we want to create one supplierinvoice ?
        if (iOrders.size() > 1) {
            if (SSQueryDialog.showDialog(iMainFrame, SSBundle.getBundle(),
                    "orderframe.createcollectioninvoice", iCustomer.getNumber())
                    != JOptionPane.OK_OPTION) {
                return;
            }
            iOrders = SSOrderMath.getOrdersByCustomerNr(iOrders, iCustomer.getNumber());
        }
        // Create the new supplier invoice
        SSInvoice iInvoice = new SSInvoice(iOrders.get(0));

        // iInvoice.doAutoIncrecement();

        iInvoice.setLocalDate(SSDateUtil.today());
        iInvoice.setNumber(null);

        for (SSOrder iCurrent : iOrders) {

            // if the selected order already has a invoice assosiated we can't create a new one
            if (iCurrent.getInvoiceNr() != null || iCurrent.getPeriodicInvoiceNr() != null) {
                SSInformationDialog.showDialog(iMainFrame, "orderframe.orderhasinvoice",
                        iCurrent.getNumber());
                return;
            }
            // Append the rows from the order to the invoice
            iInvoice.append(iCurrent);
        }

        iInvoice.setOrderNumers(iOrders);

        if (SSInvoiceFrame.getInstance() != null) {
            SSInvoiceDialog.newDialog(iMainFrame, iInvoice, iOrders,
                    SSInvoiceFrame.getInstance().getModel());
        } else {
            SSInvoiceDialog.newDialog(iMainFrame, iInvoice, iOrders, null);
        }
        // if(iModel != null) iModel.fireTableDataChanged();
    }

    public static void cashReceiptDialog(final SSMainFrame iMainFrame, List<SSOrder> iOrders, final SSOrderTableModel iModel) {
        // Get the supplier from the first selected purchase order
        SSCustomer iCustomer = iOrders.get(0).getCustomer();

        // We have selected more then one purchase order, do we want to create one supplierinvoice ?
        if (iOrders.size() > 1) {
            if (SSQueryDialog.showDialog(iMainFrame, SSBundle.getBundle(),
                    "orderframe.createcollectioninvoice", iCustomer.getNumber())
                    != JOptionPane.OK_OPTION) {
                return;
            }
            iOrders = SSOrderMath.getOrdersByCustomerNr(iOrders, iCustomer.getNumber());
        }
        // Create the new supplier invoice
        SSInvoice iInvoice = new SSInvoice(iOrders.get(0));

        // iInvoice.doAutoIncrecement();

        iInvoice.setLocalDate(SSDateUtil.today());
        iInvoice.setNumber(null);
        iInvoice.setType(SSInvoiceType.CASH);

        for (SSOrder iCurrent : iOrders) {

            // if the selected order already has a invoice assosiated we can't create a new one
            if (iCurrent.getInvoiceNr() != null || iCurrent.getPeriodicInvoiceNr() != null) {
                SSInformationDialog.showDialog(iMainFrame, "orderframe.orderhasinvoice",
                        iCurrent.getNumber());
                return;
            }
            // Append the rows from the order to the invoice
            iInvoice.append(iCurrent);
        }

        iInvoice.setOrderNumers(iOrders);

        if (SSInvoiceFrame.getInstance() != null) {
            SSInvoiceDialog.newDialog(iMainFrame, iInvoice, iOrders,
                    SSInvoiceFrame.getInstance().getModel());
        } else {
            SSInvoiceDialog.newDialog(iMainFrame, iInvoice, iOrders, null);
        }
        // if(iModel != null) iModel.fireTableDataChanged();
    }

    public static void periodicInvoiceDialog(final SSMainFrame iMainFrame, List<SSOrder> iOrders, final SSOrderTableModel iModel) {
        // Get the supplier from the first selected purchase order
        SSCustomer iCustomer = iOrders.get(0).getCustomer();

        // We have selected more then one purchase order, do we want to create one supplierinvoice ?
        if (iOrders.size() > 1) {
            if (SSQueryDialog.showDialog(iMainFrame, SSBundle.getBundle(),
                    "orderframe.createcollectioninvoice", iCustomer.getNumber())
                    != JOptionPane.OK_OPTION) {
                return;
            }
            iOrders = SSOrderMath.getOrdersByCustomerNr(iOrders, iCustomer.getNumber());
        }
        // Create the new supplier invoice
        SSInvoice iInvoice = new SSInvoice(iOrders.get(0));

        // iInvoice.doAutoIncrecement();

        iInvoice.setLocalDate(SSDateUtil.today());
        iInvoice.setNumber(null);

        for (SSOrder iCurrent : iOrders) {

            // if the selected order already has a invoice assosiated we can't create a new one
            if (iCurrent.getInvoiceNr() != null || iCurrent.getPeriodicInvoiceNr() != null) {
                SSInformationDialog.showDialog(iMainFrame, "orderframe.orderhasinvoice",
                        iCurrent.getNumber());
                return;
            }
            // Append the rows from the order to the invoice
            iInvoice.append(iCurrent);
        }
        iInvoice.setOrderNumers(iOrders);

        iInvoice.setPaymentTerm(iOrders.get(0).getPaymentTerm());
        iInvoice.setDeliveryTerm(iOrders.get(0).getDeliveryTerm());
        iInvoice.setDeliveryWay(iOrders.get(0).getDeliveryWay());
        iInvoice.setCurrency(iOrders.get(0).getCurrency());
        iInvoice.setCurrencyRate(iOrders.get(0).getCurrencyRate());

        SSPeriodicInvoice iPeriodicInvoice = new SSPeriodicInvoice();

        iPeriodicInvoice.setTemplate(iInvoice);

        if (SSPeriodicInvoiceFrame.getInstance() != null) {
            SSPeriodicInvoiceDialog.newDialog(iMainFrame,
                    SSPeriodicInvoiceFrame.getInstance().getModel(), iPeriodicInvoice,
                    iOrders);
        } else {
            SSPeriodicInvoiceDialog.newDialog(iMainFrame, null, iPeriodicInvoice, iOrders);
        }
        // if(iModel != null) iModel.fireTableDataChanged();
    }

}
