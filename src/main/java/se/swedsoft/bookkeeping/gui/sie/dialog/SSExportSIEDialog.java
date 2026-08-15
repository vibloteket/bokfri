package se.swedsoft.bookkeeping.gui.sie.dialog;


import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.gui.sie.panel.SSExportSIEPanel;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSDialog;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSErrorDialog;
import se.swedsoft.bookkeeping.gui.util.filechooser.SSSIEFileChooser;
import se.swedsoft.bookkeeping.importexport.sie.SSSIEExporter;
import se.swedsoft.bookkeeping.importexport.sie.util.SIEType;
import se.swedsoft.bookkeeping.importexport.util.SSExportException;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ResourceBundle;


/**
 * Date: 2006-feb-13
 * Time: 16:40:28
 */
public class SSExportSIEDialog {

    private static ResourceBundle bundle = SSBundle.getBundle();

    private SSExportSIEDialog() {}

    /**
     *
     * @param iMainFrame
     */
    public static void showDialog(final SSMainFrame iMainFrame) {
        final SSDialog         iDialog = new SSDialog(iMainFrame,
                bundle.getString("sieframe.export.title"));
        final SSExportSIEPanel iPanel = new SSExportSIEPanel();

        SSSIEFileChooser iFileChooser = SSSIEFileChooser.getInstance();

        iFileChooser.setDefaultFileName();

        if (iFileChooser.showSaveDialog(iMainFrame) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        final File iFile = iFileChooser.getSelectedFile();

        iDialog.add(iPanel.getPanel(), BorderLayout.CENTER);

        iPanel.addOkAction(
                e -> {

                        String iComment = iPanel.getComment();
                        SIEType iType = iPanel.getType();

                        try {
                            SSSIEExporter iExporter = new SSSIEExporter(iType, iComment);
                            var adjustments = iExporter.findRequiredAdjustments();
                            if (!adjustments.isEmpty()) {
                                int response = JOptionPane.showConfirmDialog(iMainFrame,
                                        adjustments.size() + " verifikation(er) behöver en "
                                                + "avrundningsrad endast i SIE-filen för att "
                                                + "tvådecimalbeloppen ska balansera. Fortsätt?",
                                        "SIE-export", JOptionPane.OK_CANCEL_OPTION,
                                        JOptionPane.WARNING_MESSAGE);
                                if (response != JOptionPane.OK_OPTION) {
                                    return;
                                }
                                iExporter.setAllowRoundingAdjustments(true);
                            }
                            iExporter.exportSIE(iFile);
                        } catch (SSExportException ex) {
                            new SSErrorDialog(iMainFrame, "exportexceptiondialog", ex.getMessage());
                        }

                        iDialog.closeDialog();

                    });
        iPanel.addCancelAction(e -> iDialog.closeDialog());

        iDialog.pack();
        iDialog.setLocationRelativeTo(iMainFrame);
        iDialog.setVisible();

    }
}
