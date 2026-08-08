package se.swedsoft.bookkeeping.gui;

import org.fribok.bookkeeping.app.Path;
import org.fribok.bookkeeping.service.sie.SieImportService;
import se.swedsoft.bookkeeping.data.*;
import se.swedsoft.bookkeeping.data.backup.SSBackupDatabase;
import se.swedsoft.bookkeeping.data.system.*;
import se.swedsoft.bookkeeping.gui.about.dialog.SSAboutDialog;
import se.swedsoft.bookkeeping.gui.accountingyear.SSAccountingYearFrame;
import se.swedsoft.bookkeeping.gui.accountingyear.SSStartingAmountFrame;
import se.swedsoft.bookkeeping.gui.accountplans.SSAccountPlanDialog;
import se.swedsoft.bookkeeping.gui.accountplans.SSAccountPlanFrame;
import se.swedsoft.bookkeeping.gui.autodist.SSAutoDistFrame;
import se.swedsoft.bookkeeping.gui.backup.SSBackupDialog;
import se.swedsoft.bookkeeping.gui.backup.SSBackupFrame;
import se.swedsoft.bookkeeping.gui.budget.SSBudgetFrame;
import se.swedsoft.bookkeeping.gui.company.SSCompanyDialog;
import se.swedsoft.bookkeeping.gui.company.SSCompanyFrame;
import se.swedsoft.bookkeeping.gui.creditinvoice.SSCreditInvoiceFrame;
import se.swedsoft.bookkeeping.gui.customer.SSCustomerFrame;
import se.swedsoft.bookkeeping.gui.help.SSHelpBrowser;
import se.swedsoft.bookkeeping.gui.indelivery.SSIndeliveryFrame;
import se.swedsoft.bookkeeping.gui.inpayment.SSInpaymentFrame;
import se.swedsoft.bookkeeping.gui.inventory.SSInventoryFrame;
import se.swedsoft.bookkeeping.gui.invoice.SSInvoiceFrame;
import se.swedsoft.bookkeeping.gui.order.SSOrderFrame;
import se.swedsoft.bookkeeping.gui.outdelivery.SSOutdeliveryFrame;
import se.swedsoft.bookkeeping.gui.outpayment.SSOutpaymentFrame;
import se.swedsoft.bookkeeping.gui.ownreport.SSOwnReportFrame;
import se.swedsoft.bookkeeping.gui.periodicinvoice.SSPeriodicInvoiceFrame;
import se.swedsoft.bookkeeping.gui.product.SSProductFrame;
import se.swedsoft.bookkeeping.gui.project.SSProjectFrame;
import se.swedsoft.bookkeeping.gui.purchaseorder.SSPurchaseOrderFrame;
import se.swedsoft.bookkeeping.gui.purchasesuggestion.dialog.SSPurchaseSuggestionDialog;
import se.swedsoft.bookkeeping.gui.resultunit.SSResultUnitFrame;
import se.swedsoft.bookkeeping.gui.sie.dialog.SSExportSIEDialog;
import se.swedsoft.bookkeeping.gui.supplier.SSSupplierFrame;
import se.swedsoft.bookkeeping.gui.suppliercreditinvoice.SSSupplierCreditInvoiceFrame;
import se.swedsoft.bookkeeping.gui.supplierinvoice.SSSupplierInvoiceFrame;
import se.swedsoft.bookkeeping.gui.tender.SSTenderFrame;
import se.swedsoft.bookkeeping.gui.util.SSBundle;
import se.swedsoft.bookkeeping.gui.util.dialogs.*;
import se.swedsoft.bookkeeping.gui.util.filechooser.SSBackupFileChooser;
import se.swedsoft.bookkeeping.gui.util.filechooser.SSFileChooser;
import se.swedsoft.bookkeeping.gui.util.filechooser.SSSIEFileChooser;
import se.swedsoft.bookkeeping.gui.util.filechooser.util.SSFilterALL;
import se.swedsoft.bookkeeping.gui.util.filechooser.util.SSFilterBGMAX;
import se.swedsoft.bookkeeping.gui.util.filechooser.util.SSFilterTXT;
import se.swedsoft.bookkeeping.gui.util.frame.SSFrameManager;
import se.swedsoft.bookkeeping.gui.util.frame.SSInternalFrame;
import se.swedsoft.bookkeeping.gui.util.menu.SSMenuLoader;
import se.swedsoft.bookkeeping.gui.voucher.SSVoucherFrame;
import se.swedsoft.bookkeeping.gui.vouchertemplate.SSVoucherTemplateFrame;
import se.swedsoft.bookkeeping.importexport.bgmax.SSBgMaxImporter;
import se.swedsoft.bookkeeping.importexport.bgmax.data.BgMaxFile;
import se.swedsoft.bookkeeping.importexport.sie.SSSIEExporter;
import se.swedsoft.bookkeeping.importexport.sie.util.SIEType;
import se.swedsoft.bookkeeping.importexport.supplierpayments.SSSupplierPaymentImporter;
import se.swedsoft.bookkeeping.importexport.util.SSExportException;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;
import se.swedsoft.bookkeeping.print.SSReportFactory;
import se.swedsoft.bookkeeping.print.dialog.SSInventoryBasisDialog;
import se.swedsoft.bookkeeping.print.report.SSAccountPlanPrinter;
import se.swedsoft.bookkeeping.print.report.SSInventoryBasisPrinter;
import se.swedsoft.bookkeeping.print.report.SSStartingAmountPrinter;
import se.swedsoft.bookkeeping.util.BrowserLaunch;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @version $Id$
 */
public class SSMainMenu {    private static final Logger LOG = LoggerFactory.getLogger(SSMainMenu.class);


    private static final String MENU_RES = "/MainMenu.xml";

    private static final ResourceBundle bundle = SSBundle.getBundle();

    private SSMainFrame iMainFrame;

    private SSMenuLoader iMenuLoader;

    /**
     * Default constructor.
     * @param pMainFrame
     */
    public SSMainMenu(SSMainFrame pMainFrame) {
        iMainFrame  = pMainFrame;
        iMenuLoader = new SSMenuLoader();

        InputStream stream = getClass().getResourceAsStream(MENU_RES);
        iMenuLoader.loadMenus(stream);

        iMenuLoader.setEnabled("Company", false);
        iMenuLoader.setEnabled("Year", false);

        loadActions();

        // Called when the year is changed
        SSDB.getInstance().addPropertyChangeListener("YEAR"   , evt -> iMenuLoader.setEnabled("Year" , evt.getNewValue() != null));

        // Called when the company is changed
        SSDB.getInstance().addPropertyChangeListener("COMPANY", evt -> {

                if(evt.getNewValue() == null){
                    iMenuLoader.setEnabled("Company", false);
                    iMenuLoader.setEnabled("Year"   , false);
                } else {
                    iMenuLoader.setEnabled("Company", true);
                }

            });

    }

    /**
     *
     * @return
     */
    public JMenuBar getMenuBar(){
        return iMenuLoader.getMenuBar("MainMenu");
    }

    /**
     *
     */
    private void loadActions(){
        loadFileActions();

        loadRegisterActions();

        loadInvoiceActions();

        bgcmenuActions();

        addStockActions();

        loadBookkeepingActions();

        loadPrintBookkeepingActions();

        loadHelpActions();

        loadWindowActions();
    }

    /**
     * Add actions to the File menu
     */
    private void loadFileActions(){
        // Open company
        // *****************************
        iMenuLoader.addActionListener("filemenu.companies", e -> SSCompanyFrame.showFrame(iMainFrame, 500, 300));

        // Company settings
        // *****************************
        iMenuLoader.addActionListener("filemenu.company.settings", e -> SSCompanyDialog.editCurrentDialog(iMainFrame, SSDB.getInstance().getCurrentCompany(), null));

        // Account plans
        // *****************************
        iMenuLoader.addActionListener("filemenu.accountplans", e -> SSAccountPlanFrame.showFrame(iMainFrame, 640, 480));

        // SIE Import
        // *****************************
        iMenuLoader.addActionListener("filemenu.import.sie", e -> {

                SSNewCompany iCurrentCompany = SSDB.getInstance().getCurrentCompany();
                SSFileChooser iFileChooser = SSSIEFileChooser.getInstance();
                int iResponce = iFileChooser.showOpenDialog(iMainFrame);
                boolean iShowDialog = false;
                if(iResponce == JFileChooser.APPROVE_OPTION ){
                    iShowDialog = true;
                    try {
                        SieImportService iImporter = new SieImportService(
                                Path.get(Path.USER_DATA).toPath());
                        iImporter.importFile(iFileChooser.getSelectedFile().toPath(), false, false);

                    } catch (SSImportException | IOException ex) {
                        iShowDialog = false;
                        new SSErrorDialog(iMainFrame, "importexceptiondialog", ex.getMessage());
                    } catch (RuntimeException ex) {
                        LOG.error("Unexpected error", ex);
                        iShowDialog = false;
                    }
                }
                if(iShowDialog)
                    new SSInformationDialog(iMainFrame, "sieimport.importdone");

            });

        // Voucher import
        // *****************************
        iMenuLoader.addActionListener("filemenu.import.vouchers", e -> {

                SSSIEFileChooser iFileChooser = SSSIEFileChooser.getInstance();
                if (iFileChooser.showOpenDialog(iMainFrame) == JFileChooser.APPROVE_OPTION) {
                    try {
                        SieImportService iImporter = new SieImportService(
                                Path.get(Path.USER_DATA).toPath());
                        iImporter.importFile(iFileChooser.getSelectedFile().toPath(), true, false);
                    } catch (SSImportException | IOException ex) {
                        new SSErrorDialog(iMainFrame, "importexceptiondialog", ex.getMessage());
                    }
                    if (SSVoucherFrame.getInstance() != null) {
                        SSVoucherFrame.getInstance().getModel().fireTableDataChanged();
                    }
                }


            });

        // SIE export
        // *****************************
        iMenuLoader.addActionListener("filemenu.export.sie", e -> SSExportSIEDialog.showDialog(iMainFrame));

        // Voucher export
        // *****************************
        iMenuLoader.addActionListener("filemenu.export.vouchers", e -> {

                SSSIEFileChooser iFileChooser = SSSIEFileChooser.getInstance();

                iFileChooser.setDefaultFileName();

                if(iFileChooser.showSaveDialog(iMainFrame) == JFileChooser.APPROVE_OPTION ){
                    try {
                        SSSIEExporter iExporter = new SSSIEExporter(SIEType.SIE_4I, "Format SIE 4I");
                        iExporter.exportSIE(iFileChooser.getSelectedFile() );
                    } catch (SSExportException ex ) {
                        new SSErrorDialog(iMainFrame, "exportexceptiondialog", ex.getMessage());
                    } catch (RuntimeException ex) {
                        LOG.error("Unexpected error", ex);
                    }
                }

            });

        // Backup all
        // *****************************
        iMenuLoader.addActionListener("filemenu.backup.all", e -> createBackupDialog());

        // Restore backup
        // *****************************
        iMenuLoader.addActionListener("filemenu.backup.restore", e -> SSBackupFrame.showFrame(iMainFrame, 600, 400));

        // Exit
        // *****************************
        iMenuLoader.addActionListener("filemenu.exit", e -> {

                iMainFrame.saveSizeAndLocation();
                System.exit(0);

            });

    }

    /**
     * Add actions to register menu
     */
    private void loadRegisterActions() {

        // Select accountingyear
        // *****************************
        iMenuLoader.addActionListener("registermenu.accountingyear", e -> SSAccountingYearFrame.showFrame(iMainFrame, 500, 300));

        // Change accountplan for this year
        // *****************************
        iMenuLoader.addActionListener("registermenu.accountplan", e -> {

                SSNewAccountingYear yearData = SSDB.getInstance().getCurrentYear();

                // Open warningdialog if no yeardata
                if (yearData == null) {
                    SSNewAccountingYear.openWarningDialogNoYearData(iMainFrame);
                    return;
                }
                SSAccountPlan iAccountPlan = yearData.getAccountPlan();

                SSAccountPlanDialog.editCurrentDialog( iMainFrame, iAccountPlan, null);

            });

        // Projects
        // *****************************
        iMenuLoader.addActionListener("registermenu.project", e -> SSProjectFrame.showFrame(iMainFrame, 400, 300));

        // Resultatenhet
        // *****************************
        iMenuLoader.addActionListener("registermenu.resultunit", e -> SSResultUnitFrame.showFrame(iMainFrame, 400, 300));

        // Producter
        // *****************************
        iMenuLoader.addActionListener("registermenu.product", e -> SSProductFrame.showFrame(iMainFrame, 640, 480));
        // Customer
        // *****************************
        iMenuLoader.addActionListener("registermenu.customer", e -> SSCustomerFrame.showFrame(iMainFrame, 800, 600));

        // Supplier
        // *****************************
        iMenuLoader.addActionListener("registermenu.supplier", e -> SSSupplierFrame.showFrame(iMainFrame, 800, 600));

        iMenuLoader.addActionListener("registermenu.vouchertemplates", e -> {

                //SSVoucherTemplateTableFrame voucherTemplateFrame = new SSVoucherTemplateTableFrame(iMainFrame, 400, 300);
                //voucherTemplateFrame.setVisible(true);

                SSVoucherTemplateFrame.showFrame(iMainFrame);


            });

        // Automatfördelning
        iMenuLoader.addActionListener("registermenu.autodist", e -> SSAutoDistFrame.showFrame(iMainFrame, 488, 360));
    }

    /**
     *
     */
    private void loadInvoiceActions() {

        // Inbetalningar
        // *****************************
        iMenuLoader.addActionListener("salemenu.inpayment", e -> SSInpaymentFrame.showFrame(iMainFrame, 800, 600));

        // Utbetalningar
        // *****************************
        iMenuLoader.addActionListener("salemenu.outpayment", e -> SSOutpaymentFrame.showFrame(iMainFrame, 800, 600));

        // Kreditfakturor
        // *****************************
        iMenuLoader.addActionListener("salemenu.creditinvoice", e -> SSCreditInvoiceFrame.showFrame(iMainFrame, 800, 600));

        // Periodfaktura
        // *****************************
        iMenuLoader.addActionListener("salemenu.periodicinvoice", e -> SSPeriodicInvoiceFrame.showFrame(iMainFrame, 800, 600));

        // Offerter
        // *****************************
        iMenuLoader.addActionListener("salemenu.tender", e -> SSTenderFrame.showFrame(iMainFrame, 800, 600));

        // Order
        // *****************************
        iMenuLoader.addActionListener("salemenu.order", e -> SSOrderFrame.showFrame(iMainFrame, 800, 600));
        // Faktura
        // *****************************
        iMenuLoader.addActionListener("salemenu.invoice", e -> SSInvoiceFrame.showFrame(iMainFrame, 880, 600));
        // Inköpsorder
        // *****************************
        iMenuLoader.addActionListener("salemenu.purchaseorder", e -> SSPurchaseOrderFrame.showFrame(iMainFrame, 880, 600));

        // Leverantörsfaktura
        // *****************************
        iMenuLoader.addActionListener("salemenu.supplierinvoice", e -> SSSupplierInvoiceFrame.showFrame(iMainFrame, 880, 600));

        // Leverantörskreditfaktura
        // *****************************
        iMenuLoader.addActionListener("salemenu.suppliercreditinvoice", e -> SSSupplierCreditInvoiceFrame.showFrame(iMainFrame, 880, 600));

    }

    /**
     *
     */
    private void bgcmenuActions() {
        // Importera från bgmax
        // *****************************
        iMenuLoader.addActionListener("bgcmenu.bgmax", e -> {

                SSFileChooser iFileChooser = new SSFileChooser(  );

                SSFilterBGMAX iFilter = new SSFilterBGMAX();

                iFileChooser.addChoosableFileFilter( iFilter );
                iFileChooser.addChoosableFileFilter( new SSFilterTXT() );
                iFileChooser.addChoosableFileFilter( new SSFilterALL() );
                iFileChooser.setFileFilter(  iFilter );

                if( iFileChooser.showOpenDialog(iMainFrame) != SSFileChooser.APPROVE_OPTION) return;
                BgMaxFile iBgMaxFile;
                try {
                    iBgMaxFile = SSBgMaxImporter.Import(iFileChooser.getSelectedFile());
                } catch (SSImportException ex) {
                    new SSErrorDialog(iMainFrame, "importexceptiondialog", ex.getMessage());
                    return;
                }

                if(iBgMaxFile == null) {
                    return;
                }
                List<SSInpayment> iInpayments = SSBgMaxImporter.getInpayments(iMainFrame, iBgMaxFile);

                if(iInpayments == null) {
                    return;
                }
                StringBuilder iNumbers = new StringBuilder();
                for (SSInpayment iInpayment : iInpayments) {
                    SSDB.getInstance().addInpayment(iInpayment);

                    if(iNumbers.length() > 0) iNumbers.append(", ");

                    iNumbers.append( iInpayment.getNumber() );
                }
                SSInformationDialog.showDialog(iMainFrame,"bgmaximport.success",iNumbers.toString());


            });

        // Importera från lbin fil
        // *****************************
        iMenuLoader.addActionListener("bgcmenu.lbin", e -> {

                SSFileChooser iFileChooser = new SSFileChooser( );

                SSFilterBGMAX iFilter = new SSFilterBGMAX();

                iFileChooser.addChoosableFileFilter( iFilter );
                iFileChooser.addChoosableFileFilter( new SSFilterTXT() );
                iFileChooser.addChoosableFileFilter( new SSFilterALL() );
                iFileChooser.setFileFilter(  iFilter );

                if( iFileChooser.showOpenDialog(iMainFrame) != SSFileChooser.APPROVE_OPTION) return;
                //  List<SSOutpayment> iOutpayments = SSSupplierPaymentImporter.Import(iFileChooser.getSelectedFile());

                List<SSOutpayment> iOutpayments ;
                try {
                    iOutpayments = SSSupplierPaymentImporter.Import(iFileChooser.getSelectedFile());
                } catch (SSImportException ex) {
                    new SSErrorDialog(iMainFrame, "importexceptiondialog", ex.getMessage());
                    return;
                }
                StringBuilder iNumbers = new StringBuilder();
                for (SSOutpayment iOutpayment : iOutpayments) {
                    SSDB.getInstance().addOutpayment(iOutpayment);

                    if(iNumbers.length() > 0) iNumbers.append(", ");

                    iNumbers.append( iOutpayment.getNumber() );
                }
                new SSInformationDialog(iMainFrame, "supplierpaymentimport.importcomplete", iNumbers.toString() );

            });

    }

    /**
     *
     */
    private void addStockActions() {

        // Inleverans
        // *****************************
        iMenuLoader.addActionListener("stockmenu.indelivery", e -> SSIndeliveryFrame.showFrame(iMainFrame, 800, 600));

        // Utleverans
        // *****************************
        iMenuLoader.addActionListener("stockmenu.outdelivery", e -> SSOutdeliveryFrame.showFrame(iMainFrame, 800, 600));

        // Inköpsförslag
        // *****************************
        iMenuLoader.addActionListener("stockmenu.purchasesuggestion", e -> SSPurchaseSuggestionDialog.showDialog(iMainFrame));

        // Inventering
        // *****************************
        iMenuLoader.addActionListener("stockmenu.inventory", e -> SSInventoryFrame.showFrame(iMainFrame, 800, 600));

        // Inventeringsunderlag
        // *****************************
        iMenuLoader.addActionListener("stockmenu.inventorybasisreport", e -> {

                SSInventoryBasisDialog iDialog = new SSInventoryBasisDialog(iMainFrame);

                iDialog.setLocationRelativeTo(iMainFrame);
                if(iDialog.showDialog() != JOptionPane.OK_OPTION) return;
                final LocalDate iDate       = iDialog.getLocalDate();
                final boolean iDateSelected = iDialog.isDateSelected();
                SSProgressDialog.runProgress(iMainFrame, () -> {

                        SSInventoryBasisPrinter iPrinter;
                        if(iDateSelected){
                            iPrinter = new SSInventoryBasisPrinter(iDate);
                        } else {
                            iPrinter = new SSInventoryBasisPrinter();
                        }
                        iPrinter.preview( iMainFrame );

                    });

            });

        // Lagerredovisning
        // *****************************
        iMenuLoader.addActionListener("stockmenu.stockaccountreport", e -> SSReportFactory.StockAccount(iMainFrame));

        // Lagervärde
        // *****************************
        iMenuLoader.addActionListener("stockmenu.stockvaluereport", e -> SSReportFactory.StockValue(iMainFrame));

    }

    /**
     *
     */
    private void loadBookkeepingActions() {

        // Ingående balans
        // *****************************
        iMenuLoader.addActionListener("bookkeepingmenu.startingamount", e -> SSStartingAmountFrame.showFrame(iMainFrame, 500, 400));

        // Vouchers
        // *****************************
        iMenuLoader.addActionListener("bookkeepingmenu.vouchers", e -> SSVoucherFrame.showFrame(iMainFrame, 800, 600));

        // Budget
        // *****************************
        iMenuLoader.addActionListener("bookkeepingmenu.budget", e -> SSBudgetFrame.showFrame(iMainFrame, 800, 600));

    }

    /**
     *
     */
    private void loadPrintBookkeepingActions() {

        // Vouchers
        // *****************************
        iMenuLoader.addActionListener("reportmenu.vouchers", e -> {

                SSNewAccountingYear yearData = SSDB.getInstance().getCurrentYear();

                // Check so the yeardata isn't null
                if (yearData == null) {
                    SSNewAccountingYear.openWarningDialogNoYearData(iMainFrame);
                    return;
                }

                SSReportFactory.buildVoucherReport(iMainFrame, bundle, yearData);

            });

        // Starting ammounts
        // *****************************
        iMenuLoader.addActionListener("reportmenu.startingamounts", e -> {

                final SSNewAccountingYear iAccountingYear = SSDB.getInstance().getCurrentYear();
                // Check so the yeardata isn't null
                if (iAccountingYear == null) {
                    SSNewAccountingYear.openWarningDialogNoYearData(iMainFrame);
                    return;
                }

                SSProgressDialog.runProgress(iMainFrame, () -> {

                        SSStartingAmountPrinter iPrinter = new SSStartingAmountPrinter(iAccountingYear);
                        iPrinter.preview( iMainFrame );

                    });

            });

        // Account plan
        // *****************************
        iMenuLoader.addActionListener("reportmenu.accountplan", e -> {

                SSNewAccountingYear yearData = SSDB.getInstance().getCurrentYear();

                // Check so the yeardata isn't null
                if (yearData == null) {
                    SSNewAccountingYear.openWarningDialogNoYearData(iMainFrame);
                    return;
                }
                SSProgressDialog.runProgress(iMainFrame, () -> {

                        SSAccountPlanPrinter iPrinter = new SSAccountPlanPrinter();

                        iPrinter.preview( iMainFrame );

                    });


            });

        // Mainbook
        // *****************************
        iMenuLoader.addActionListener("reportmenu.mainbook", e -> {

                SSNewAccountingYear yearData = SSDB.getInstance().getCurrentYear();

                // Check so the yeardata isn't null
                if (yearData == null) {
                    SSNewAccountingYear.openWarningDialogNoYearData(iMainFrame);
                    return;
                }
                SSReportFactory.MainbookReport(iMainFrame, yearData);

            });

        // Result report
        // *****************************
        iMenuLoader.addActionListener("reportmenu.resultreport", e -> {

                SSNewAccountingYear yearData = SSDB.getInstance().getCurrentYear();

                // Check so the yeardata isn't null
                if (yearData == null) {
                    SSNewAccountingYear.openWarningDialogNoYearData(iMainFrame);
                    return;
                }
                SSNewAccountingYear previousYearData = SSDB.getInstance().getPreviousYear().orElse(null);

                SSReportFactory.buildResultReport(iMainFrame, bundle, yearData, previousYearData);

            });

        // Project result
        // *****************************
        iMenuLoader.addActionListener("reportmenu.projectresult", e -> {

                SSNewAccountingYear iAccountingYear = SSDB.getInstance().getCurrentYear();

                // Check so the yeardata isn't null
                if (iAccountingYear == null) {
                    SSNewAccountingYear.openWarningDialogNoYearData(iMainFrame);
                    return;
                }
                SSReportFactory.ProjectResultReport(iMainFrame, bundle, iAccountingYear);

            });

        // Resultunit result
        // *****************************
        iMenuLoader.addActionListener("reportmenu.resultunitresult", e -> {

                SSNewAccountingYear iAccountingYear = SSDB.getInstance().getCurrentYear();

                // Check so the yeardata isn't null
                if (iAccountingYear == null) {
                    SSNewAccountingYear.openWarningDialogNoYearData(iMainFrame);
                    return;
                }

                SSReportFactory.ResultUnitResultReport(iMainFrame, bundle, iAccountingYear);

            });

        // Budget report
        // *****************************
        iMenuLoader.addActionListener("reportmenu.budget", e -> {

                SSNewAccountingYear yearData = SSDB.getInstance().getCurrentYear();

                // Check so the yeardata isn't null
                if (yearData == null) {
                    SSNewAccountingYear.openWarningDialogNoYearData(iMainFrame);
                    return;
                }

                SSReportFactory.buildBudgetReport(iMainFrame, bundle, yearData);

            });

        // Balance report
        // *****************************
        iMenuLoader.addActionListener("reportmenu.balancereport", e -> {

                SSNewAccountingYear yearData = SSDB.getInstance().getCurrentYear();

                // Check so the yeardata isn't null
                if (yearData == null) {
                    SSNewAccountingYear.openWarningDialogNoYearData(iMainFrame);
                    return;
                }

                SSReportFactory.buildBalanceReport(iMainFrame, bundle, yearData);

            });

        // Momsrapport 2015
        // *****************************
        iMenuLoader.addActionListener("reportmenu.vatreport2015", e -> {

                SSNewAccountingYear iAccountingYear = SSDB.getInstance().getCurrentYear();

                // Check so the yeardata isn't null
                if (iAccountingYear == null) {
                    SSNewAccountingYear.openWarningDialogNoYearData(iMainFrame);
                    return;
                }

                SSReportFactory.VATReport2015(iMainFrame, bundle, iAccountingYear);

            });

        // Räkenskapsschema
        // *****************************
        iMenuLoader.addActionListener("reportmenu.accountdiagram", e -> {

                SSNewAccountingYear yearData = SSDB.getInstance().getCurrentYear();

                // Check so the yeardata isn't null
                if (yearData == null) {
                    SSNewAccountingYear.openWarningDialogNoYearData(iMainFrame);
                    return;
                }

                SSReportFactory.buildAccountDiagramReport(iMainFrame, bundle, yearData);

            });

        // Förenklat årsbokslut
        // *****************************
        iMenuLoader.addActionListener("reportmenu.simplestatement", e -> {

                SSNewAccountingYear yearData = SSDB.getInstance().getCurrentYear();

                // Check so the yeardata isn't null
                if (yearData == null) {
                    SSNewAccountingYear.openWarningDialogNoYearData(iMainFrame);
                    return;
                }

                SSReportFactory.SimplestatementReport(iMainFrame);

            });

        iMenuLoader.addActionListener("reportmenu.ownreports", e -> {

                SSNewAccountingYear yearData = SSDB.getInstance().getCurrentYear();
                // Check so the yeardata isn't null
                if (yearData == null) {
                    SSNewAccountingYear.openWarningDialogNoYearData(iMainFrame);
                    return;
                }
                SSOwnReportFrame.showFrame(iMainFrame,800,600);

            });

        /////////////////////////////////////////////////////////////////////////////////////////////

        // Orderlista
        // *****************************
        iMenuLoader.addActionListener("reportmenu.orderlist", e -> SSReportFactory.OrderListReport(iMainFrame));

        // Kundreskontra
        // *****************************
        iMenuLoader.addActionListener("reportmenu.accountsrecievable", e -> SSReportFactory.AccountsRecievableReport(iMainFrame));

        // Kundfodran
        // *****************************
        iMenuLoader.addActionListener("reportmenu.customerclaim", e -> SSReportFactory.CustomerClaimReport(iMainFrame));

        // EU-Kvartalsredovisning
        // *****************************
        iMenuLoader.addActionListener("reportmenu.quarterreport", e -> SSReportFactory.QuarterReport(iMainFrame));

        // Faktura journal
        // *****************************
        iMenuLoader.addActionListener("journalmenu.invoicejornal", e -> SSReportFactory.InvoiceJournal(iMainFrame));

        // Kreditfaktura journal
        // *****************************
        iMenuLoader.addActionListener("journalmenu.creditinvoicejornal", e -> SSReportFactory.CreditInvoiceJournal(iMainFrame));

        // Inbetalningsjournal
        // *****************************
        iMenuLoader.addActionListener("journalmenu.inpaymentjournal", e -> SSReportFactory.InpaymentJournal(iMainFrame));

        // leverantörsfaktura journal
        // *****************************
        iMenuLoader.addActionListener("journalmenu.supplierinvoicejornal", e -> SSReportFactory.SupplierInvoiceJournal(iMainFrame));

        // Leverantörskreditfaktura journal
        // *****************************
        iMenuLoader.addActionListener("journalmenu.suppliercreditinvoicejornal", e -> SSReportFactory.SupplierCreditInvoiceJournal(iMainFrame));

        // Utbetalningsjournal
        // *****************************
        iMenuLoader.addActionListener("journalmenu.outpaymentjournal", e -> SSReportFactory.OutpaymentJournal(iMainFrame));

        // Leverantörsreskontra
        // *****************************
        iMenuLoader.addActionListener("reportmenu.accountspayable", e -> SSReportFactory.AccountsPayableReport(iMainFrame));

        // Leverantörsskuld
        // *****************************
        iMenuLoader.addActionListener("reportmenu.supplierdept", e -> SSReportFactory.SupplierDebtReport(iMainFrame));

        // Försäljningsrapport
        // *****************************
        iMenuLoader.addActionListener("reportmenu.salereport", e -> SSReportFactory.SaleReport(iMainFrame));

        // Försäljningsvärden
        // *****************************
        iMenuLoader.addActionListener("reportmenu.salevalues",e -> {

                SSNewAccountingYear yearData = SSDB.getInstance().getCurrentYear();

                SSReportFactory.Salevalues(iMainFrame, bundle, yearData);

            });

        // Inköpsvärden
        // *****************************
        iMenuLoader.addActionListener("reportmenu.purchasevalues",e -> {

                SSNewAccountingYear yearData = SSDB.getInstance().getCurrentYear();
                SSReportFactory.Purchasevalues(iMainFrame, bundle, yearData);

            });

    }

    /**
     *
     */
    private void loadHelpActions() {
        // Hjälp
        // *****************************
        iMenuLoader.addActionListener("helpmenu.help", e -> SSHelpBrowser.openHelp());

        // Online support
        // *****************************
        iMenuLoader.addActionListener("helpmenu.support", e -> {

                String url =  SSBundle.getBundle().getString("application.url.support") ;
                BrowserLaunch.openURL( url );

            });

        // Online updates
        // *****************************
        iMenuLoader.addActionListener("helpmenu.updates", e -> {

                String url =  SSBundle.getBundle().getString("application.url.updates") ;
                BrowserLaunch.openURL( url );

            });

        iMenuLoader.addActionListener("helpmenu.compress", e -> {

                SSConfirmDialog iDialog = new SSConfirmDialog("helpmenu.compress.warning");
                if(iDialog.openDialog(iMainFrame)==JOptionPane.OK_OPTION){

                    SSDB.getInstance().shutdownCompact();
                    System.exit(0);
                }


            });

        // About
        // *****************************
        iMenuLoader.addActionListener("helpmenu.about", e -> SSAboutDialog.showDialog(iMainFrame));

    }
    private List<JMenuItem> iWindowItems;

    /**
     * Window actions
     */
    private void loadWindowActions(){
        iWindowItems = new LinkedList<>();

        SSFrameManager.getInstance().addFrameListener( e -> {

                JMenu iMenu = iMenuLoader.getMenu("Window");

                if(iMenu == null) return;

                // Remove all old items
                for(JMenuItem iMenuItem : iWindowItems){
                    iMenu.remove(iMenuItem);
                }
                iWindowItems.clear();

                int iCounter = 0;
                for(final SSInternalFrame iFrame : SSFrameManager.getInstance().getFrames()){
                    JMenuItem iMenuItem = new JMenuItem();
                    iMenuItem.setText(Integer.toString(iCounter) + ": " +  iFrame.getTitle() );
                    iMenuItem.setMnemonic( (char)('0' + iCounter));

                    iMenuItem.addActionListener(e1 -> iFrame.deIconize());
                    iMenu.add(iMenuItem);

                    iWindowItems.add(iMenuItem);

                    iCounter++;

                }


            });

        // Cascade...
        // *****************************
        iMenuLoader.addActionListener("windowmenu.cascade", e -> SSFrameManager.getInstance().cascade());

        // Close...
        // *****************************
        iMenuLoader.addActionListener("windowmenu.close", e -> SSFrameManager.getInstance().close());

    }

    /**
     * Set up and create a BackupDialog
     * @return true on successful backup
     */
    private boolean createBackupDialog() {
        JFileChooser fc             = SSBackupFileChooser.getInstance();
        SSBackupDatabase db         = SSBackupDatabase.getInstance();
        SSBackupDialog backupDialog = new SSBackupDialog(iMainFrame, fc, db);
        SSBackupFrame.hideFrame();
        return backupDialog.show();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("se.swedsoft.bookkeeping.gui.SSMainMenu");
        sb.append("{iMainFrame=").append(iMainFrame);
        sb.append(", iMenuLoader=").append(iMenuLoader);
        sb.append(", iWindowItems=").append(iWindowItems);
        sb.append('}');
        return sb.toString();
    }
}
