package se.swedsoft.bookkeeping.print.view;


import net.sf.jasperreports.engine.JRException;
import se.swedsoft.bookkeeping.print.PdfReportExporter;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperPrintManager;
import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.gui.status.SSStatusBar;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.components.SSButton;
import se.swedsoft.bookkeeping.gui.util.filechooser.SSJasperFileChooser;
import se.swedsoft.bookkeeping.gui.util.frame.SSDefaultTableFrame;
import se.swedsoft.bookkeeping.print.SSReport;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.ResourceBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * $Id$
 *
 */
public class SSJasperPreviewFrame extends SSDefaultTableFrame implements PropertyChangeListener {    private static final Logger LOG = LoggerFactory.getLogger(SSJasperPreviewFrame.class);


    public static ResourceBundle bundle = SSBundle.getBundle();

    private JasperPrint iPrinter;

    private SSViewer iViewer;

    private JLabel iPageLabel;

    private JComboBox<Object> iZoomLevels;

    private SSReport iReport;

    SSButton iFirst;
    SSButton iBack;
    SSButton iForward;
    SSButton iLast;

    /**
     * Default constructor.
     * @param frame
     * @param width
     * @param height
     */
    public SSJasperPreviewFrame(SSMainFrame frame, int width, int height) {
        super(frame, SSBundle.getBundle().getString("printpreviewframe.title"), width,
                height);

        iViewer.addPropertyChangeListener("page_zoom", evt -> updateStatusBar());

        iViewer.addPropertyChangeListener("page_change", evt -> updateStatusBar());

    }

    /**
     * This method should return a toolbar if the sub-class wants one.
     * Otherwise, it may return null.
     *
     * @return A JToolBar or null.
     */
    @Override
    public JToolBar getToolBar() {
        JToolBar toolbar = new JToolBar();

        iZoomLevels = new JComboBox<>(SSZoomLevel.values());
        iZoomLevels.setEditable(true);
        iZoomLevels.setMaximumSize(new Dimension(75, 20));
        iZoomLevels.setSelectedItem(SSZoomLevel.ZOOM_100);

        // Save
        // ***************************
        SSButton iButton = new SSButton("ICON_SAVEITEM", "printpreviewframe.savebutton",
                e -> {

                        SSJasperFileChooser iFileChooser = SSJasperFileChooser.getInstance();

                        if (iReport != null) {
                            String iTitle = (String) iReport.getParameter("title");

                            iFileChooser.setSelectedFile(new File(iTitle + ".pdf"));
                        } else {
                            iFileChooser.setSelectedFile(new File("Rapport.pdf"));

                        }

                        if (iFileChooser.showSaveDialog(SSJasperPreviewFrame.this)
                                == JFileChooser.APPROVE_OPTION) {
                            saveDocument(iFileChooser.getSelectedFile());
                        }


                    });

        iButton.setDefaultSize();
        toolbar.add(iButton);

        // Print
        // ***************************
        iButton = new SSButton("ICON_PRINT", "printpreviewframe.printbutton",
                e -> {

                        try {
                            JasperPrintManager.printReport(iPrinter, true);
                        } catch (JRException ex) {
                            LOG.error("Unexpected error", ex);
                        }

                    });
        iButton.setDefaultSize();
        toolbar.add(iButton);
        toolbar.addSeparator();

        // First
        // ***************************
        iFirst = new SSButton("ICON_FIRST", "printpreviewframe.firstbutton",
                e -> iViewer.firstPage());
        iFirst.setDefaultSize();
        toolbar.add(iFirst);

        // Back
        // ***************************
        iBack = new SSButton("ICON_BACK", "printpreviewframe.prevbutton",
                e -> iViewer.prevPage());
        iBack.setDefaultSize();
        toolbar.add(iBack);

        // Forward
        // ***************************
        iForward = new SSButton("ICON_FORWARD", "printpreviewframe.nextbutton",
                e -> iViewer.nextPage());
        iForward.setDefaultSize();
        toolbar.add(iForward);

        // Last
        // ***************************
        iLast = new SSButton("ICON_LAST", "printpreviewframe.lastbutton",
                e -> iViewer.lastPage());
        iLast.setDefaultSize();
        toolbar.add(iLast);
        toolbar.addSeparator();

        // Zoom in
        // ***************************
        iButton = new SSButton("ICON_ZOOMIN", "printpreviewframe.zoominbutton",
                e -> {

                        int index = iZoomLevels.getSelectedIndex() - 1;

                        if (index >= 0) {
                            iZoomLevels.setSelectedItem(SSZoomLevel.values()[index]);
                        }


                    });
        iButton.setDefaultSize();
        toolbar.add(iButton);

        // Zoom out
        // ***************************
        iButton = new SSButton("ICON_ZOOMOUT", "printpreviewframe.zoomoutbutton",
                e -> {

                        int index = iZoomLevels.getSelectedIndex() + 2;

                        if (index < SSZoomLevel.values().length) {
                            iZoomLevels.setSelectedItem(SSZoomLevel.values()[index - 1]);
                        }


                    });
        iButton.setDefaultSize();
        toolbar.add(iButton);
        toolbar.addSeparator();

        iZoomLevels.addActionListener(
                e -> {


                        int iZoom;

                        try {
                            if (iZoomLevels.getSelectedIndex() >= 0) {
                                SSZoomLevel iSelected = (SSZoomLevel) iZoomLevels.getSelectedItem();

                                iZoom = iSelected.getZoom();
                            } else {
                                iZoom = Integer.parseInt((String) iZoomLevels.getSelectedItem());
                            }

                        } catch (NumberFormatException e1) {
                            return;
                        }
                        iViewer.setZoom(iZoom);


                    });

        toolbar.add(iZoomLevels);

        iViewer.addPropertyChangeListener("page_change", this);

        return toolbar;
    }

    /**
     * This method gets called when a bound property is changed.
     *
     * @param evt A PropertyChangeEvent object describing the event source
     *            and the property that has changed.
     */

    public void propertyChange(PropertyChangeEvent evt) {
        int iCount = iViewer.getPageCount();
        int iCurrent = iViewer.getCurrentPage();

        iFirst.setEnabled(iCurrent > 0);
        iBack.setEnabled(iCurrent > 0);
        iForward.setEnabled(iCurrent < (iCount - 1));
        iLast.setEnabled(iCurrent < (iCount - 1));
    }

    /**
     * This method should return the main content for the frame.
     * Such as an object table.
     *
     * @return The main content for this frame.
     */
    @Override
    public JComponent getMainContent() {
        iViewer = new SSViewer();

        JScrollPane iScrollPane = new JScrollPane(iViewer);

        iScrollPane.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
        iScrollPane.setVerticalScrollBarPolicy(
                ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        return iScrollPane;
    }

    /**
     * This method should return the status bar content, if any.
     *
     * @return The content for the status bar or null if none is wanted.
     */
    @Override
    public JComponent getStatusBar() {
        iPageLabel = new JLabel();

        SSStatusBar iStatusBar = new SSStatusBar();

        iStatusBar.addSpacer();
        iStatusBar.addPanel(iPageLabel);

        return iStatusBar;
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

    /**
     *
     * @param iPrinter
     */
    public void setPrinter(JasperPrint iPrinter) {
        this.iPrinter = iPrinter;

        iViewer.setPrinter(iPrinter);
    }

    /**
     *
     * @param iReport
     */
    public void setReport(SSReport iReport) {
        this.iReport = iReport;
    }

    /**
     *
     */
    private void updateStatusBar() {
        int iCurrent = iViewer.getCurrentPage() + 1;
        int iTotal = iViewer.getPageCount();

        // iStatusBar.setText( String.format(bundle.getString("printpreviewframe.pages"), iCurrent,iTotal )   );
        iPageLabel.setText(
                String.format(bundle.getString("printpreviewframe.pages"), iCurrent,
                iTotal));
    }

    /**
     * Saves the report as PDF.
     *
     * @param pSelectedFile selected destination
     */
    private void saveDocument(File pSelectedFile) {
        String iFileName = pSelectedFile.getAbsolutePath();
        if (!iFileName.toLowerCase().endsWith(".pdf")) {
            iFileName = iFileName + ".pdf";
        }

        try {
            PdfReportExporter.export(iPrinter, new File(iFileName).toPath(), true);
        } catch (JRException | java.io.IOException ex) {
            LOG.error("Unexpected error", ex);
        }
    }

    public void actionPerformed(ActionEvent e) {
        iPrinter = null;
        iViewer = null;
        iPageLabel = null;
        iZoomLevels = null;
        iReport = null;
        iFirst = null;
        iBack = null;
        iForward = null;
        iLast = null;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.view.SSJasperPreviewFrame");
        sb.append("{iBack=").append(iBack);
        sb.append(", iFirst=").append(iFirst);
        sb.append(", iForward=").append(iForward);
        sb.append(", iLast=").append(iLast);
        sb.append(", iPageLabel=").append(iPageLabel);
        sb.append(", iPrinter=").append(iPrinter);
        sb.append(", iReport=").append(iReport);
        sb.append(", iViewer=").append(iViewer);
        sb.append(", iZoomLevels=").append(iZoomLevels);
        sb.append('}');
        return sb.toString();
    }
}

