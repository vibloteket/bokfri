package se.swedsoft.bookkeeping.print;


import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.type.WhenNoDataTypeEnum;
import net.sf.jasperreports.engine.type.HorizontalTextAlignEnum;
import net.sf.jasperreports.engine.design.JRDesignBand;
import net.sf.jasperreports.engine.design.JRDesignSection;
import net.sf.jasperreports.engine.design.JRDesignExpression;
import net.sf.jasperreports.engine.design.JRDesignTextField;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.view.JasperViewer;
import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSErrorDialog;
import se.swedsoft.bookkeeping.gui.util.model.SSDefaultTableModel;
import se.swedsoft.bookkeeping.print.util.SSDefaultJasperDataSource;
import se.swedsoft.bookkeeping.print.util.SSReportCache;
import se.swedsoft.bookkeeping.print.view.SSJasperPreviewFrame;
import se.swedsoft.bookkeeping.util.SSException;

import javax.swing.*;
import javax.swing.event.InternalFrameListener;
import java.awt.*;
import java.util.*;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Date: 2006-feb-16
 * Time: 10:50:24
 */
public class SSReport {    private static final Logger LOG = LoggerFactory.getLogger(SSReport.class);


    private static SSReportCache cReportCache = SSReportCache.getInstance();

    /**
     *
     */
    public enum ReportField {
        PAGE_HEADER, PAGE_FOOTER, COLUMN_HEADER, COLUMN_FOOTER, DETAIL, SUMMARY, BACKGROUND, LAST_PAGE_FOOTER

    }

    private JasperPrint iPrinter;

    private JasperReport iReport;

    private Insets iMargins;

    private Point iSize;

    private int iColumnCount;

    private int iColumnSpacing;

    private int iColumnWidth;

    private JasperDesign iDesign;

    private SSDefaultJasperDataSource iDataSource;

    protected Map<String, Object     > iParameters;

    protected Map<ReportField, String> iFields;

    public SSReport() {
        iDesign = null;
        iPrinter = null;
        iReport = null;
        iDataSource = null;
        iMargins = new Insets(30, 20, 30, 20);
        iSize = new Point(595, 842);
        iColumnCount = 1;
        iColumnSpacing = 0;
        iColumnWidth = 555;
        iParameters = new HashMap<>();
        iFields = new HashMap<>();
    }

    /**
     *
     * @param pModel
     */
    public void setModel(SSDefaultTableModel pModel) {
        iDataSource = new SSDefaultJasperDataSource(pModel);
    }

    /**
     *
     * @param pName
     * @param pValue
     */
    public void addParameter(String pName, Object pValue) {
        iParameters.put(pName, pValue);
    }

    /**
     *
     * @param iParameters
     */
    public void addParameters(Map<String, Object> iParameters) {
        iParameters.putAll(iParameters);
    }

    /**
     *
     * @param pName
     * @param pField
     */
    public void setField(ReportField pField, String pName) {
        iFields.put(pField, pName);

    }

    /**
     *
     * @param pMargins
     */
    protected void setMargins(Insets pMargins) {
        iMargins = pMargins;
    }

    /**
     *
     * @param iSize
     */
    public void setSize(Point iSize) {
        this.iSize = iSize;
    }

    /**
     *
     * @param iColumnCount
     */
    public void setColumnCount(int iColumnCount) {
        this.iColumnCount = iColumnCount;
    }

    /**
     *
     * @param iColumnSpacing
     */
    public void setColumnSpacing(int iColumnSpacing) {
        this.iColumnSpacing = iColumnSpacing;
    }

    /**
     *
     * @param iColumnWidth
     */
    public void setColumnWidth(int iColumnWidth) {
        this.iColumnWidth = iColumnWidth;
    }

    /**
     *
     * @throws SSException
     */
    public void generateReport()  throws SSException {
        if (iDesign == null) {
            try {
                compileDesign();
            } catch (SSException ex) {
                iPrinter = getEmptyReport();
                throw ex;
            }
        }
        try {
            iReport = JasperCompileManager.compileReport(iDesign);

            iPrinter = JasperFillManager.fillReport(iReport, iParameters, iDataSource);

            if (iPrinter.getPages().isEmpty()) {
                iPrinter = getNoPagesReport();
            }

        } catch (JRException e) {
            LOG.error("Unexpected error", e);
            throw new SSException("Kunde inte skapa rapporten: " + e.getLocalizedMessage());
        }

    }

    /**
     *
     * @throws SSException
     */
    private void compileDesign() throws SSException {
        List<JRStyle> theStyles = new LinkedList<>();
        List<JRReportTemplate> theTemplates = new LinkedList<>();

        // Contains the fields from all subreports
        List<JRField>     theFields = new LinkedList<>();
        // Contains the parameters from all subreport
        List<JRParameter> theParameters = new LinkedList<>();

        List<JRVariable> theVariables = new LinkedList<>();

        List<JRGroup>    theGroups = new LinkedList<>();

        JRBand iPageHeader = null;

        // Page header
        if (iFields.containsKey(ReportField.PAGE_HEADER)) {
            iPageHeader = getField(ReportField.PAGE_HEADER, theFields, theParameters,
                    theVariables, theGroups, theStyles, theTemplates);
        }
        // Page footer
        JRBand iPageFooter = null;

        if (iFields.containsKey(ReportField.PAGE_FOOTER)) {
            iPageFooter = getField(ReportField.PAGE_FOOTER, theFields, theParameters,
                    theVariables, theGroups, theStyles, theTemplates);
        }

        // Column header
        JRBand iColumnHeader = null;

        if (iFields.containsKey(ReportField.COLUMN_HEADER)) {
            iColumnHeader = getField(ReportField.COLUMN_HEADER, theFields, theParameters,
                    theVariables, theGroups, theStyles, theTemplates);
        }
        // Column footer
        JRBand iColumnFooter = null;

        if (iFields.containsKey(ReportField.COLUMN_FOOTER)) {
            iColumnFooter = getField(ReportField.COLUMN_FOOTER, theFields, theParameters,
                    theVariables, theGroups, theStyles, theTemplates);
        }

        // Detail
        JRSection iDetail = null;

        if (iFields.containsKey(ReportField.DETAIL)) {
            iDetail = getDetailField(theFields, theParameters, theVariables,
                    theGroups, theStyles, theTemplates);
        }

        // Summary
        JRBand iSummary = null;

        if (iFields.containsKey(ReportField.SUMMARY)) {
            iSummary = getField(ReportField.SUMMARY, theFields, theParameters,
                    theVariables, theGroups, theStyles, theTemplates);
        }

        // Background
        JRBand iBackground = null;

        if (iFields.containsKey(ReportField.BACKGROUND)) {
            iBackground = getField(ReportField.BACKGROUND, theFields, theParameters,
                    theVariables, theGroups, theStyles, theTemplates);
        }
        // Last page footer
        JRBand iLastPageFooter = null;

        if (iFields.containsKey(ReportField.LAST_PAGE_FOOTER)) {
            iLastPageFooter = getField(ReportField.LAST_PAGE_FOOTER, theFields,
                    theParameters, theVariables, theGroups, theStyles, theTemplates);
        }

        iDesign = new JasperDesign();
        iDesign.setResourceBundle("book");
        iDesign.setName("JasperDocument");
        iDesign.setWhenNoDataType(WhenNoDataTypeEnum.ALL_SECTIONS_NO_DETAIL); // JRReport.WHEN_NO_DATA_TYPE_NO_PAGES

        iDesign.setTopMargin(iMargins.top);
        iDesign.setBottomMargin(iMargins.bottom);
        iDesign.setLeftMargin(iMargins.left);
        iDesign.setRightMargin(iMargins.right);

        iDesign.setPageWidth(iSize.x);
        iDesign.setPageHeight(iSize.y);

        iDesign.setColumnCount(iColumnCount);
        iDesign.setColumnSpacing(iColumnSpacing);
        iDesign.setColumnWidth(iColumnWidth);

        iDesign.setPageHeader(iPageHeader);
        iDesign.setPageFooter(iPageFooter);
        iDesign.setColumnHeader(iColumnHeader);
        iDesign.setColumnFooter(iColumnFooter);
        if (iDetail != null) {
            for (JRBand band: iDetail.getBands()) {
                JRDesignBand b = new JRDesignBand();
                b.setSplitType(band.getSplitTypeValue());
                b.setHeight(band.getHeight());
                b.setPrintWhenExpression(band.getPrintWhenExpression());
                if (band.getReturnValues() != null) {
                    for (ExpressionReturnValue val: band.getReturnValues()) {
                        b.addReturnValue(val);
                    }
                }
                JRElement[] elems = band.getElements();
                if (elems != null) {
                    for (JRElement e: elems) {
                        b.addElement(e);
                    }
                }
                ((JRDesignSection)iDesign.getDetailSection()).addBand(b);
            }
	}
        iDesign.setSummary(iSummary);
        iDesign.setBackground(iBackground);
        iDesign.setLastPageFooter(iLastPageFooter);

        LOG.info("Report:");
        LOG.info(" Height: " + iSize.y);
        LOG.info(" Width : " + iSize.x);
        LOG.info("Margins:");
        LOG.info(" Top : " + iMargins.top);
        LOG.info(" Bottom: " + iMargins.bottom);
        LOG.info(" Left : " + iMargins.left);
        LOG.info(" Right : " + iMargins.right);
        LOG.info("Band heights:");
        LOG.info(" PageHeader : " + (iPageHeader == null ? 0 : iPageHeader.getHeight()));
        LOG.info(" PageFooter : " + (iPageFooter == null ? 0 : iPageFooter.getHeight()));
        LOG.info(" ColumnHeader : " + (iColumnHeader == null ? 0 : iColumnHeader.getHeight()));
        LOG.info(" ColumnFooter : " + (iColumnFooter == null ? 0 : iColumnFooter.getHeight()));
        if (iDetail != null) {
            int detailHeight = 0;
            for (JRBand band: iDetail.getBands()) {
                detailHeight += band.getHeight();
            }
            LOG.info(" Detail : " + detailHeight);
	} else {
            LOG.info(" Detail : 0");
        }
        LOG.info(" Summary : " + (iSummary == null ? 0 : iSummary.getHeight()));
        LOG.info(" Background : " + (iBackground == null ? 0 : iBackground.getHeight()));
        LOG.info(" LastPageFooter: " + (iLastPageFooter == null ? 0 : iLastPageFooter.getHeight()));

        try {

            for (JRReportTemplate template : theTemplates) {
                if (!iDesign.getTemplatesList().contains(template)) {
                    iDesign.addTemplate(template);
                }
            }

            // Add all styles to the design
            for (JRStyle iStyle: theStyles) {
                try {
                    iDesign.addStyle(iStyle);
                } catch (JRException ignored) {}
            }

            // Add all fields to the design
            for (JRField iField: theFields) {
                try {
                    iDesign.addField(iField);
                } catch (JRException ignored) {}
            }
            // Add all parameters to the design
            for (JRParameter iParameter: theParameters) {
                try {
                    iDesign.addParameter(iParameter);
                } catch (JRException ignored) {}
            }

            // Add all groups to the desgin
            for (JRGroup iGroup: theGroups) {
                try {
                    if (iDesign.getMainDesignDataset().getGroupsMap().containsKey(
                            iGroup.getName())) {
                        continue;
                    }

                    iDesign.getMainDesignDataset().getGroupsList().add(iGroup);
                    iDesign.getMainDesignDataset().getGroupsMap().put(iGroup.getName(),
                            iGroup);

                } catch (RuntimeException ignored) {}
            }

            // Add all variables to the design
            for (JRVariable iVariable: theVariables) {
                try {
                    if (iDesign.getMainDesignDataset().getVariablesMap().containsKey(
                            iVariable.getName())) {
                        continue;
                    }

                    iDesign.getMainDesignDataset().getVariablesList().add(iVariable);
                    iDesign.getMainDesignDataset().getVariablesMap().put(
                            iVariable.getName(), iVariable);

                } catch (RuntimeException ignored) {}
            }

        } catch (Throwable t) {
            LOG.error("Unexpected error", t);

        }

    }

    /**
     *
     * @param pField
     * @param pFields
     * @param pParameters
     * @param pVariables
     * @param pGroups
     * @param pStyles
     * @return
     * @throws SSException
     */
    private JRBand getField(ReportField pField, List<JRField> pFields,
            List<JRParameter> pParameters, List<JRVariable> pVariables,
            List<JRGroup> pGroups, List<JRStyle> pStyles,
            List<JRReportTemplate> pTemplates) throws SSException {

        String pReportName = iFields.get(pField);

        try {
            // Get the report
            JasperReport iReport = cReportCache.getReport(pReportName);

            // Add all parameters
            if (iReport.getParameters() != null) {
                pParameters.addAll(Arrays.asList(iReport.getParameters()));
            }

            // Add all fields
            if (iReport.getFields() != null) {
                pFields.addAll(Arrays.asList(iReport.getFields()));
            }

            // Add all variables
            if (iReport.getVariables() != null) {
                pVariables.addAll(Arrays.asList(iReport.getVariables()));
            }

            // Add all groups
            if (iReport.getGroups() != null) {

                pGroups.addAll(Arrays.asList(iReport.getGroups()));
            }

            // Add all styles and external style templates.
            if (iReport.getStyles() != null) {
                pStyles.addAll(Arrays.asList(iReport.getStyles()));
            }
            if (iReport.getTemplates() != null) {
                pTemplates.addAll(Arrays.asList(iReport.getTemplates()));
            }

            switch (pField) {
            case PAGE_HEADER:
                return iReport.getPageHeader();

            case PAGE_FOOTER:
                return iReport.getPageFooter();

            case COLUMN_HEADER:
                return iReport.getColumnHeader();

            case COLUMN_FOOTER:
                return iReport.getColumnFooter();

            case SUMMARY:
                return iReport.getSummary();

            case BACKGROUND:
                return iReport.getBackground();

            case LAST_PAGE_FOOTER:
                return iReport.getLastPageFooter();

            }

            return null;
        } catch (SSException ex) {
            throw ex;
        } catch (Throwable ex) {
            LOG.error("Unexpected error", ex);
        }
        return null;
    }

    /**
     *
     * @param pField
     * @param pFields
     * @param pParameters
     * @param pVariables
     * @param pGroups
     * @param pStyles
     * @return
     * @throws SSException
     */
    private JRSection getDetailField(List<JRField> pFields, List<JRParameter> pParameters,
            List<JRVariable> pVariables, List<JRGroup> pGroups, List<JRStyle> pStyles,
            List<JRReportTemplate> pTemplates) throws SSException {
        String pReportName = iFields.get(ReportField.DETAIL);

        try {
            // Get the report
            JasperReport iReport = cReportCache.getReport(pReportName);

            // Add all parameters
            if (iReport.getParameters() != null) {
                pParameters.addAll(Arrays.asList(iReport.getParameters()));
            }

            // Add all fields
            if (iReport.getFields() != null) {
                pFields.addAll(Arrays.asList(iReport.getFields()));
            }

            // Add all variables
            if (iReport.getVariables() != null) {
                pVariables.addAll(Arrays.asList(iReport.getVariables()));
            }

            // Add all groups
            if (iReport.getGroups() != null) {

                pGroups.addAll(Arrays.asList(iReport.getGroups()));
            }

            // Add all styles and external style templates.
            if (iReport.getStyles() != null) {
                pStyles.addAll(Arrays.asList(iReport.getStyles()));
            }
            if (iReport.getTemplates() != null) {
                pTemplates.addAll(Arrays.asList(iReport.getTemplates()));
            }

            return iReport.getDetailSection();
        } catch (SSException ex) {
            throw ex;
        } catch (Throwable ex) {
            LOG.error("Unexpected error", ex);
        }
        return null;
    }

    /**
     * Generates a default report to show when no pages are avaiable
     *
     * @return The report
     */
    private JasperPrint getNoPagesReport() {
        JasperDesign iDesign = new JasperDesign();

        iDesign.setResourceBundle("book");
        iDesign.setName("NoPages");
        iDesign.setWhenNoDataType(WhenNoDataTypeEnum.ALL_SECTIONS_NO_DETAIL);

        JRDesignBand iTitle = new JRDesignBand();

        iTitle.setHeight(25);

        JRDesignTextField iTextField = new JRDesignTextField();

        iTextField.setX(0);
        iTextField.setY(0);
        iTextField.setWidth(515);
        iTextField.setHeight(25);
        iTextField.setForecolor(new Color(255, 0, 0));

        iTextField.setHorizontalTextAlign(HorizontalTextAlignEnum.LEFT);
        iTextField.setFontSize(12.f);
        iTextField.setItalic(true);

        JRDesignExpression iEpression = new JRDesignExpression();

        iEpression.setText("$R{report.nopages}");

        iTextField.setExpression(iEpression);

        iTitle.addElement(iTextField);

        iDesign.setTitle(iTitle);
        try {
            iReport = JasperCompileManager.compileReport(iDesign);

            return JasperFillManager.fillReport(iReport, iParameters, iDataSource);

        } catch (Throwable t) {
            LOG.error("Unexpected error", t);
        }
        return null;
    }

    /**
     * Generates an empty report
     *
     * @return
     */
    private JasperPrint getEmptyReport() {
        JasperDesign iDesign = new JasperDesign();

        iDesign.setResourceBundle("book");
        iDesign.setName("Empty");
        iDesign.setWhenNoDataType(WhenNoDataTypeEnum.ALL_SECTIONS_NO_DETAIL);

        try {
            iReport = JasperCompileManager.compileReport(iDesign);

            return JasperFillManager.fillReport(iReport, iParameters, iDataSource);

        } catch (Throwable t) {
            LOG.error("Unexpected error", t);
        }
        return null;
    }

    /**
     *
     * @return
     */
    public JasperPrint getPrinter() {
        return iPrinter;
    }

    /**
     *
     * @return
     */
    public JasperReport getReport() {
        return iReport;
    }

    /**
     *
     * @return
     */
    public Map<String, Object> getParameters() {
        return iParameters;
    }

    /**
     *
     * @param iName
     * @return
     * @param iName
     */
    public Object getParameter(String iName) {
        return iParameters.get(iName);
    }

    /**
     *
     * @return
     */
    public SSDefaultJasperDataSource getDataSource() {
        return iDataSource;
    }

    /**
     *
     */
    public void viewReport() {
        try {
            generateReport();
        } catch (SSException ex) {
            LOG.error("Unexpected error", ex);
        }

        JasperViewer.viewReport(iPrinter, false);
    }

    /**
     *
     * @param iMainFrame
     */
    public void viewReport(SSMainFrame iMainFrame) {
        try {
            generateReport();
        } catch (SSException ex) {
            new SSErrorDialog(iMainFrame, "exceptiondialog", ex.getLocalizedMessage());
        }

        SSJasperPreviewFrame iJasperPreviewFrame = new SSJasperPreviewFrame(iMainFrame,
                800, 600);

        iJasperPreviewFrame.setInCenter(iMainFrame);
        iJasperPreviewFrame.setReport(this);
        iJasperPreviewFrame.setPrinter(iPrinter);
        iJasperPreviewFrame.setVisible(true);
    }

    /**
     *
     * @param iDialog
     */
    public void viewReport(JDialog iDialog) {
        try {
            generateReport();
        } catch (SSException ex) {
            new SSErrorDialog(SSMainFrame.getInstance(), "exceptiondialog",
                    ex.getLocalizedMessage());
        }

        SSJasperPreviewFrame iJasperPreviewFrame = new SSJasperPreviewFrame(
                SSMainFrame.getInstance(), 800, 600);

        iJasperPreviewFrame.setInCenter(iDialog);
        iJasperPreviewFrame.setReport(this);
        iJasperPreviewFrame.setPrinter(iPrinter);
        iJasperPreviewFrame.setVisible(true);
    }

    /**
     *
     * @param iMainFrame
     * @param listener
     */
    public void viewReport(SSMainFrame iMainFrame, InternalFrameListener listener) {
        try {
            generateReport();
        } catch (SSException ex) {
            new SSErrorDialog(iMainFrame, "exceptiondialog", ex.getLocalizedMessage());
        }

        SSJasperPreviewFrame iJasperPreviewFrame = new SSJasperPreviewFrame(iMainFrame,
                800, 600);

        iJasperPreviewFrame.addInternalFrameListener(listener);
        iJasperPreviewFrame.setReport(this);
        iJasperPreviewFrame.setPrinter(iPrinter);
        iJasperPreviewFrame.setInCenter(iMainFrame);
        iJasperPreviewFrame.setVisible(true);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.print.SSReport");
        sb.append("{iColumnCount=").append(iColumnCount);
        sb.append(", iColumnSpacing=").append(iColumnSpacing);
        sb.append(", iColumnWidth=").append(iColumnWidth);
        sb.append(", iDataSource=").append(iDataSource);
        sb.append(", iDesign=").append(iDesign);
        sb.append(", iFields=").append(iFields);
        sb.append(", iMargins=").append(iMargins);
        sb.append(", iParameters=").append(iParameters);
        sb.append(", iPrinter=").append(iPrinter);
        sb.append(", iReport=").append(iReport);
        sb.append(", iSize=").append(iSize);
        sb.append('}');
        return sb.toString();
    }
}
