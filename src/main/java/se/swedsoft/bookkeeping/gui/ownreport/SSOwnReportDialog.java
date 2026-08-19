package se.swedsoft.bookkeeping.gui.ownreport;


import se.swedsoft.bookkeeping.data.SSOwnReport;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.gui.ownreport.panel.SSOwnReportPanel;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSDialog;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSQueryDialog;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ResourceBundle;


/**
 * User: Johan Gunnarsson
 * Date: 2007-nov-23
 * Time: 14:13:37
 */
public class SSOwnReportDialog {

    private static ResourceBundle bundle = SSBundle.getBundle();

    private SSOwnReportDialog() {}

    public static void newDialog(final SSMainFrame iMainFrame) {
        final SSDialog iDialog = new SSDialog(iMainFrame,
                bundle.getString("ownreportframe.new.title"));
        final SSOwnReportPanel iPanel = new SSOwnReportPanel(new SSOwnReport());

        iDialog.add(iPanel.getPanel());

        final ActionListener iSaveAction = e -> {

                if (!iPanel.isValid()) {
                    return;
                }
                SSOwnReport iOwnReport = iPanel.getOwnReport();

                SSDB.getInstance().addOwnReport(iOwnReport);

                iDialog.setVisible(false);
                iDialog.dispose();

            };

        iPanel.addOkAction(iSaveAction);

        final ActionListener iCancelAction = e -> {

                iDialog.setVisible(false);
                iDialog.dispose();

            };

        iPanel.addCancelAction(iCancelAction);

        iDialog.addWindowListener(
                new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!iPanel.isValid()) {
                    return;
                }

                if (SSQueryDialog.showDialog(iMainFrame, SSBundle.getBundle(),
                        "ownreportframe.saveonclose")
                        != JOptionPane.OK_OPTION) {
                    return;
                }

                iSaveAction.actionPerformed(null);
            }
        });
        iDialog.setSize(640, 480);
        iDialog.setLocationRelativeTo(iMainFrame);
        iDialog.setVisible();
    }

    public static void editDialog(final SSMainFrame iMainFrame, SSOwnReport pOwnReport) {
        final SSDialog iDialog = new SSDialog(iMainFrame,
                bundle.getString("ownreportframe.edit.title"));
        final SSOwnReportPanel iPanel = new SSOwnReportPanel(pOwnReport);

        iDialog.add(iPanel.getPanel());

        final ActionListener iSaveAction = e -> {

                if (!iPanel.isValid()) {
                    return;
                }
                SSOwnReport iOwnReport = iPanel.getOwnReport();

                SSDB.getInstance().updateOwnReport(iOwnReport);

                iDialog.setVisible(false);
                iDialog.dispose();

            };

        iPanel.addOkAction(iSaveAction);

        final ActionListener iCancelAction = e -> {

                iDialog.setVisible(false);
                iDialog.dispose();

            };

        iPanel.addCancelAction(iCancelAction);

        iDialog.addWindowListener(
                new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!iPanel.isValid()) {
                    return;
                }

                if (SSQueryDialog.showDialog(iMainFrame, SSBundle.getBundle(),
                        "ownreportframe.saveonclose")
                        != JOptionPane.OK_OPTION) {
                    return;
                }

                iSaveAction.actionPerformed(null);
            }
        });
        iDialog.setSize(640, 480);
        iDialog.setLocationRelativeTo(iMainFrame);
        iDialog.setVisible();
    }
}
