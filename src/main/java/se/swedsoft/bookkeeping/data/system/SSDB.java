package se.swedsoft.bookkeeping.data.system;


import org.fribok.bookkeeping.service.demo.DemoCompanyService;
import se.swedsoft.bookkeeping.SSTriggerHandler;
import org.fribok.bookkeeping.app.Path;
import se.swedsoft.bookkeeping.calc.math.*;
import se.swedsoft.bookkeeping.calc.util.SSAutoIncrement;
import se.swedsoft.bookkeeping.data.*;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.*;
import se.swedsoft.bookkeeping.gui.SSMainFrame;
import se.swedsoft.bookkeeping.gui.autodist.SSAutoDistFrame;
import se.swedsoft.bookkeeping.gui.creditinvoice.SSCreditInvoiceFrame;
import se.swedsoft.bookkeeping.gui.customer.SSCustomerFrame;
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
import se.swedsoft.bookkeeping.gui.resultunit.SSResultUnitFrame;
import se.swedsoft.bookkeeping.gui.supplier.SSSupplierFrame;
import se.swedsoft.bookkeeping.gui.suppliercreditinvoice.SSSupplierCreditInvoiceFrame;
import se.swedsoft.bookkeeping.gui.supplierinvoice.SSSupplierInvoiceFrame;
import se.swedsoft.bookkeeping.gui.tender.SSTenderFrame;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSUnexpectedErrorDialog;
import se.swedsoft.bookkeeping.gui.util.dialogs.SSInitDialog;
import se.swedsoft.bookkeeping.gui.util.frame.SSFrameManager;
import se.swedsoft.bookkeeping.gui.voucher.SSVoucherFrame;
import se.swedsoft.bookkeeping.gui.vouchertemplate.SSVoucherTemplateFrame;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.*;
import java.rmi.server.UID;
import java.sql.*;
import java.util.*;
import java.util.Optional;
import java.util.stream.Collectors;
import se.swedsoft.bookkeeping.importexport.excel.SSAccountPlanImporter;
import se.swedsoft.bookkeeping.importexport.util.SSImportException;
import se.swedsoft.bookkeeping.util.SSUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 *
 * $Id$
 */
public class SSDB {    private static final Logger LOG = LoggerFactory.getLogger(SSDB.class);


    // The instance of the database
    private static SSDB cInstance;

    private SSNewCompany iCurrentCompany;

    private SSNewAccountingYear iCurrentYear;

    List<SSProduct> iProducts;
    List<SSCustomer> iCustomers;
    List<SSSupplier> iSuppliers;
    List<SSAutoDist> iAutoDists;

    List<SSInpayment> iInpayments;
    List<SSTender> iTenders;
    List<SSOrder> iOrders;
    List<SSInvoice> iInvoices;
    List<SSCreditInvoice> iCreditInvoices;
    List<SSPeriodicInvoice> iPeriodicInvoices;

    List<SSOutpayment> iOutpayments;
    List<SSPurchaseOrder> iPurchaseOrders;
    List<SSSupplierInvoice> iSupplierInvoices;
    List<SSSupplierCreditInvoice> iSupplierCreditInvoices;

    List<SSInventory> iInventories;
    List<SSIndelivery> iIndeliveries;
    List<SSOutdelivery> iOutdeliveries;

    List<SSVoucher> iVouchers;
    List<SSOwnReport> iOwnReports;

    /**
     * Returns the instance of the database
     *
     * @return the database
     */
    public static SSDB getInstance() {
        if (cInstance == null) {
            cInstance = new SSDB();
        }
        return cInstance;
    }

    public static final Object iSyncObject = new Object();

    Connection iConnection;

    // Listeners
    private Map<String, List<PropertyChangeListener>> iListenerMap;

    private SSDB() {
        iListenerMap = new HashMap<>();
    }

    /**
     *
     * @param pConnection
     *
     * @throws SQLException
     */
    public void startupLocal(Connection pConnection) throws SQLException {
        iConnection = pConnection;
        iConnection.setAutoCommit(false);

        createNewTables();
        // dropTriggers();
        createLocalTriggers();
        
        checkImportDefaultAccountPlans();
        checkCreateExampleCompany();
        
        // Läs in företaget och året som senast var öppet.
        Integer iLastCompany = SSDBConfig.getCompanyId();
        Integer iLastYear = SSDBConfig.getYearId();

        ResultSet iResultSet;
        PreparedStatement iStatement;

        if (iLastCompany != null) {
            iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_company WHERE id=?");
            iStatement.setObject(1, iLastCompany);
            iResultSet = iStatement.executeQuery();
            if (iResultSet.next()) {
                SSNewCompany iCompany = (SSNewCompany) iResultSet.getObject("company");

                setCurrentCompany(iCompany);
            }
            iResultSet.close();
            iStatement.close();
        }

        if (iLastYear != null && iCurrentCompany != null) {
            iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_accountingyear WHERE id=?");
            iStatement.setObject(1, iLastYear);
            iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSNewAccountingYear iYear = (SSNewAccountingYear) iResultSet.getObject(
                        "accountingyear");

                setCurrentYear(iYear);
            }
            iResultSet.close();
            iStatement.close();
        }
    }

    public void init(boolean iShowDialog) {
        if (iCurrentCompany == null) {
            return;
        }

        if (iShowDialog) {
            SSInitDialog.runProgress(SSMainFrame.getInstance(), "Läser in data",
                    () -> {

                            getProducts();
                            getCustomers();
                            getSuppliers();
                            getAutoDists();

                            getInpayments();
                            getTenders();
                            getOrders();
                            getInvoices();
                            getCreditInvoices();
                            getPeriodicInvoices();

                            getOutpayments();
                            getPurchaseOrders();
                            getSupplierInvoices();
                            getSupplierCreditInvoices();

                            getInventories();
                            getIndeliveries();
                            getOutdeliveries();

                            getOwnReports();

                            SSInvoiceMath.iSaldoMap = null;
                            SSInvoiceMath.calculateSaldos();
                            SSCustomerMath.iInvoicesForCustomers = null;
                            SSCustomerMath.getInvoicesForCustomers();
                            SSSupplierInvoiceMath.iSaldoMap = null;
                            SSSupplierInvoiceMath.calculateSaldos();
                            SSSupplierMath.iInvoicesForSuppliers = null;
                            SSSupplierMath.getInvoicesForSuppliers();
                            // SSOrderMath.setInvoiceForOrders();
                            initYear(false);

                        });
        } else {
            getProducts();
            getCustomers();
            getSuppliers();
            getAutoDists();

            getInpayments();
            getTenders();
            getOrders();
            getInvoices();
            getCreditInvoices();
            getPeriodicInvoices();

            getOutpayments();
            getPurchaseOrders();
            getSupplierInvoices();
            getSupplierCreditInvoices();

            getInventories();
            getIndeliveries();
            getOutdeliveries();

            getOwnReports();

            SSInvoiceMath.iSaldoMap = null;
            SSInvoiceMath.calculateSaldos();
            SSCustomerMath.iInvoicesForCustomers = null;
            SSCustomerMath.getInvoicesForCustomers();
            SSSupplierInvoiceMath.iSaldoMap = null;
            SSSupplierInvoiceMath.calculateSaldos();
            SSSupplierMath.iInvoicesForSuppliers = null;
            SSSupplierMath.getInvoicesForSuppliers();
            // SSOrderMath.setInvoiceForOrders();
            initYear(false);
        }

    }

    public void initYear(boolean iShowLoadingDialog) {
        if (iCurrentYear == null) {
            return;
        }

        iVouchers = null;
        getCurrentYear();

        if (iShowLoadingDialog) {
            SSInitDialog.runProgress(SSMainFrame.getInstance(), "Läser in data",
                    () -> getVouchers());
        } else {
            getVouchers();
        }

    }

    public void shutdown() {
        try {
            if (!iConnection.isClosed()) {
                Statement iStatement = iConnection.createStatement();

                iStatement.executeQuery("SHUTDOWN");
                iStatement.close();
                iConnection.close();
            }
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
        }
    }

    public void shutdownCompact() {
        try {
            Statement iStatement = iConnection.createStatement();

            iStatement.executeQuery("SHUTDOWN COMPACT");
            iStatement.close();
            iConnection.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
        }
    }

    public void loadLocalDatabase() {
        try {
            if (iConnection != null) {
                iConnection.close();
            }
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
        }
        try {
            Class.forName("org.hsqldb.jdbcDriver");
        } catch (ClassNotFoundException e) {
            LOG.info("ERROR: failed to load HSQLDB JDBC driver.");
            LOG.error("Unexpected error", e);
            return;
        }

        try {
            File dbDir = new File(Path.get(Path.USER_DATA), "db");
            iConnection = DriverManager.getConnection(
                    "jdbc:hsqldb:file:" + dbDir.getAbsolutePath() + File.separator + "JFSDB", "sa", "");
            iConnection.setAutoCommit(false);
            createNewTables();
            dropTriggers();
            createLocalTriggers();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
        }
    }
    
    /* Skapa demoföretaget i en tom databas. */
    private void checkCreateExampleCompany() {
        try {
            if (iConnection == null || iConnection.isClosed()) {
                return;
            }
            if (getCompanies().isEmpty()) {
                LOG.info("Creating demo company.");
                new DemoCompanyService(this).createIfDatabaseEmpty();
            }
        } catch (SQLException | RuntimeException e) {
            LOG.error("Could not create demo company", e);
        }
    }
    
    /* Add bundled account plans that are not already present by name. */
    void checkImportDefaultAccountPlans() {
        try {
            if (iConnection == null || iConnection.isClosed()) {
                return;
            }

            String[][] defaults = new String[][]{
                {"BAS 2026 - Aktiebolag", "BAS-2026---Aktiebolag.xls"},
                {"BAS 2026 - Ekonomisk förening", "BAS-2026---Ekonomisk-forening.xls"},
                {"BAS 2026 - Enskild firma K1", "BAS-2026---Enskild-firma-K1.xls"},
                {"BAS 2026 - Enskild firma, ej K1", "BAS-2026---Enskild-firma-ej-K1.xls"},
                {"BAS 2026 - Handelsbolag och kommanditbolag", "BAS-2026---Handelsbolag-och-kommanditbolag.xls"},
                {"BAS 2026 - Ideell förening, stiftelse och trossamfund", "BAS-2026---Ideell-forening-stiftelse-och-trossamfund.xls"},};

            Set<String> existingNames = getAccountPlans().stream()
                    .map(SSAccountPlan::getName)
                    .collect(Collectors.toSet());

            for (String[] accountPlan : defaults) {
                String name = accountPlan[0];
                String filename = accountPlan[1];
                if (existingNames.contains(name)) {
                    continue;
                }

                LOG.info("Adding default account plan: {}", name);
                String path = "account/default/" + filename;
                try (InputStream input = SSDB.class.getClassLoader().getResourceAsStream(path)) {
                    if (input == null) {
                        throw new RuntimeException("Resource not found: " + path);
                    }
                    SSAccountPlanImporter.doImport(input);
                    existingNames.add(name);
                } catch (IOException | SSImportException ex) {
                    LOG.error("Could not import default account plan " + name, ex);
                }
            }
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
        }
    }

    public void restart() {}

    public void delete() {
        try {
            PreparedStatement iStatement = iConnection.prepareStatement("SHUTDOWN");

            iStatement.executeUpdate();
            iStatement.close();
            iConnection.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        File iDbDir = new File(Path.get(Path.USER_DATA), "db");
        File iPropFile = new File(iDbDir, "JFSDB.properties");
        File iScriptFile = new File(iDbDir, "JFSDB.script");
        File iDataFile = new File(iDbDir, "JFSDB.data");
        File iBackupFile = new File(iDbDir, "JFSDB.backup");
        File iLogFile = new File(iDbDir, "JFSDB.log");

        if (iPropFile.exists()) {
            iPropFile.delete();
        }
        if (iScriptFile.exists()) {
            iScriptFile.delete();
        }
        if (iDataFile.exists()) {
            iDataFile.delete();
        }
        if (iBackupFile.exists()) {
            iBackupFile.delete();
        }
        if (iLogFile.exists()) {
            iLogFile.delete();
        }
    }

    public void clear() {}

    public void clearLists() {
        iProducts = null;
        iCustomers = null;
        iSuppliers = null;
        iAutoDists = null;
        iInpayments = null;
        iTenders = null;
        iOrders = null;
        iInvoices = null;
        iCreditInvoices = null;
        iPeriodicInvoices = null;
        iOutpayments = null;
        iPurchaseOrders = null;
        iSupplierInvoices = null;
        iSupplierCreditInvoices = null;
        iInventories = null;
        iIndeliveries = null;
        iOutdeliveries = null;
        iOwnReports = null;
    }

    public void setCurrentCompany(SSNewCompany iCompany) {
        iCurrentCompany = getCompany(iCompany).orElse(null);
        iProducts = null;
        iCustomers = null;
        iSuppliers = null;
        iAutoDists = null;
        iInpayments = null;
        iTenders = null;
        iOrders = null;
        iInvoices = null;
        iCreditInvoices = null;
        iPeriodicInvoices = null;
        iOutpayments = null;
        iPurchaseOrders = null;
        iSupplierInvoices = null;
        iSupplierCreditInvoices = null;
        iInventories = null;
        iIndeliveries = null;
        iOutdeliveries = null;
        iOwnReports = null;
        notifyListeners("COMPANY", iCurrentCompany, null);
    }

    public SSNewCompany getCurrentCompany() {
        iCurrentCompany = getCompany(iCurrentCompany).orElse(null);
        return iCurrentCompany;
    }

    public void setCurrentYear(SSNewAccountingYear iYear) {
        iCurrentYear = iYear;
        iVouchers = null;
        notifyListeners("YEAR", iCurrentYear, null);
    }

    public SSNewAccountingYear getCurrentYear() {
        return getAccountingYear(iCurrentYear).orElse(null);
    }

    public List<SSNewCompany> getCompanies() {
        List<SSNewCompany> iCompanies = null;

        try {
            iCompanies = new LinkedList<>();

            if (iConnection == null || iConnection.isClosed()) {
                return iCompanies;
            }

            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_company");
            ResultSet iResultSet = iStatement.executeQuery();

            while (iResultSet.next()) {
                iCompanies.add((SSNewCompany) iResultSet.getObject("company"));
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iCompanies;
    }

    public Optional<SSNewCompany> getCompany(SSNewCompany pCompany) {
        try {
            if (pCompany == null || iConnection.isClosed()) {
                return Optional.empty();
            }

            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_company WHERE id=?");

            iStatement.setObject(1, pCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSNewCompany iCompany = (SSNewCompany) iResultSet.getObject("company");

                iResultSet.close();
                iStatement.close();
                return Optional.of(iCompany);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public void addCompany(SSNewCompany iCompany) {
        if (iCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_company VALUES(NULL,?)");

            iStatement.setObject(1, iCompany);
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement("SELECT * FROM tbl_company");
            ResultSet iResultSet = iStatement.executeQuery();
            Integer iId = -1;

            while (iResultSet.next()) {
                if (iResultSet.isLast()) {
                    iId = iResultSet.getInt("id");
                }
            }
            iResultSet.close();
            iStatement.close();
            iCompany.setId(iId);

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                LOG.error("Unexpected error", e);
            }
            iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_company SET company=? WHERE id=?");
            iStatement.setObject(1, iCompany);
            iStatement.setObject(2, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateCompany(SSNewCompany iCompany) {
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_company SET company=? WHERE id=?");

            iStatement.setObject(1, iCompany);
            iStatement.setObject(2, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            notifyListeners("COMPANY", iCompany, null);

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteCompany(SSNewCompany iCompany) {
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_project WHERE companyid=?");

            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_resultunit WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_product WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_customer WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_supplier WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_vouchertemplate WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_autodist WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_inpayment WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_tender WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_order WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_invoice WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_creditinvoice WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_periodicinvoice WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_outpayment WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_purchaseorder WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_supplierinvoice WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_suppliercreditinvoice WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_inventory WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_indelivery WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_outdelivery WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_ownreport WHERE companyid=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            for (SSNewAccountingYear iYear : getYearsForCompany(iCompany)) {
                deleteAccountingYear(iYear);
            }

            iStatement = iConnection.prepareStatement("DELETE FROM tbl_company WHERE id=?");
            iStatement.setObject(1, iCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public List<SSNewAccountingYear> getYears() {
        List<SSNewAccountingYear> iYears = new LinkedList<>();

        if (iCurrentCompany != null) {
            try {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_accountingyear WHERE companyid=?");

                iStatement.setObject(1, iCurrentCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                while (iResultSet.next()) {
                    iYears.add(
                            (SSNewAccountingYear) iResultSet.getObject("accountingyear"));
                }
                iResultSet.close();
                iStatement.close();
            } catch (SQLException e) {
                LOG.error("Unexpected error", e);
                try {
                    iConnection.rollback();
                } catch (SQLException ignored) {}
                SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
            }
        }
        return iYears;
    }

    public List<SSNewAccountingYear> getYearsForCompany(SSNewCompany iCompany) {
        List<SSNewAccountingYear> iYears = new LinkedList<>();

        if (iCompany != null) {
            try {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_accountingyear WHERE companyid=?");

                iStatement.setObject(1, iCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                while (iResultSet.next()) {
                    iYears.add(
                            (SSNewAccountingYear) iResultSet.getObject("accountingyear"));
                }
                iResultSet.close();
                iStatement.close();
            } catch (SQLException e) {
                LOG.error("Unexpected error", e);
                try {
                    iConnection.rollback();
                } catch (SQLException ignored) {}
                SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
            }
        }
        return iYears;
    }

    public Optional<SSNewAccountingYear> getAccountingYear(SSNewAccountingYear pAccountingYear) {
        try {
            if (pAccountingYear == null) {
                return Optional.empty();
            }

            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_accountingyear WHERE id=?");

            iStatement.setObject(1, pAccountingYear.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSNewAccountingYear iAccountingYear = (SSNewAccountingYear) iResultSet.getObject(
                        "accountingyear");

                iResultSet.close();
                iStatement.close();
                return Optional.of(iAccountingYear);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public void addAccountingYear(SSNewAccountingYear iAccountingYear) {
        if (iAccountingYear == null) {
            return;
        }
        if (iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_accountingyear VALUES(NULL,?,?)");

            iStatement.setObject(1, iAccountingYear);
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement("SELECT * FROM tbl_accountingyear");
            ResultSet iResultSet = iStatement.executeQuery();
            Integer iId = -1;

            while (iResultSet.next()) {
                if (iResultSet.isLast()) {
                    iId = iResultSet.getInt("id");
                }
            }
            iAccountingYear.setId(iId);
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_accountingyear SET accountingyear=? WHERE id=?");
            iStatement.setObject(1, iAccountingYear);
            iStatement.setObject(2, iAccountingYear.getId());
            iStatement.executeUpdate();
            iConnection.commit();

            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateAccountingYear(SSNewAccountingYear iAccountingYear) {
        if (iAccountingYear == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_accountingyear SET accountingyear=? WHERE id=?");

            iStatement.setObject(1, iAccountingYear);
            iStatement.setObject(2, iAccountingYear.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            if (iAccountingYear.equals(iCurrentYear)) {
                iCurrentYear = iAccountingYear;
                notifyListeners("YEAR", iAccountingYear, null);
            }

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteAccountingYear(SSNewAccountingYear iAccountingYear) {
        if (iAccountingYear == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_voucher WHERE yearid=?");

            iStatement.setObject(1, iAccountingYear.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_accountingyear WHERE id=?");
            iStatement.setObject(1, iAccountingYear.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public Optional<SSNewAccountingYear> getPreviousYear() {
        iCurrentYear = getCurrentYear();
        if (iCurrentYear == null) {
            return Optional.empty();
        }
        List<SSNewAccountingYear> iYears = getYears();

        java.time.LocalDate iFirstDayOfCurrent = iCurrentYear.getLocalFrom();

        // Get the last day of the previous year (day before the current year starts)
        java.time.LocalDate dayBeforeCurrent = iFirstDayOfCurrent.minusDays(1);

        for (SSNewAccountingYear iAccountingYear : iYears) {
            java.time.LocalDate lastDayOfYear = iAccountingYear.getLocalTo();
            if (dayBeforeCurrent.equals(lastDayOfYear)) {
                return Optional.of(iAccountingYear);
            }
        }
        return Optional.empty();
    }

    public Optional<SSNewAccountingYear> getLastYear() {
        List<SSNewAccountingYear> iYears = new LinkedList<>();

        if (iCurrentCompany != null) {
            try {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_accountingyear WHERE companyid=?");

                iStatement.setObject(1, iCurrentCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                while (iResultSet.next()) {
                    iYears.add(
                            (SSNewAccountingYear) iResultSet.getObject("accountingyear"));
                }
                iResultSet.close();
                iStatement.close();

                java.time.LocalDate iLastDate = null;
                SSNewAccountingYear iAccountingYear = null;

                for (SSNewAccountingYear iYear : iYears) {
                    if (iLastDate == null) {
                        iLastDate = iYear.getLocalTo();
                        iAccountingYear = iYear;
                    }
                    java.time.LocalDate iTo = iYear.getLocalTo();

                    if (iTo.isAfter(iLastDate)) {
                        iLastDate = iTo;
                        iAccountingYear = iYear;
                    }
                }
                return Optional.ofNullable(iAccountingYear);
            } catch (SQLException e) {
                LOG.error("Unexpected error", e);
                try {
                    iConnection.rollback();
                } catch (SQLException ignored) {}
                SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
            }
        }
        return Optional.empty();
    }

    /**
     *
     * Adds a property listerner to the database, the avaiable properties is:
     *   IO      : I/O event
     *   COMPANY : Changed active company
     *   YEAR    : Changed active year
     *
     * @param pProperty
     * @param pPropertyChangeListener
     */
    public void addPropertyChangeListener(String pProperty, PropertyChangeListener pPropertyChangeListener) {
        List<PropertyChangeListener> iPropertyChangeListeners = iListenerMap.get(pProperty);

        if (iPropertyChangeListeners == null) {
            iPropertyChangeListeners = new LinkedList<>();

            iListenerMap.put(pProperty, iPropertyChangeListeners);
        }

        iPropertyChangeListeners.add(pPropertyChangeListener);
    }

    /**
     *
     * @param pProperty
     * @param pNewValue
     * @param pOldValue
     */
    public void notifyListeners(String pProperty, Object pNewValue, Object pOldValue) {

        List<PropertyChangeListener> iPropertyChangeListeners = iListenerMap.get(pProperty);

        if (iPropertyChangeListeners == null) {
            return;
        }

        PropertyChangeEvent iPropertyChangeEvent = new PropertyChangeEvent(this, pProperty,
                pOldValue, pNewValue);

        for (PropertyChangeListener iPropertyChangeListener : iPropertyChangeListeners) {
            iPropertyChangeListener.propertyChange(iPropertyChangeEvent);
        }
    }

    public Optional<SSAutoIncrement> getAutoIncrement() {
        return Optional.empty();
    }

    public List<SSVoucher> getVouchers() {
        if (iVouchers != null) {
            return iVouchers;
        }
        iVouchers = new LinkedList<>();
        if (iCurrentYear == null) {
            return iVouchers;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_voucher WHERE yearid=? AND id>?");
                iStatement.setObject(1, iCurrentYear.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);

                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iVouchers.add((SSVoucher) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iVouchers;
    }

    public List<SSVoucher> getVouchers(SSNewAccountingYear iAccountingYear) {
        List<SSVoucher> iVoucherList = new LinkedList<>();

        if (iAccountingYear == null) {
            return iVoucherList;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_voucher WHERE yearid=? AND id>?");
                iStatement.setObject(1, iAccountingYear.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);

                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iVoucherList.add((SSVoucher) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iVoucherList;
    }

    public Optional<SSVoucher> getVoucher(SSVoucher pVoucher) {
        if (pVoucher == null || iCurrentYear == null) {
            return Optional.empty();
        }

        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_voucher WHERE number=? AND yearid=?");

            iStatement.setObject(1, pVoucher.getNumber());
            iStatement.setObject(2, iCurrentYear.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSVoucher iVoucher = (SSVoucher) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iVoucher);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public List<SSVoucher> getVouchers(List<SSVoucher> pVouchers) {
        if (pVouchers == null || iCurrentYear == null) {
            return Collections.emptyList();
        }
        List<SSVoucher> iVouchers = new LinkedList<>();

        try {
            for (SSVoucher iVoucher : pVouchers) {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_voucher WHERE number=? AND yearid=?");

                iStatement.setObject(1, iVoucher.getNumber());
                iStatement.setObject(2, iCurrentYear.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                if (iResultSet.next()) {
                    iVouchers.add((SSVoucher) iResultSet.getObject("voucher"));
                }
                iStatement.close();
            }

            return iVouchers;
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Collections.emptyList();
    }

    public void addVoucher(SSVoucher iVoucher, boolean iHasNumber) {
        if (iVoucher == null || iCurrentYear == null) {
            return;
        }
        try {
            PreparedStatement iStatement;

            if (!iHasNumber) {
                iStatement = iConnection.prepareStatement(
                        "SELECT MAX(number) AS maxnum FROM tbl_voucher WHERE yearid=?");
                iStatement.setObject(1, iCurrentYear.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                if (iResultSet.next()) {
                    Integer iNumber = iResultSet.getInt("maxnum");

                    iVoucher.setNumber(iNumber + 1);
                } else {
                    iVoucher.setNumber(1);
                }
                iResultSet.close();
                iStatement.close();
            }

            iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_voucher VALUES(NULL,?,?,?)");
            iStatement.setObject(1, iVoucher.getNumber());
            iStatement.setObject(2, iVoucher);
            iStatement.setObject(3, iCurrentYear.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public Integer getLastVoucherNumber() {
        if (iCurrentYear == null) {
            return 0;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT MAX(number) AS maxnum FROM tbl_voucher WHERE yearid=?");

            iStatement.setObject(1, iCurrentYear.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            Integer iNumber = 0;

            if (iResultSet.next()) {
                iNumber = iResultSet.getInt("maxnum");
            }
            iResultSet.close();
            iStatement.close();

            return iNumber;
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return 0;
    }

    public void updateVoucher(SSVoucher iVoucher) {
        if (iVoucher == null || iCurrentYear == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_voucher SET voucher=? WHERE number=? AND yearid=?");

            iStatement.setObject(1, iVoucher);
            iStatement.setObject(2, iVoucher.getNumber());
            iStatement.setObject(3, iCurrentYear.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteVoucher(SSVoucher iVoucher) {
        if (iVoucher == null || iCurrentYear == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_voucher WHERE number=? AND yearid=?");

            iStatement.setObject(1, iVoucher.getNumber());
            iStatement.setObject(2, iCurrentYear.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public List<SSVoucherTemplate> getVoucherTemplates() {
        List<SSVoucherTemplate> iVoucherTemplates = new LinkedList<>();

        if (iCurrentCompany == null) {
            return iVoucherTemplates;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_vouchertemplate WHERE companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();
            int i = 0;

            while (iResultSet.next()) {
                iVoucherTemplates.add((SSVoucherTemplate) iResultSet.getObject(2));
                i++;
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iVoucherTemplates;
    }

    public List<SSVoucherTemplate> getVoucherTemplates(List<SSVoucherTemplate> pVoucherTemplates) {
        if (pVoucherTemplates == null) {
            return Collections.emptyList();
        }
        List<SSVoucherTemplate> iVoucherTemplates = new LinkedList<>();

        if (iCurrentCompany == null) {
            return iVoucherTemplates;
        }
        try {
            for (SSVoucherTemplate iVoucherTemplate : pVoucherTemplates) {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_vouchertemplate WHERE name=? AND companyid=?");

                iStatement.setObject(1, iVoucherTemplate.getDescription());
                iStatement.setObject(2, iCurrentCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                if (iResultSet.next()) {
                    iVoucherTemplates.add((SSVoucherTemplate) iResultSet.getObject(2));
                }
                iStatement.close();
            }

            return iVoucherTemplates;
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Collections.emptyList();
    }

    public void addVoucherTemplate(SSVoucherTemplate iVoucherTemplate) {
        if (iVoucherTemplate == null) {
            return;
        }
        if (iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_vouchertemplate SET vouchertemplate=? WHERE name=? AND companyid=?");

            iStatement.setObject(1, iVoucherTemplate);
            iStatement.setObject(2, iVoucherTemplate.getDescription());
            iStatement.setObject(3, iCurrentCompany.getId());
            int updatedRows = iStatement.executeUpdate();
            iStatement.close();

            if (updatedRows == 0) {
                iStatement = iConnection.prepareStatement(
                        "INSERT INTO tbl_vouchertemplate VALUES(?,?,?)");
                iStatement.setObject(1, iVoucherTemplate.getDescription());
                iStatement.setObject(2, iVoucherTemplate);
                iStatement.setObject(3, iCurrentCompany.getId());
                iStatement.executeUpdate();
                iStatement.close();
            }
            iConnection.commit();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteVoucherTemplate(SSVoucherTemplate iVoucherTemplate) {
        if (iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_vouchertemplate WHERE name=? AND companyid=?");

            iStatement.setObject(1, iVoucherTemplate.getDescription());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public List<SSAccount> getAccounts() {
        return iCurrentYear == null
                ? new LinkedList<>()
                : iCurrentYear.getAccounts();
    }

    /**
     * Retuns the account plan for the current year
     *
     * @return the acoount plan for the current year
     */
    public SSAccountPlan getCurrentAccountPlan() {

        if (iCurrentYear != null) {
            return iCurrentYear.getAccountPlan();
        }
        return new SSAccountPlan("Default");
    }

    public List<SSAccountPlan> getAccountPlans() {
        List<SSAccountPlan> iAccountPlans = new LinkedList<>();

        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_accountplan");
            ResultSet iResultSet = iStatement.executeQuery();

            while (iResultSet.next()) {
                iAccountPlans.add((SSAccountPlan) iResultSet.getObject("accountplan"));
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iAccountPlans;
    }

    public Optional<SSAccountPlan> getAccountPlan(SSAccountPlan pAccountPlan) {
        if (pAccountPlan == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_accountplan WHERE id=?");

            iStatement.setObject(1, pAccountPlan.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSAccountPlan iAccountPlan = (SSAccountPlan) iResultSet.getObject(
                        "accountplan");

                iStatement.close();
                return Optional.of(iAccountPlan);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public void addAccountPlan(SSAccountPlan iAccountPlan) {
        if (iAccountPlan == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_accountplan VALUES(NULL,?)");

            iStatement.setObject(1, iAccountPlan);
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement("SELECT * FROM tbl_accountplan");
            ResultSet iResultSet = iStatement.executeQuery();
            Integer iId = -1;

            while (iResultSet.next()) {
                if (iResultSet.isLast()) {
                    iId = iResultSet.getInt("id");
                }
            }
            iAccountPlan.setId(iId);
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_accountplan SET accountplan=? WHERE id=?");
            iStatement.setObject(1, iAccountPlan);
            iStatement.setObject(2, iAccountPlan.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateAccountPlan(SSAccountPlan iAccountPlan) {
        if (iAccountPlan == null) {
            return;
        }

        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_accountplan SET accountplan=? WHERE id=?");

            iStatement.setObject(1, iAccountPlan);
            iStatement.setObject(2, iAccountPlan.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteAccountPlan(SSAccountPlan iAccountPlan) {
        if (iAccountPlan == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_accountplan WHERE id=?");

            iStatement.setObject(1, iAccountPlan.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public List<SSUnit> getUnits() {
        List<SSUnit> iUnits = new LinkedList<>();

        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_unit");
            ResultSet iResultSet = iStatement.executeQuery();

            while (iResultSet.next()) {
                iUnits.add((SSUnit) iResultSet.getObject("unit"));
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iUnits;
    }

    public void addUnit(SSUnit iUnit) {
        if (iUnit == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_unit VALUES(?,?)");

            iStatement.setObject(1, iUnit.getName());
            iStatement.setObject(2, iUnit);
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateUnit(SSUnit iUnit) {
        if (iUnit == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_unit SET unit=? WHERE name=?");

            iStatement.setObject(1, iUnit);
            iStatement.setObject(2, iUnit.getName());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteUnit(SSUnit iUnit) {
        if (iUnit == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_unit WHERE name=?");

            iStatement.setObject(1, iUnit.getName());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns a List of the current curriencies
     *
     * @return A List of curriencies.
     */
    public List<SSCurrency> getCurrencies() {
        List<SSCurrency> iCurrencies = new LinkedList<>();

        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_currency");
            ResultSet iResultSet = iStatement.executeQuery();

            while (iResultSet.next()) {
                iCurrencies.add((SSCurrency) iResultSet.getObject("currency"));
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iCurrencies;
    }

    public Optional<SSCurrency> getCurrency(SSCurrency iCurrency) {
        SSCurrency iUpdatedCurrency = new SSCurrency();

        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_currency WHERE code=?");

            iStatement.setObject(1, iCurrency.getName());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                iUpdatedCurrency = (SSCurrency) iResultSet.getObject("currency");
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.of(iUpdatedCurrency);
    }

    public void addCurrency(SSCurrency iCurrency) {
        if (iCurrency == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_currency VALUES(?,?)");

            iStatement.setObject(1, iCurrency.getName());
            iStatement.setObject(2, iCurrency);
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateCurrency(SSCurrency iCurrency) {
        if (iCurrency == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_currency SET currency=? WHERE code=?");

            iStatement.setObject(1, iCurrency);
            iStatement.setObject(2, iCurrency.getName());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteCurrency(SSCurrency iCurrency) {
        if (iCurrency == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_currency WHERE code=?");

            iStatement.setObject(1, iCurrency.getName());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns the delivery ways
     *
     * @return a list of deliveryways
     */
    public List<SSDeliveryWay> getDeliveryWays() {
        List<SSDeliveryWay> iDeliveryWays = new LinkedList<>();

        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_deliveryway");
            ResultSet iResultSet = iStatement.executeQuery();

            while (iResultSet.next()) {
                iDeliveryWays.add((SSDeliveryWay) iResultSet.getObject("deliveryway"));
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iDeliveryWays;
    }

    public void addDeliveryWay(SSDeliveryWay iDeliveryWay) {
        if (iDeliveryWay == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_deliveryway VALUES(?,?)");

            iStatement.setObject(1, iDeliveryWay.getName());
            iStatement.setObject(2, iDeliveryWay);
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateDeliveryWay(SSDeliveryWay iDeliveryWay) {
        if (iDeliveryWay == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_deliveryway SET deliveryway=? WHERE name=?");

            iStatement.setObject(1, iDeliveryWay);
            iStatement.setObject(2, iDeliveryWay.getName());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteDeliveryWay(SSDeliveryWay iDeliveryWay) {
        if (iDeliveryWay == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_deliveryway WHERE name=?");

            iStatement.setObject(1, iDeliveryWay.getName());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     * Retuns a list of delivery terms.
     *
     * @return a list of delivery terms
     */
    public List<SSDeliveryTerm> getDeliveryTerms() {
        List<SSDeliveryTerm> iDeliveryTerms = new LinkedList<>();

        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_deliveryterm");
            ResultSet iResultSet = iStatement.executeQuery();

            while (iResultSet.next()) {
                iDeliveryTerms.add((SSDeliveryTerm) iResultSet.getObject("deliveryterm"));
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iDeliveryTerms;
    }

    public void addDeliveryTerm(SSDeliveryTerm iDeliveryTerm) {
        if (iDeliveryTerm == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_deliveryterm VALUES(?,?)");

            iStatement.setObject(1, iDeliveryTerm.getName());
            iStatement.setObject(2, iDeliveryTerm);
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateDeliveryTerm(SSDeliveryTerm iDeliveryTerm) {
        if (iDeliveryTerm == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_deliveryterm SET deliveryterm=? WHERE name=?");

            iStatement.setObject(1, iDeliveryTerm);
            iStatement.setObject(2, iDeliveryTerm.getName());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteDeliveryTerm(SSDeliveryTerm iDeliveryTerm) {
        if (iDeliveryTerm == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_deliveryterm WHERE name=?");

            iStatement.setObject(1, iDeliveryTerm.getName());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     *  Returns the payment terms
     *
     * @return a list of payment terms
     */
    public List<SSPaymentTerm> getPaymentTerms() {
        List<SSPaymentTerm> iPaymentTerms = new LinkedList<>();

        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_paymentterm");
            ResultSet iResultSet = iStatement.executeQuery();

            while (iResultSet.next()) {
                iPaymentTerms.add((SSPaymentTerm) iResultSet.getObject("paymentterm"));
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iPaymentTerms;
    }

    public void addPaymentTerm(SSPaymentTerm iPaymentTerm) {
        if (iPaymentTerm == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_paymentterm VALUES(?,?)");

            iStatement.setObject(1, iPaymentTerm.getName());
            iStatement.setObject(2, iPaymentTerm);
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updatePaymentTerm(SSPaymentTerm iPaymentTerm) {
        if (iPaymentTerm == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_paymentterm SET paymentterm=? WHERE name=?");

            iStatement.setObject(1, iPaymentTerm);
            iStatement.setObject(2, iPaymentTerm.getName());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deletePaymentTerm(SSPaymentTerm iPaymentTerm) {
        if (iPaymentTerm == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_paymentterm WHERE name=?");

            iStatement.setObject(1, iPaymentTerm.getName());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    public List<SSNewResultUnit> getResultUnits() {
        List<SSNewResultUnit> iResultUnits = new LinkedList<>();

        if (iCurrentCompany == null) {
            return iResultUnits;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_resultunit WHERE companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            while (iResultSet.next()) {
                iResultUnits.add((SSNewResultUnit) iResultSet.getObject("resultunit"));
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iResultUnits;
    }

    public Optional<SSNewResultUnit> getResultUnit(SSNewResultUnit pResultUnit) {
        if (pResultUnit == null) {
            return Optional.empty();
        }
        if (iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_resultunit WHERE number=? AND companyid=?");

            iStatement.setObject(1, pResultUnit.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSNewResultUnit iResultUnit = (SSNewResultUnit) iResultSet.getObject(
                        "resultunit");

                iStatement.close();
                return Optional.of(iResultUnit);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public Optional<SSNewResultUnit> getResultUnit(String pResultUnitNumber) {
        if (pResultUnitNumber == null) {
            return Optional.empty();
        }
        if (iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_resultunit WHERE number=? AND companyid=?");

            iStatement.setObject(1, pResultUnitNumber);
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSNewResultUnit iResultUnit = (SSNewResultUnit) iResultSet.getObject(
                        "resultunit");

                iStatement.close();
                return Optional.of(iResultUnit);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public List<SSNewResultUnit> getResultUnits(List<SSNewResultUnit> pResultUnits) {
        if (pResultUnits == null) {
            return Collections.emptyList();
        }
        List<SSNewResultUnit> iResultUnits = new LinkedList<>();

        if (iCurrentCompany == null) {
            return iResultUnits;
        }
        try {
            for (SSNewResultUnit iResultUnit : pResultUnits) {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_resultunit WHERE number=? AND companyid=?");

                iStatement.setObject(1, iResultUnit.getNumber());
                iStatement.setObject(2, iCurrentCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                if (iResultSet.next()) {
                    iResultUnits.add((SSNewResultUnit) iResultSet.getObject("resultunit"));
                }
                iStatement.close();
            }

            return iResultUnits;
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Collections.emptyList();
    }

    public void addResultUnit(SSNewResultUnit iResultUnit) {
        if (iResultUnit == null) {
            return;
        }
        if (iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_resultunit VALUES(?,?,?)");

            iStatement.setObject(1, iResultUnit.getNumber());
            iStatement.setObject(2, iResultUnit);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateResultUnit(SSNewResultUnit iResultUnit) {
        if (iResultUnit == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_resultunit SET resultunit=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iResultUnit);
            iStatement.setObject(2, iResultUnit.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteResultUnit(SSNewResultUnit iResultUnit) {
        if (iResultUnit == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_resultunit WHERE number=? AND companyid=?");

            iStatement.setObject(1, iResultUnit.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    public List<SSNewProject> getProjects() {
        List<SSNewProject> iProjects = new LinkedList<>();

        if (iCurrentCompany == null) {
            return iProjects;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_project WHERE companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            while (iResultSet.next()) {
                iProjects.add((SSNewProject) iResultSet.getObject("project"));
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iProjects;
    }

    public Optional<SSNewProject> getProject(SSNewProject pProject) {
        if (pProject == null) {
            return Optional.empty();
        }
        if (iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_project WHERE number=? AND companyid=?");

            iStatement.setObject(1, pProject.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSNewProject iProject = (SSNewProject) iResultSet.getObject("project");

                iStatement.close();
                return Optional.of(iProject);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public Optional<SSNewProject> getProject(String pProjectNumber) {
        if (pProjectNumber == null) {
            return Optional.empty();
        }
        if (iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_project WHERE number=? AND companyid=?");

            iStatement.setObject(1, pProjectNumber);
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSNewProject iProject = (SSNewProject) iResultSet.getObject("project");

                iStatement.close();
                return Optional.of(iProject);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public List<SSNewProject> getProjects(List<SSNewProject> pProjects) {
        if (pProjects == null) {
            return Collections.emptyList();
        }
        List<SSNewProject> iProjects = new LinkedList<>();

        if (iCurrentCompany == null) {
            return iProjects;
        }
        try {
            for (SSNewProject iProject : pProjects) {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_project WHERE number=? AND companyid=?");

                iStatement.setObject(1, iProject.getNumber());
                iStatement.setObject(2, iCurrentCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                if (iResultSet.next()) {
                    iProjects.add((SSNewProject) iResultSet.getObject("project"));
                }
                iStatement.close();
            }

            return iProjects;
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Collections.emptyList();
    }

    public void addProject(SSNewProject iProject) {
        if (iProject == null) {
            return;
        }
        if (iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_project VALUES(?,?,?)");

            iStatement.setObject(1, iProject.getNumber());
            iStatement.setObject(2, iProject);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateProject(SSNewProject iProject) {
        if (iProject == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_project SET project=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iProject);
            iStatement.setObject(2, iProject.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteProject(SSNewProject iProject) {
        if (iProject == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_project WHERE number=? AND companyid=?");

            iStatement.setObject(1, iProject.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    public synchronized void triggerAction(String iTriggerName, String iTableName, String iNumber) {

        /** Körs då en trigger triggas i databasen. De flesta triggers uppdaterar listan som
         *  som motsvarar objekten triggen körts på. Projekt, Resultatenhet och konteringsmallar får
         *  behandlas något annorlunda då dessa inte lästs in i minnet vid uppstart.
         */

        try {

            /**
             *  REGISTER
             */
            if (iTriggerName.contains("PROJECT")) {
                if (SSProjectFrame.getInstance() != null) {
                    SSProjectFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.contains("RESULTUNIT")) {
                if (SSResultUnitFrame.getInstance() != null) {
                    SSResultUnitFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("NEWPRODUCT") && iProducts != null) {
                SSProduct iProduct = new SSProduct();

                iProduct.setNumber(iNumber);
                Optional<SSProduct> optProduct = getProduct(iProduct);
                if (optProduct.isEmpty()) {
                    LOG.warn("NEWPRODUCT trigger: product not found for number {}", iNumber);
                    return;
                }
                iProduct = optProduct.get();

                iProducts.add(iProduct);
                iProduct = null;
                if (SSProductFrame.getInstance() != null) {
                    SSProductFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("EDITPRODUCT") && iProducts != null) {
                SSProduct iProduct = new SSProduct();

                iProduct.setNumber(iNumber);
                Optional<SSProduct> optProduct = getProduct(iProduct);
                if (optProduct.isEmpty()) {
                    LOG.warn("EDITPRODUCT trigger: product not found for number {}", iNumber);
                    return;
                }
                iProduct = optProduct.get();
                int iIndex = iProducts.lastIndexOf(iProduct);

                if (iIndex == -1) {
                    return;
                }
                iProducts.remove(iIndex);
                iProducts.add(iIndex, iProduct);
                iProduct = null;
                if (SSProductFrame.getInstance() != null) {
                    SSProductFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETEPRODUCT") && iProducts != null) {
                SSProduct iProduct = new SSProduct();

                iProduct.setNumber(iNumber);
                iProducts.remove(iProduct);
                iProduct = null;
                if (SSProductFrame.getInstance() != null) {
                    SSProductFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("NEWCUSTOMER") && iCustomers != null) {
                SSCustomer iCustomer = new SSCustomer();

                iCustomer.setNumber(iNumber);
                Optional<SSCustomer> optCustomer = getCustomer(iCustomer);
                if (optCustomer.isEmpty()) {
                    LOG.warn("NEWCUSTOMER trigger: customer not found for number {}", iNumber);
                    return;
                }
                iCustomer = optCustomer.get();
                iCustomers.add(iCustomer);
                SSCustomerMath.iInvoicesForCustomers.put(iCustomer.getNumber(),
                        new LinkedList<>());
                iCustomer = null;
                if (SSCustomerFrame.getInstance() != null) {
                    SSCustomerFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("EDITCUSTOMER") && iCustomers != null) {
                SSCustomer iCustomer = new SSCustomer();

                iCustomer.setNumber(iNumber);
                Optional<SSCustomer> optCustomer = getCustomer(iCustomer);
                if (optCustomer.isEmpty()) {
                    LOG.warn("EDITCUSTOMER trigger: customer not found for number {}", iNumber);
                    return;
                }
                iCustomer = optCustomer.get();
                int iIndex = iCustomers.lastIndexOf(iCustomer);

                if (iIndex == -1) {
                    return;
                }
                iCustomers.remove(iIndex);
                iCustomers.add(iIndex, iCustomer);
                iCustomer = null;
                if (SSCustomerFrame.getInstance() != null) {
                    SSCustomerFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETECUSTOMER") && iCustomers != null) {
                SSCustomer iCustomer = new SSCustomer();

                iCustomer.setNumber(iNumber);
                iCustomers.remove(iCustomer);
                iCustomer = null;
                if (SSCustomerFrame.getInstance() != null) {
                    SSCustomerFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("NEWSUPPLIER") && iSuppliers != null) {
                SSSupplier iSupplier = new SSSupplier();

                iSupplier.setNumber(iNumber);
                Optional<SSSupplier> optSupplier = getSupplier(iSupplier);
                if (optSupplier.isEmpty()) {
                    LOG.warn("NEWSUPPLIER trigger: supplier not found for number {}", iNumber);
                    return;
                }
                iSupplier = optSupplier.get();
                iSuppliers.add(iSupplier);
                SSSupplierMath.iInvoicesForSuppliers.put(iSupplier.getNumber(),
                        new LinkedList<>());
                iSupplier = null;
                if (SSSupplierFrame.getInstance() != null) {
                    SSSupplierFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("EDITSUPPLIER") && iSuppliers != null) {
                SSSupplier iSupplier = new SSSupplier();

                iSupplier.setNumber(iNumber);
                Optional<SSSupplier> optSupplier = getSupplier(iSupplier);
                if (optSupplier.isEmpty()) {
                    LOG.warn("EDITSUPPLIER trigger: supplier not found for number {}", iNumber);
                    return;
                }
                iSupplier = optSupplier.get();
                int iIndex = iSuppliers.lastIndexOf(iSupplier);

                if (iIndex == -1) {
                    return;
                }
                iSuppliers.remove(iIndex);
                iSuppliers.add(iIndex, iSupplier);
                iSupplier = null;
                if (SSSupplierFrame.getInstance() != null) {
                    SSSupplierFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETESUPPLIER") && iSuppliers != null) {
                SSSupplier iSupplier = new SSSupplier();

                iSupplier.setNumber(iNumber);
                iSuppliers.remove(iSupplier);
                iSupplier = null;
                if (SSSupplierFrame.getInstance() != null) {
                    SSSupplierFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.contains("VOUCHERTEMPLATE")) {
                if (SSVoucherTemplateFrame.getInstance() != null) {
                    SSVoucherTemplateFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("NEWAUTODIST") && iAutoDists != null) {
                Integer iAccount = Integer.parseInt(iNumber);
                SSAutoDist iAutoDist = new SSAutoDist();

                iAutoDist.setAccountNumber(iAccount);
                Optional<SSAutoDist> optAutoDist = getAutoDist(iAutoDist);
                if (optAutoDist.isEmpty()) {
                    LOG.warn("NEWAUTODIST trigger: autodist not found for number {}", iNumber);
                    return;
                }
                iAutoDist = optAutoDist.get();
                iAutoDists.add(iAutoDist);
                iAutoDist = null;
                if (SSAutoDistFrame.getInstance() != null) {
                    SSAutoDistFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("EDITAUTODIST") && iAutoDists != null) {
                Integer iAccount = Integer.parseInt(iNumber);
                SSAutoDist iAutoDist = new SSAutoDist();

                iAutoDist.setAccountNumber(iAccount);
                Optional<SSAutoDist> optAutoDist = getAutoDist(iAutoDist);
                if (optAutoDist.isEmpty()) {
                    LOG.warn("EDITAUTODIST trigger: autodist not found for number {}", iNumber);
                    return;
                }
                iAutoDist = optAutoDist.get();
                int iIndex = iAutoDists.lastIndexOf(iAutoDist);

                if (iIndex == -1) {
                    return;
                }
                iAutoDists.remove(iIndex);
                iAutoDists.add(iIndex, iAutoDist);
                iAutoDist = null;
                if (SSAutoDistFrame.getInstance() != null) {
                    SSAutoDistFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETEAUTODIST") && iAutoDists != null) {
                Integer iAccount = Integer.parseInt(iNumber);
                SSAutoDist iAutoDist = new SSAutoDist();

                iAutoDist.setAccountNumber(iAccount);
                iAutoDists.remove(iAutoDist);
                iAutoDist = null;
                if (SSAutoDistFrame.getInstance() != null) {
                    SSAutoDistFrame.getInstance().updateFrame();
                }
            } /**
             * FÖRSÄLJNING
             */ else if (iTriggerName.equals("NEWINPAYMENT") && iInpayments != null) {
                SSInpayment iInpayment = new SSInpayment();

                iInpayment.setNumber(Integer.parseInt(iNumber));
                Optional<SSInpayment> optInpayment = getInpayment(iInpayment);
                if (optInpayment.isEmpty()) {
                    LOG.warn("NEWINPAYMENT trigger: inpayment not found for number {}", iNumber);
                    return;
                }
                iInpayment = optInpayment.get();
                if (!iInpayments.contains(iInpayment)) {
                    iInpayments.add(iInpayment);
                }
                for (SSInpaymentRow iRow : iInpayment.getRows()) {
                    if (iRow.getValue() != null && iRow.getInvoiceNr() != null) {
                        if (SSInvoiceMath.iSaldoMap.containsKey(iRow.getInvoiceNr())) {
                            SSInvoiceMath.iSaldoMap.put(iRow.getInvoiceNr(),
                                    SSInvoiceMath.iSaldoMap.get(iRow.getInvoiceNr()).subtract(
                                    iRow.getValue()));
                        }
                    }
                }
                if (SSCustomerFrame.getInstance() != null) {
                    SSCustomerFrame.getInstance().updateFrame();
                }
                if (SSInvoiceFrame.getInstance() != null) {
                    SSInvoiceFrame.getInstance().updateFrame();
                }
                if (SSInpaymentFrame.getInstance() != null) {
                    SSInpaymentFrame.getInstance().updateFrame();
                }
                iInpayment = null;
            } else if (iTriggerName.equals("EDITINPAYMENT") && iInpayments != null) {
                SSInpayment iInpayment = new SSInpayment();

                iInpayment.setNumber(Integer.parseInt(iNumber));
                Optional<SSInpayment> optInpayment = getInpayment(iInpayment);
                if (optInpayment.isEmpty()) {
                    LOG.warn("EDITINPAYMENT trigger: entity not found for number {}", iNumber);
                    return;
                }
                iInpayment = optInpayment.get();
                int iIndex = iInpayments.lastIndexOf(iInpayment);

                if (iIndex == -1) {
                    return;
                }
                SSInpayment iOldInpayment = iInpayments.get(iIndex);

                for (SSInpaymentRow iRow : iOldInpayment.getRows()) {
                    if (iRow.getValue() != null && iRow.getInvoiceNr() != null) {
                        if (SSInvoiceMath.iSaldoMap.containsKey(iRow.getInvoiceNr())) {
                            SSInvoiceMath.iSaldoMap.put(iRow.getInvoiceNr(),
                                    SSInvoiceMath.iSaldoMap.get(iRow.getInvoiceNr()).add(
                                    iRow.getValue()));
                        }
                    }
                }
                iInpayments.remove(iIndex);

                iInpayments.add(iIndex, iInpayment);
                for (SSInpaymentRow iRow : iInpayment.getRows()) {
                    if (iRow.getValue() != null && iRow.getInvoiceNr() != null) {
                        if (SSInvoiceMath.iSaldoMap.containsKey(iRow.getInvoiceNr())) {
                            SSInvoiceMath.iSaldoMap.put(iRow.getInvoiceNr(),
                                    SSInvoiceMath.iSaldoMap.get(iRow.getInvoiceNr()).subtract(
                                    iRow.getValue()));
                        }
                    }
                }
                if (SSCustomerFrame.getInstance() != null) {
                    SSCustomerFrame.getInstance().updateFrame();
                }
                if (SSInvoiceFrame.getInstance() != null) {
                    SSInvoiceFrame.getInstance().updateFrame();
                }
                iInpayment = null;
                if (SSInpaymentFrame.getInstance() != null) {
                    SSInpaymentFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETEINPAYMENT") && iInpayments != null) {
                SSInpayment iInpayment = new SSInpayment();

                iInpayment.setNumber(Integer.parseInt(iNumber));
                iInpayments.remove(iInpayment);

                iInpayment = null;
                if (SSCustomerFrame.getInstance() != null) {
                    SSCustomerFrame.getInstance().updateFrame();
                }
                if (SSInvoiceFrame.getInstance() != null) {
                    SSInvoiceFrame.getInstance().updateFrame();
                }
                if (SSInpaymentFrame.getInstance() != null) {
                    SSInpaymentFrame.getInstance().updateFrame();
                }

            } else if (iTriggerName.equals("NEWTENDER") && iTenders != null) {
                SSTender iTender = new SSTender();

                iTender.setNumber(Integer.parseInt(iNumber));
                Optional<SSTender> optTender = getTender(iTender);
                if (optTender.isEmpty()) {
                    LOG.warn("NEWTENDER trigger: entity not found for number {}", iNumber);
                    return;
                }
                iTender = optTender.get();
                if (!iTenders.contains(iTender)) {
                    iTenders.add(iTender);
                }
                if (SSTenderFrame.getInstance() != null) {
                    SSTenderFrame.getInstance().updateFrame();
                }
                iTender = null;
            } else if (iTriggerName.equals("EDITTENDER") && iTenders != null) {
                SSTender iTender = new SSTender();

                iTender.setNumber(Integer.parseInt(iNumber));
                Optional<SSTender> optTender = getTender(iTender);
                if (optTender.isEmpty()) {
                    LOG.warn("EDITTENDER trigger: entity not found for number {}", iNumber);
                    return;
                }
                iTender = optTender.get();
                int iIndex = iTenders.lastIndexOf(iTender);

                if (iIndex == -1) {
                    return;
                }
                iTenders.remove(iIndex);
                iTenders.add(iIndex, iTender);
                iTender = null;
                if (SSTenderFrame.getInstance() != null) {
                    SSTenderFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETETENDER") && iTenders != null) {
                SSTender iTender = new SSTender();

                iTender.setNumber(Integer.parseInt(iNumber));
                iTenders.remove(iTender);
                iTender = null;
                if (SSTenderFrame.getInstance() != null) {
                    SSTenderFrame.getInstance().updateFrame();
                }

            } else if (iTriggerName.equals("NEWORDER") && iOrders != null) {
                SSOrder iOrder = new SSOrder();

                iOrder.setNumber(Integer.parseInt(iNumber));
                Optional<SSOrder> optOrder = getOrder(iOrder);
                if (optOrder.isEmpty()) {
                    LOG.warn("NEWORDER trigger: entity not found for number {}", iNumber);
                    return;
                }
                iOrder = optOrder.get();
                if (!iOrders.contains(iOrder)) {
                    iOrders.add(iOrder);
                }
                if (SSOrderFrame.getInstance() != null) {
                    SSOrderFrame.getInstance().updateFrame();
                }
                iOrder = null;
            } else if (iTriggerName.equals("EDITORDER") && iOrders != null) {
                SSOrder iOrder = new SSOrder();

                iOrder.setNumber(Integer.parseInt(iNumber));
                Optional<SSOrder> optOrder = getOrder(iOrder);
                if (optOrder.isEmpty()) {
                    LOG.warn("EDITORDER trigger: entity not found for number {}", iNumber);
                    return;
                }
                iOrder = optOrder.get();
                int iIndex = iOrders.lastIndexOf(iOrder);

                if (iIndex == -1) {
                    return;
                }
                iOrders.remove(iIndex);
                iOrders.add(iIndex, iOrder);
                iOrder = null;
                if (SSOrderFrame.getInstance() != null) {
                    SSOrderFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETEORDER") && iOrders != null) {
                SSOrder iOrder = new SSOrder();

                iOrder.setNumber(Integer.parseInt(iNumber));
                iOrders.remove(iOrder);
                iOrder = null;
                if (SSOrderFrame.getInstance() != null) {
                    SSOrderFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("NEWINVOICE") && iInvoices != null) {
                SSInvoice iInvoice = new SSInvoice();

                iInvoice.setNumber(Integer.parseInt(iNumber));
                Optional<SSInvoice> optInvoice = getInvoice(iInvoice);
                if (optInvoice.isEmpty()) {
                    LOG.warn("NEWINVOICE trigger: entity not found for number {}", iNumber);
                    return;
                }
                iInvoice = optInvoice.get();
                if (!iInvoices.contains(iInvoice)) {
                    iInvoices.add(iInvoice);
                }
                SSInvoiceMath.iSaldoMap.put(iInvoice.getNumber(),
                        SSInvoiceMath.getSaldo(iInvoice));
                if (SSCustomerMath.iInvoicesForCustomers.containsKey(
                        iInvoice.getCustomerNr())) {
                    SSCustomerMath.iInvoicesForCustomers.get(iInvoice.getCustomerNr()).add(
                            iInvoice);
                } else {
                    List<SSInvoice> iNumbers = new LinkedList<>();

                    iNumbers.add(iInvoice);
                    SSCustomerMath.iInvoicesForCustomers.put(iInvoice.getCustomerNr(),
                            iNumbers);
                }
                if (SSOrderFrame.getInstance() != null) {
                    SSOrderFrame.getInstance().updateFrame();
                }
                if (SSCustomerFrame.getInstance() != null) {
                    SSCustomerFrame.getInstance().updateFrame();
                }
                if (SSInvoiceFrame.getInstance() != null) {
                    SSInvoiceFrame.getInstance().updateFrame();
                }
                iInvoice = null;
            } else if (iTriggerName.equals("EDITINVOICE") && iInvoices != null) {
                SSInvoice iInvoice = new SSInvoice();

                iInvoice.setNumber(Integer.parseInt(iNumber));
                Optional<SSInvoice> optInvoice = getInvoice(iInvoice);
                if (optInvoice.isEmpty()) {
                    LOG.warn("EDITINVOICE trigger: entity not found for number {}", iNumber);
                    return;
                }
                iInvoice = optInvoice.get();
                int iIndex = iInvoices.lastIndexOf(iInvoice);

                if (iIndex == -1) {
                    return;
                }
                iInvoices.remove(iIndex);
                iInvoices.add(iIndex, iInvoice);
                SSInvoiceMath.iSaldoMap.put(iInvoice.getNumber(),
                        SSInvoiceMath.getSaldo(iInvoice));
                List<SSInvoice> customerInvoices =
                        SSCustomerMath.iInvoicesForCustomers.get(iInvoice.getCustomerNr());
                if (customerInvoices != null) {
                    iIndex = customerInvoices.indexOf(iInvoice);
                    if (iIndex != -1) {
                        customerInvoices.remove(iIndex);
                        customerInvoices.add(iIndex, iInvoice);
                    }
                }
                iInvoice = null;
                if (SSOrderFrame.getInstance() != null) {
                    SSOrderFrame.getInstance().updateFrame();
                }
                if (SSCustomerFrame.getInstance() != null) {
                    SSCustomerFrame.getInstance().updateFrame();
                }
                if (SSInvoiceFrame.getInstance() != null) {
                    SSInvoiceFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETEINVOICE") && iInvoices != null) {
                SSInvoice iInvoice = new SSInvoice();

                iInvoice.setNumber(Integer.parseInt(iNumber));
                iInvoices.remove(iInvoice);
                SSInvoiceMath.iSaldoMap.remove(iInvoice.getNumber());
                iInvoice = null;
                if (SSCustomerFrame.getInstance() != null) {
                    SSCustomerFrame.getInstance().updateFrame();
                }
                if (SSInvoiceFrame.getInstance() != null) {
                    SSInvoiceFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("NEWCREDITINVOICE") && iCreditInvoices != null) {
                SSCreditInvoice iCreditInvoice = new SSCreditInvoice();

                iCreditInvoice.setNumber(Integer.parseInt(iNumber));
                Optional<SSCreditInvoice> optCreditInvoice = getCreditInvoice(iCreditInvoice);
                if (optCreditInvoice.isEmpty()) {
                    LOG.warn("NEWCREDITINVOICE trigger: entity not found for number {}", iNumber);
                    return;
                }
                iCreditInvoice = optCreditInvoice.get();
                if (!iCreditInvoices.contains(iCreditInvoice)) {
                    iCreditInvoices.add(iCreditInvoice);
                }

                if (SSInvoiceMath.iSaldoMap.containsKey(iCreditInvoice.getCreditingNr())) {
                    SSInvoiceMath.iSaldoMap.put(iCreditInvoice.getCreditingNr(),
                            SSInvoiceMath.iSaldoMap.get(iCreditInvoice.getCreditingNr()).subtract(
                            SSCreditInvoiceMath.getTotalSum(iCreditInvoice)));
                }
                if (SSCustomerFrame.getInstance() != null) {
                    SSCustomerFrame.getInstance().updateFrame();
                }
                if (SSInvoiceFrame.getInstance() != null) {
                    SSInvoiceFrame.getInstance().updateFrame();
                }
                if (SSCreditInvoiceFrame.getInstance() != null) {
                    SSCreditInvoiceFrame.getInstance().updateFrame();
                }
                iCreditInvoice = null;
            } else if (iTriggerName.equals("EDITCREDITINVOICE") && iCreditInvoices != null) {
                SSCreditInvoice iCreditInvoice = new SSCreditInvoice();

                iCreditInvoice.setNumber(Integer.parseInt(iNumber));
                Optional<SSCreditInvoice> optCreditInvoice = getCreditInvoice(iCreditInvoice);
                if (optCreditInvoice.isEmpty()) {
                    LOG.warn("EDITCREDITINVOICE trigger: entity not found for number {}", iNumber);
                    return;
                }
                iCreditInvoice = optCreditInvoice.get();
                int iIndex = iCreditInvoices.lastIndexOf(iCreditInvoice);

                if (iIndex == -1) {
                    return;
                }
                SSCreditInvoice iOldCreditInvoice = iCreditInvoices.get(iIndex);

                if (SSInvoiceMath.iSaldoMap.containsKey(iOldCreditInvoice.getCreditingNr())) {
                    SSInvoiceMath.iSaldoMap.put(iOldCreditInvoice.getCreditingNr(),
                            SSInvoiceMath.iSaldoMap.get(iOldCreditInvoice.getCreditingNr()).add(
                            SSCreditInvoiceMath.getTotalSum(iOldCreditInvoice)));
                }
                if (SSCustomerFrame.getInstance() != null) {
                    SSCustomerFrame.getInstance().updateFrame();
                }
                if (SSInvoiceFrame.getInstance() != null) {
                    SSInvoiceFrame.getInstance().updateFrame();
                }
                iCreditInvoices.remove(iIndex);
                iCreditInvoices.add(iIndex, iCreditInvoice);
                if (SSInvoiceMath.iSaldoMap.containsKey(iCreditInvoice.getCreditingNr())) {
                    SSInvoiceMath.iSaldoMap.put(iCreditInvoice.getCreditingNr(),
                            SSInvoiceMath.iSaldoMap.get(iCreditInvoice.getCreditingNr()).subtract(
                            SSCreditInvoiceMath.getTotalSum(iCreditInvoice)));
                }
                if (SSInvoiceFrame.getInstance() != null) {
                    SSInvoiceFrame.getInstance().updateFrame();
                }
                iCreditInvoice = null;
                if (SSCreditInvoiceFrame.getInstance() != null) {
                    SSCreditInvoiceFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETECREDITINVOICE")
                    && iCreditInvoices != null) {
                SSCreditInvoice iCreditInvoice = new SSCreditInvoice();

                iCreditInvoice.setNumber(Integer.parseInt(iNumber));
                iCreditInvoices.remove(iCreditInvoice);
                iCreditInvoice = null;
                if (SSCustomerFrame.getInstance() != null) {
                    SSCustomerFrame.getInstance().updateFrame();
                }
                if (SSCreditInvoiceFrame.getInstance() != null) {
                    SSCreditInvoiceFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("NEWPERIODICINVOICE")
                    && iPeriodicInvoices != null) {
                SSPeriodicInvoice iPeriodicInvoice = new SSPeriodicInvoice();

                iPeriodicInvoice.setNumber(Integer.parseInt(iNumber));
                Optional<SSPeriodicInvoice> optPeriodicInvoice = getPeriodicInvoice(iPeriodicInvoice);
                if (optPeriodicInvoice.isEmpty()) {
                    LOG.warn("NEWPERIODICINVOICE trigger: entity not found for number {}", iNumber);
                    return;
                }
                iPeriodicInvoice = optPeriodicInvoice.get();
                if (!iPeriodicInvoices.contains(iPeriodicInvoice)) {
                    iPeriodicInvoices.add(iPeriodicInvoice);
                }
                if (SSPeriodicInvoiceFrame.getInstance() != null) {
                    SSPeriodicInvoiceFrame.getInstance().updateFrame();
                }
                iPeriodicInvoice = null;
            } else if (iTriggerName.equals("EDITPERIODICINVOICE")
                    && iPeriodicInvoices != null) {
                SSPeriodicInvoice iPeriodicInvoice = new SSPeriodicInvoice();

                iPeriodicInvoice.setNumber(Integer.parseInt(iNumber));
                Optional<SSPeriodicInvoice> optPeriodicInvoice = getPeriodicInvoice(iPeriodicInvoice);
                if (optPeriodicInvoice.isEmpty()) {
                    LOG.warn("EDITPERIODICINVOICE trigger: entity not found for number {}", iNumber);
                    return;
                }
                iPeriodicInvoice = optPeriodicInvoice.get();
                int iIndex = iPeriodicInvoices.lastIndexOf(iPeriodicInvoice);

                if (iIndex == -1) {
                    return;
                }
                iPeriodicInvoices.remove(iIndex);
                iPeriodicInvoices.add(iIndex, iPeriodicInvoice);
                iPeriodicInvoice = null;
                if (SSPeriodicInvoiceFrame.getInstance() != null) {
                    SSPeriodicInvoiceFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETEPERIODICINVOICE")
                    && iPeriodicInvoices != null) {
                SSPeriodicInvoice iPeriodicInvoice = new SSPeriodicInvoice();

                iPeriodicInvoice.setNumber(Integer.parseInt(iNumber));
                iPeriodicInvoices.remove(iPeriodicInvoice);
                iPeriodicInvoice = null;
                if (SSPeriodicInvoiceFrame.getInstance() != null) {
                    SSPeriodicInvoiceFrame.getInstance().updateFrame();
                }
            } /**
             * INKÖP
             */ else if (iTriggerName.equals("NEWOUTPAYMENT") && iOutpayments != null) {
                SSOutpayment iOutpayment = new SSOutpayment();

                iOutpayment.setNumber(Integer.parseInt(iNumber));
                Optional<SSOutpayment> optOutpayment = getOutpayment(iOutpayment);
                if (optOutpayment.isEmpty()) {
                    LOG.warn("NEWOUTPAYMENT trigger: entity not found for number {}", iNumber);
                    return;
                }
                iOutpayment = optOutpayment.get();
                if (!iOutpayments.contains(iOutpayment)) {
                    iOutpayments.add(iOutpayment);
                }
                for (SSOutpaymentRow iRow : iOutpayment.getRows()) {
                    if (iRow.getValue() != null && iRow.getInvoiceNr() != null) {
                        if (SSSupplierInvoiceMath.iSaldoMap.containsKey(
                                iRow.getInvoiceNr())) {
                            SSSupplierInvoiceMath.iSaldoMap.put(iRow.getInvoiceNr(),
                                    SSSupplierInvoiceMath.iSaldoMap.get(iRow.getInvoiceNr()).subtract(
                                    iRow.getValue()));
                        }
                    }
                }
                if (SSSupplierFrame.getInstance() != null) {
                    SSSupplierFrame.getInstance().updateFrame();
                }
                if (SSSupplierInvoiceFrame.getInstance() != null) {
                    SSSupplierInvoiceFrame.getInstance().updateFrame();
                }
                if (SSOutpaymentFrame.getInstance() != null) {
                    SSOutpaymentFrame.getInstance().updateFrame();
                }
                iOutpayment = null;
            } else if (iTriggerName.equals("EDITOUTPAYMENT") && iOutpayments != null) {
                SSOutpayment iOutpayment = new SSOutpayment();

                iOutpayment.setNumber(Integer.parseInt(iNumber));
                Optional<SSOutpayment> optOutpayment = getOutpayment(iOutpayment);
                if (optOutpayment.isEmpty()) {
                    LOG.warn("EDITOUTPAYMENT trigger: entity not found for number {}", iNumber);
                    return;
                }
                iOutpayment = optOutpayment.get();
                int iIndex = iOutpayments.lastIndexOf(iOutpayment);

                if (iIndex == -1) {
                    return;
                }
                SSOutpayment iOldOutpayment = iOutpayments.get(iIndex);

                for (SSOutpaymentRow iRow : iOldOutpayment.getRows()) {
                    if (iRow.getValue() != null && iRow.getInvoiceNr() != null) {
                        if (SSSupplierInvoiceMath.iSaldoMap.containsKey(
                                iRow.getInvoiceNr())) {
                            SSSupplierInvoiceMath.iSaldoMap.put(iRow.getInvoiceNr(),
                                    SSSupplierInvoiceMath.iSaldoMap.get(iRow.getInvoiceNr()).add(
                                    iRow.getValue()));
                        }
                    }
                }
                iOutpayments.remove(iIndex);
                iOutpayments.add(iIndex, iOutpayment);
                for (SSOutpaymentRow iRow : iOutpayment.getRows()) {
                    if (iRow.getValue() != null && iRow.getInvoiceNr() != null) {
                        if (SSSupplierInvoiceMath.iSaldoMap.containsKey(
                                iRow.getInvoiceNr())) {
                            SSSupplierInvoiceMath.iSaldoMap.put(iRow.getInvoiceNr(),
                                    SSSupplierInvoiceMath.iSaldoMap.get(iRow.getInvoiceNr()).subtract(
                                    iRow.getValue()));
                        }
                    }
                }
                iOutpayment = null;
                if (SSSupplierFrame.getInstance() != null) {
                    SSSupplierFrame.getInstance().updateFrame();
                }
                if (SSSupplierInvoiceFrame.getInstance() != null) {
                    SSSupplierInvoiceFrame.getInstance().updateFrame();
                }
                if (SSOutpaymentFrame.getInstance() != null) {
                    SSOutpaymentFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETEOUTPAYMENT") && iOutpayments != null) {
                SSOutpayment iOutpayment = new SSOutpayment();

                iOutpayment.setNumber(Integer.parseInt(iNumber));
                iOutpayments.remove(iOutpayment);
                iOutpayment = null;
                if (SSSupplierFrame.getInstance() != null) {
                    SSSupplierFrame.getInstance().updateFrame();
                }
                if (SSSupplierInvoiceFrame.getInstance() != null) {
                    SSSupplierInvoiceFrame.getInstance().updateFrame();
                }
                if (SSOutpaymentFrame.getInstance() != null) {
                    SSOutpaymentFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("NEWPURCHASEORDER") && iPurchaseOrders != null) {
                SSPurchaseOrder iPurchaseOrder = new SSPurchaseOrder();

                iPurchaseOrder.setNumber(Integer.parseInt(iNumber));
                Optional<SSPurchaseOrder> optPurchaseOrder = getPurchaseOrder(iPurchaseOrder);
                if (optPurchaseOrder.isEmpty()) {
                    LOG.warn("NEWPURCHASEORDER trigger: entity not found for number {}", iNumber);
                    return;
                }
                iPurchaseOrder = optPurchaseOrder.get();
                if (!iPurchaseOrders.contains(iPurchaseOrder)) {
                    iPurchaseOrders.add(iPurchaseOrder);
                }
                if (SSOrderFrame.getInstance() != null) {
                    SSOrderFrame.getInstance().updateFrame();
                }
                if (SSPurchaseOrderFrame.getInstance() != null) {
                    SSPurchaseOrderFrame.getInstance().updateFrame();
                }
                iPurchaseOrder = null;
            } else if (iTriggerName.equals("EDITPURCHASEORDER") && iPurchaseOrders != null) {
                SSPurchaseOrder iPurchaseOrder = new SSPurchaseOrder();

                iPurchaseOrder.setNumber(Integer.parseInt(iNumber));
                Optional<SSPurchaseOrder> optPurchaseOrder = getPurchaseOrder(iPurchaseOrder);
                if (optPurchaseOrder.isEmpty()) {
                    LOG.warn("EDITPURCHASEORDER trigger: entity not found for number {}", iNumber);
                    return;
                }
                iPurchaseOrder = optPurchaseOrder.get();
                int iIndex = iPurchaseOrders.lastIndexOf(iPurchaseOrder);

                if (iIndex == -1) {
                    return;
                }
                iPurchaseOrders.remove(iIndex);
                iPurchaseOrders.add(iIndex, iPurchaseOrder);
                iPurchaseOrder = null;
                if (SSPurchaseOrderFrame.getInstance() != null) {
                    SSPurchaseOrderFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETEPURCHASEORDER")
                    && iPurchaseOrders != null) {
                SSPurchaseOrder iPurchaseOrder = new SSPurchaseOrder();

                iPurchaseOrder.setNumber(Integer.parseInt(iNumber));
                iPurchaseOrders.remove(iPurchaseOrder);
                iPurchaseOrder = null;
                if (SSOrderFrame.getInstance() != null) {
                    SSOrderFrame.getInstance().updateFrame();
                }
                if (SSPurchaseOrderFrame.getInstance() != null) {
                    SSPurchaseOrderFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("NEWSUPPLIERINVOICE")
                    && iSupplierInvoices != null) {
                SSSupplierInvoice iSupplierInvoice = new SSSupplierInvoice();

                iSupplierInvoice.setNumber(Integer.parseInt(iNumber));
                Optional<SSSupplierInvoice> optSupplierInvoice = getSupplierInvoice(iSupplierInvoice);
                if (optSupplierInvoice.isEmpty()) {
                    LOG.warn("NEWSUPPLIERINVOICE trigger: entity not found for number {}", iNumber);
                    return;
                }
                iSupplierInvoice = optSupplierInvoice.get();
                if (!iSupplierInvoices.contains(iSupplierInvoice)) {
                    iSupplierInvoices.add(iSupplierInvoice);
                }
                SSSupplierInvoiceMath.iSaldoMap.put(iSupplierInvoice.getNumber(),
                        SSSupplierInvoiceMath.getSaldo(iSupplierInvoice));
                if (SSSupplierMath.iInvoicesForSuppliers.containsKey(
                        iSupplierInvoice.getSupplierNr())) {
                    SSSupplierMath.iInvoicesForSuppliers.get(iSupplierInvoice.getSupplierNr()).add(
                            iSupplierInvoice);
                } else {
                    List<SSSupplierInvoice> iNumbers = new LinkedList<>();

                    iNumbers.add(iSupplierInvoice);
                    SSSupplierMath.iInvoicesForSuppliers.put(
                            iSupplierInvoice.getSupplierNr(), iNumbers);
                }
                if (SSSupplierFrame.getInstance() != null) {
                    SSSupplierFrame.getInstance().updateFrame();
                }
                if (SSSupplierInvoiceFrame.getInstance() != null) {
                    SSSupplierInvoiceFrame.getInstance().updateFrame();
                }
                iSupplierInvoice = null;
            } else if (iTriggerName.equals("EDITSUPPLIERINVOICE")
                    && iSupplierInvoices != null) {
                SSSupplierInvoice iSupplierInvoice = new SSSupplierInvoice();

                iSupplierInvoice.setNumber(Integer.parseInt(iNumber));
                Optional<SSSupplierInvoice> optSupplierInvoice = getSupplierInvoice(iSupplierInvoice);
                if (optSupplierInvoice.isEmpty()) {
                    LOG.warn("EDITSUPPLIERINVOICE trigger: entity not found for number {}", iNumber);
                    return;
                }
                iSupplierInvoice = optSupplierInvoice.get();
                int iIndex = iSupplierInvoices.lastIndexOf(iSupplierInvoice);

                if (iIndex == -1) {
                    return;
                }
                iSupplierInvoices.remove(iIndex);
                iSupplierInvoices.add(iIndex, iSupplierInvoice);
                SSSupplierInvoiceMath.iSaldoMap.put(iSupplierInvoice.getNumber(),
                        SSSupplierInvoiceMath.getSaldo(iSupplierInvoice));
                iIndex = SSSupplierMath.iInvoicesForSuppliers.get(iSupplierInvoice.getSupplierNr()).indexOf(
                        iSupplierInvoice);
                if (iIndex != -1) {
                    SSSupplierMath.iInvoicesForSuppliers.get(iSupplierInvoice.getSupplierNr()).remove(
                            iIndex);
                    SSSupplierMath.iInvoicesForSuppliers.get(iSupplierInvoice.getSupplierNr()).add(
                            iIndex, iSupplierInvoice);
                }
                iSupplierInvoice = null;
                if (SSSupplierFrame.getInstance() != null) {
                    SSSupplierFrame.getInstance().updateFrame();
                }
                if (SSSupplierInvoiceFrame.getInstance() != null) {
                    SSSupplierInvoiceFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETESUPPLIERINVOICE")
                    && iSupplierInvoices != null) {
                SSSupplierInvoice iSupplierInvoice = new SSSupplierInvoice();

                iSupplierInvoice.setNumber(Integer.parseInt(iNumber));
                iSupplierInvoices.remove(iSupplierInvoice);
                SSSupplierInvoiceMath.iSaldoMap.remove(iSupplierInvoice.getNumber());
                iSupplierInvoice = null;
                if (SSSupplierFrame.getInstance() != null) {
                    SSSupplierFrame.getInstance().updateFrame();
                }
                if (SSSupplierInvoiceFrame.getInstance() != null) {
                    SSSupplierInvoiceFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("NEWSUPPLIERCREDITINVOICE")
                    && iSupplierCreditInvoices != null) {
                SSSupplierCreditInvoice iSupplierCreditInvoice = new SSSupplierCreditInvoice();

                iSupplierCreditInvoice.setNumber(Integer.parseInt(iNumber));
                Optional<SSSupplierCreditInvoice> optSupplierCreditInvoice = getSupplierCreditInvoice(iSupplierCreditInvoice);
                if (optSupplierCreditInvoice.isEmpty()) {
                    LOG.warn("NEWSUPPLIERCREDITINVOICE trigger: entity not found for number {}", iNumber);
                    return;
                }
                iSupplierCreditInvoice = optSupplierCreditInvoice.get();
                if (!iSupplierCreditInvoices.contains(iSupplierCreditInvoice)) {
                    iSupplierCreditInvoices.add(iSupplierCreditInvoice);
                }
                if (SSSupplierInvoiceMath.iSaldoMap.containsKey(
                        iSupplierCreditInvoice.getCreditingNr())) {
                    SSSupplierInvoiceMath.iSaldoMap.put(
                            iSupplierCreditInvoice.getCreditingNr(),
                            SSSupplierInvoiceMath.iSaldoMap.get(iSupplierCreditInvoice.getCreditingNr()).subtract(
                                    SSSupplierInvoiceMath.getTotalSum(
                                            iSupplierCreditInvoice)));
                }
                if (SSSupplierFrame.getInstance() != null) {
                    SSSupplierFrame.getInstance().updateFrame();
                }
                if (SSSupplierInvoiceFrame.getInstance() != null) {
                    SSSupplierInvoiceFrame.getInstance().updateFrame();
                }
                if (SSSupplierCreditInvoiceFrame.getInstance() != null) {
                    SSSupplierCreditInvoiceFrame.getInstance().updateFrame();
                }
                iSupplierCreditInvoice = null;
            } else if (iTriggerName.equals("EDITSUPPLIERCREDITINVOICE")
                    && iSupplierCreditInvoices != null) {
                SSSupplierCreditInvoice iSupplierCreditInvoice = new SSSupplierCreditInvoice();

                iSupplierCreditInvoice.setNumber(Integer.parseInt(iNumber));
                Optional<SSSupplierCreditInvoice> optSupplierCreditInvoice = getSupplierCreditInvoice(iSupplierCreditInvoice);
                if (optSupplierCreditInvoice.isEmpty()) {
                    LOG.warn("EDITSUPPLIERCREDITINVOICE trigger: entity not found for number {}", iNumber);
                    return;
                }
                iSupplierCreditInvoice = optSupplierCreditInvoice.get();
                int iIndex = iSupplierCreditInvoices.lastIndexOf(iSupplierCreditInvoice);

                if (iIndex == -1) {
                    return;
                }
                SSSupplierCreditInvoice iOldSupplierCreditInvoice = iSupplierCreditInvoices.get(
                        iIndex);

                if (SSSupplierInvoiceMath.iSaldoMap.containsKey(
                        iOldSupplierCreditInvoice.getCreditingNr())) {
                    SSSupplierInvoiceMath.iSaldoMap.put(
                            iOldSupplierCreditInvoice.getCreditingNr(),
                            SSSupplierInvoiceMath.iSaldoMap.get(iOldSupplierCreditInvoice.getCreditingNr()).add(
                                    SSSupplierInvoiceMath.getTotalSum(
                                            iOldSupplierCreditInvoice)));
                }
                iSupplierCreditInvoices.remove(iIndex);
                iSupplierCreditInvoices.add(iIndex, iSupplierCreditInvoice);
                if (SSSupplierInvoiceMath.iSaldoMap.containsKey(
                        iSupplierCreditInvoice.getCreditingNr())) {
                    SSSupplierInvoiceMath.iSaldoMap.put(
                            iSupplierCreditInvoice.getCreditingNr(),
                            SSSupplierInvoiceMath.iSaldoMap.get(iSupplierCreditInvoice.getCreditingNr()).subtract(
                                    SSSupplierInvoiceMath.getTotalSum(
                                            iSupplierCreditInvoice)));
                }
                if (SSSupplierInvoiceFrame.getInstance() != null) {
                    SSSupplierInvoiceFrame.getInstance().updateFrame();
                }
                iSupplierCreditInvoice = null;
                if (SSSupplierFrame.getInstance() != null) {
                    SSSupplierFrame.getInstance().updateFrame();
                }
                if (SSSupplierCreditInvoiceFrame.getInstance() != null) {
                    SSSupplierCreditInvoiceFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETESUPPLIERCREDITINVOICE")
                    && iSupplierCreditInvoices != null) {
                SSSupplierCreditInvoice iSupplierCreditInvoice = new SSSupplierCreditInvoice();

                iSupplierCreditInvoice.setNumber(Integer.parseInt(iNumber));
                iSupplierCreditInvoices.remove(iSupplierCreditInvoice);
                iSupplierCreditInvoice = null;
                if (SSSupplierFrame.getInstance() != null) {
                    SSSupplierFrame.getInstance().updateFrame();
                }
                if (SSSupplierInvoiceFrame.getInstance() != null) {
                    SSSupplierInvoiceFrame.getInstance().updateFrame();
                }
                if (SSSupplierCreditInvoiceFrame.getInstance() != null) {
                    SSSupplierCreditInvoiceFrame.getInstance().updateFrame();
                }
            } /**
             * LAGER
             */ else if (iTriggerName.equals("NEWINVENTORY") && iInventories != null) {
                SSInventory iInventory = new SSInventory();

                iInventory.setNumber(Integer.parseInt(iNumber));
                Optional<SSInventory> optInventory = getInventory(iInventory);
                if (optInventory.isEmpty()) {
                    LOG.warn("NEWINVENTORY trigger: entity not found for number {}", iNumber);
                    return;
                }
                iInventory = optInventory.get();
                if (!iInventories.contains(iInventory)) {
                    iInventories.add(iInventory);
                }
                if (SSInventoryFrame.getInstance() != null) {
                    SSInventoryFrame.getInstance().updateFrame();
                }
                iInventory = null;
            } else if (iTriggerName.equals("EDITINVENTORY") && iInventories != null) {
                SSInventory iInventory = new SSInventory();

                iInventory.setNumber(Integer.parseInt(iNumber));
                Optional<SSInventory> optInventory = getInventory(iInventory);
                if (optInventory.isEmpty()) {
                    LOG.warn("EDITINVENTORY trigger: entity not found for number {}", iNumber);
                    return;
                }
                iInventory = optInventory.get();
                int iIndex = iInventories.lastIndexOf(iInventory);

                if (iIndex == -1) {
                    return;
                }
                iInventories.remove(iIndex);
                iInventories.add(iIndex, iInventory);
                iInventory = null;
                if (SSInventoryFrame.getInstance() != null) {
                    SSInventoryFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETEINVENTORY") && iInventories != null) {
                SSInventory iInventory = new SSInventory();

                iInventory.setNumber(Integer.parseInt(iNumber));
                iInventories.remove(iInventory);
                iInventory = null;
                if (SSInventoryFrame.getInstance() != null) {
                    SSInventoryFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("NEWINDELIVERY") && iIndeliveries != null) {
                SSIndelivery iIndelivery = new SSIndelivery();

                iIndelivery.setNumber(Integer.parseInt(iNumber));
                Optional<SSIndelivery> optIndelivery = getIndelivery(iIndelivery);
                if (optIndelivery.isEmpty()) {
                    LOG.warn("NEWINDELIVERY trigger: entity not found for number {}", iNumber);
                    return;
                }
                iIndelivery = optIndelivery.get();
                if (!iIndeliveries.contains(iIndelivery)) {
                    iIndeliveries.add(iIndelivery);
                }
                if (SSIndeliveryFrame.getInstance() != null) {
                    SSIndeliveryFrame.getInstance().updateFrame();
                }
                iIndelivery = null;
            } else if (iTriggerName.equals("EDITINDELIVERY") && iIndeliveries != null) {
                SSIndelivery iIndelivery = new SSIndelivery();

                iIndelivery.setNumber(Integer.parseInt(iNumber));
                Optional<SSIndelivery> optIndelivery = getIndelivery(iIndelivery);
                if (optIndelivery.isEmpty()) {
                    LOG.warn("EDITINDELIVERY trigger: entity not found for number {}", iNumber);
                    return;
                }
                iIndelivery = optIndelivery.get();
                int iIndex = iIndeliveries.lastIndexOf(iIndelivery);

                if (iIndex == -1) {
                    return;
                }
                iIndeliveries.remove(iIndex);
                iIndeliveries.add(iIndex, iIndelivery);
                iIndelivery = null;
                if (SSIndeliveryFrame.getInstance() != null) {
                    SSIndeliveryFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETEINDELIVERY") && iIndeliveries != null) {
                SSIndelivery iIndelivery = new SSIndelivery();

                iIndelivery.setNumber(Integer.parseInt(iNumber));
                iIndeliveries.remove(iIndelivery);
                iIndelivery = null;
                if (SSIndeliveryFrame.getInstance() != null) {
                    SSIndeliveryFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("NEWOUTDELIVERY") && iOutdeliveries != null) {
                SSOutdelivery iOutdelivery = new SSOutdelivery();

                iOutdelivery.setNumber(Integer.parseInt(iNumber));
                Optional<SSOutdelivery> optOutdelivery = getOutdelivery(iOutdelivery);
                if (optOutdelivery.isEmpty()) {
                    LOG.warn("NEWOUTDELIVERY trigger: entity not found for number {}", iNumber);
                    return;
                }
                iOutdelivery = optOutdelivery.get();
                if (!iOutdeliveries.contains(iOutdelivery)) {
                    iOutdeliveries.add(iOutdelivery);
                }
                if (SSOutdeliveryFrame.getInstance() != null) {
                    SSOutdeliveryFrame.getInstance().updateFrame();
                }
                iOutdelivery = null;
            } else if (iTriggerName.equals("EDITOUTDELIVERY") && iOutdeliveries != null) {
                SSOutdelivery iOutdelivery = new SSOutdelivery();

                iOutdelivery.setNumber(Integer.parseInt(iNumber));
                Optional<SSOutdelivery> optOutdelivery = getOutdelivery(iOutdelivery);
                if (optOutdelivery.isEmpty()) {
                    LOG.warn("EDITOUTDELIVERY trigger: entity not found for number {}", iNumber);
                    return;
                }
                iOutdelivery = optOutdelivery.get();
                int iIndex = iOutdeliveries.lastIndexOf(iOutdelivery);

                if (iIndex == -1) {
                    return;
                }
                iOutdeliveries.remove(iIndex);
                iOutdeliveries.add(iIndex, iOutdelivery);
                iOutdelivery = null;
                if (SSOutdeliveryFrame.getInstance() != null) {
                    SSOutdeliveryFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETEOUTDELIVERY") && iOutdeliveries != null) {
                SSOutdelivery iOutdelivery = new SSOutdelivery();

                iOutdelivery.setNumber(Integer.parseInt(iNumber));
                iOutdeliveries.remove(iOutdelivery);
                iOutdelivery = null;
                if (SSOutdeliveryFrame.getInstance() != null) {
                    SSOutdeliveryFrame.getInstance().updateFrame();
                }
            } /**
             * BOKFÖRING
             */ else if (iTriggerName.equals("NEWVOUCHER") && iVouchers != null) {
                SSVoucher iVoucher = new SSVoucher(Integer.parseInt(iNumber));

                Optional<SSVoucher> optVoucher = getVoucher(iVoucher);
                if (optVoucher.isEmpty()) {
                    LOG.warn("NEWVOUCHER trigger: entity not found for number {}", iNumber);
                    return;
                }
                iVoucher = optVoucher.get();
                if (!iVouchers.contains(iVoucher)) {
                    iVouchers.add(iVoucher);
                }
                if (SSVoucherFrame.getInstance() != null) {
                    SSVoucherFrame.getInstance().updateFrame();
                }
                iVoucher = null;
            } else if (iTriggerName.equals("EDITVOUCHER") && iVouchers != null) {
                SSVoucher iVoucher = new SSVoucher(Integer.parseInt(iNumber));

                Optional<SSVoucher> optVoucher = getVoucher(iVoucher);
                if (optVoucher.isEmpty()) {
                    LOG.warn("EDITVOUCHER trigger: entity not found for number {}", iNumber);
                    return;
                }
                iVoucher = optVoucher.get();
                int iIndex = iVouchers.lastIndexOf(iVoucher);

                if (iIndex == -1) {
                    return;
                }
                iVouchers.remove(iIndex);
                iVouchers.add(iIndex, iVoucher);
                iVoucher = null;
                if (SSVoucherFrame.getInstance() != null) {
                    SSVoucherFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETEVOUCHER") && iVouchers != null) {
                SSVoucher iVoucher = new SSVoucher(Integer.parseInt(iNumber));

                iVouchers.remove(iVoucher);
                iVoucher = null;
                if (SSVoucherFrame.getInstance() != null) {
                    SSVoucherFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("NEWOWNREPORT") && iOwnReports != null) {
                SSOwnReport iOwnReport = new SSOwnReport();

                iOwnReport.setId(Integer.parseInt(iNumber));
                Optional<SSOwnReport> optOwnReport = getOwnReport(iOwnReport);
                if (optOwnReport.isEmpty()) {
                    LOG.warn("NEWOWNREPORT trigger: entity not found for number {}", iNumber);
                    return;
                }
                iOwnReport = optOwnReport.get();
                if (!iOwnReports.contains(iOwnReport) && iOwnReport.getId() != -1) {
                    iOwnReports.add(iOwnReport);
                }
                if (SSOwnReportFrame.getInstance() != null) {
                    SSOwnReportFrame.getInstance().updateFrame();
                }
                iOwnReport = null;
            } else if (iTriggerName.equals("EDITOWNREPORT") && iOwnReports != null) {
                SSOwnReport iOwnReport = new SSOwnReport();

                iOwnReport.setId(Integer.parseInt(iNumber));
                Optional<SSOwnReport> optOwnReport = getOwnReport(iOwnReport);
                if (optOwnReport.isEmpty()) {
                    LOG.warn("EDITOWNREPORT trigger: entity not found for number {}", iNumber);
                    return;
                }
                iOwnReport = optOwnReport.get();
                int iIndex = iOwnReports.lastIndexOf(iOwnReport);

                if (iIndex != -1) {
                    iOwnReports.remove(iIndex);
                    iOwnReports.add(iIndex, iOwnReport);
                } else {
                    iOwnReports.add(iOwnReport);
                }
                iOwnReport = null;
                if (SSOwnReportFrame.getInstance() != null) {
                    SSOwnReportFrame.getInstance().updateFrame();
                }
            } else if (iTriggerName.equals("DELETEOWNREPORT") && iOwnReports != null) {
                SSOwnReport iOwnReport = new SSOwnReport();

                iOwnReport.setId(Integer.parseInt(iNumber));
                iOwnReports.remove(iOwnReport);
                iOwnReport = null;
                if (SSOwnReportFrame.getInstance() != null) {
                    SSOwnReportFrame.getInstance().updateFrame();
                }
            }
        } catch (NumberFormatException e) {
            LOG.error("Unexpected error", e);
        }
    }

    public List<SSProduct> getProducts() {
        if (iProducts != null) {
            return iProducts;
        }
        iProducts = new LinkedList<>();

        if (iCurrentCompany == null) {
            return iProducts;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_product WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);
                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iProducts.add((SSProduct) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iProducts;
    }

    public Optional<SSProduct> getProduct(SSProduct pProduct) {
        if (pProduct == null) {
            return Optional.empty();
        }
        if (iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_product WHERE number=? AND companyid=?");

            iStatement.setObject(1, pProduct.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSProduct iProduct = (SSProduct) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iProduct);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public Optional<SSProduct> getProduct(String iProductNumber) {
        if (iProductNumber == null) {
            return Optional.empty();
        }
        if (iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_product WHERE LOWER(number)=LOWER('"
                            + iProductNumber + "') AND companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSProduct iProduct = (SSProduct) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iProduct);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public List<SSProduct> getProducts(List<SSProduct> pProducts) {
        if (pProducts == null) {
            return Collections.emptyList();
        }
        List<SSProduct> iProducts = new LinkedList<>();

        if (this.iProducts != null) {
            for (SSProduct iProduct : pProducts) {
                if (this.iProducts.contains(iProduct)) {
                    iProducts.add(iProduct);
                }
            }
            return iProducts;
        }
        if (iCurrentCompany == null) {
            return iProducts;
        }
        try {
            for (SSProduct iProduct : pProducts) {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_product WHERE number=? AND companyid=?");

                iStatement.setObject(1, iProduct.getNumber());
                iStatement.setObject(2, iCurrentCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                if (iResultSet.next()) {
                    iProducts.add((SSProduct) iResultSet.getObject(3));
                }
                iStatement.close();
            }

            return iProducts;
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Collections.emptyList();
    }

    public void addProduct(SSProduct iProduct) {
        if (iProduct == null) {
            return;
        }
        if (iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_product VALUES(NULL,?,?,?)");

            iStatement.setObject(1, iProduct.getNumber());
            iStatement.setObject(2, iProduct);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateProduct(SSProduct iProduct) {
        if (iProduct == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_product SET product=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iProduct);
            iStatement.setObject(2, iProduct.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteProduct(SSProduct iProduct) {
        if (iProduct == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_product WHERE number=? AND companyid=?");

            iStatement.setObject(1, iProduct.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns the customers for the current company.
     *
     * @return  A List of customers or an empty list.
     */
    public List<SSCustomer> getCustomers() {
        if (iCustomers != null) {
            return iCustomers;
        }
        iCustomers = new LinkedList<>();
        if (iCurrentCompany == null) {
            return iCustomers;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_customer WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);
                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iCustomers.add((SSCustomer) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iCustomers;
    }

    public Optional<SSCustomer> getCustomer(SSCustomer pCustomer) {
        if (pCustomer == null) {
            return Optional.empty();
        }
        if (iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_customer WHERE number=? AND companyid=?");

            iStatement.setObject(1, pCustomer.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSCustomer iCustomer = (SSCustomer) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iCustomer);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public Optional<SSCustomer> getCustomer(String iCustomerNumber) {
        if (iCustomerNumber == null) {
            return Optional.empty();
        }
        if (iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_customer WHERE LOWER(number)=LOWER('"
                            + iCustomerNumber + "') AND companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSCustomer iCustomer = (SSCustomer) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iCustomer);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public List<SSCustomer> getCustomers(List<SSCustomer> pCustomers) {
        if (pCustomers == null) {
            return Collections.emptyList();
        }
        List<SSCustomer> iCustomers = new LinkedList<>();

        if (this.iCustomers != null) {
            for (SSCustomer iCustomer : pCustomers) {
                if (this.iCustomers.contains(iCustomer)) {
                    iCustomers.add(iCustomer);
                }
            }
            return iCustomers;
        }
        if (iCurrentCompany == null) {
            return iCustomers;
        }
        try {
            for (SSCustomer iCustomer : pCustomers) {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_customer WHERE number=? AND companyid=?");

                iStatement.setObject(1, iCustomer.getNumber());
                iStatement.setObject(2, iCurrentCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                if (iResultSet.next()) {
                    iCustomers.add((SSCustomer) iResultSet.getObject(3));
                }
                iStatement.close();
            }

            return iCustomers;
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Collections.emptyList();
    }

    public void addCustomer(SSCustomer iCustomer) {
        if (iCustomer == null) {
            return;
        }
        if (iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_customer VALUES(NULL,?,?,?)");

            iStatement.setObject(1, iCustomer.getNumber());
            iStatement.setObject(2, iCustomer);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateCustomer(SSCustomer iCustomer) {
        if (iCustomer == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_customer SET customer=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iCustomer);
            iStatement.setObject(2, iCustomer.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteCustomer(SSCustomer iCustomer) {
        if (iCustomer == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_customer WHERE number=? AND companyid=?");

            iStatement.setObject(1, iCustomer.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns the suppliers for the current company.
     *
     * @return  A List of suppliers or an empty list.
     */
    public List<SSSupplier> getSuppliers() {
        if (iSuppliers != null) {
            return iSuppliers;
        }
        iSuppliers = new LinkedList<>();
        if (iCurrentCompany == null) {
            return iSuppliers;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_supplier WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);
                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iSuppliers.add((SSSupplier) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iSuppliers;
    }

    public Optional<SSSupplier> getSupplier(SSSupplier pSupplier) {
        if (pSupplier == null || iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_supplier WHERE number=? AND companyid=?");

            iStatement.setObject(1, pSupplier.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSSupplier iSupplier = (SSSupplier) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iSupplier);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public List<SSSupplier> getSuppliers(List<SSSupplier> pSuppliers) {
        if (pSuppliers == null) {
            return Collections.emptyList();
        }
        List<SSSupplier> iSuppliers = new LinkedList<>();

        if (this.iSuppliers != null) {
            for (SSSupplier iSupplier : pSuppliers) {
                if (this.iSuppliers.contains(iSupplier)) {
                    iSuppliers.add(iSupplier);
                }
            }
            return iSuppliers;
        }
        if (iCurrentCompany == null) {
            return iSuppliers;
        }
        try {
            for (SSSupplier iSupplier : pSuppliers) {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_supplier WHERE number=? AND companyid=?");

                iStatement.setObject(1, iSupplier.getNumber());
                iStatement.setObject(2, iCurrentCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                if (iResultSet.next()) {
                    iSuppliers.add((SSSupplier) iResultSet.getObject(3));
                }
                iStatement.close();
            }

            return iSuppliers;
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Collections.emptyList();
    }

    public void addSupplier(SSSupplier iSupplier) {
        if (iSupplier == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_supplier VALUES(NULL,?,?,?)");

            iStatement.setObject(1, iSupplier.getNumber());
            iStatement.setObject(2, iSupplier);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateSupplier(SSSupplier iSupplier) {
        if (iSupplier == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_supplier SET supplier=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iSupplier);
            iStatement.setObject(2, iSupplier.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteSupplier(SSSupplier iSupplier) {
        if (iSupplier == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_supplier WHERE number=? AND companyid=?");

            iStatement.setObject(1, iSupplier.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns the autodistributions for the current company.
     *
     * @return  A List of autodists or an empty list.
     */
    public List<SSAutoDist> getAutoDists() {
        if (iAutoDists != null) {
            return iAutoDists;
        }
        iAutoDists = new LinkedList<>();
        if (iCurrentCompany == null) {
            return iAutoDists;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_autodist WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);
                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iAutoDists.add((SSAutoDist) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iAutoDists;
    }

    public Optional<SSAutoDist> getAutoDist(SSAutoDist pAutoDist) {
        if (pAutoDist == null || iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_autodist WHERE number=? AND companyid=?");

            iStatement.setObject(1, pAutoDist.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSAutoDist iAutoDist = (SSAutoDist) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iAutoDist);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public List<SSAutoDist> getAutoDists(List<SSAutoDist> pAutoDists) {
        if (pAutoDists == null) {
            return Collections.emptyList();
        }
        List<SSAutoDist> iAutoDists = new LinkedList<>();

        if (this.iAutoDists != null) {
            for (SSAutoDist iAutoDist : pAutoDists) {
                if (this.iAutoDists.contains(iAutoDist)) {
                    iAutoDists.add(iAutoDist);
                }
            }
            return iAutoDists;
        }
        if (iCurrentCompany == null) {
            return iAutoDists;
        }
        try {
            for (SSAutoDist iAutoDist : pAutoDists) {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_autodist WHERE number=? AND companyid=?");

                iStatement.setObject(1, iAutoDist.getNumber());
                iStatement.setObject(2, iCurrentCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                if (iResultSet.next()) {
                    iAutoDists.add((SSAutoDist) iResultSet.getObject(3));
                }
                iStatement.close();
            }

            return iAutoDists;
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Collections.emptyList();
    }

    public void addAutoDist(SSAutoDist iAutoDist) {
        if (iAutoDist == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_autodist VALUES(NULL,?,?,?)");

            iStatement.setObject(1, iAutoDist.getNumber());
            iStatement.setObject(2, iAutoDist);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateAutoDist(SSAutoDist iAutoDist, SSAutoDist iOriginal) {
        if (iAutoDist == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_autodist SET autodist=?, number=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iAutoDist);
            iStatement.setObject(2, iAutoDist.getNumber());
            iStatement.setObject(3, iOriginal.getNumber());
            iStatement.setObject(4, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteAutoDist(SSAutoDist iAutoDist) {
        if (iAutoDist == null || iCurrentCompany == null) {
            return;
        }
        if (iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_autodist WHERE number=? AND companyid=?");

            iStatement.setObject(1, iAutoDist.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns the tenders in the current company.
     *
     * @return  A List of tenders or an empty list.
     */
    public List<SSTender> getTenders() {
        if (iTenders != null) {
            return iTenders;
        }
        iTenders = new LinkedList<>();
        if (iCurrentCompany == null) {
            return iTenders;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_tender WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);

                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iTenders.add((SSTender) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iTenders;
    }

    public Optional<SSTender> getTender(SSTender pTender) {
        if (pTender == null || iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_tender WHERE number=? AND companyid=?");

            iStatement.setObject(1, pTender.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSTender iTender = (SSTender) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iTender);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public List<SSTender> getTenders(List<SSTender> pTenders) {
        if (pTenders == null) {
            return Collections.emptyList();
        }
        List<SSTender> iTenders = new LinkedList<>();

        if (this.iTenders != null) {
            for (SSTender iTender : pTenders) {
                if (this.iTenders.contains(iTender)) {
                    iTenders.add(iTender);
                }
            }
            return iTenders;
        }
        if (iCurrentCompany == null) {
            return iTenders;
        }

        try {
            for (SSTender iTender : pTenders) {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_tender WHERE number=? AND companyid=?");

                iStatement.setObject(1, iTender.getNumber());
                iStatement.setObject(2, iCurrentCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                if (iResultSet.next()) {
                    iTenders.add((SSTender) iResultSet.getObject(3));
                }
                iStatement.close();
            }

            return iTenders;
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Collections.emptyList();
    }

    public void addTender(SSTender iTender) {
        if (iTender == null || iCurrentCompany == null) {
            return;
        }
        try {

            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT MAX(number) AS maxnum FROM tbl_tender WHERE companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            Integer iCompanyNumber = getCurrentCompany().getAutoIncrement().getNumber(
                    "tender");

            if (iResultSet.next()) {
                Integer iNumber = iResultSet.getInt("maxnum");

                if (iNumber > iCompanyNumber) {
                    iTender.setNumber(iNumber + 1);
                } else {
                    iTender.setNumber(iCompanyNumber + 1);
                }
            } else {
                iTender.setNumber(iCompanyNumber + 1);
            }
            iResultSet.close();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_tender VALUES(NULL,?,?,?)");
            iStatement.setObject(1, iTender.getNumber());
            iStatement.setObject(2, iTender);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);

            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateTender(SSTender iTender) {
        if (iTender == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_tender SET tender=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iTender);
            iStatement.setObject(2, iTender.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteTender(SSTender iTender) {
        if (iTender == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_tender WHERE number=? AND companyid=?");

            iStatement.setObject(1, iTender.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    public List<SSOrder> getOrders() {
        if (iOrders != null) {
            return iOrders;
        }
        iOrders = new LinkedList<>();
        if (iCurrentCompany == null) {
            return iOrders;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_order WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);

                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iOrders.add((SSOrder) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iOrders;
    }

    public Optional<SSOrder> getOrder(SSOrder pOrder) {
        if (pOrder == null || iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_order WHERE number=? AND companyid=?");

            iStatement.setObject(1, pOrder.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSOrder iOrder = (SSOrder) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iOrder);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public List<SSOrder> getOrders(List<SSOrder> pOrders) {
        if (pOrders == null) {
            return Collections.emptyList();
        }
        List<SSOrder> iOrders = new LinkedList<>();

        if (this.iOrders != null) {
            for (SSOrder iOrder : pOrders) {
                if (this.iOrders.contains(iOrder)) {
                    iOrders.add(iOrder);
                }
            }
            return iOrders;
        }
        if (iCurrentCompany == null) {
            return iOrders;
        }
        try {
            for (SSOrder iOrder : pOrders) {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_order WHERE number=? AND companyid=?");

                iStatement.setObject(1, iOrder.getNumber());
                iStatement.setObject(2, iCurrentCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                if (iResultSet.next()) {
                    iOrders.add((SSOrder) iResultSet.getObject(3));
                }
                iStatement.close();
            }

            return iOrders;
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Collections.emptyList();
    }

    public void addOrder(SSOrder iOrder) {
        if (iOrder == null || iCurrentCompany == null) {
            return;
        }
        try {

            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT MAX(number) AS maxnum FROM tbl_order WHERE companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            Integer iCompanyNumber = getCurrentCompany().getAutoIncrement().getNumber(
                    "order");

            if (iResultSet.next()) {
                Integer iNumber = iResultSet.getInt("maxnum");

                if (iNumber > iCompanyNumber) {
                    iOrder.setNumber(iNumber + 1);
                } else {
                    iOrder.setNumber(iCompanyNumber + 1);
                }
            } else {
                iOrder.setNumber(iCompanyNumber + 1);
            }
            iResultSet.close();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_order VALUES(NULL,?,?,?)");
            iStatement.setObject(1, iOrder.getNumber());
            iStatement.setObject(2, iOrder);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);

            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateOrder(SSOrder iOrder) {
        if (iOrder == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_order SET iorder=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iOrder);
            iStatement.setObject(2, iOrder.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteOrder(SSOrder iOrder) {
        if (iOrder == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_order WHERE number=? AND companyid=?");

            iStatement.setObject(1, iOrder.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns the invoices in the current company.
     *
     * @return  A List of invoices or an empty list.
     */
    public List<SSInvoice> getInvoices() {
        if (iInvoices != null) {
            return iInvoices;
        }
        iInvoices = new LinkedList<>();
        if (iCurrentCompany == null) {
            return iInvoices;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_invoice WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);
                iResultSet = iStatement.executeQuery();

                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iInvoices.add((SSInvoice) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iInvoices;
    }

    public Optional<SSInvoice> getInvoice(SSInvoice pInvoice) {
        if (pInvoice == null || iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_invoice WHERE number=? AND companyid=?");

            iStatement.setObject(1, pInvoice.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSInvoice iInvoice = (SSInvoice) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iInvoice);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public List<SSInvoice> getInvoices(List<SSInvoice> pInvoices) {
        if (pInvoices == null) {
            return Collections.emptyList();
        }
        List<SSInvoice> iInvoices = new LinkedList<>();

        if (this.iInvoices != null) {
            for (SSInvoice iInvoice : pInvoices) {
                if (this.iInvoices.contains(iInvoice)) {
                    iInvoices.add(iInvoice);
                }
            }
            return iInvoices;
        }
        if (iCurrentCompany == null) {
            return iInvoices;
        }
        try {
            for (SSInvoice iInvoice : pInvoices) {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_invoice WHERE number=? AND companyid=?");

                iStatement.setObject(1, iInvoice.getNumber());
                iStatement.setObject(2, iCurrentCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                if (iResultSet.next()) {
                    iInvoices.add((SSInvoice) iResultSet.getObject(3));
                }
                iStatement.close();
            }

            return iInvoices;
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Collections.emptyList();
    }

    public void addInvoice(SSInvoice iInvoice) {
        if (iInvoice == null || iCurrentCompany == null) {
            return;
        }
        try {

            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT MAX(number) AS maxnum FROM tbl_invoice WHERE companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            Integer iCompanyNumber = getCurrentCompany().getAutoIncrement().getNumber(
                    "invoice");

            if (iResultSet.next()) {
                Integer iNumber = iResultSet.getInt("maxnum");

                if (iNumber > iCompanyNumber) {
                    iInvoice.setNumber(iNumber + 1);
                } else {
                    iInvoice.setNumber(iCompanyNumber + 1);
                }
            } else {
                iInvoice.setNumber(iCompanyNumber + 1);
            }
            iResultSet.close();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_invoice VALUES(NULL,?,?,?)");
            iStatement.setObject(1, iInvoice.getNumber());
            iStatement.setObject(2, iInvoice);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);

            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateInvoice(SSInvoice iInvoice) {
        if (iInvoice == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_invoice SET invoice=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iInvoice);
            iStatement.setObject(2, iInvoice.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteInvoice(SSInvoice iInvoice) {
        if (iInvoice == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_invoice WHERE number=? AND companyid=?");

            iStatement.setObject(1, iInvoice.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns the inpayments in the current company.
     *
     * @return  A List of invoices or an empty list.
     */
    public List<SSInpayment> getInpayments() {
        if (iInpayments != null) {
            return iInpayments;
        }
        iInpayments = new LinkedList<>();
        if (iCurrentCompany == null) {
            return iInpayments;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_inpayment WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);

                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iInpayments.add((SSInpayment) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iInpayments;
    }

    public Optional<SSInpayment> getInpayment(SSInpayment pInpayment) {
        if (pInpayment == null || iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_inpayment WHERE number=? AND companyid=?");

            iStatement.setObject(1, pInpayment.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSInpayment iInpayment = (SSInpayment) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iInpayment);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public void addInpayment(SSInpayment iInpayment) {
        if (iInpayment == null || iCurrentCompany == null) {
            return;
        }
        try {

            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT MAX(number) AS maxnum FROM tbl_inpayment WHERE companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            Integer iCompanyNumber = getCurrentCompany().getAutoIncrement().getNumber(
                    "inpayment");

            if (iResultSet.next()) {
                Integer iNumber = iResultSet.getInt("maxnum");

                if (iNumber > iCompanyNumber) {
                    iInpayment.setNumber(iNumber + 1);
                } else {
                    iInpayment.setNumber(iCompanyNumber + 1);
                }
            } else {
                iInpayment.setNumber(iCompanyNumber + 1);
            }
            iResultSet.close();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_inpayment VALUES(NULL,?,?,?)");
            iStatement.setObject(1, iInpayment.getNumber());
            iStatement.setObject(2, iInpayment);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);

            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateInpayment(SSInpayment iInpayment) {
        if (iInpayment == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_inpayment SET inpayment=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iInpayment);
            iStatement.setObject(2, iInpayment.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteInpayment(SSInpayment iInpayment) {
        if (iInpayment == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_inpayment WHERE number=? AND companyid=?");

            iStatement.setObject(1, iInpayment.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    /**
     * Returns the outpayments in the current company.
     *
     * @return  A List of outpayments or an empty list.
     */
    public List<SSOutpayment> getOutpayments() {
        if (iOutpayments != null) {
            return iOutpayments;
        }
        iOutpayments = new LinkedList<>();
        if (iCurrentCompany == null) {
            return iOutpayments;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_outpayment WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);

                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iOutpayments.add((SSOutpayment) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iOutpayments;
    }

    public Optional<SSOutpayment> getOutpayment(SSOutpayment pOutpayment) {
        if (pOutpayment == null || iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_outpayment WHERE number=? AND companyid=?");

            iStatement.setObject(1, pOutpayment.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSOutpayment iOutpayment = (SSOutpayment) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iOutpayment);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public void addOutpayment(SSOutpayment iOutpayment) {
        if (iOutpayment == null || iCurrentCompany == null) {
            return;
        }
        try {

            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT MAX(number) AS maxnum FROM tbl_outpayment WHERE companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            Integer iCompanyNumber = getCurrentCompany().getAutoIncrement().getNumber(
                    "outpayment");

            if (iResultSet.next()) {
                Integer iNumber = iResultSet.getInt("maxnum");

                if (iNumber > iCompanyNumber) {
                    iOutpayment.setNumber(iNumber + 1);
                } else {
                    iOutpayment.setNumber(iCompanyNumber + 1);
                }
            } else {
                iOutpayment.setNumber(iCompanyNumber + 1);
            }
            iResultSet.close();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_outpayment VALUES(NULL,?,?,?)");
            iStatement.setObject(1, iOutpayment.getNumber());
            iStatement.setObject(2, iOutpayment);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);

            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateOutpayment(SSOutpayment iOutpayment) {
        if (iOutpayment == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_outpayment SET outpayment=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iOutpayment);
            iStatement.setObject(2, iOutpayment.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteOutpayment(SSOutpayment iOutpayment) {
        if (iOutpayment == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_outpayment WHERE number=? AND companyid=?");

            iStatement.setObject(1, iOutpayment.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns the credit invoices in the current company.
     *
     * @return  A List of invoices or an empty list.
     */
    public List<SSCreditInvoice> getCreditInvoices() {
        if (iCreditInvoices != null) {
            return iCreditInvoices;
        }
        iCreditInvoices = new LinkedList<>();
        if (iCurrentCompany == null) {
            return iCreditInvoices;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_creditinvoice WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);

                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iCreditInvoices.add((SSCreditInvoice) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iCreditInvoices;
    }

    public Optional<SSCreditInvoice> getCreditInvoice(SSCreditInvoice pCreditInvoice) {
        if (pCreditInvoice == null || iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_creditinvoice WHERE number=? AND companyid=?");

            iStatement.setObject(1, pCreditInvoice.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSCreditInvoice iCreditInvoice = (SSCreditInvoice) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iCreditInvoice);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public List<SSCreditInvoice> getCreditInvoices(List<SSCreditInvoice> pCreditInvoices) {
        if (pCreditInvoices == null) {
            return Collections.emptyList();
        }
        List<SSCreditInvoice> iCreditInvoices = new LinkedList<>();

        if (this.iCreditInvoices != null) {
            for (SSCreditInvoice iCreditInvoice : pCreditInvoices) {
                if (this.iCreditInvoices.contains(iCreditInvoice)) {
                    iCreditInvoices.add(iCreditInvoice);
                }
            }
            return iCreditInvoices;
        }
        if (iCurrentCompany == null) {
            return iCreditInvoices;
        }
        try {
            for (SSCreditInvoice iCreditInvoice : pCreditInvoices) {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_creditinvoice WHERE number=? AND companyid=?");

                iStatement.setObject(1, iCreditInvoice.getNumber());
                iStatement.setObject(2, iCurrentCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                if (iResultSet.next()) {
                    iCreditInvoices.add((SSCreditInvoice) iResultSet.getObject(3));
                }
                iStatement.close();
            }

            return iCreditInvoices;
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Collections.emptyList();
    }

    public void addCreditInvoice(SSCreditInvoice iCreditInvoice) {
        if (iCreditInvoice == null || iCurrentCompany == null) {
            return;
        }
        try {

            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT MAX(number) AS maxnum FROM tbl_creditinvoice WHERE companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            Integer iCompanyNumber = getCurrentCompany().getAutoIncrement().getNumber(
                    "creditinvoice");

            if (iResultSet.next()) {
                Integer iNumber = iResultSet.getInt("maxnum");

                if (iNumber > iCompanyNumber) {
                    iCreditInvoice.setNumber(iNumber + 1);
                } else {
                    iCreditInvoice.setNumber(iCompanyNumber + 1);
                }
            } else {
                iCreditInvoice.setNumber(iCompanyNumber + 1);
            }
            iResultSet.close();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_creditinvoice VALUES(NULL,?,?,?)");
            iStatement.setObject(1, iCreditInvoice.getNumber());
            iStatement.setObject(2, iCreditInvoice);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);

            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateCreditInvoice(SSCreditInvoice iCreditInvoice) {
        if (iCreditInvoice == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_creditinvoice SET creditinvoice=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iCreditInvoice);
            iStatement.setObject(2, iCreditInvoice.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteCreditInvoice(SSCreditInvoice iCreditInvoice) {
        if (iCreditInvoice == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_creditinvoice WHERE number=? AND companyid=?");

            iStatement.setObject(1, iCreditInvoice.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns the periodic invoices in the current company.
     *
     * @return  A List of periodic invoices or an empty list.
     */
    public List<SSPeriodicInvoice> getPeriodicInvoices() {
        if (iPeriodicInvoices != null) {
            return iPeriodicInvoices;
        }
        iPeriodicInvoices = new LinkedList<>();
        if (iCurrentCompany == null) {
            return iPeriodicInvoices;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_periodicinvoice WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);

                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iPeriodicInvoices.add((SSPeriodicInvoice) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iPeriodicInvoices;
    }

    public Optional<SSPeriodicInvoice> getPeriodicInvoice(SSPeriodicInvoice pPeriodicInvoice) {
        if (pPeriodicInvoice == null || iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_periodicinvoice WHERE number=? AND companyid=?");

            iStatement.setObject(1, pPeriodicInvoice.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSPeriodicInvoice iPeriodicInvoice = (SSPeriodicInvoice) iResultSet.getObject(
                        3);

                iStatement.close();
                return Optional.of(iPeriodicInvoice);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public void addPeriodicInvoice(SSPeriodicInvoice iPeriodicInvoice) {
        if (iPeriodicInvoice == null || iCurrentCompany == null) {
            return;
        }
        try {

            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT MAX(number) AS maxnum FROM tbl_periodicinvoice WHERE companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            Integer iCompanyNumber = getCurrentCompany().getAutoIncrement().getNumber(
                    "periodicinvoice");

            if (iResultSet.next()) {
                Integer iNumber = iResultSet.getInt("maxnum");

                if (iNumber > iCompanyNumber) {
                    iPeriodicInvoice.setNumber(iNumber + 1);
                } else {
                    iPeriodicInvoice.setNumber(iCompanyNumber + 1);
                }
            } else {
                iPeriodicInvoice.setNumber(iCompanyNumber + 1);
            }
            iResultSet.close();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_periodicinvoice VALUES(NULL,?,?,?)");
            iStatement.setObject(1, iPeriodicInvoice.getNumber());
            iStatement.setObject(2, iPeriodicInvoice);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);

            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updatePeriodicInvoice(SSPeriodicInvoice iPeriodicInvoice) {
        if (iPeriodicInvoice == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_periodicinvoice SET periodicinvoice=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iPeriodicInvoice);
            iStatement.setObject(2, iPeriodicInvoice.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deletePeriodicInvoice(SSPeriodicInvoice iPeriodicInvoice) {
        if (iPeriodicInvoice == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_periodicinvoice WHERE number=? AND companyid=?");

            iStatement.setObject(1, iPeriodicInvoice.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns the purchase orders in the current company.
     *
     * @return  A List of orders or an empty list.
     */
    public List<SSPurchaseOrder> getPurchaseOrders() {
        if (iPurchaseOrders != null) {
            return iPurchaseOrders;
        }
        iPurchaseOrders = new LinkedList<>();
        if (iCurrentCompany == null) {
            return iPurchaseOrders;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_purchaseorder WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);

                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iPurchaseOrders.add((SSPurchaseOrder) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iPurchaseOrders;
    }

    public Optional<SSPurchaseOrder> getPurchaseOrder(SSPurchaseOrder pPurchaseOrder) {
        if (pPurchaseOrder == null || iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_purchaseorder WHERE number=? AND companyid=?");

            iStatement.setObject(1, pPurchaseOrder.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSPurchaseOrder iPurchaseOrder = (SSPurchaseOrder) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iPurchaseOrder);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public List<SSPurchaseOrder> getPurchaseOrders(List<SSPurchaseOrder> pPurchaseOrders) {
        if (pPurchaseOrders == null) {
            return Collections.emptyList();
        }
        List<SSPurchaseOrder> iPurchaseOrders = new LinkedList<>();

        if (this.iPurchaseOrders != null) {
            for (SSPurchaseOrder iPurchaseOrder : pPurchaseOrders) {
                if (this.iPurchaseOrders.contains(iPurchaseOrder)) {
                    iPurchaseOrders.add(iPurchaseOrder);
                }
            }
            return iPurchaseOrders;
        }
        if (iCurrentCompany == null) {
            return iPurchaseOrders;
        }
        try {
            for (SSPurchaseOrder iPurchaseOrder : pPurchaseOrders) {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_purchaseorder WHERE number=? AND companyid=?");

                iStatement.setObject(1, iPurchaseOrder.getNumber());
                iStatement.setObject(2, iCurrentCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                if (iResultSet.next()) {
                    iPurchaseOrders.add((SSPurchaseOrder) iResultSet.getObject(3));
                }
                iStatement.close();
            }

            return iPurchaseOrders;
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Collections.emptyList();
    }

    public void addPurchaseOrder(SSPurchaseOrder iPurchaseOrder) {
        if (iPurchaseOrder == null || iCurrentCompany == null) {
            return;
        }
        try {

            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT MAX(number) AS maxnum FROM tbl_purchaseorder WHERE companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            Integer iCompanyNumber = getCurrentCompany().getAutoIncrement().getNumber(
                    "purchaseorder");

            if (iResultSet.next()) {
                Integer iNumber = iResultSet.getInt("maxnum");

                if (iNumber > iCompanyNumber) {
                    iPurchaseOrder.setNumber(iNumber + 1);
                } else {
                    iPurchaseOrder.setNumber(iCompanyNumber + 1);
                }
            } else {
                iPurchaseOrder.setNumber(iCompanyNumber + 1);
            }
            iResultSet.close();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_purchaseorder VALUES(NULL,?,?,?)");
            iStatement.setObject(1, iPurchaseOrder.getNumber());
            iStatement.setObject(2, iPurchaseOrder);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);

            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updatePurchaseOrder(SSPurchaseOrder iPurchaseOrder) {
        if (iPurchaseOrder == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_purchaseorder SET purchaseorder=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iPurchaseOrder);
            iStatement.setObject(2, iPurchaseOrder.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deletePurchaseOrder(SSPurchaseOrder iPurchaseOrder) {
        if (iPurchaseOrder == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_purchaseorder WHERE number=? AND companyid=?");

            iStatement.setObject(1, iPurchaseOrder.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns the supplier invoices in the current company.
     *
     * @return  A List of invoices or an empty list.
     */
    public List<SSSupplierInvoice> getSupplierInvoices() {
        if (iSupplierInvoices != null) {
            return iSupplierInvoices;
        }
        iSupplierInvoices = new LinkedList<>();
        if (iCurrentCompany == null) {
            return iSupplierInvoices;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_supplierinvoice WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);

                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iSupplierInvoices.add((SSSupplierInvoice) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iSupplierInvoices;
    }

    public Optional<SSSupplierInvoice> getSupplierInvoice(SSSupplierInvoice pSupplierInvoice) {
        if (pSupplierInvoice == null || iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_supplierinvoice WHERE number=? AND companyid=?");

            iStatement.setObject(1, pSupplierInvoice.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSSupplierInvoice iSupplierInvoice = (SSSupplierInvoice) iResultSet.getObject(
                        3);

                iStatement.close();
                return Optional.of(iSupplierInvoice);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public List<SSSupplierInvoice> getSupplierInvoices(List<SSSupplierInvoice> pSupplierInvoices) {
        if (pSupplierInvoices == null) {
            return Collections.emptyList();
        }
        List<SSSupplierInvoice> iSupplierInvoices = new LinkedList<>();

        if (this.iSupplierInvoices != null) {
            for (SSSupplierInvoice iSupplierInvoice : pSupplierInvoices) {
                if (this.iSupplierInvoices.contains(iSupplierInvoice)) {
                    iSupplierInvoices.add(iSupplierInvoice);
                }
            }
            return iSupplierInvoices;
        }
        if (iCurrentCompany == null) {
            return iSupplierInvoices;
        }
        try {
            for (SSSupplierInvoice iSupplierInvoice : pSupplierInvoices) {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_supplierinvoice WHERE number=? AND companyid=?");

                iStatement.setObject(1, iSupplierInvoice.getNumber());
                iStatement.setObject(2, iCurrentCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                if (iResultSet.next()) {
                    iSupplierInvoices.add((SSSupplierInvoice) iResultSet.getObject(3));
                }
                iStatement.close();
            }

            return iSupplierInvoices;
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Collections.emptyList();
    }

    public void addSupplierInvoice(SSSupplierInvoice iSupplierInvoice) {
        if (iSupplierInvoice == null || iCurrentCompany == null) {
            return;
        }
        try {

            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT MAX(number) AS maxnum FROM tbl_supplierinvoice WHERE companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            Integer iCompanyNumber = getCurrentCompany().getAutoIncrement().getNumber(
                    "supplierinvoice");

            if (iResultSet.next()) {
                Integer iNumber = iResultSet.getInt("maxnum");

                if (iNumber > iCompanyNumber) {
                    iSupplierInvoice.setNumber(iNumber + 1);
                } else {
                    iSupplierInvoice.setNumber(iCompanyNumber + 1);
                }
            } else {
                iSupplierInvoice.setNumber(iCompanyNumber + 1);
            }
            iResultSet.close();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_supplierinvoice VALUES(NULL,?,?,?)");
            iStatement.setObject(1, iSupplierInvoice.getNumber());
            iStatement.setObject(2, iSupplierInvoice);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);

            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateSupplierInvoice(SSSupplierInvoice iSupplierInvoice) {
        if (iSupplierInvoice == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_supplierinvoice SET supplierinvoice=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iSupplierInvoice);
            iStatement.setObject(2, iSupplierInvoice.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteSupplierInvoice(SSSupplierInvoice iSupplierInvoice) {
        if (iSupplierInvoice == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_supplierinvoice WHERE number=? AND companyid=?");

            iStatement.setObject(1, iSupplierInvoice.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns the credit invoices in the current company.
     *
     * @return  A List of invoices or an empty list.
     */
    public List<SSSupplierCreditInvoice> getSupplierCreditInvoices() {
        if (iSupplierCreditInvoices != null) {
            return iSupplierCreditInvoices;
        }
        iSupplierCreditInvoices = new LinkedList<>();
        if (iCurrentCompany == null) {
            return iSupplierCreditInvoices;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_suppliercreditinvoice WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);

                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iSupplierCreditInvoices.add(
                            (SSSupplierCreditInvoice) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iSupplierCreditInvoices;
    }

    public Optional<SSSupplierCreditInvoice> getSupplierCreditInvoice(SSSupplierCreditInvoice pSupplierCreditInvoice) {
        if (pSupplierCreditInvoice == null || iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_suppliercreditinvoice WHERE number=? AND companyid=?");

            iStatement.setObject(1, pSupplierCreditInvoice.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSSupplierCreditInvoice iSupplierCreditInvoice = (SSSupplierCreditInvoice) iResultSet.getObject(
                        3);

                iStatement.close();
                return Optional.of(iSupplierCreditInvoice);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public void addSupplierCreditInvoice(SSSupplierCreditInvoice iSupplierCreditInvoice) {
        if (iSupplierCreditInvoice == null || iCurrentCompany == null) {
            return;
        }
        try {

            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT MAX(number) AS maxnum FROM tbl_suppliercreditinvoice WHERE companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            Integer iCompanyNumber = getCurrentCompany().getAutoIncrement().getNumber(
                    "suppliercreditinvoice");

            if (iResultSet.next()) {
                Integer iNumber = iResultSet.getInt("maxnum");

                if (iNumber > iCompanyNumber) {
                    iSupplierCreditInvoice.setNumber(iNumber + 1);
                } else {
                    iSupplierCreditInvoice.setNumber(iCompanyNumber + 1);
                }
            } else {
                iSupplierCreditInvoice.setNumber(iCompanyNumber + 1);
            }
            iResultSet.close();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_suppliercreditinvoice VALUES(NULL,?,?,?)");
            iStatement.setObject(1, iSupplierCreditInvoice.getNumber());
            iStatement.setObject(2, iSupplierCreditInvoice);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);

            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateSupplierCreditInvoice(SSSupplierCreditInvoice iSupplierCreditInvoice) {
        if (iSupplierCreditInvoice == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_suppliercreditinvoice SET suppliercreditinvoice=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iSupplierCreditInvoice);
            iStatement.setObject(2, iSupplierCreditInvoice.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteSupplierCreditInvoice(SSSupplierCreditInvoice iSupplierCreditInvoice) {
        if (iSupplierCreditInvoice == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_suppliercreditinvoice WHERE number=? AND companyid=?");

            iStatement.setObject(1, iSupplierCreditInvoice.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public List<SSInventory> getInventories() {
        if (iInventories != null) {
            return iInventories;
        }
        iInventories = new LinkedList<>();
        if (iCurrentCompany == null) {
            return iInventories;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_inventory WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);

                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iInventories.add((SSInventory) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iInventories;
    }

    public Optional<SSInventory> getInventory(SSInventory pInventory) {
        if (pInventory == null || iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_inventory WHERE number=? AND companyid=?");

            iStatement.setObject(1, pInventory.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSInventory iInventory = (SSInventory) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iInventory);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public void addInventory(SSInventory iInventory) {
        if (iInventory == null || iCurrentCompany == null) {
            return;
        }
        try {

            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT MAX(number) AS maxnum FROM tbl_inventory WHERE companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            Integer iCompanyNumber = getCurrentCompany().getAutoIncrement().getNumber(
                    "inventory");

            if (iResultSet.next()) {
                Integer iNumber = iResultSet.getInt("maxnum");

                if (iNumber > iCompanyNumber) {
                    iInventory.setNumber(iNumber + 1);
                } else {
                    iInventory.setNumber(iCompanyNumber + 1);
                }
            } else {
                iInventory.setNumber(iCompanyNumber + 1);
            }
            iResultSet.close();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_inventory VALUES(NULL,?,?,?)");
            iStatement.setObject(1, iInventory.getNumber());
            iStatement.setObject(2, iInventory);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);

            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateInventory(SSInventory iInventory) {
        if (iInventory == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_inventory SET inventory=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iInventory);
            iStatement.setObject(2, iInventory.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteInventory(SSInventory iInventory) {
        if (iInventory == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_inventory WHERE number=? AND companyid=?");

            iStatement.setObject(1, iInventory.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public List<SSIndelivery> getIndeliveries() {
        if (iIndeliveries != null) {
            return iIndeliveries;
        }
        iIndeliveries = new LinkedList<>();
        if (iCurrentCompany == null) {
            return iIndeliveries;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_indelivery WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);

                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iIndeliveries.add((SSIndelivery) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iIndeliveries;
    }

    public Optional<SSIndelivery> getIndelivery(SSIndelivery pIndelivery) {
        if (pIndelivery == null || iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_indelivery WHERE number=? AND companyid=?");

            iStatement.setObject(1, pIndelivery.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSIndelivery iIndelivery = (SSIndelivery) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iIndelivery);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public void addIndelivery(SSIndelivery iIndelivery) {
        if (iIndelivery == null || iCurrentCompany == null) {
            return;
        }
        try {

            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT MAX(number) AS maxnum FROM tbl_indelivery WHERE companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            Integer iCompanyNumber = getCurrentCompany().getAutoIncrement().getNumber(
                    "indelivery");

            if (iResultSet.next()) {
                Integer iNumber = iResultSet.getInt("maxnum");

                if (iNumber > iCompanyNumber) {
                    iIndelivery.setNumber(iNumber + 1);
                } else {
                    iIndelivery.setNumber(iCompanyNumber + 1);
                }
            } else {
                iIndelivery.setNumber(iCompanyNumber + 1);
            }
            iResultSet.close();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_indelivery VALUES(NULL,?,?,?)");
            iStatement.setObject(1, iIndelivery.getNumber());
            iStatement.setObject(2, iIndelivery);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);

            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateIndelivery(SSIndelivery iIndelivery) {
        if (iIndelivery == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_indelivery SET indelivery=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iIndelivery);
            iStatement.setObject(2, iIndelivery.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteIndelivery(SSIndelivery iIndelivery) {
        if (iIndelivery == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_indelivery WHERE number=? AND companyid=?");

            iStatement.setObject(1, iIndelivery.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////

    /**
     *
     * @return
     */
    public List<SSOutdelivery> getOutdeliveries() {
        if (iOutdeliveries != null) {
            return iOutdeliveries;
        }
        iOutdeliveries = new LinkedList<>();
        if (iCurrentCompany == null) {
            return iOutdeliveries;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_outdelivery WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);

                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iOutdeliveries.add((SSOutdelivery) iResultSet.getObject(3));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iOutdeliveries;
    }

    public Optional<SSOutdelivery> getOutdelivery(SSOutdelivery pOutdelivery) {
        if (pOutdelivery == null || iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_outdelivery WHERE number=? AND companyid=?");

            iStatement.setObject(1, pOutdelivery.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSOutdelivery iOutdelivery = (SSOutdelivery) iResultSet.getObject(3);

                iStatement.close();
                return Optional.of(iOutdelivery);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public void addOutdelivery(SSOutdelivery iOutdelivery) {
        if (iOutdelivery == null || iCurrentCompany == null) {
            return;
        }
        try {

            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT MAX(number) AS maxnum FROM tbl_outdelivery WHERE companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            Integer iCompanyNumber = getCurrentCompany().getAutoIncrement().getNumber(
                    "outdelivery");

            if (iResultSet.next()) {
                Integer iNumber = iResultSet.getInt("maxnum");

                if (iNumber > iCompanyNumber) {
                    iOutdelivery.setNumber(iNumber + 1);
                } else {
                    iOutdelivery.setNumber(iCompanyNumber + 1);
                }
            } else {
                iOutdelivery.setNumber(iCompanyNumber + 1);
            }
            iResultSet.close();
            iStatement.close();

            iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_outdelivery VALUES(NULL,?,?,?)");
            iStatement.setObject(1, iOutdelivery.getNumber());
            iStatement.setObject(2, iOutdelivery);
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);

            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateOutdelivery(SSOutdelivery iOutdelivery) {
        if (iOutdelivery == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_outdelivery SET outdelivery=? WHERE number=? AND companyid=?");

            iStatement.setObject(1, iOutdelivery);
            iStatement.setObject(2, iOutdelivery.getNumber());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteOutdelivery(SSOutdelivery iOutdelivery) {
        if (iOutdelivery == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_outdelivery WHERE number=? AND companyid=?");

            iStatement.setObject(1, iOutdelivery.getNumber());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // /////////////////////////////////////////////////////////////////////////////
    public List<SSOwnReport> getOwnReports() {
        if (iOwnReports != null) {
            return iOwnReports;
        }
        iOwnReports = new LinkedList<>();

        if (iCurrentCompany == null) {
            return iOwnReports;
        }
        try {
            Integer iMax = -1;
            ResultSet iResultSet;
            PreparedStatement iStatement;

            while (true) {
                iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_ownreport WHERE companyid=? AND id>?");
                iStatement.setObject(1, iCurrentCompany.getId());
                iStatement.setObject(2, iMax);
                iStatement.setMaxRows(1024);
                iResultSet = iStatement.executeQuery();
                int i = 0;

                while (iResultSet.next()) {
                    iMax = iResultSet.getInt(1);
                    iOwnReports.add((SSOwnReport) iResultSet.getObject(2));
                    i++;
                }
                if (i != 1024) {
                    break;
                }
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return iOwnReports;
    }

    public Optional<SSOwnReport> getOwnReport(SSOwnReport pOwnReport) {
        if (pOwnReport == null) {
            return Optional.empty();
        }
        if (iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_ownreport WHERE id=? AND companyid=?");

            iStatement.setObject(1, pOwnReport.getId());
            iStatement.setObject(2, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSOwnReport iOwnReport = (SSOwnReport) iResultSet.getObject(2);

                iStatement.close();
                return Optional.of(iOwnReport);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public Optional<SSOwnReport> getOwnReport(Integer iOwnReportNumber) {
        if (iOwnReportNumber == null) {
            return Optional.empty();
        }
        if (iCurrentCompany == null) {
            return Optional.empty();
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "SELECT * FROM tbl_ownreport WHERE id=" + iOwnReportNumber
                    + " AND companyid=?");

            iStatement.setObject(1, iCurrentCompany.getId());
            ResultSet iResultSet = iStatement.executeQuery();

            if (iResultSet.next()) {
                SSOwnReport iOwnReport = (SSOwnReport) iResultSet.getObject(2);

                iStatement.close();
                return Optional.of(iOwnReport);
            }
            iResultSet.close();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Optional.empty();
    }

    public List<SSOwnReport> getOwnReports(List<SSOwnReport> pOwnReports) {
        if (pOwnReports == null) {
            return Collections.emptyList();
        }
        List<SSOwnReport> iOwnReports = new LinkedList<>();

        if (this.iOwnReports != null) {
            for (SSOwnReport iOwnReport : pOwnReports) {
                if (this.iOwnReports.contains(iOwnReport)) {
                    iOwnReports.add(iOwnReport);
                }
            }
            return iOwnReports;
        }
        if (iCurrentCompany == null) {
            return iOwnReports;
        }
        try {
            for (SSOwnReport iOwnReport : pOwnReports) {
                PreparedStatement iStatement = iConnection.prepareStatement(
                        "SELECT * FROM tbl_ownreport WHERE id=? AND companyid=?");

                iStatement.setObject(1, iOwnReport.getId());
                iStatement.setObject(2, iCurrentCompany.getId());
                ResultSet iResultSet = iStatement.executeQuery();

                if (iResultSet.next()) {
                    iOwnReports.add((SSOwnReport) iResultSet.getObject(2));
                }
                iStatement.close();
            }

            return iOwnReports;
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
        return Collections.emptyList();
    }

    public void addOwnReport(SSOwnReport iOwnReport) {
        if (iOwnReport == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "INSERT INTO tbl_ownreport VALUES(NULL,?,?)");

            iStatement.setObject(1, iOwnReport);
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            iStatement = iConnection.prepareStatement("SELECT * FROM tbl_ownreport");
            ResultSet iResultSet = iStatement.executeQuery();
            Integer iId = -1;

            while (iResultSet.next()) {
                if (iResultSet.isLast()) {
                    iId = iResultSet.getInt("id");
                }
            }
            iResultSet.close();
            iStatement.close();
            iOwnReport.setId(iId);

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                LOG.error("Unexpected error", e);
            }
            iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_ownreport SET ownreport=? WHERE id=?");
            iStatement.setObject(1, iOwnReport);
            iStatement.setObject(2, iOwnReport.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();
        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void updateOwnReport(SSOwnReport iOwnReport) {
        if (iOwnReport == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "UPDATE tbl_ownreport SET ownreport=? WHERE id=? AND companyid=?");

            iStatement.setObject(1, iOwnReport);
            iStatement.setObject(2, iOwnReport.getId());
            iStatement.setObject(3, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    public void deleteOwnReport(SSOwnReport iOwnReport) {
        if (iOwnReport == null || iCurrentCompany == null) {
            return;
        }
        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DELETE FROM tbl_ownreport WHERE id=? AND companyid=?");

            iStatement.setObject(1, iOwnReport.getId());
            iStatement.setObject(2, iCurrentCompany.getId());
            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {
            LOG.error("Unexpected error", e);
            try {
                iConnection.rollback();
            } catch (SQLException ignored) {}
            SSUnexpectedErrorDialog.showDialog(SSMainFrame.getInstance(), "Databasfel",
                    "Bokfri kunde inte slutföra databasåtgärden.", e);
        }
    }

    // /////////////////////////////////////////////////////////////////////////////

    public void createLocalTriggers() {

        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "CREATE TRIGGER NEWPROJECT  AFTER INSERT ON tbl_project FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITPROJECT  AFTER UPDATE ON tbl_project FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETEPROJECT  AFTER DELETE ON tbl_project FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWRESULTUNIT  AFTER INSERT ON tbl_resultunit FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITRESULTUNIT  AFTER UPDATE ON tbl_resultunit FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETERESULTUNIT  AFTER DELETE ON tbl_resultunit FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWPRODUCT  AFTER INSERT ON tbl_product FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITPRODUCT  AFTER UPDATE ON tbl_product FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETEPRODUCT  AFTER DELETE ON tbl_product FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWCUSTOMER  AFTER INSERT ON tbl_customer FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITCUSTOMER  AFTER UPDATE ON tbl_customer FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETECUSTOMER  AFTER DELETE ON tbl_customer FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWSUPPLIER  AFTER INSERT ON tbl_supplier FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITSUPPLIER  AFTER UPDATE ON tbl_supplier FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETESUPPLIER  AFTER DELETE ON tbl_supplier FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWVOUCHERTEMPLATE  AFTER INSERT ON tbl_vouchertemplate FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETEVOUCHERTEMPLATE  AFTER DELETE ON tbl_vouchertemplate FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWAUTODIST  AFTER INSERT ON tbl_autodist FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITAUTODIST  AFTER UPDATE ON tbl_autodist FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETEAUTODIST  AFTER DELETE ON tbl_autodist FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWINPAYMENT  AFTER INSERT ON tbl_inpayment FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITINPAYMENT  AFTER UPDATE ON tbl_inpayment FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETEINPAYMENT  AFTER DELETE ON tbl_inpayment FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWTENDER  AFTER INSERT ON tbl_tender FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITTENDER  AFTER UPDATE ON tbl_tender FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETETENDER  AFTER DELETE ON tbl_tender FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWORDER  AFTER INSERT ON tbl_order FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITORDER  AFTER UPDATE ON tbl_order FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETEORDER  AFTER DELETE ON tbl_order FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWINVOICE  AFTER INSERT ON tbl_invoice FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITINVOICE  AFTER UPDATE ON tbl_invoice FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETEINVOICE  AFTER DELETE ON tbl_invoice FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWCREDITINVOICE  AFTER INSERT ON tbl_creditinvoice FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITCREDITINVOICE  AFTER UPDATE ON tbl_creditinvoice FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETECREDITINVOICE  AFTER DELETE ON tbl_creditinvoice FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWPERIODICINVOICE  AFTER INSERT ON tbl_periodicinvoice FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITPERIODICINVOICE  AFTER UPDATE ON tbl_periodicinvoice FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETEPERIODICINVOICE  AFTER DELETE ON tbl_periodicinvoice FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWOUTPAYMENT  AFTER INSERT ON tbl_outpayment FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITOUTPAYMENT  AFTER UPDATE ON tbl_outpayment FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETEOUTPAYMENT  AFTER DELETE ON tbl_outpayment FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWPURCHASEORDER  AFTER INSERT ON tbl_purchaseorder FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITPURCHASEORDER  AFTER UPDATE ON tbl_purchaseorder FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETEPURCHASEORDER  AFTER DELETE ON tbl_purchaseorder FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWSUPPLIERINVOICE  AFTER INSERT ON tbl_supplierinvoice FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITSUPPLIERINVOICE  AFTER UPDATE ON tbl_supplierinvoice FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETESUPPLIERINVOICE  AFTER DELETE ON tbl_supplierinvoice FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWSUPPLIERCREDITINVOICE  AFTER INSERT ON tbl_suppliercreditinvoice FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITSUPPLIERCREDITINVOICE  AFTER UPDATE ON tbl_suppliercreditinvoice FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETESUPPLIERCREDITINVOICE  AFTER DELETE ON tbl_suppliercreditinvoice FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWINVENTORY  AFTER INSERT ON tbl_inventory FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITINVENTORY  AFTER UPDATE ON tbl_inventory FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETEINVENTORY  AFTER DELETE ON tbl_inventory FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWINDELIVERY  AFTER INSERT ON tbl_indelivery FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITINDELIVERY  AFTER UPDATE ON tbl_indelivery FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETEINDELIVERY  AFTER DELETE ON tbl_indelivery FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWOUTDELIVERY  AFTER INSERT ON tbl_outdelivery FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITOUTDELIVERY  AFTER UPDATE ON tbl_outdelivery FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETEOUTDELIVERY  AFTER DELETE ON tbl_outdelivery FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWVOUCHER  AFTER INSERT ON tbl_voucher FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITVOUCHER  AFTER UPDATE ON tbl_voucher FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETEVOUCHER  AFTER DELETE ON tbl_voucher FOR EACH ROW QUEUE 10000 CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER NEWOWNREPORT  AFTER INSERT ON tbl_ownreport FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER EDITOWNREPORT  AFTER UPDATE ON tbl_ownreport FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";"
                            + "CREATE TRIGGER DELETEOWNREPORT  AFTER DELETE ON tbl_ownreport FOR EACH ROW CALL \"se.swedsoft.bookkeeping.SSTriggerHandler\";");

            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {// LOG.info("Triggers fanns redan vi lokal tilläggning");
            // LOG.error("Unexpected error", e);
        }
    }

    public void createTriggers() {
        createLocalTriggers();
    }

    public void dropTriggers() {

        try {
            PreparedStatement iStatement = iConnection.prepareStatement(
                    "DROP TRIGGER NEWPROJECT;" + "DROP TRIGGER EDITPROJECT;"
                    + "DROP TRIGGER DELETEPROJECT;" + "DROP TRIGGER NEWRESULTUNIT;"
                    + "DROP TRIGGER EDITRESULTUNIT;" + "DROP TRIGGER DELETERESULTUNIT;"
                    + "DROP TRIGGER NEWPRODUCT;" + "DROP TRIGGER EDITPRODUCT;"
                    + "DROP TRIGGER DELETEPRODUCT;" + "DROP TRIGGER NEWCUSTOMER;"
                    + "DROP TRIGGER EDITCUSTOMER;" + "DROP TRIGGER DELETECUSTOMER;"
                    + "DROP TRIGGER NEWSUPPLIER;" + "DROP TRIGGER EDITSUPPLIER;"
                    + "DROP TRIGGER DELETESUPPLIER;" + "DROP TRIGGER NEWVOUCHERTEMPLATE;"
                    + "DROP TRIGGER DELETEVOUCHERTEMPLATE;" + "DROP TRIGGER NEWAUTODIST;"
                    + "DROP TRIGGER EDITAUTODIST;" + "DROP TRIGGER DELETEAUTODIST;"
                    + "DROP TRIGGER NEWINPAYMENT;" + "DROP TRIGGER EDITINPAYMENT;"
                    + "DROP TRIGGER DELETEINPAYMENT;" + "DROP TRIGGER NEWTENDER;"
                    + "DROP TRIGGER EDITTENDER;" + "DROP TRIGGER DELETETENDER;"
                    + "DROP TRIGGER NEWORDER;" + "DROP TRIGGER EDITORDER;"
                    + "DROP TRIGGER DELETEORDER;" + "DROP TRIGGER NEWINVOICE;"
                    + "DROP TRIGGER EDITINVOICE;" + "DROP TRIGGER DELETEINVOICE;"
                    + "DROP TRIGGER NEWCREDITINVOICE;" + "DROP TRIGGER EDITCREDITINVOICE;"
                    + "DROP TRIGGER DELETECREDITINVOICE;"
                    + "DROP TRIGGER NEWPERIODICINVOICE;"
                    + "DROP TRIGGER EDITPERIODICINVOICE;"
                    + "DROP TRIGGER DELETEPERIODICINVOICE;"
                    + "DROP TRIGGER NEWOUTPAYMENT;" + "DROP TRIGGER EDITOUTPAYMENT;"
                    + "DROP TRIGGER DELETEOUTPAYMENT;" + "DROP TRIGGER NEWPURCHASEORDER;"
                    + "DROP TRIGGER EDITPURCHASEORDER;"
                    + "DROP TRIGGER DELETEPURCHASEORDER;"
                    + "DROP TRIGGER NEWSUPPLIERINVOICE;"
                    + "DROP TRIGGER EDITSUPPLIERINVOICE;"
                    + "DROP TRIGGER DELETESUPPLIERINVOICE;"
                    + "DROP TRIGGER NEWSUPPLIERCREDITINVOICE;"
                    + "DROP TRIGGER EDITSUPPLIERCREDITINVOICE;"
                    + "DROP TRIGGER DELETESUPPLIERCREDITINVOICE;"
                    + "DROP TRIGGER NEWINVENTORY;" + "DROP TRIGGER EDITINVENTORY;"
                    + "DROP TRIGGER DELETEINVENTORY;" + "DROP TRIGGER NEWINDELIVERY;"
                    + "DROP TRIGGER EDITINDELIVERY;" + "DROP TRIGGER DELETEINDELIVERY;"
                    + "DROP TRIGGER NEWOUTDELIVERY;" + "DROP TRIGGER EDITOUTDELIVERY;"
                    + "DROP TRIGGER DELETEOUTDELIVERY;" + "DROP TRIGGER NEWVOUCHER;"
                    + "DROP TRIGGER EDITVOUCHER;" + "DROP TRIGGER DELETEVOUCHER;"
                    + "DROP TRIGGER NEWOWNREPORT;" + "DROP TRIGGER EDITOWNREPORT;"
                    + "DROP TRIGGER DELETEOWNREPORT;");

            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

        } catch (SQLException e) {// LOG.info("Triggers fanns inte vid borttagning");
        }
    }

    public File getFile(UID pIdentifier) {
        String iFileName = pIdentifier.toString();

        iFileName = iFileName.replace(":", ".");
        iFileName = iFileName.replace("-", ".");

        return new File(Path.get(Path.USER_DATA), "db/" + iFileName + ".data");
    }

    public void createNewTables() {
        try {
            if (iConnection == null || iConnection.isClosed()) {
                return;
            }
            
            String q = SSUtil.readResourceToString("sql/create_tables.sql");

            PreparedStatement iStatement = iConnection.prepareStatement(q);

            iStatement.executeUpdate();
            iConnection.commit();
            iStatement.close();

            dropTriggers();
        } catch (SQLException e) {// LOG.error("Unexpected error", e);
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        sb.append("se.swedsoft.bookkeeping.data.system.SSDB");
        sb.append("{iAutoDists=").append(iAutoDists);
        sb.append(", iConnection=").append(iConnection);
        sb.append(", iCreditInvoices=").append(iCreditInvoices);
        sb.append(", iCurrentCompany=").append(iCurrentCompany);
        sb.append(", iCurrentYear=").append(iCurrentYear);
        sb.append(", iCustomers=").append(iCustomers);
        sb.append(", iIndeliveries=").append(iIndeliveries);
        sb.append(", iInpayments=").append(iInpayments);
        sb.append(", iInventories=").append(iInventories);
        sb.append(", iInvoices=").append(iInvoices);
        sb.append(", iListenerMap=").append(iListenerMap);
        sb.append(", iOrders=").append(iOrders);
        sb.append(", iOutdeliveries=").append(iOutdeliveries);
        sb.append(", iOutpayments=").append(iOutpayments);
        sb.append(", iOwnReports=").append(iOwnReports);
        sb.append(", iPeriodicInvoices=").append(iPeriodicInvoices);
        sb.append(", iProducts=").append(iProducts);
        sb.append(", iPurchaseOrders=").append(iPurchaseOrders);
        sb.append(", iSupplierCreditInvoices=").append(iSupplierCreditInvoices);
        sb.append(", iSupplierInvoices=").append(iSupplierInvoices);
        sb.append(", iSuppliers=").append(iSuppliers);
        sb.append(", iTenders=").append(iTenders);
        sb.append(", iVouchers=").append(iVouchers);
        sb.append('}');
        return sb.toString();
    }
}
