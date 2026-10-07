package org.fribok.bookkeeping.cli;

import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.HsqlEngineMigrationService;
import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedInpaymentStore;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedOutpaymentStore;
import org.fribok.bookkeeping.dataformat.NormalizedRegisterStore;
import org.fribok.bookkeeping.dataformat.NormalizedSupplierInvoiceStore;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;
import se.swedsoft.bookkeeping.data.common.SSUnit;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;

/**
 * Headless database lifecycle used by command-line operations.
 *
 * <p>The runtime dispatches on the recorded data format: a normalized database (format 3)
 * is served by the normalized stores over a plain JDBC connection without starting the
 * legacy object storage, while legacy formats start SSDB exactly as before. Commands that
 * still require SSDB fail fast with {@code NORMALIZED_STORAGE_UNSUPPORTED} on a normalized
 * database instead of reading missing legacy tables.
 */
public final class BokfriRuntime implements AutoCloseable {
    private final SSDB database;
    private final Connection connection;
    private final NormalizedAccountingStore normalizedStore;
    private final NormalizedRegisterStore registerStore;
    private final NormalizedInvoiceStore invoiceStore;
    private final NormalizedInpaymentStore inpaymentStore;
    private final NormalizedSupplierInvoiceStore supplierInvoiceStore;
    private final NormalizedOutpaymentStore outpaymentStore;
    private final int dataFormat;

    private BokfriRuntime(SSDB database, int dataFormat) {
        this.database = database;
        this.dataFormat = dataFormat;
        this.connection = null;
        this.normalizedStore = null;
        this.registerStore = null;
        this.invoiceStore = null;
        this.inpaymentStore = null;
        this.supplierInvoiceStore = null;
        this.outpaymentStore = null;
    }

    private BokfriRuntime(Connection connection, int dataFormat) {
        this.database = null;
        this.dataFormat = dataFormat;
        this.connection = connection;
        this.normalizedStore = new NormalizedAccountingStore(connection, Clock.systemDefaultZone());
        this.registerStore = new NormalizedRegisterStore(connection);
        this.invoiceStore = new NormalizedInvoiceStore(connection);
        this.inpaymentStore = new NormalizedInpaymentStore(connection);
        this.supplierInvoiceStore = new NormalizedSupplierInvoiceStore(connection);
        this.outpaymentStore = new NormalizedOutpaymentStore(connection);
    }

    public static BokfriRuntime open(Path dataDir)
            throws IOException, SQLException, ClassNotFoundException {
        HsqlEngineMigrationService.migrateIfRequired(dataDir);
        Path databaseDirectory = dataDir.toAbsolutePath().normalize().resolve("db");
        Files.createDirectories(databaseDirectory);
        boolean databaseExisted = Files.exists(databaseDirectory.resolve("JFSDB.properties"));
        Class.forName("org.hsqldb.jdbcDriver");
        String databasePath = databaseDirectory.resolve("JFSDB").toString();
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:file:" + databasePath, "sa", "");
        try {
            connection.setAutoCommit(false);
            int format = DataFormatManager.detect(connection);
            if (format >= DataFormatManager.NORMALIZED_DATA_FORMAT_VERSION) {
                // Domain-object constructors read defaults from the global SSDB singleton;
                // a normalized runtime must not observe stale legacy selection state since
                // the legacy store is never started. Both calls are safe without a legacy
                // connection (null selection short-circuits before any database access).
                SSDB stale = SSDB.getInstance();
                stale.setCurrentYear(null);
                stale.setCurrentCompany(null);
                return new BokfriRuntime(connection, format);
            }
            format = DataFormatManager.checkAndInitialize(connection, databaseExisted);
            SSDB database = SSDB.getInstance();
            database.startupLocal(connection);
            return new BokfriRuntime(database, format);
        } catch (SQLException | RuntimeException exception) {
            connection.close();
            throw exception;
        }
    }

    /** Returns whether this runtime serves the normalized schema (data format 3). */
    public boolean isNormalized() {
        return normalizedStore != null;
    }

    /** Returns the detected data format version of the opened database. */
    public int dataFormat() {
        return dataFormat;
    }

    /**
     * Returns the legacy object storage.
     *
     * @throws CliException with code {@code NORMALIZED_STORAGE_UNSUPPORTED} when the database
     *         is normalized and the calling command has not been ported to the normalized stores
     */
    public SSDB database() {
        if (database == null) {
            throw new CliException("NORMALIZED_STORAGE_UNSUPPORTED",
                    "This command does not yet support normalized storage (data format " + dataFormat
                            + "); the accounting core is available through ported commands");
        }
        return database;
    }

    /** Returns the normalized accounting-core store. */
    public NormalizedAccountingStore normalizedStore() {
        if (normalizedStore == null) {
            throw new CliException("LEGACY_STORAGE",
                    "Normalized storage is not active for this database (data format " + dataFormat + ")");
        }
        return normalizedStore;
    }

    /** Returns the normalized register store (customers, suppliers, products). */
    public NormalizedRegisterStore registerStore() {
        if (registerStore == null) {
            throw new CliException("LEGACY_STORAGE",
                    "Normalized storage is not active for this database (data format " + dataFormat + ")");
        }
        return registerStore;
    }

    /** Returns the normalized customer invoice store. */
    public NormalizedInvoiceStore invoiceStore() {
        if (invoiceStore == null) {
            throw new CliException("LEGACY_STORAGE",
                    "Normalized storage is not active for this database (data format " + dataFormat + ")");
        }
        return invoiceStore;
    }

    /** Lists all companies from the active storage. */
    public List<SSNewCompany> getCompanies() throws SQLException {
        return isNormalized() ? normalizedStore.getCompanies() : database().getCompanies();
    }

    /** Lists the accounting years of a company from the active storage. */
    public List<SSNewAccountingYear> getYears(SSNewCompany company) throws SQLException {
        if (isNormalized()) {
            normalizedStore.setCurrentCompany(company);
            return normalizedStore.getYears();
        }
        return database().getYearsForCompany(company);
    }

    /** Lists the accounts of the selected accounting year from the active storage. */
    public List<SSAccount> getAccounts() throws SQLException {
        return isNormalized() ? normalizedStore.getAccounts() : database().getAccounts();
    }

    /** Lists the vouchers of the selected accounting year from the active storage. */
    public List<SSVoucher> getVouchers() throws SQLException {
        return isNormalized() ? normalizedStore.getVouchers() : database().getVouchers();
    }

    /** Returns the next free voucher number in the selected accounting year. */
    public int nextVoucherNumber() throws SQLException {
        if (isNormalized()) {
            return normalizedStore.getVouchers().stream()
                    .mapToInt(SSVoucher::getNumber).max().orElse(0) + 1;
        }
        return database().getLastVoucherNumber() + 1;
    }

    /**
     * Adds a voucher to the selected accounting year, assigning the next free number and
     * committing, mirroring the legacy {@code SSDB.addVoucher(voucher, false)} contract.
     */
    public void addVoucher(SSVoucher voucher) throws SQLException {
        if (isNormalized()) {
            voucher.setNumber(nextVoucherNumber());
            normalizedStore.addVoucher(voucher);
            connection.commit();
            return;
        }
        database().addVoucher(voucher, false);
    }

    /** Lists the customers of the selected company from the active storage. */
    public List<SSCustomer> getCustomers() throws SQLException {
        return isNormalized()
                ? registerStore.getCustomers(requireCurrentCompany().getId())
                : database().getCustomers();
    }

    /**
     * Adds a customer to the selected company, committing in normalized mode, mirroring the
     * legacy internal-commit contract of {@code SSDB.addCustomer}.
     */
    public void addCustomer(SSCustomer customer) throws SQLException {
        if (isNormalized()) {
            registerStore.addCustomer(requireCurrentCompany().getId(), customer);
            connection.commit();
            return;
        }
        database().addCustomer(customer);
    }

    /** Lists the suppliers of the selected company from the active storage. */
    public List<SSSupplier> getSuppliers() throws SQLException {
        return isNormalized()
                ? registerStore.getSuppliers(requireCurrentCompany().getId())
                : database().getSuppliers();
    }

    /** Adds a supplier to the selected company, committing in normalized mode. */
    public void addSupplier(SSSupplier supplier) throws SQLException {
        if (isNormalized()) {
            registerStore.addSupplier(requireCurrentCompany().getId(), supplier);
            connection.commit();
            return;
        }
        database().addSupplier(supplier);
    }

    /** Returns the next free outpayment number across the selected company's suppliers. */
    public int nextOutpaymentNumber() throws SQLException {
        return getSuppliers().stream().map(SSSupplier::getOutpaymentNumber)
                .filter(java.util.Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
    }

    /** Lists the products of the selected company from the active storage. */
    public List<SSProduct> getProducts() throws SQLException {
        return isNormalized()
                ? registerStore.getProducts(requireCurrentCompany().getId())
                : database().getProducts();
    }

    /** Adds a product to the selected company, committing in normalized mode. */
    public void addProduct(SSProduct product) throws SQLException {
        if (isNormalized()) {
            registerStore.addProduct(requireCurrentCompany().getId(), product);
            connection.commit();
            return;
        }
        database().addProduct(product);
    }

    /** Lists the customer invoices of the selected company from the active storage. */
    public List<SSInvoice> getInvoices() throws SQLException {
        return isNormalized()
                ? invoiceStore.getInvoices(requireCurrentCompany().getId())
                : database().getInvoices();
    }

    /**
     * Returns the number the next created invoice receives, mirroring the legacy contract:
     * one above the greater of the highest existing number and the company's "invoice"
     * auto-increment counter.
     */
    public int nextInvoiceNumber() throws SQLException {
        if (isNormalized()) {
            return invoiceStore.nextInvoiceNumber(requireCurrentCompany().getId());
        }
        SSDB database = database();
        return database.getInvoices().stream()
                .map(SSInvoice::getNumber)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(database.getCurrentCompany().getAutoIncrement().getNumber("invoice")) + 1;
    }

    /** Adds an unbooked invoice to the selected company, committing in normalized mode. */
    public void addInvoice(SSInvoice invoice) throws SQLException {
        if (isNormalized()) {
            invoice.setNumber(invoiceStore.nextInvoiceNumber(requireCurrentCompany().getId()));
            invoiceStore.addInvoice(requireCurrentCompany().getId(), invoice);
            connection.commit();
            return;
        }
        database().addInvoice(invoice);
    }

    /** Returns the normalized inpayment store. */
    public NormalizedInpaymentStore inpaymentStore() {
        if (inpaymentStore == null) {
            throw new CliException("LEGACY_STORAGE",
                    "Normalized storage is not active for this database (data format " + dataFormat + ")");
        }
        return inpaymentStore;
    }

    /** Finds an invoice of the selected company by number in the active storage. */
    public java.util.Optional<SSInvoice> findInvoice(int number) throws SQLException {
        return getInvoices().stream()
                .filter(java.util.Objects::nonNull)
                .filter(invoice -> invoice.getNumber() != null && invoice.getNumber() == number)
                .findFirst();
    }

    /** Outstanding invoice balance in the active storage. */
    public java.math.BigDecimal invoiceBalance(SSInvoice invoice) throws SQLException {
        if (!isNormalized()) {
            return se.swedsoft.bookkeeping.calc.math.SSInvoiceMath.getSaldo(invoice);
        }
        if (invoice.getType() == se.swedsoft.bookkeeping.data.common.SSInvoiceType.CASH) {
            return java.math.BigDecimal.ZERO;
        }
        boolean roundingOff = currentCompany().isRoundingOff();
        int companyId = currentCompany().getId();
        return se.swedsoft.bookkeeping.calc.math.SSSaleMath.getTotalSum(invoice, roundingOff)
                .subtract(invoiceStore.creditInvoiceSum(companyId, invoice.getNumber(), roundingOff))
                .subtract(invoiceStore.inpaymentSum(companyId, invoice.getNumber()))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Builds an account-plan view for the selected year from the active storage. */
    public se.swedsoft.bookkeeping.data.SSAccountPlan currentAccountPlan() throws SQLException {
        if (isNormalized()) {
            se.swedsoft.bookkeeping.data.SSAccountPlan plan =
                    new se.swedsoft.bookkeeping.data.SSAccountPlan();
            plan.setAccounts(getAccounts());
            return plan;
        }
        return database().getCurrentAccountPlan();
    }

    /** Returns the current value of a company auto-increment counter (0 when absent). */
    public int counterValue(String counter) throws SQLException {
        if (isNormalized()) {
            return registerStore.autoIncrementValue(requireCurrentCompany().getId(), counter);
        }
        return database().getCurrentCompany().getAutoIncrement().getNumber(counter);
    }

    /** Increments a company auto-increment counter, mirroring the legacy company update. */
    public void bumpCounter(String counter) throws SQLException {
        if (isNormalized()) {
            registerStore.bumpAutoIncrement(requireCurrentCompany().getId(), counter);
            return;
        }
        SSNewCompany company = database().getCurrentCompany();
        company.getAutoIncrement().doAutoIncrement(counter);
        database().updateCompany(company);
    }

    /** Marks an invoice as booked and stores its voucher snapshot in the active storage. */
    public void markInvoiceEntered(SSInvoice invoice) throws SQLException {
        if (isNormalized()) {
            invoiceStore.markInvoiceEntered(requireCurrentCompany().getId(),
                    invoice.getNumber(), invoice.getStoredVoucher());
            return;
        }
        database().updateInvoice(invoice);
    }

    /** Generates the booking voucher for an invoice or credit invoice in the active storage. */
    public SSVoucher generateInvoiceVoucher(SSInvoice invoice) throws SQLException {
        if (isNormalized()) {
            return invoice.generateVoucher(currentAccountPlan(),
                    currentCompany().isRoundingOff(), getProjects(), getResultUnits());
        }
        return invoice.generateVoucher();
    }

    /** Generates the booking voucher for a supplier invoice or credit in the active storage. */
    public SSVoucher generateSupplierInvoiceVoucher(SSSupplierInvoice invoice)
            throws SQLException {
        if (isNormalized()) {
            return invoice.generateVoucher(currentAccountPlan(), getProjects(), getResultUnits());
        }
        return invoice.generateVoucher();
    }

    /** Generates the booking voucher for an inpayment in the active storage. */
    public SSVoucher generateInpaymentVoucher(SSInpayment inpayment) throws SQLException {
        return isNormalized()
                ? inpayment.generateVoucher(currentAccountPlan())
                : inpayment.generateVoucher();
    }

    /** Generates the booking voucher for an outpayment in the active storage. */
    public SSVoucher generateOutpaymentVoucher(SSOutpayment outpayment) throws SQLException {
        return isNormalized()
                ? outpayment.generateVoucher(currentAccountPlan())
                : outpayment.generateVoucher();
    }

    /** Marks a credit invoice as booked and stores its voucher snapshot. */
    public void markCreditInvoiceEntered(SSCreditInvoice invoice) throws SQLException {
        if (isNormalized()) {
            invoiceStore.markCreditInvoiceEntered(requireCurrentCompany().getId(),
                    invoice.getNumber(), invoice.getStoredVoucher());
            return;
        }
        database().updateCreditInvoice(invoice);
    }

    /** Marks a supplier invoice as booked and stores its voucher snapshot. */
    public void markSupplierInvoiceEntered(SSSupplierInvoice invoice) throws SQLException {
        if (isNormalized()) {
            supplierInvoiceStore.markSupplierInvoiceEntered(requireCurrentCompany().getId(),
                    invoice.getNumber(), invoice.getVoucher());
            return;
        }
        database().updateSupplierInvoice(invoice);
    }

    /** Marks a supplier credit invoice as booked and stores its voucher snapshot. */
    public void markSupplierCreditInvoiceEntered(SSSupplierCreditInvoice invoice)
            throws SQLException {
        if (isNormalized()) {
            supplierInvoiceStore.markSupplierCreditInvoiceEntered(requireCurrentCompany().getId(),
                    invoice.getNumber(), invoice.getVoucher());
            return;
        }
        database().updateSupplierCreditInvoice(invoice);
    }

    /** Marks an inpayment as booked and stores its voucher snapshots. */
    public void markInpaymentEntered(SSInpayment inpayment) throws SQLException {
        if (isNormalized()) {
            inpaymentStore.markEntered(requireCurrentCompany().getId(), inpayment.getNumber(),
                    inpayment.getVoucher(), inpayment.getStoredDifference());
            return;
        }
        database().updateInpayment(inpayment);
    }

    /** Marks an outpayment as booked and stores its voucher snapshots. */
    public void markOutpaymentEntered(SSOutpayment outpayment) throws SQLException {
        if (isNormalized()) {
            outpaymentStore.markEntered(requireCurrentCompany().getId(), outpayment.getNumber(),
                    outpayment.getVoucher(), outpayment.getStoredDifference());
            return;
        }
        database().updateOutpayment(outpayment);
    }

    /** Lists the credit invoices of the selected company from the active storage. */
    public List<SSCreditInvoice> getCreditInvoices() throws SQLException {
        return isNormalized()
                ? invoiceStore.getCreditInvoices(requireCurrentCompany().getId())
                : database().getCreditInvoices();
    }

    /** Returns the number the next created credit invoice receives in the active storage. */
    public int nextCreditInvoiceNumber() throws SQLException {
        if (isNormalized()) {
            return invoiceStore.nextCreditInvoiceNumber(requireCurrentCompany().getId());
        }
        return new org.fribok.bookkeeping.service.creditinvoice.CreditInvoiceService(database())
                .nextNumber();
    }

    /** Adds a credit invoice to the selected company, committing in normalized mode. */
    public void addCreditInvoice(SSCreditInvoice creditInvoice) throws SQLException {
        if (isNormalized()) {
            creditInvoice.setNumber(
                    invoiceStore.nextCreditInvoiceNumber(requireCurrentCompany().getId()));
            invoiceStore.addCreditInvoice(requireCurrentCompany().getId(), creditInvoice);
            connection.commit();
            return;
        }
        database().addCreditInvoice(creditInvoice);
    }

    /** Lists the inpayments of the selected company from the active storage. */
    public List<SSInpayment> getInpayments() throws SQLException {
        return isNormalized()
                ? inpaymentStore.getInpayments(requireCurrentCompany().getId())
                : database().getInpayments();
    }

    /** Returns the number the next created inpayment receives in the active storage. */
    public int nextInpaymentNumber() throws SQLException {
        if (isNormalized()) {
            return inpaymentStore.nextInpaymentNumber(requireCurrentCompany().getId());
        }
        return new org.fribok.bookkeeping.service.inpayment.InpaymentService(database())
                .nextNumber();
    }

    /** Adds an inpayment to the selected company, committing in normalized mode. */
    public void addInpayment(SSInpayment inpayment) throws SQLException {
        if (isNormalized()) {
            inpayment.setNumber(
                    inpaymentStore.nextInpaymentNumber(requireCurrentCompany().getId()));
            inpaymentStore.addInpayment(requireCurrentCompany().getId(), inpayment);
            connection.commit();
            return;
        }
        database().addInpayment(inpayment);
    }

    /** Returns the normalized outpayment store. */
    public NormalizedOutpaymentStore outpaymentStore() {
        if (outpaymentStore == null) {
            throw new CliException("LEGACY_STORAGE",
                    "Normalized storage is not active for this database (data format " + dataFormat + ")");
        }
        return outpaymentStore;
    }

    /** Lists the supplier invoices of the selected company from the active storage. */
    public List<SSSupplierInvoice> getSupplierInvoices() throws SQLException {
        return isNormalized()
                ? supplierInvoiceStore.getSupplierInvoices(requireCurrentCompany().getId())
                : database().getSupplierInvoices();
    }

    /** Finds a supplier invoice of the selected company by number in the active storage. */
    public java.util.Optional<SSSupplierInvoice> findSupplierInvoice(int number)
            throws SQLException {
        return getSupplierInvoices().stream()
                .filter(java.util.Objects::nonNull)
                .filter(invoice -> invoice.getNumber() != null && invoice.getNumber() == number)
                .findFirst();
    }

    /** Returns the next supplier invoice number in the active storage. */
    public int nextSupplierInvoiceNumber() throws SQLException {
        if (isNormalized()) {
            return supplierInvoiceStore.nextSupplierInvoiceNumber(
                    requireCurrentCompany().getId());
        }
        return new org.fribok.bookkeeping.service.supplierinvoice.SupplierInvoiceService(
                database()).nextNumber();
    }

    /** Adds a supplier invoice to the selected company, committing in normalized mode. */
    public void addSupplierInvoice(SSSupplierInvoice invoice) throws SQLException {
        if (isNormalized()) {
            invoice.setNumber(supplierInvoiceStore.nextSupplierInvoiceNumber(
                    requireCurrentCompany().getId()));
            supplierInvoiceStore.addSupplierInvoice(requireCurrentCompany().getId(), invoice);
            connection.commit();
            return;
        }
        database().addSupplierInvoice(invoice);
    }

    /** Outstanding supplier invoice balance in the active storage. */
    public java.math.BigDecimal supplierInvoiceBalance(SSSupplierInvoice invoice)
            throws SQLException {
        if (!isNormalized()) {
            return se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath.getSaldo(invoice);
        }
        int companyId = requireCurrentCompany().getId();
        return se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath.getTotalSum(invoice)
                .subtract(supplierInvoiceStore.supplierCreditInvoiceSum(companyId,
                        invoice.getNumber()))
                .subtract(outpaymentStore.outpaymentSum(companyId, invoice.getNumber()));
    }

    /** Lists the supplier credit invoices of the selected company from the active storage. */
    public List<SSSupplierCreditInvoice> getSupplierCreditInvoices() throws SQLException {
        return isNormalized()
                ? supplierInvoiceStore.getSupplierCreditInvoices(requireCurrentCompany().getId())
                : database().getSupplierCreditInvoices();
    }

    /** Returns the next supplier credit invoice number in the active storage. */
    public int nextSupplierCreditInvoiceNumber() throws SQLException {
        if (isNormalized()) {
            return supplierInvoiceStore.nextSupplierCreditInvoiceNumber(
                    requireCurrentCompany().getId());
        }
        return new org.fribok.bookkeeping.service.suppliercreditinvoice
                .SupplierCreditInvoiceService(database()).nextNumber();
    }

    /** Adds a supplier credit invoice to the selected company, committing in normalized mode. */
    public void addSupplierCreditInvoice(SSSupplierCreditInvoice creditInvoice)
            throws SQLException {
        if (isNormalized()) {
            creditInvoice.setNumber(supplierInvoiceStore.nextSupplierCreditInvoiceNumber(
                    requireCurrentCompany().getId()));
            supplierInvoiceStore.addSupplierCreditInvoice(requireCurrentCompany().getId(),
                    creditInvoice);
            connection.commit();
            return;
        }
        database().addSupplierCreditInvoice(creditInvoice);
    }

    /** Lists the outpayments of the selected company from the active storage. */
    public List<SSOutpayment> getOutpayments() throws SQLException {
        return isNormalized()
                ? outpaymentStore.getOutpayments(requireCurrentCompany().getId())
                : database().getOutpayments();
    }

    /** Returns the number the next created outpayment receives in the active storage. */
    public int nextOutpaymentVoucherNumber() throws SQLException {
        if (isNormalized()) {
            return outpaymentStore.nextOutpaymentNumber(requireCurrentCompany().getId());
        }
        return new org.fribok.bookkeeping.service.outpayment.OutpaymentService(database())
                .nextNumber();
    }

    /** Adds an outpayment to the selected company, committing in normalized mode. */
    public void addOutpayment(SSOutpayment outpayment) throws SQLException {
        if (isNormalized()) {
            outpayment.setNumber(
                    outpaymentStore.nextOutpaymentNumber(requireCurrentCompany().getId()));
            outpaymentStore.addOutpayment(requireCurrentCompany().getId(), outpayment);
            connection.commit();
            return;
        }
        database().addOutpayment(outpayment);
    }

    /** Finds a customer of the selected company by number in the active storage. */
    public java.util.Optional<SSCustomer> findCustomer(String number) throws SQLException {
        if (isNormalized()) {
            return getCustomers().stream()
                    .filter(customer -> java.util.Objects.equals(number, customer.getNumber()))
                    .findFirst();
        }
        return database().getCustomer(number);
    }

    /** Finds a product of the selected company by number in the active storage. */
    public java.util.Optional<SSProduct> findProduct(String number) throws SQLException {
        if (isNormalized()) {
            return getProducts().stream()
                    .filter(product -> java.util.Objects.equals(number, product.getNumber()))
                    .findFirst();
        }
        return database().getProduct(number);
    }

    /** Returns the selected company in the active storage. */
    public SSNewCompany currentCompany() {
        return isNormalized()
                ? normalizedStore.getCurrentCompany()
                : database().getCurrentCompany();
    }

    /** Lists the projects of the selected company from the active storage. */
    public List<SSNewProject> getProjects() throws SQLException {
        return isNormalized()
                ? registerStore.getProjects(requireCurrentCompany().getId())
                : database().getProjects();
    }

    /** Lists the result units of the selected company from the active storage. */
    public List<SSNewResultUnit> getResultUnits() throws SQLException {
        return isNormalized()
                ? registerStore.getResultUnits(requireCurrentCompany().getId())
                : database().getResultUnits();
    }

    /** Lists the shared currency lookup from the active storage. */
    public List<SSCurrency> getCurrencies() throws SQLException {
        return isNormalized() ? registerStore.getCurrencies() : database().getCurrencies();
    }

    /** Lists the shared unit lookup from the active storage. */
    public List<SSUnit> getUnits() throws SQLException {
        return isNormalized() ? registerStore.getUnits() : database().getUnits();
    }

    /** Lists the shared payment term lookup from the active storage. */
    public List<SSPaymentTerm> getPaymentTerms() throws SQLException {
        return isNormalized() ? registerStore.getPaymentTerms() : database().getPaymentTerms();
    }

    private SSNewCompany requireCurrentCompany() {
        SSNewCompany company = normalizedStore().getCurrentCompany();
        if (company == null) {
            throw new CliException("COMPANY_REQUIRED", "No company is selected");
        }
        return company;
    }

    public SSNewCompany selectCompany(int companyId) throws SQLException {
        if (isNormalized()) {
            SSNewCompany company = getCompanies().stream()
                    .filter(candidate -> candidate.getId().equals(companyId))
                    .findFirst()
                    .orElseThrow(() -> new CliException("COMPANY_NOT_FOUND",
                            "No company has id " + companyId));
            normalizedStore.setCurrentCompany(company);
            return normalizedStore.getCurrentCompany();
        }
        SSNewCompany company = database.getCompanies().stream()
                .filter(candidate -> candidate.getId().equals(companyId))
                .findFirst()
                .orElseThrow(() -> new CliException("COMPANY_NOT_FOUND",
                        "No company has id " + companyId));
        database.setCurrentCompany(company);
        return database.getCurrentCompany();
    }

    public SSNewAccountingYear selectYear(SSNewCompany company, int yearId) throws SQLException {
        if (isNormalized()) {
            normalizedStore.setCurrentCompany(company);
            SSNewAccountingYear year = normalizedStore.getYears().stream()
                    .filter(candidate -> candidate.getId().equals(yearId))
                    .findFirst()
                    .orElseThrow(() -> new CliException("YEAR_NOT_FOUND",
                            "Company " + company.getId()
                                    + " has no accounting year with id " + yearId));
            normalizedStore.setCurrentYear(year);
            return normalizedStore.getCurrentYear();
        }
        List<SSNewAccountingYear> years = database.getYearsForCompany(company);
        SSNewAccountingYear year = years.stream()
                .filter(candidate -> candidate.getId().equals(yearId))
                .findFirst()
                .orElseThrow(() -> new CliException("YEAR_NOT_FOUND",
                        "Company " + company.getId() + " has no accounting year with id " + yearId));
        database.setCurrentYear(year);
        return database.getCurrentYear();
    }

    @Override
    public void close() {
        if (database != null) {
            database.setCurrentYear(null);
            database.setCurrentCompany(null);
            database.clearLists();
            database.shutdown();
            return;
        }
        if (connection != null) {
            // Discard uncommitted work, then shut the HSQLDB file database down cleanly.
            // Cleanup failures are intentionally swallowed here, matching the legacy
            // SSDB.shutdown() behaviour; HSQLDB recovers from its log on the next open.
            try {
                connection.rollback();
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SHUTDOWN");
                }
                connection.close();
            } catch (SQLException ignored) {
                // nothing sensible to do while the process is shutting down
            }
        }
    }
}
